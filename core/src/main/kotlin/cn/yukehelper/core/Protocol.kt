package cn.yukehelper.core

import kotlinx.serialization.json.*

internal val protocolJson = Json { ignoreUnknownKeys = true }
internal fun JsonObject.text(key: String): String = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
internal fun JsonObject.obj(key: String): JsonObject = get(key) as? JsonObject ?: JsonObject(emptyMap())

object Protocol {
    fun parseUser(domain: String, session: String, root: JsonObject): Account {
        val user = data(root)
        val id = user.text("id").takeIf { it.isNotBlank() }
            ?: throw NetworkFailure("登录返回缺少用户信息")
        return Account(id, user.text("name").ifBlank { "同学" }, domain, session)
    }

    fun parseCourses(root: JsonObject): List<Course> {
        val items = data(root)["list"] as? JsonArray ?: throw NetworkFailure("课程列表格式发生变化")
        return items.map { element ->
            val item = element as? JsonObject ?: throw NetworkFailure("课程信息格式异常")
            Course(
                item.text("classroom_id"), item.obj("course").text("name").ifBlank { "未命名课程" },
                item.text("name"), item.obj("teacher").text("name"),
            )
        }.filter { it.id.isNotBlank() }
    }

    fun parseLessons(root: JsonObject): List<ActiveLesson> {
        val items = data(root)["onLessonClassrooms"] as? JsonArray
            ?: throw NetworkFailure("课堂列表格式发生变化")
        return items.map { element ->
            val item = element.jsonObject
            val value = item["lessonId"] as? JsonPrimitive ?: throw NetworkFailure("课堂编号格式异常")
            val id = value.content.toLongOrNull() ?: throw NetworkFailure("课堂编号格式异常")
            ActiveLesson(id, item.text("classroomId"), item.text("courseName").ifBlank { "正在上课" }, value)
        }
    }

    fun data(root: JsonObject): JsonObject {
        checkResult(root)
        return root["data"] as? JsonObject ?: throw NetworkFailure("雨课堂返回的数据格式异常", ConnectionIssue.RESPONSE)
    }

    internal fun checkResult(root: JsonObject) {
        val code = (root["code"] as? JsonPrimitive)?.intOrNull
        val failed = (root["success"] as? JsonPrimitive)?.booleanOrNull == false
        if ((code != null && code != 0) || failed) {
            // Never include arbitrary upstream payloads or credential-like messages in UI/logs.
            val message = root.text("msg").take(200)
            if (code in listOf(401, 403) || Regex("未登录|登录失效|登录过期|重新登录|not.?logged|session.*expir|\\bunauthenticated\\b", RegexOption.IGNORE_CASE).containsMatchIn(message)) {
                throw SessionExpired()
            }
            throw ApiRejected(code, "雨课堂暂未接受请求${code?.let { "（$it）" }.orEmpty()}")
        }
    }

    fun hello(account: Account, access: LessonAccess): String = buildJsonObject {
        put("op", "hello")
        account.userId.toLongOrNull()?.let { put("userid", it) } ?: put("userid", account.userId)
        put("role", "student")
        put("auth", access.lessonToken)
        put("lessonid", access.lesson.requestId)
    }.toString()

    fun decodeFrame(text: String, lesson: ActiveLesson, userName: String): List<LessonFrame> {
        val root = protocolJson.parseToJsonElement(text) as? JsonObject ?: return emptyList()
        fun event(kind: EventKind, summary: String) = LessonFrame.Event(
            ClassroomEvent(kind, summary, lesson.name, lesson.id),
        )
        val events = when (root.text("op")) {
            "hello" -> listOf(LessonFrame.Connected)
            "unlockproblem" -> listOf(event(EventKind.QUESTION, "老师发布了新题目，请前往雨课堂查看"))
            "callpaused" -> if (root.text("name") == userName) listOf(event(EventKind.CALL, "老师正在点名，请留意课堂")) else emptyList()
            "newdanmu" -> root.text("danmu").take(200).takeIf { it.isNotBlank() }
                ?.let { listOf(event(EventKind.DANMU, it)) } ?: emptyList()
            "gainbonus" -> listOf(event(EventKind.BONUS, "课堂出现了红包"))
            "jumpinstart" -> listOf(event(EventKind.QUICK_ANSWER, "老师发起了抢答"))
            "lessonfinished" -> listOf(event(EventKind.LESSON_END, "本堂课已结束"), LessonFrame.Finished)
            else -> emptyList()
        }
        val automation = root.text("op") in setOf("hello", "unlockproblem", "newdanmu", "gainbonus", "jumpinstart", "jumpinend", "presentationupdated", "presentationcreated", "showpresentation") || root["unlockedproblem"] is JsonArray
        return events + if (automation) listOf(LessonFrame.Signal(root)) else emptyList()
    }
}
