package cn.yukehelper.core

import kotlinx.coroutines.*
import kotlinx.serialization.json.*

data class QuickAnswerRound(val id: JsonPrimitive, val waitMillis: Long)
enum class QuickAnswerResult { SUCCESS, ENDED, MISSED }

/** Application-owned ledger also protects against monitoring restarts after settings edits. */
class QuickAnswerLedger {
    private data class Key(val account: String, val lesson: Long, val round: String)
    private val attempted = mutableSetOf<Key>()
    @Synchronized fun contains(account: Account, lesson: Long, round: String) = Key(account.key, lesson, round) in attempted
    @Synchronized fun claim(account: Account, lesson: Long, round: String): Boolean =
        attempted.size < 4096 && attempted.add(Key(account.key, lesson, round))
}

/** Fields and status values follow the public student jumpin component. */
object QuickAnswerProtocol {
    fun identifier(value: JsonElement?): JsonPrimitive? = (value as? JsonPrimitive)?.takeIf {
        it.content.length in 1..40 && it.content.all { c -> c in '0'..'9' } && it.content.any { c -> c != '0' }
    }

    fun round(data: JsonObject): QuickAnswerRound? {
        val id = identifier(data["id"]) ?: return null
        val start = data.text("start").toLongOrNull()?.takeIf { it >= 0 } ?: return null
        val now = data.text("now").toLongOrNull()?.takeIf { it >= 0 } ?: return null
        // Use the server's relative countdown, independent of the phone's wall clock.
        val wait = if (start <= now) 0L else start - now
        if (wait > 300_000) return null
        return QuickAnswerRound(id, wait)
    }

    fun result(root: JsonObject): QuickAnswerResult {
        val data = Protocol.data(root)
        return when ((data["status"] as? JsonPrimitive)?.intOrNull
            ?: throw NetworkFailure("抢答结果格式异常", ConnectionIssue.RESPONSE)) {
            1 -> QuickAnswerResult.SUCCESS
            2 -> QuickAnswerResult.ENDED
            else -> QuickAnswerResult.MISSED
        }
    }
}

/** One coroutine per round; socket reconnects retain the same per-lesson ledger. */
internal class QuickAnswerAutomation(
    private val scope: CoroutineScope,
    private val account: Account,
    private val access: () -> LessonAccess,
    private val gateway: AutomationGateway,
    private val settings: () -> CourseSettings,
    private val ledger: QuickAnswerLedger,
    private val emit: (String) -> Unit,
) {
    private val lock = Any()
    private val seen = mutableSetOf<String>()
    private var generation = 0L
    private var pending: Job? = null
    private var warned = false

    private fun enabled() = settings().let { it.enabled && it.autoQuickAnswer }

    fun accept(root: JsonObject) {
        if (root.text("op") == "jumpinend") {
            synchronized(lock) { generation++; pending?.cancel(); pending = null }
            return
        }
        if (!enabled()) return
        when (root.text("op")) {
            "jumpinstart" -> {
                val round = QuickAnswerProtocol.round(root.obj("jumpin"))
                if (round == null) { warn(); return }
                schedule(round.id) { round }
            }
            "hello" -> {
                // hello only carries an ID. Read current server timing before participating.
                val id = QuickAnswerProtocol.identifier(root["jumpinid"]) ?: return
                schedule(id) { gateway.quickAnswerInfo(account, access(), id) }
            }
        }
    }

    private fun warn() {
        val first = synchronized(lock) { if (warned) false else { warned = true; true } }
        if (first) emit("抢答信息不完整或超出等待范围，暂未自动参与，请在官方雨课堂查看")
    }

    private fun schedule(id: JsonPrimitive, obtain: suspend () -> QuickAnswerRound) {
        if (ledger.contains(account, access().lesson.id, id.content)) return
        val job = synchronized(lock) {
            if (id.content in seen) return
            // Refuse extra rounds instead of evicting deduplication entries and replaying them.
            if (seen.size >= 1024) { warn(); return }
            seen.add(id.content)
            pending?.cancel()
            val token = ++generation
            scope.launch(start = CoroutineStart.LAZY) {
                fun current() = synchronized(lock) { generation == token }
                var submitted = false
                try {
                    if (!current() || !enabled()) return@launch
                    val round = withTimeout(3_000) { obtain() }
                    if (round.id.content != id.content || round.waitMillis !in 0L..300_000L) {
                        if (current()) warn()
                        return@launch
                    }
                    delay(round.waitMillis)
                    if (!current() || !enabled()) return@launch
                    currentCoroutineContext().ensureActive()
                    if (!ledger.claim(account, access().lesson.id, id.content)) return@launch
                    submitted = true
                    val result = withTimeout(3_000) { gateway.quickAnswer(account, access(), round.id) }
                    if (!current()) return@launch
                    emit(when (result) {
                        QuickAnswerResult.SUCCESS -> "自动抢答成功，请留意老师提问"
                        QuickAnswerResult.ENDED -> "本轮抢答已结束，未抢到"
                        QuickAnswerResult.MISSED -> "本轮未抢到，请留意下一轮"
                    })
                } catch (_: TimeoutCancellationException) {
                    currentCoroutineContext().ensureActive()
                    if (current()) emit(if (submitted) "自动抢答请求超时，结果未确认；本轮不重复提交，请在官方雨课堂核对"
                        else "抢答状态查询超时，本轮未自动参与，请在官方雨课堂查看")
                } catch (e: CancellationException) { throw e }
                catch (e: SessionExpired) { throw e }
                catch (e: Exception) {
                    if (current()) {
                        val detail = if (e is ActionFailure) "（HTTP ${e.httpStatus}${e.code?.let { "，接口代码 $it" }.orEmpty()}）" else ""
                        emit(if (submitted) "自动抢答未确认$detail；本轮不重复提交，请在官方雨课堂核对"
                            else "抢答状态查询失败，本轮未自动参与，请在官方雨课堂查看")
                    }
                }
            }.also { pending = it }
        }
        job.start()
    }
}
