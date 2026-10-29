package cn.yukehelper.android

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.WindowManager
import android.webkit.CookieManager
import android.widget.*
import cn.yukehelper.core.*
import kotlinx.coroutines.*

/** Separate editor views keep input stable while background events update the main screen. */
class ConfigurationActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val store get() = (application as HelperApplication).store
    private lateinit var body: LinearLayout
    private lateinit var mode: String
    private var nextInputId = 1000
    private val kinds = listOf(EventKind.SIGN_IN, EventKind.QUESTION, EventKind.CALL, EventKind.DANMU, EventKind.BONUS, EventKind.HOMEWORK, EventKind.QUICK_ANSWER)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mode = intent.getStringExtra("mode").orEmpty()
        if (mode == "ai") window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val root = column().apply { setBackgroundColor(Palette.background) }
        val header = row().apply { setPadding(dp(16), dp(14), dp(16), dp(8)) }
        header.addView(action("返回", false) { finish() }, touchParams(dp(76)))
        header.addView(label(when (mode) { "common" -> "  通用课程设置"; "ai" -> "  AI 服务与密钥"; else -> "  账号管理" }, 20f, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(header)
        body = column().apply { setPadding(dp(20), dp(14), dp(20), dp(24)) }
        root.addView(ScrollView(this).apply { addView(body) }, LinearLayout.LayoutParams(-1, 0, 1f))
        showContent(root)
        when (mode) { "common" -> commonEditor(); "ai" -> aiEditor(); else -> accounts() }
    }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
    private fun check(title: String, checked: Boolean, parent: LinearLayout = body): CheckBox = CheckBox(this).apply {
        id = nextInputId++
        text = title; textSize = 15f; setTextColor(Palette.text); isChecked = checked
        parent.block(this, 5)
    }
    private fun spinner(title: String, items: List<String>, selected: Int, parent: LinearLayout = body): Spinner {
        parent.block(label(title, 14f, Palette.muted), 3)
        return Spinner(this).apply {
            id = nextInputId++
            adapter = ArrayAdapter(this@ConfigurationActivity, android.R.layout.simple_spinner_dropdown_item, items)
            setSelection(selected.coerceIn(0, (items.size - 1).coerceAtLeast(0)))
            parent.block(this, 14)
        }
    }
    private fun field(title: String, value: String = "", secret: Boolean = false, numeric: Boolean = false, parent: LinearLayout = body): EditText {
        parent.block(label(title, 13f, Palette.muted), 2)
        return EditText(this).apply {
            id = nextInputId++; isSaveEnabled = !secret
            setText(value); setTextColor(Palette.text); textSize = 15f; isSingleLine = true
            inputType = when { secret -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                numeric -> InputType.TYPE_CLASS_NUMBER; else -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES }
            importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO
            parent.block(this, 12)
        }
    }
    private fun subscription(title: String, value: Subscription, includeHomework: Boolean = true): () -> Subscription {
        val card = card()
        card.block(label(title, 18f, bold = true), 8)
        val enabled = check("启用$title", value.enabled, card)
        val checks = kinds.filter { includeHomework || it != EventKind.HOMEWORK }.associateWith { check(it.label, it in value.kinds, card) }
        body.block(card)
        return { Subscription(enabled.isChecked, checks.filterValues { it.isChecked }.keys) }
    }
    private fun save(change: () -> Unit, after: () -> Unit = { finish() }) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) { change() }
                Toast.makeText(this@ConfigurationActivity, "已保存", Toast.LENGTH_SHORT).show(); after()
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { Toast.makeText(this@ConfigurationActivity, "保存失败，请重试", Toast.LENGTH_LONG).show() }
        }
    }
    private fun commonEditor() {
        val config = store.state.value.configuration
        val s = config.courseSettings
        body.block(label("课堂自动操作", 25f, bold = true), 6)
        body.block(label("适用于所有账号的已选课程。", 13f, Palette.muted))
        if (config.settingsMigration.isNotBlank()) body.block(label(config.settingsMigration, 13f, Palette.warning))
        body.block(label("AI 会读取题目图片；失败时选择题随机、主观题空白。", 13f, Palette.warning))
        val choices = listOf(AnswerMode.OFF, AnswerMode.AI, AnswerMode.RANDOM)
        val shorts = listOf(AnswerMode.OFF, AnswerMode.AI, AnswerMode.BLANK)
        val single = spinner("单选题", choices.map { it.label }, choices.indexOf(s.single))
        val multiple = spinner("多选题", choices.map { it.label }, choices.indexOf(s.multiple))
        val vote = spinner("投票题", choices.map { it.label }, choices.indexOf(s.vote))
        val short = spinner("主观题", shorts.map { it.label }, shorts.indexOf(s.short))
        val near = check("临近截止提交（提前约 10–15 秒）", s.nearDeadline)
        val danmu = check("跟随重复弹幕（60 秒内达到阈值）", s.autoDanmu)
        val threshold = field("相同弹幕阈值（1–100）", s.danmuThreshold.toString(), numeric = true)
        val bonus = check("自动领取课堂红包", s.autoBonus)
        val quickAnswer = check("自动抢答", s.autoQuickAnswer)
        body.block(label("抢到后请留意老师提问。", 12f, Palette.muted))
        body.gap()
        val notification = subscription("本机通知", s.notification)
        val voice = subscription("中文语音提醒", s.voice, includeHomework = false)
        body.block(action("保存通用课程设置") {
            val count = threshold.text.toString().toIntOrNull()
            if (count == null || count !in 1..100) { threshold.error = "请输入 1–100"; return@action }
            val value = CourseSettings(single = choices[single.selectedItemPosition], multiple = choices[multiple.selectedItemPosition],
                vote = choices[vote.selectedItemPosition], short = shorts[short.selectedItemPosition], nearDeadline = near.isChecked,
                autoDanmu = danmu.isChecked, autoBonus = bonus.isChecked, danmuThreshold = count,
                notification = notification(), voice = voice(), autoQuickAnswer = quickAnswer.isChecked)
            save({ store.saveCourseSettings(value) })
        })
    }
    private fun aiEditor() {
        body.removeAllViews()
        nextInputId = 1000
        val config = store.state.value.configuration
        val ai = config.ai
        body.block(label("AI 密钥", 23f, bold = true))
        body.block(label("所有账号共用，密钥保存在本机。", 13f, Palette.muted))
        if (config.aiMigration.isNotBlank()) body.block(label(config.aiMigration, 13f, Palette.warning))
        if (ai.keys.isEmpty()) body.block(label("添加密钥后，在通用课程设置中启用 AI 答题。", 14f, Palette.muted))
        else {
            val active = spinner("优先使用", ai.keys.map { "${it.name} · ${it.provider.label}" }, ai.active)
            val fallback = check("失败时尝试其他密钥", ai.fallback)
            body.block(action("保存密钥顺序", false) {
                val selected = active.selectedItemPosition
                val useFallback = fallback.isChecked
                save({ store.editAiSettings { it.copy(active = selected, fallback = useFallback) } }, { aiEditor() })
            })
        }
        ai.keys.forEachIndexed { index, key -> body.block(card().apply {
            addView(label(key.name, 18f, bold = true)); gap(6)
            addView(label("${key.provider.label}\n${key.model}", 13f, Palette.muted)); gap()
            block(action("编辑", false) { editKey(index) }, 8)
            block(action("删除密钥", false) {
                AlertDialog.Builder(this@ConfigurationActivity).setTitle("删除${key.name}？")
                    .setMessage("删除后所有雨课堂账号都将无法再使用此密钥。")
                    .setNegativeButton("取消", null).setPositiveButton("删除") { _, _ ->
                        save({ store.editAiSettings { current ->
                            val keys = current.keys.filterIndexed { i, _ -> i != index }
                            val selected = when { current.active > index -> current.active - 1; current.active == index -> 0; else -> current.active }
                            current.copy(keys = keys, active = selected.coerceIn(0, (keys.size - 1).coerceAtLeast(0)))
                        } }, { aiEditor() })
                    }.show()
            }, 0)
        }) }
        body.block(action("添加 AI 密钥") { editKey(null) })
    }
    private fun editKey(index: Int?, initialProvider: AiProvider = AiProvider.QWEN) {
        val old = index?.let { store.state.value.configuration.ai.keys.getOrNull(it) }
        val form = column().apply { setPadding(dp(20), dp(14), dp(20), dp(12)) }
        val name = field("密钥名称", old?.name.orEmpty(), parent = form)
        val providers = AiProvider.entries
        val provider = spinner("服务商", providers.map { it.label }, providers.indexOf(old?.provider ?: initialProvider), form)
        val model = field("模型名", old?.model ?: initialProvider.model, parent = form)
        val providerHelp = label("", 12f, Palette.muted)
        form.block(providerHelp)
        fun updateHelp(position: Int) {
            providerHelp.text = when (providers[position]) {
                AiProvider.DEEPSEEK -> "推荐 deepseek-flash，模型需支持图片输入。"
                AiProvider.QWEN -> "使用 ModelScope API Key，模型需支持图片输入。"
                AiProvider.GEMINI -> "模型需支持图片，手机网络需能访问 Google 服务。"
            }
        }
        updateHelp(provider.selectedItemPosition)
        provider.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            private var first = true
            override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                if (!first) model.setText(providers[position].model)
                updateHelp(position)
                first = false
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        val secret = field(if (old == null) "API Key" else "API Key（留空保留原值）", secret = true, parent = form)
        val dialog = AlertDialog.Builder(this).setTitle(if (old == null) "添加密钥" else "编辑密钥")
            .setView(ScrollView(this).apply { addView(form) }).setNegativeButton("取消", null).setPositiveButton("保存", null).create()
        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val entered = secret.text.toString().trim().ifBlank { old?.secret.orEmpty() }
            if (name.text.isBlank()) { name.error = "请输入名称"; return@setOnClickListener }
            if (entered.isBlank()) { secret.error = "请输入密钥"; return@setOnClickListener }
            if (model.text.isBlank()) { model.error = "请输入模型名"; return@setOnClickListener }
            if (providers[provider.selectedItemPosition] == AiProvider.DEEPSEEK &&
                model.text.toString().trim() in setOf("deepseek-v4-pro", "deepseek-chat", "deepseek-reasoner")) {
                model.error = "此处需要图片模型，请使用 deepseek-flash"; return@setOnClickListener
            }
            if (old != null && providers[provider.selectedItemPosition] != old.provider && secret.text.isBlank()) {
                secret.error = "切换服务商后请输入对应密钥"; return@setOnClickListener
            }
            val key = AiKey(name.text.toString().trim(), providers[provider.selectedItemPosition], entered, model.text.toString().trim())
            save({ store.editAiSettings { current ->
                val keys = current.keys.toMutableList()
                if (index != null && index in keys.indices) keys[index] = key else keys.add(key)
                current.copy(keys = keys)
            } }, { dialog.dismiss(); aiEditor() })
        } }
        dialog.show()
    }
    private fun accounts() {
        body.removeAllViews()
        nextInputId = 1000
        body.block(label("账号与监测", 25f, bold = true))
        body.block(label("已启用的账号同时监测。", 13f, Palette.muted))
        store.state.value.configuration.profiles.forEach { p -> body.block(card().apply {
            addView(label(p.account.name, 19f, bold = true)); gap(5)
            addView(label(if (p.account.demo) "演示账号" else Domain.requireHost(p.account.domain).label, 13f, Palette.muted))
            val valid = p.account.demo || p.account.sessionId.isNotBlank()
            if (!valid) { gap(5); addView(label("登录已失效，请重新登录", 13f, Palette.warning)) }
            val enabled = check("启用此账号监测", p.monitored, this).apply { isEnabled = valid }
            val poll = field("检查间隔（秒，10–3600）", p.pollSeconds.toString(), numeric = true, parent = this)
            block(action("保存账号监测设置", false) {
                val value = poll.text.toString().toIntOrNull()
                if (value == null || value !in 10..3600) { poll.error = "请输入 10–3600"; return@action }
                save({ store.editProfile(p.account.key) { it.copy(monitored = valid && enabled.isChecked, pollSeconds = value) } }, { accounts() })
            }, 8)
            block(action("查看此账号", false) { store.select(p.account.key); finish() }, 8)
            if (!p.account.demo) block(action("重新登录", false) { chooseLogin(p.account.domain) }, 8)
            block(action("移除此账号", false) {
                AlertDialog.Builder(this@ConfigurationActivity).setTitle("移除${p.account.name}？").setMessage("将删除此账号保存在本机的登录、课程选择和消息；全局 AI 密钥及通用课程设置会保留。")
                    .setNegativeButton("取消", null).setPositiveButton("移除") { _, _ ->
                        save({ store.signOut(p.account.key) }, { accounts() })
                    }.show()
            }, 0)
        }) }
        body.block(action("添加账号") { chooseLogin() })
    }
    private fun chooseLogin(fixedDomain: String? = null) {
        val open: (String) -> Unit = { domain ->
            AlertDialog.Builder(this).setTitle("登录方式").setItems(arrayOf("微信扫码登录", "网页登录")) { _, method ->
                // WebView cookies are shared; native sessions remain in the encrypted account store.
                CookieManager.getInstance().removeAllCookies {
                    startActivity(Intent(this, LoginActivity::class.java).putExtra("domain", domain).putExtra("mode", if (method == 0) "qr" else "web"))
                    finish()
                }
            }.show()
        }
        if (fixedDomain != null) open(fixedDomain) else AlertDialog.Builder(this).setTitle("选择雨课堂站点")
            .setItems(Domain.all.map { it.label }.toTypedArray()) { _, index -> open(Domain.all[index].host) }.show()
    }
}
