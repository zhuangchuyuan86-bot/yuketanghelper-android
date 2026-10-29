package cn.yukehelper.core

import kotlinx.serialization.json.*

enum class AnswerMode(val label: String) { OFF("关闭"), AI("AI（失败随机/空白）"), RANDOM("随机"), BLANK("空白") }
enum class AiProvider(val label: String, val model: String) {
    QWEN("通义千问 · ModelScope", "Qwen/Qwen3.5-397B-A17B"),
    GEMINI("Google Gemini", "gemini-flash-latest"),
    DEEPSEEK("DeepSeek", "deepseek-flash"),
}
class AiKey(val name: String, val provider: AiProvider, val secret: String, val model: String = provider.model) {
    override fun toString() = "AiKey(provider=$provider)"
}
data class AiSettings(val keys: List<AiKey> = emptyList(), val active: Int = 0, val fallback: Boolean = true) {
    fun ordered(): List<AiKey> {
        val selected = keys.getOrNull(active) ?: return emptyList()
        return (listOf(selected) + if (fallback) keys.filterIndexed { i, _ -> i != active } else emptyList()).filter { it.secret.isNotBlank() }
    }
}
data class Subscription(
    val enabled: Boolean = false,
    val kinds: Set<EventKind> = setOf(EventKind.SIGN_IN, EventKind.QUESTION, EventKind.CALL, EventKind.DANMU, EventKind.BONUS, EventKind.HOMEWORK, EventKind.QUICK_ANSWER),
) { fun accepts(kind: EventKind) = enabled && kind in kinds }

data class CourseSettings(
    // Effective runtime selection only; global rules are always stored with enabled=true.
    val enabled: Boolean = true,
    val single: AnswerMode = AnswerMode.OFF,
    val multiple: AnswerMode = AnswerMode.OFF,
    val vote: AnswerMode = AnswerMode.OFF,
    val short: AnswerMode = AnswerMode.OFF,
    val nearDeadline: Boolean = false,
    val autoDanmu: Boolean = false,
    val autoBonus: Boolean = false,
    val danmuThreshold: Int = 3,
    val notification: Subscription = Subscription(true, setOf(EventKind.QUESTION, EventKind.CALL, EventKind.HOMEWORK)),
    val voice: Subscription = Subscription(),
    val autoQuickAnswer: Boolean = false,
) {
    fun mode(type: Int) = when (type) { 1 -> single; 2 -> multiple; 3 -> vote; 5 -> short; else -> AnswerMode.OFF }
}
data class AccountProfile(
    val account: Account,
    val monitored: Boolean = true,
    val pollSeconds: Int = 30,
    val courses: List<Course> = emptyList(),
    val excludedCourses: Set<String> = emptySet(),
) { fun selects(id: String) = id !in excludedCourses }

data class Configuration(
    val profiles: List<AccountProfile> = emptyList(), val selected: String? = null,
    val courseSettings: CourseSettings = CourseSettings(), val settingsMigration: String = "",
    val ai: AiSettings = AiSettings(), val aiMigration: String = "",
) {
    fun active() = profiles.firstOrNull { it.account.key == selected } ?: profiles.firstOrNull()
    fun effectiveCourseSettings(accountKey: String, courseId: String): CourseSettings {
        val p = profiles.firstOrNull { it.account.key == accountKey }
        return courseSettings.copy(enabled = p != null && p.monitored && p.selects(courseId))
    }
    fun withCourseSettings(value: CourseSettings) = copy(courseSettings = value.copy(enabled = true), settingsMigration = "")
    fun withAiSettings(value: AiSettings) = copy(ai = value, aiMigration = "")
}

