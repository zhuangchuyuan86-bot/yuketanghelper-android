package cn.yukehelper.core

import kotlinx.serialization.json.*

data class Question(
    val id: String, val slideId: String, val presentation: String, val type: Int,
    val options: List<String>, val maxChoices: Int, val cover: String,
    val limitSeconds: Double = 0.0, val sentAt: Long = 0, val answered: Boolean = false,
    val originalId: JsonPrimitive? = null,
) {
    val requestId: JsonPrimitive get() = originalId ?: identifier(id)
    fun deadline(): Long? = if (limitSeconds > 0 && sentAt > 0) sentAt + (limitSeconds * 1000).toLong() else null
}
sealed interface Answer {
    data class Choices(val options: List<String>) : Answer
    data class Text(val content: String) : Answer
}
interface AutomationGateway {
    suspend fun presentation(account: Account, access: LessonAccess, id: String): List<Question>
    suspend fun submit(account: Account, access: LessonAccess, question: Question, answer: Answer, now: Long)
    suspend fun danmu(account: Account, access: LessonAccess, content: String)
    suspend fun bonus(account: Account, access: LessonAccess, id: String)
    suspend fun quickAnswerInfo(account: Account, access: LessonAccess, id: JsonPrimitive): QuickAnswerRound
    suspend fun quickAnswer(account: Account, access: LessonAccess, id: JsonPrimitive): QuickAnswerResult
}

internal fun identifier(value: String): JsonPrimitive = value.toLongOrNull()?.let { JsonPrimitive(it) } ?: JsonPrimitive(value)
object QuestionProtocol {
    fun parse(root: JsonObject, presentation: String): List<Question> =
        (Protocol.data(root)["slides"] as? JsonArray).orEmpty().mapNotNull { element ->
            val slide = element as? JsonObject ?: return@mapNotNull null
            val p = slide["problem"] as? JsonObject ?: return@mapNotNull null
            val id = p.text("problemId").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val options = (p["options"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.text("key")?.takeIf(String::isNotBlank) }.distinct()
            val type = p.text("problemType").toIntOrNull() ?: 0
            val max = when (type) { 1 -> 1; 3 -> (p.text("pollingCount").toIntOrNull() ?: 1).coerceAtLeast(1); else -> options.size }
            Question(id, slide.text("id"), presentation, type, options, max.coerceAtMost(options.size), slide.text("cover"),
                p.text("limit").toDoubleOrNull() ?: 0.0, p.text("sendTime").toDoubleOrNull()?.toLong() ?: 0L,
                p["result"] != null && p["result"] != JsonNull, p["problemId"] as? JsonPrimitive)
        }

    fun answer(q: Question, answer: Answer, now: Long) = buildJsonObject {
        put("problemId", q.requestId); put("problemType", q.type); put("dt", now)
        put("result", when (answer) {
            is Answer.Choices -> JsonArray(answer.options.map { JsonPrimitive(it) })
            is Answer.Text -> buildJsonObject {
                put("content", answer.content)
                put("pics", buildJsonArray { add(buildJsonObject { put("pic", ""); put("thumb", "") }) })
            }
        })
    }
}
