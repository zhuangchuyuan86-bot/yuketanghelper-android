package cn.yukehelper.core

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

data class Domain(val host: String, val label: String) {
    companion object {
        val all = listOf(
            Domain("pro.yuketang.cn", "荷塘雨课堂"),
            Domain("www.yuketang.cn", "雨课堂"),
            Domain("changjiang.yuketang.cn", "长江雨课堂"),
            Domain("huanghe.yuketang.cn", "黄河雨课堂"),
        )
        fun requireHost(host: String) = all.firstOrNull { it.host == host }
            ?: throw IllegalArgumentException("不支持的雨课堂站点")
    }
}

// Deliberately omit the session from toString: diagnostic output must not expose it.
class Account(
    val userId: String,
    val name: String,
    val domain: String,
    val sessionId: String,
    val demo: Boolean = false,
) {
    val key: String get() = if (demo) "demo" else "$domain:$userId"
    override fun toString() = "Account(domain=$domain, demo=$demo)"
}

data class Course(val id: String, val name: String, val classroomName: String, val teacherName: String)
data class ActiveLesson(
    val id: Long,
    val classroomId: String,
    val name: String,
    val requestId: JsonPrimitive = JsonPrimitive(id),
) {
    init { require(requestId.content.toLongOrNull() == id) { "课堂编号格式异常" } }
}
class LessonAccess(val lesson: ActiveLesson, val lessonToken: String, val bearer: String?) {
    override fun toString() = "LessonAccess(lesson=${lesson.id})"
}

enum class EventKind(val label: String) {
    SYSTEM("状态"), SIGN_IN("签到"), QUESTION("题目"), CALL("点名"),
    DANMU("弹幕"), BONUS("红包"), LESSON_END("下课"), ERROR("连接"), ANSWER("答题"), ACTION("操作"), HOMEWORK("作业"),
    QUICK_ANSWER("抢答"),
}

data class ClassroomEvent(
    val kind: EventKind,
    val summary: String,
    val course: String = "",
    val lessonId: Long? = null,
    val at: Long = System.currentTimeMillis(),
    val homeworkKey: String? = null,
)

sealed interface LessonFrame {
    data object Connected : LessonFrame
    data class Event(val event: ClassroomEvent) : LessonFrame
    data class Signal(val root: JsonObject) : LessonFrame
    data object Finished : LessonFrame
}

sealed interface LoginState {
    data object Connecting : LoginState
    class QrCode(val bytes: ByteArray) : LoginState
    data object Verifying : LoginState
    data class Success(val account: Account) : LoginState
}

interface ClassroomGateway {
    suspend fun activeLessons(account: Account): List<ActiveLesson>
    suspend fun checkIn(account: Account, lesson: ActiveLesson): LessonAccess
    fun frames(account: Account, access: LessonAccess): Flow<LessonFrame>
}

class SessionExpired : Exception("登录已失效，请重新登录")
class ApiRejected(val code: Int?, message: String) : Exception(message)
class NetworkFailure(message: String, val issue: ConnectionIssue? = null, val status: Int? = null) : Exception(message)

/** Only numeric status and a fixed validation category are retained from an action error. */
class ActionFailure(
    val httpStatus: Int,
    val code: Int? = null,
    val detail: LessonEntryDetail? = null,
) : Exception(
    "操作请求未被接受，HTTP $httpStatus" + code?.let { "，接口代码 $it" }.orEmpty() +
        detail?.let { "，${it.summary}" }.orEmpty(),
)

enum class LessonEntryIssue(val summary: String, val advice: String) {
    REJECTED("入课请求被拒绝，具体原因待确认", "请在本 App 重新登录后重试；若仍失败，请反馈此错误码"),
    ACCESS("当前登录无权进入该课堂", "请确认本 App 的账号和站点，并在官方雨课堂检查入课权限"),
    MEMBERSHIP("当前账号尚未加入班级", "请在官方雨课堂加入班级后重试"),
    IDENTITY("当前账号需要绑定学校身份", "请在官方雨课堂完成身份绑定后重试"),
    RESTRICTED("班级限制加入或人数已满", "请在官方雨课堂确认加入条件"),
    FINISHED("服务端提示课堂已结束", "请在官方雨课堂核对当前课堂状态"),
    DYNAMIC_QR("该课堂要求动态二维码签到", "请使用官方雨课堂扫描现场有效二维码"),
    RATE_LIMIT("入课请求过于频繁", "正在降低请求频率，稍后重试"),
    ;