/** Versioned plaintext codec; Android encrypts the entire document before persisting it. */
object ConfigurationCodec {
    private fun JsonObject.bool(key: String, default: Boolean) = (get(key) as? JsonPrimitive)?.booleanOrNull ?: default
    private fun JsonObject.int(key: String, default: Int) = (get(key) as? JsonPrimitive)?.intOrNull ?: default
    private fun JsonObject.array(key: String) = get(key) as? JsonArray ?: JsonArray(emptyList())
    private fun subscription(value: Subscription) = buildJsonObject {
        put("enabled", value.enabled); put("kinds", JsonArray(value.kinds.map { JsonPrimitive(it.name) }))
    }
    private fun subscription(root: JsonObject, default: Subscription): Subscription = Subscription(
        root.bool("enabled", default.enabled),
        if ("kinds" in root) root.array("kinds").mapNotNull { runCatching { EventKind.valueOf(it.jsonPrimitive.content) }.getOrNull() }.toSet() else default.kinds,
    )
    private fun course(s: CourseSettings) = buildJsonObject {
        put("single", s.single.name); put("multiple", s.multiple.name)
        put("vote", s.vote.name); put("short", s.short.name); put("near", s.nearDeadline)
        put("danmu", s.autoDanmu); put("bonus", s.autoBonus); put("threshold", s.danmuThreshold)
        put("quickAnswer", s.autoQuickAnswer)
        put("notification", subscription(s.notification)); put("voice", subscription(s.voice))
    }
    private fun course(r: JsonObject): CourseSettings {
        fun mode(key: String, short: Boolean = false): AnswerMode {
            val mode = runCatching { AnswerMode.valueOf(r.text(key)) }.getOrDefault(AnswerMode.OFF)
            return if (short && mode == AnswerMode.RANDOM || !short && mode == AnswerMode.BLANK) AnswerMode.OFF else mode
        }
        return CourseSettings(r.bool("enabled", true), mode("single"), mode("multiple"), mode("vote"), mode("short", true),
            r.bool("near", false), r.bool("danmu", false), r.bool("bonus", false), r.int("threshold", 3).coerceIn(1, 100),
            subscription(r.obj("notification"), CourseSettings().notification), subscription(r.obj("voice"), Subscription()),
            r.bool("quickAnswer", false))
    }
    private fun ai(value: AiSettings) = buildJsonObject {
        put("active", value.active); put("fallback", value.fallback)
        put("keys", buildJsonArray { value.keys.forEach { k -> add(buildJsonObject {
            put("name", k.name); put("provider", k.provider.name); put("secret", k.secret); put("model", k.model)
        }) } })
    }
    private fun ai(root: JsonObject): AiSettings {
        val keys = root.array("keys").mapNotNull { value ->
            val k = value.jsonObject
            val provider = runCatching { AiProvider.valueOf(k.text("provider")) }.getOrNull() ?: return@mapNotNull null
            AiKey(k.text("name"), provider, k.text("secret"), k.text("model").ifBlank { provider.model })
        }
        return AiSettings(keys, root.int("active", 0).coerceIn(0, (keys.size - 1).coerceAtLeast(0)), root.bool("fallback", true))
    }
    fun encode(config: Configuration): String = buildJsonObject {
        put("version", 5); put("selected", config.selected)
        put("courseSettings", course(config.courseSettings))
        put("settingsMigration", config.settingsMigration)
        put("ai", ai(config.ai)); put("aiMigration", config.aiMigration)
        put("accounts", buildJsonArray { config.profiles.filterNot { it.account.demo }.forEach { p -> add(buildJsonObject {
            val a = p.account
            put("id", a.userId); put("name", a.name); put("domain", a.domain); put("session", a.sessionId)
            put("monitored", p.monitored); put("poll", p.pollSeconds)
            put("courses", buildJsonArray { p.courses.forEach { c -> add(buildJsonObject {
                put("id", c.id); put("name", c.name); put("classroom", c.classroomName); put("teacher", c.teacherName)
            }) } })
            put("excluded", JsonArray(p.excludedCourses.sorted().map(::JsonPrimitive)))
        }) } })
    }.toString()

