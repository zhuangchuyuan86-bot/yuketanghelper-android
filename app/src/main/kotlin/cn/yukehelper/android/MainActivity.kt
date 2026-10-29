package cn.yukehelper.android

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.webkit.CookieManager
import android.widget.*
import cn.yukehelper.core.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

class MainActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val graph get() = application as HelperApplication
    private lateinit var scroll: ScrollView
    private lateinit var body: LinearLayout
    private lateinit var nav: LinearLayout
    private lateinit var badge: TextView
    private lateinit var header: LinearLayout
    private lateinit var courseIndex: CourseIndexView
    private var page = 0
    private var messageTab = 0
    private var homeworkLimit = 30
    private var domain = Domain.all.first().host
    private var startAfterPermission = false
    private var voiceTester: VoiceNotifier? = null
    private val time = SimpleDateFormat("HH:mm:ss", Locale.CHINA)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        page = savedInstanceState?.getInt("page") ?: 0
        messageTab = savedInstanceState?.getInt("messageTab") ?: 0
        homeworkLimit = savedInstanceState?.getInt("homeworkLimit") ?: 30
        showHomeworkIntent(intent)
        val root = column().apply { setBackgroundColor(Palette.background) }
        header = row().apply { setPadding(dp(22), dp(18), dp(22), dp(14)) }
        val titles = column().apply {
            addView(label("雨课助手", 25f, bold = true))
        }
        header.addView(titles, LinearLayout.LayoutParams(0, -2, 1f))
        badge = label("准备就绪", 11f, Palette.accent).apply {
            background = shape(Palette.card, 20); setPadding(dp(12), dp(7), dp(12), dp(7))
        }
        header.addView(badge)
        root.addView(header)
        body = column().apply { setPadding(dp(20), dp(8), dp(20), dp(22)) }
        scroll = ScrollView(this).apply { isFillViewport = true; addView(body) }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        courseIndex = CourseIndexView(this, ::refreshCourses, ::switchAccount) { key, known, selected ->
            courseIndex.saving(true)
            scope.launch {
                try {
                    withContext(Dispatchers.IO + NonCancellable) { graph.store.applyCourseSelection(key, known, selected) }
                    Toast.makeText(this@MainActivity, "课程选择已应用", Toast.LENGTH_SHORT).show()
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { Toast.makeText(this@MainActivity, "保存失败，请重试", Toast.LENGTH_LONG).show() }
                finally { courseIndex.saving(false) }
            }
        }.apply { visibility = View.GONE; restoreState(savedInstanceState?.getBundle("courseIndex")) }
        root.addView(courseIndex, LinearLayout.LayoutParams(-1, 0, 1f))
        nav = row().apply { setPadding(dp(12), dp(8), dp(12), dp(10)); setBackgroundColor(Palette.background) }
        root.addView(nav, LinearLayout.LayoutParams(-1, -2))
        showContent(root)
        scope.launch { graph.store.state.collect { render(it) } }
    }

    override fun onResume() {
        super.onResume()
        val state = graph.store.state.value
        if (state.account != null && state.courses.isEmpty() && !state.loading) refreshCourses()
        if (::body.isInitialized) render(graph.store.state.value)
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("page", page); outState.putInt("messageTab", messageTab); outState.putInt("homeworkLimit", homeworkLimit)
        outState.putBundle("courseIndex", courseIndex.saveState()); super.onSaveInstanceState(outState)
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent); setIntent(intent); showHomeworkIntent(intent)
        if (::body.isInitialized) render(graph.store.state.value)
    }
    private fun showHomeworkIntent(intent: Intent) {
        if (!intent.getBooleanExtra("showHomework", false)) return
        intent.getStringExtra("accountKey")?.let { graph.store.select(it) }
        page = 2; messageTab = 0
        intent.removeExtra("showHomework")
    }
    override fun onDestroy() { scope.cancel(); voiceTester?.close(); super.onDestroy() }

    private fun render(state: AppState) {
        val scrollY = scroll.scrollY
        body.removeAllViews()
        nav.removeAllViews()
        badge.text = when { state.account?.demo == true -> "演示模式"; state.monitoring -> "● 监测中"; else -> "准备就绪" }
        val showCourses = state.account != null && page == 1
        header.visibility = if (showCourses) View.GONE else View.VISIBLE
        courseIndex.visibility = if (showCourses) View.VISIBLE else View.GONE
        scroll.visibility = if (showCourses) View.GONE else View.VISIBLE
        if (state.account != null) courseIndex.bind(state)
        if (state.message.isNotBlank()) body.block(label(state.message, 13f, Palette.warning).apply {
            background = shape(Palette.card); setPadding(dp(15), dp(12), dp(15), dp(12))
        })
        if (state.account == null) {
            nav.visibility = View.GONE
            welcome()
        } else {
            nav.visibility = View.VISIBLE
            if (!showCourses && state.configuration.profiles.size > 1) body.block(action("${state.account!!.name} · 切换账号（${state.configuration.profiles.size}）", false, ::switchAccount))
            listOf("总览", "课程", "消息", "设置").forEachIndexed { index, title ->
                nav.addView(action(title, primary = page == index) {
                    page = index; scroll.scrollTo(0, 0); render(graph.store.state.value)
                }.apply { minWidth = 0; minimumWidth = 0; textSize = 14f; setPadding(dp(6), dp(8), dp(6), dp(8)) }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(3); marginEnd = dp(3) })
            }
            when (page) { 0 -> overview(state); 1 -> Unit; 2 -> events(state); else -> settings(state) }
        }
        scroll.post { scroll.scrollTo(0, scrollY) }
    }

    private fun welcome() {
        body.gap(22)
        body.block(label("登录雨课堂", 30f, bold = true), 20)
        body.block(card().apply {
            addView(label("选择你的雨课堂", 17f, bold = true)); gap()
            val chooser = Spinner(this@MainActivity).apply {
                adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, Domain.all.map { it.label })
                setSelection(Domain.all.indexOfFirst { it.host == domain }.coerceAtLeast(0))
                onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) { domain = Domain.all[position].host }
                    override fun onNothingSelected(parent: AdapterView<*>?) = Unit
                }
            }
            addView(chooser, touchParams()); gap()
            block(action("微信扫码登录") { login("qr") }, 10)
            block(action("使用网页登录", false) { login("web") }, 4)
        })
        body.block(action("先体验演示", false) {
            scope.launch {
                withContext(Dispatchers.IO) { graph.store.signIn(Account("demo", "体验同学", Domain.all.first().host, "", demo = true)) }
                refreshCourses()
            }
        })
        body.block(action("AI 服务与密钥（全局）", false) { configure("ai") })
    }

    private fun overview(state: AppState) {
        val account = state.account ?: return
        body.gap(8)
        body.block(label("你好，${state.account!!.name}", 26f, bold = true), 6)
        body.block(label(if (account.demo) "演示数据" else Domain.requireHost(account.domain).label, 13f, Palette.muted), 16)
        body.block(card().apply {
            val title = row()
            title.addView(label("课堂监测", 19f, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
            title.addView(label(if (state.monitoring) "运行中" else "未开启", 12f, if (state.monitoring) Palette.accent else Palette.muted))
            addView(title); gap()
            addView(label(if (state.monitoring) "已连接 ${state.connected.size} 堂课" else "监测已选课程，考勤以雨课堂为准。", 14f, Palette.muted))
            gap(18)
            block(action(if (state.monitoring) "停止监测" else "开始监测", primary = !state.monitoring) {
                if (state.monitoring) stopMonitoring() else startMonitoring()
            }, 10)
            addView(label("每次最长 4 小时 · 监测期间显示持续通知", 11f, Palette.muted))
        })
        val stats = row()
        listOf("${state.courses.size}" to "我的课程", "${state.lessons.size}" to "正在上课", if (state.lastPoll > 0) time.format(Date(state.lastPoll)).take(5) to "最近检查" else "—" to "最近检查").forEach { (value, name) ->
            stats.addView(card().apply { setPadding(dp(10), dp(15), dp(10), dp(15)); addView(label(value, 21f, Palette.accent, true)); addView(label(name, 11f, Palette.muted)) },
                LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(6) })
        }
        body.block(stats, 24)
        body.block(label("最近动态", 19f, bold = true), 12)
        if (state.events.isEmpty()) body.block(emptyCard("暂时没有课堂消息", "开启监测后，课堂事件会出现在这里。"))
        else state.events.take(4).forEach { body.block(eventCard(it), 10) }
    }

    private fun events(state: AppState) {
        body.block(label("消息", 27f, bold = true), 6)
        body.block(row().apply {
            listOf("作业提醒（${state.selectedHomework.size}）", "课堂动态").forEachIndexed { index, title ->
                addView(action(title, primary = messageTab == index) {
                    messageTab = index; render(graph.store.state.value)
                }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(6) })
            }
        }, 12)
        if (messageTab == 0) { homework(state); return }
        body.block(label("最近 100 条动态 · 保存在本机", 13f, Palette.muted), 20)
        if (state.events.isEmpty()) body.block(emptyCard("还没有消息", "开启监测后，可在这里查看签到与课堂动态。"))
        else state.events.forEach { body.block(eventCard(it), 10) }
    }

    private fun homework(state: AppState) {
        val account = state.account ?: return
        val status = state.homeworkStatus
        body.block(label("监测中每 5 分钟同步，也可手动刷新。", 13f, Palette.muted), 12)
        body.block(action(if (status.loading) "正在同步作业…" else "立即同步作业", false) {
            scope.launch { withContext(Dispatchers.IO) { graph.homework.sync(account) } }
        }.apply { isEnabled = !status.loading && (account.demo || account.sessionId.isNotBlank()) }, 10)
        body.block(label(status.message + if (status.lastSuccess > 0) "\n最近完整同步：${SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(status.lastSuccess))}" else "", 12f, Palette.muted), 12)
        val now = System.currentTimeMillis()
        val items = state.selectedHomework.sortedWith(compareBy<Homework> { it.expired(now) }
            .thenBy { if (it.expired(now)) -(it.deadline ?: 0L) else it.deadline ?: Long.MAX_VALUE }.thenBy { it.title })
        if (items.isEmpty()) {
            body.block(emptyCard("暂无已同步作业", "勾选课程后点击“立即同步作业”。"))
            return
        }
        items.take(homeworkLimit).forEach { item -> body.block(card().apply {
            addView(label(if (item.expired(now)) "已截止 · ${item.course}" else item.course, 12f, Palette.muted)); gap(8)
            addView(label(item.title, 19f, bold = true)); gap(8)
            addView(label("截止：${item.deadlineLabel()}", 14f, if (item.expired(now)) Palette.muted else Palette.warning)); gap(8)
            addView(label("作业要求：${item.requirementsLabel()}", 14f).apply {
                maxLines = 4; ellipsize = android.text.TextUtils.TruncateAt.END
            }); gap()
            block(action("查看作业要求", false) { homeworkDialog(item, account) }, 8)
            block(action("去雨课堂查看", false) { openHomework(item, account) }, 0)
        }, 10) }
        if (items.size > homeworkLimit) body.block(action("显示更多作业（还有 ${items.size - homeworkLimit} 份）", false) {
            homeworkLimit += 30; render(graph.store.state.value)
        })
    }

    private fun homeworkDialog(item: Homework, account: Account) {
        val content = column().apply {
            setPadding(dp(20), dp(12), dp(20), dp(20))
            addView(label("${item.course}\n截止：${item.deadlineLabel()}\n\n作业要求\n${item.requirementsLabel()}", 15f).apply { setTextIsSelectable(true) })
            gap(); addView(label("图片、附件和最新要求请到雨课堂查看。", 12f, Palette.muted))
        }
        AlertDialog.Builder(this).setTitle(item.title).setView(ScrollView(this).apply { addView(content) })
            .setNegativeButton("关闭", null).setPositiveButton("去雨课堂") { _, _ -> openHomework(item, account) }.show()
    }
    private fun openHomework(item: Homework, account: Account) {
        if (account.demo) { Toast.makeText(this, "这是演示作业，不会访问真实课堂", Toast.LENGTH_SHORT).show(); return }
        val route = if (item.type == HomeworkType.QUIZ) "studentQuiz_web/${Uri.encode(item.courseId)}/${Uri.encode(item.id)}"
            else "cloud/student/exercise/${Uri.encode(item.courseId)}/${Uri.encode(item.leafId)}/${Uri.encode(item.skuId)}"
        val url = "https://${Domain.requireHost(account.domain).host}/v2/web/$route"
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        catch (_: android.content.ActivityNotFoundException) { Toast.makeText(this, "未找到可打开雨课堂的浏览器", Toast.LENGTH_LONG).show() }
    }

    private fun settings(state: AppState) {
        val account = state.account ?: return
        body.block(label("设置", 27f, bold = true), 20)
        body.block(card().apply {
            addView(label("通用课程设置", 19f, bold = true)); gap(6)
            addView(label("所有账号的已选课程共用。", 13f, Palette.muted)); gap()
            block(action("打开通用课程设置") { configure("common", account.key) }, 0)
        })
        body.block(card().apply {
            addView(label("AI 服务与密钥（全局）", 19f, bold = true)); gap(6)
            addView(label("配置一次，所有账号共用。", 13f, Palette.muted)); gap()
            block(action("管理全局 AI 设置", false) { configure("ai") }, 0)
        })
        body.block(card().apply {
            addView(label(state.account!!.name, 19f, bold = true)); gap(6)
            addView(label(if (account.demo) "演示账号" else Domain.requireHost(account.domain).label, 13f, Palette.muted))
            gap()
            block(action("账号管理", false) { configure("accounts", account.key) }, 8)
            if (!account.demo && account.sessionId.isBlank()) block(action("重新登录此账号") { domain = account.domain; login("qr") })
        })
        body.block(card().apply {
            addView(label("提醒与后台运行", 18f, bold = true)); gap()
            val enabled = getSystemService(NotificationManager::class.java).areNotificationsEnabled()
            block(action(if (enabled) "通知设置" else "开启课堂提醒", false) { notificationSettings() }, 10)
            block(action("后台运行设置", false) { startActivity(Intent(this@MainActivity, BackgroundActivity::class.java)) }, 10)
            block(action("系统文字转语音设置", false) { openSettings("com.android.settings.TTS_SETTINGS") }, 10)
            block(action("试听中文语音", false) {
                voiceTester?.close()
                val tester = VoiceNotifier(this@MainActivity) { message -> Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show() }
                voiceTester = tester
                tester.speak("雨课助手，课堂语音提醒已准备就绪")
                scope.launch { delay(8_000); tester.close() }
            }, 10)
        })
        body.block(action(if (account.demo) "退出演示" else "移除此账号", false) {
            AlertDialog.Builder(this).setTitle("移除${account.name}？").setMessage("此账号的本机登录、课程选择和消息将被移除。全局 AI 密钥及通用课程设置会保留。")
                .setNegativeButton("取消", null).setPositiveButton("移除") { _, _ ->
                    CookieManager.getInstance().removeAllCookies(null)
                    scope.launch { withContext(Dispatchers.IO) { graph.store.signOut(account.key) } }
                    page = 0
                }.show()
        })
        body.block(label("雨课助手 ${BuildConfig.VERSION_NAME}", 12f, Palette.muted))
    }

    private fun eventCard(event: ClassroomEvent) = card().apply {
        val heading = row()
        heading.addView(label(event.kind.label, 12f, if (event.kind == EventKind.ERROR) Palette.warning else Palette.accent, true), LinearLayout.LayoutParams(0, -2, 1f))
        heading.addView(label(time.format(Date(event.at)), 11f, Palette.muted)); addView(heading); gap(8)
        addView(label(event.summary, 15f))
        if (event.course.isNotBlank()) { gap(6); addView(label(event.course, 12f, Palette.muted)) }
        if (event.homeworkKey != null) {
            val item = graph.store.state.value.homework.firstOrNull { it.key == event.homeworkKey }
            val account = graph.store.state.value.account
            if (item != null && account != null) { gap(); block(action("查看作业", false) { homeworkDialog(item, account) }, 0) }
        }
    }
    private fun emptyCard(title: String, subtitle: String) = card().apply {
        addView(label(title, 16f, bold = true)); gap(8); addView(label(subtitle, 13f, Palette.muted))
    }
    private fun login(mode: String) { startActivity(Intent(this, LoginActivity::class.java).putExtra("domain", domain).putExtra("mode", mode)) }
    private fun configure(mode: String, account: String? = null) {
        startActivity(Intent(this, ConfigurationActivity::class.java).putExtra("mode", mode).putExtra("account", account))
    }
    private fun switchAccount() {
        val profiles = graph.store.state.value.configuration.profiles
        AlertDialog.Builder(this).setTitle("选择当前查看的账号")
            .setItems(profiles.map { "${it.account.name} · ${if (it.account.demo) "演示" else Domain.requireHost(it.account.domain).label}" }.toTypedArray()) { _, index ->
                graph.store.select(profiles[index].account.key)
                refreshCourses()
            }.show()
    }
    private fun refreshCourses() {
        val account = graph.store.state.value.account ?: return
        if (graph.store.state.value.loading) return
        graph.store.update { it.copy(loading = true, message = "") }
        scope.launch {
            try {
                val courses = withContext(Dispatchers.IO) { if (account.demo) DemoGateway.courses else graph.api.courses(account) }
                if (graph.store.profile(account.key)?.account === account) withContext(Dispatchers.IO) {
                    graph.store.editProfile(account.key) { it.copy(courses = courses) }
                }
                if (graph.store.state.value.account === account) graph.store.update { it.copy(loading = false) }
            } catch (error: CancellationException) { throw error }
            catch (_: SessionExpired) {
                withContext(Dispatchers.IO) { graph.store.expire(account) }
            } catch (_: Exception) {
                if (graph.store.state.value.account === account) graph.store.update { it.copy(loading = false, message = "课程加载失败，请检查网络后重试") }
            }
        }
    }
    private fun startMonitoring() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            startAfterPermission = true
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 7)
        } else launchMonitoring()
    }
    private fun launchMonitoring() {
        try {
            startForegroundService(Intent(this, MonitorService::class.java).setAction(MonitorService.START))
            scope.launch {
                // Start the foreground service first. Granting this request is optional.
                val started = withTimeoutOrNull(5_000) { graph.store.state.first { it.monitoring } } ?: return@launch
                if (!graph.store.state.value.monitoring) return@launch
                if (started.configuration.profiles.any { it.monitored && !it.account.demo && it.account.sessionId.isNotBlank() })
                    BackgroundAccess.requestOnce(this@MainActivity)
            }
        }
        catch (_: Exception) { graph.store.update { it.copy(message = "系统暂未允许后台运行，请稍后重试") } }
    }
    private fun stopMonitoring() {
        val monitor = Intent(this, MonitorService::class.java)
        stopService(monitor)
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 8) render(graph.store.state.value)
        if (requestCode == 7 && startAfterPermission) {
            startAfterPermission = false
            if (grantResults.firstOrNull() != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "通知未授权，课堂提醒将无法显示；可在设置中开启", Toast.LENGTH_LONG).show()
            }
            launchMonitoring()
        }
    }
    private fun notificationSettings() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 8)
        } else openSystemSettings(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
    }
    private fun openSettings(action: String, withPackage: Boolean = false) {
        openSystemSettings(Intent(action).apply { if (withPackage) data = Uri.parse("package:$packageName") })
    }
}