    companion object {
        fun fromCode(code: Int?): LessonEntryIssue = when (code) {
            50000, 50002, 50003 -> ACCESS
            50027 -> MEMBERSHIP
            50015, 50019 -> IDENTITY
            50017, 50018, 50020, 50021, 50022 -> RESTRICTED
            50004 -> FINISHED
            50070 -> DYNAMIC_QR
            50025, 50029 -> RATE_LIMIT
            else -> REJECTED
        }
    }
}

enum class LessonEntryDetail(val summary: String) {
    STRING_TYPE("服务端要求字符串字段"), NUMBER_TYPE("服务端要求数字字段"),
    JSON("服务端无法解析请求 JSON"), REQUIRED("服务端提示缺少必填参数"),
    PARAMETERS("服务端提示请求参数校验失败"), CSRF("服务端要求请求校验信息"),
    ;

    companion object {
        fun fromMessage(message: String): LessonEntryDetail? {
            val text = message.take(1_024)
            fun matches(pattern: String) = Regex(pattern, RegexOption.IGNORE_CASE).containsMatchIn(text)
            return when {
                matches("\\b(?:csrf|xsrf)\\b") -> CSRF
                matches("cannot unmarshal number[^\\r\\n]*of type string|expected (?:a )?string|must be (?:a )?string") -> STRING_TYPE
                matches("cannot unmarshal string[^\\r\\n]*of type (?:u?int(?:\\d+)?|float(?:\\d+)?|number)|expected (?:an? )?(?:integer|number)|must be (?:an? )?(?:integer|number)") -> NUMBER_TYPE
                matches("invalid.*json|json.*(?:decode|syntax|parse)|invalid character|无法解析.*json") -> JSON
                matches("missing.*required|required.*field|不能为空|缺少.*参数") -> REQUIRED
                matches("validation.*failed|invalid (?:request|param)|参数.*(?:错误|不合法|校验失败)|bad request") -> PARAMETERS
                else -> null
            }
        }
    }
}

/** Only fixed categories, request types and numeric codes cross the network/UI boundary. */
class LessonEntryFailure(
    val issue: LessonEntryIssue,
    val httpStatus: Int,
    val code: Int? = null,
    val requestIdIsString: Boolean? = null,
    val detail: LessonEntryDetail? = null,
) : Exception(
    "${issue.summary}，HTTP $httpStatus" + code?.let { "，接口代码 $it" }.orEmpty() +
        requestIdIsString?.let { "，编号格式：${if (it) "字符串" else "数字"}" }.orEmpty() +
        detail?.let { "，${it.summary}" }.orEmpty(),
) {
    val advice: String get() = if (code == 50400) "请反馈接口代码、编号格式及校验提示" else issue.advice
}

enum class ConnectionIssue(val summary: String) {
    NETWORK("网络连接中断"), DNS("无法解析雨课堂地址"), TLS("安全连接建立失败"),
    TIMEOUT("网络连接超时"), HANDSHAKE("服务器未确认课堂连接"),
    CREDENTIALS("课堂连接凭据被拒绝"), HTTP("课堂连接请求未被接受"),
    CLOSED("课堂消息连接已断开"), FORMAT("课堂消息格式异常"), QUEUE("课堂消息积压"),
    CONNECT("无法连接雨课堂服务"), RESPONSE("接口响应格式异常"), EMPTY("接口未返回数据"),
    MISSING_ACCESS("响应未包含课堂连接凭据"),
}

/** Expose only a fixed category and numeric status, never a server payload or URL. */
class ClassroomConnectionFailure(val issue: ConnectionIssue, val status: Int? = null) : Exception(issue.summary)

sealed interface MonitorUpdate {
    data class Poll(val lessons: List<ActiveLesson>, val at: Long) : MonitorUpdate
    data class Connection(val lessonId: Long, val connected: Boolean) : MonitorUpdate
    data class Event(val event: ClassroomEvent) : MonitorUpdate
}