    fun decode(plain: String, legacyExcluded: Set<String> = emptySet()): Configuration {
        val root = protocolJson.parseToJsonElement(plain).jsonObject
        // v0.1 stored exactly one account. Retain its session and monitoring switches.
        val legacy = root["accounts"] !is JsonArray
        val items = if (legacy) listOf(root) else root.array("accounts").map { it.jsonObject }
        val profiles = items.map { r ->
            val domain = Domain.requireHost(r.text("domain")).host
            val account = Account(r.text("id"), r.text("name"), domain, r.text("session"))
            require(account.userId.isNotBlank())
            AccountProfile(account, r.bool("monitored", true), r.int("poll", 30).coerceIn(10, 3600),
                r.array("courses").map { c -> c.jsonObject.let { Course(it.text("id"), it.text("name"), it.text("classroom"), it.text("teacher")) } },
                when {
                    legacy -> legacyExcluded
                    root.int("version", 2) >= 3 -> r.array("excluded").mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.toSet()
                    else -> r.obj("settings").filterValues { (it as? JsonObject)?.bool("enabled", true) == false }.keys
                })
        }.distinctBy { it.account.key }
        val selected = root.text("selected").takeIf { selected -> profiles.any { it.account.key == selected } } ?: profiles.firstOrNull()?.account?.key
        val rules = if (root.int("version", 2) >= 3)
            course(root.obj("courseSettings")).copy(enabled = true) to root.text("settingsMigration")
        else migrateSettings(profiles, selected, items)
        val sharedAi = if (root.int("version", 2) >= 4) ai(root.obj("ai")) to root.text("aiMigration")
        else migrateAi(profiles, selected, items)
        val settings = if (root.int("version", 2) < 5) rules.first.copy(
            notification = rules.first.notification.copy(kinds = rules.first.notification.kinds + EventKind.HOMEWORK),
        ) else rules.first
        return Configuration(profiles, selected, settings, rules.second, sharedAi.first, sharedAi.second)
    }

    /** Retain every distinct usable key, preferring the current account's active key and fallback setting. */
    private fun migrateAi(profiles: List<AccountProfile>, selected: String?, items: List<JsonObject>): Pair<AiSettings, String> {
        val candidates = profiles.sortedBy { if (it.account.key == selected) 0 else 1 }.map { p ->
            ai(items.first { it.text("id") == p.account.userId && it.text("domain") == p.account.domain }.obj("ai"))
        }
        val source = candidates.firstOrNull { it.keys.getOrNull(it.active)?.secret?.isNotBlank() == true }
            ?: candidates.firstOrNull { it.keys.any { key -> key.secret.isNotBlank() } }
            ?: candidates.firstOrNull() ?: AiSettings()
        val keys = (listOf(source) + candidates.filterNot { it === source }).flatMap { it.keys }
            .filter { it.secret.isNotBlank() }.distinctBy { listOf(it.provider.name, it.secret, it.model) }
        val preferred = source.keys.getOrNull(source.active)?.takeIf { it.secret.isNotBlank() }
            ?: source.keys.firstOrNull { it.secret.isNotBlank() }
        val active = keys.indexOfFirst { preferred != null && it.provider == preferred.provider && it.secret == preferred.secret && it.model == preferred.model }.coerceAtLeast(0)
        return AiSettings(keys, active, source.fallback) to if (keys.isEmpty()) "" else
            "已将旧版 AI 设置迁移为全局配置，原有密钥已合并保留。所有雨课堂账号共用，请确认优先密钥和失败回退设置。"
    }

    /** Use one existing rule set, never merge switches from different courses into new actions. */
    private fun migrateSettings(profiles: List<AccountProfile>, selected: String?, items: List<JsonObject>): Pair<CourseSettings, String> {
        val ordered = profiles.sortedBy { if (it.account.key == selected) 0 else 1 }
        for (profile in ordered) {
            val old = items.firstOrNull { it.text("id") == profile.account.userId && it.text("domain") == profile.account.domain } ?: continue
            val saved = old.obj("settings").mapValues { course(it.value.jsonObject).copy(enabled = true) }
            if (saved.isEmpty()) continue
            val ids = (profile.courses.map { it.id } + saved.keys.sorted()).distinct().filter { it in saved }
            val candidates = ids.filter(profile::selects).ifEmpty { ids }
            val chosen = candidates.groupBy { saved.getValue(it) }.maxBy { it.value.size }.value.first()
            val name = profile.courses.firstOrNull { it.id == chosen }?.name ?: "课程 $chosen"
            return saved.getValue(chosen) to "已从旧版账号“${profile.account.name}”的“$name”初始化通用设置，所有账号的课程统一使用。原课程勾选已保留，请检查后保存。"
        }
        return CourseSettings() to ""
    }
}
