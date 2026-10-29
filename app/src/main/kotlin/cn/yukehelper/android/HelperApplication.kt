package cn.yukehelper.android

import android.app.Application
import cn.yukehelper.core.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*
import java.util.concurrent.ConcurrentHashMap

class HelperApplication : Application() {
    lateinit var store: AppStore
        private set
    val api = YuketangApi()
    val ai = AiClient()
    val quickAnswerLedger = QuickAnswerLedger()
    private val loginScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate)
    val qrLogin by lazy { QrLoginController(loginScope, api::qrLogin, { account ->
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            store.signIn(account)
        }
    }) }
    val homework by lazy { HomeworkSyncManager(this, store, api) }
    override fun onCreate() { super.onCreate(); store = AppStore(this) }
}

class DemoGateway : ClassroomGateway {
    override suspend fun activeLessons(account: Account) = listOf(ActiveLesson(9001, "demo-1", "高等数学 · 课堂演示"))
    override suspend fun checkIn(account: Account, lesson: ActiveLesson) = LessonAccess(lesson, "demo", null)
    override fun frames(account: Account, access: LessonAccess) = flow {
        emit(LessonFrame.Connected)
        emit(LessonFrame.Signal(buildJsonObject { put("op", "hello"); put("presentation", "demo") }))
        val samples = listOf(
            EventKind.QUESTION to "老师发布了一道新题目",
            EventKind.CALL to "老师正在点名，请留意课堂",
            EventKind.QUICK_ANSWER to "老师发起了演示抢答",
            EventKind.DANMU to "这部分讲解很清楚",
        )
        var index = 0
        while (currentCoroutineContext().isActive) {
            delay(8_000)
            val (kind, text) = samples[index++ % samples.size]
            emit(LessonFrame.Event(ClassroomEvent(kind, text, access.lesson.name, access.lesson.id)))
            if (kind == EventKind.QUESTION) {
                val id = System.nanoTime().toString()
                DemoActions.add(id)
                emit(LessonFrame.Signal(buildJsonObject {
                    put("op", "unlockproblem"); put("problem", buildJsonObject { put("prob", id); put("pres", "demo"); put("limit", 60) })
                }))
            }
            if (kind == EventKind.DANMU) {
                repeat(3) { emit(LessonFrame.Signal(buildJsonObject { put("op", "newdanmu"); put("danmu", text) })) }
                emit(LessonFrame.Event(ClassroomEvent(EventKind.BONUS, "课堂出现了演示红包", access.lesson.name, access.lesson.id)))
                emit(LessonFrame.Signal(buildJsonObject { put("op", "gainbonus"); put("redEnvelopeId", System.nanoTime().toString()) }))
            }
            if (kind == EventKind.QUICK_ANSWER) {
                val now = System.currentTimeMillis()
                emit(LessonFrame.Signal(buildJsonObject {
                    put("op", "jumpinstart")
                    put("jumpin", buildJsonObject { put("id", now.toString()); put("now", now); put("start", now + 3_000) })
                }))
            }
        }
    }
    companion object {
        val courses = listOf(
            Course("demo-1", "高等数学", "数学一班", "陈老师"),
            Course("demo-2", "大学英语", "英语二班", "林老师"),
            Course("demo-3", "程序设计基础", "计算机一班", "张老师"),
        )
    }
}

object DemoActions : AutomationGateway {
    private val questions = ConcurrentHashMap<String, Question>()
    fun add(id: String) {
        if (questions.size > 100) questions.clear()
        questions[id] = Question(id, id, "demo", 1, listOf("A", "B", "C", "D"), 1, "", 60.0, System.currentTimeMillis())
    }
    override suspend fun presentation(account: Account, access: LessonAccess, id: String) = questions.values.toList()
    override suspend fun submit(account: Account, access: LessonAccess, question: Question, answer: Answer, now: Long) {
        questions[question.id] = question.copy(answered = true)
    }
    override suspend fun danmu(account: Account, access: LessonAccess, content: String) { delay(100) }
    override suspend fun bonus(account: Account, access: LessonAccess, id: String) { delay(100) }
    override suspend fun quickAnswerInfo(account: Account, access: LessonAccess, id: JsonPrimitive) = QuickAnswerRound(id, 0)
    override suspend fun quickAnswer(account: Account, access: LessonAccess, id: JsonPrimitive): QuickAnswerResult {
        delay(100)
        return QuickAnswerResult.SUCCESS
    }
}
