package cn.yukehelper.core

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

enum class QrLoginPhase { IDLE, CONNECTING, WAITING, VERIFYING, SAVING, SUCCESS, FAILED }

data class QrLoginSnapshot(
    val domain: String = "",
    val attempt: Long = 0,
    val phase: QrLoginPhase = QrLoginPhase.IDLE,
    val image: ByteArray? = null,
    val message: String = "",
) {
    val active get() = phase in listOf(QrLoginPhase.CONNECTING, QrLoginPhase.WAITING, QrLoginPhase.VERIFYING, QrLoginPhase.SAVING)
    override fun toString() = "QrLoginSnapshot(phase=$phase, attempt=$attempt)"
}

/** Owned by the application; UI collectors may leave or be recreated independently. Call on the main dispatcher. */
class QrLoginController(
    private val scope: CoroutineScope,
    private val login: (String) -> Flow<LoginState>,
    private val save: suspend (Account) -> Unit,
    private val waitMillis: Long = WAIT_MILLIS,
) {
    private val mutable = MutableStateFlow(QrLoginSnapshot())
    val state: StateFlow<QrLoginSnapshot> = mutable.asStateFlow()
    private var worker: Job? = null
    private var generation = 0L

    fun start(domain: String, force: Boolean = false): Long {
        Domain.requireHost(domain)
        val current = mutable.value
        if (current.phase == QrLoginPhase.SAVING) return current.attempt
        if (!force && current.domain == domain && (current.active || current.phase == QrLoginPhase.SUCCESS)) return current.attempt
        worker?.cancel()
        val attempt = ++generation
        mutable.value = QrLoginSnapshot(domain, attempt, QrLoginPhase.CONNECTING, message = "正在获取登录二维码…")
        worker = scope.launch {
            try {
                val result = withTimeout(waitMillis) {
                    login(domain).onEach { update ->
                        ensureActive()
                        if (attempt != generation) return@onEach
                        when (update) {
                            LoginState.Connecting -> Unit
                            is LoginState.QrCode -> if (mutable.value.phase in listOf(QrLoginPhase.CONNECTING, QrLoginPhase.WAITING)) {
                                mutable.value = mutable.value.copy(phase = QrLoginPhase.WAITING, image = update.bytes,
                                    message = "等待微信确认；本次不自动换码，确认后请返回助手（最长等待 5 分钟）")
                            }
                            LoginState.Verifying -> mutable.value = mutable.value.copy(phase = QrLoginPhase.VERIFYING,
                                message = "微信已确认，正在验证雨课堂登录…")
                            is LoginState.Success -> Unit
                        }
                    }.first { it is LoginState.Success } as LoginState.Success
                }
                ensureActive()
                if (attempt != generation) return@launch
                mutable.value = mutable.value.copy(phase = QrLoginPhase.SAVING, image = null, message = "登录已验证，正在保存…")
                try { save(result.account) }
                catch (error: CancellationException) { throw error }
                catch (_: Exception) { throw LoginFailure(LoginStage.SAVE, advice = "手机暂时无法保存账号，请重试") }
                ensureActive()
                if (attempt == generation) mutable.value = mutable.value.copy(phase = QrLoginPhase.SUCCESS, message = "登录成功，账号已保存")
            } catch (_: TimeoutCancellationException) {
                if (attempt == generation) failed("本次登录等待已满 5 分钟，请重新获取二维码并确认后返回助手")
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                if (attempt == generation) failed(if (error is LoginFailure) error.message.orEmpty() else "登录连接未完成，请重新获取二维码或改用网页登录")
            }
        }
        return attempt
    }

    fun cancel(attempt: Long = mutable.value.attempt, message: String? = null) {
        if (attempt != mutable.value.attempt) return
        ++generation
        worker?.cancel(); worker = null
        mutable.value = if (message == null) QrLoginSnapshot() else mutable.value.copy(
            phase = QrLoginPhase.FAILED, image = null, message = message)
    }

    private fun failed(message: String) {
        mutable.value = mutable.value.copy(phase = QrLoginPhase.FAILED, image = null, message = message)
    }

    companion object { const val WAIT_MILLIS = 5 * 60 * 1000L }
}

enum class LoginStage(val label: String) { CONNECTION("登录连接"), MESSAGE("登录消息"), IMAGE("二维码加载"), EXCHANGE("会话交换"), USER("账号验证"), SAVE("本机保存") }

/** Only fixed labels and numeric diagnostics may be retained, never upstream text or credentials. */
class LoginFailure(stage: LoginStage, status: Int? = null, code: Int? = null,
    advice: String = "请重新获取二维码，或改用网页登录") : Exception(
    stage.label + (status?.let { "（HTTP $it）" } ?: "") + (code?.let { "（接口 $it）" } ?: "") + "：" + advice,
)
