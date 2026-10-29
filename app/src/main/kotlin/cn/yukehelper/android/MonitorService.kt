package cn.yukehelper.android

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.Build
import android.os.PowerManager
import cn.yukehelper.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

class MonitorService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val graph get() = application as HelperApplication
    private lateinit var notifications: Notifications
    private lateinit var voice: VoiceNotifier
    private var coordinator: Job? = null
    private var limit: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private data class Plan(val account: Account, val poll: Int, val excluded: Set<String>, val rules: CourseSettings)
    override fun onCreate() {
        super.onCreate()
        notifications = Notifications(this)
        voice = VoiceNotifier(this) { message -> graph.store.event(ClassroomEvent(EventKind.SYSTEM, message)) }
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) { stopMonitoring("已停止课堂监测"); return START_NOT_STICKY }
        if (graph.store.state.value.account == null) { stopSelf(); return START_NOT_STICKY }
        try {
            val notification = notifications.foreground("正在检查课堂…")
            if (Build.VERSION.SDK_INT >= 29) startForeground(Notifications.FOREGROUND_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else startForeground(Notifications.FOREGROUND_ID, notification)
        } catch (_: Exception) {
            graph.store.update { it.copy(monitoring = false, message = "系统暂未允许启动后台监测，请在应用前台重试") }
            stopSelf(); return START_NOT_STICKY
        }
        if (coordinator?.isActive == true) return START_NOT_STICKY
        graph.store.update { it.copy(monitoring = true, message = "") }
        try {
            wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:classroom").apply {
                setReferenceCounted(false); acquire(MAX_SESSION)
            }
        } catch (_: RuntimeException) {
            graph.store.event(ClassroomEvent(EventKind.SYSTEM, "系统未允许保持唤醒，锁屏时监测可能延迟，请检查后台运行设置"))
        }
        coordinator = scope.launch {
            val workers = mutableMapOf<String, Pair<Plan, Job>>()
            try {
                graph.store.state.map { state -> state.configuration.profiles.filter {
                    it.monitored && (it.account.demo || it.account.sessionId.isNotBlank())
                }.associate { it.account.key to Plan(it.account, it.pollSeconds, it.excludedCourses, state.configuration.courseSettings) } }.distinctUntilChanged().collect { plans ->
                    for (key in workers.keys.toList()) {
                        if (workers[key]?.first != plans[key]) {
                            workers.remove(key)?.second?.cancelAndJoin()
                            graph.store.runtime(key) { AccountRuntime() }
                        }
                    }
                    if (plans.isEmpty()) { stopMonitoring("没有已启用的有效账号，请登录或开启账号监测"); return@collect }
                    for ((key, plan) in plans) if (workers[key]?.second?.isActive != true) {
                        workers[key] = plan to scope.launch { monitor(plan.account, plan.poll) }
                    }
                    notifications.update("正在监测 ${plans.size} 个账号 · 可在通知中停止")
                }
            } finally { workers.values.forEach { it.second.cancel() } }
        }
        limit = scope.launch { delay(MAX_SESSION); stopMonitoring("本次监测已满 4 小时，可按需再次开启") }
        return START_NOT_STICKY
    }
    private suspend fun monitor(account: Account, poll: Int) {
        val key = account.key
        try {
            graph.store.event(ClassroomEvent(EventKind.SYSTEM, if (account.demo) "演示监测已开始，操作仅模拟" else "账号课堂监测已开始"), key)
            val gateway = if (account.demo) DemoGateway() else graph.api
            val actions: AutomationGateway = if (account.demo) DemoActions else graph.api
            coroutineScope {
                launch {
                    while (isActive) {
                        graph.homework.sync(account)
                        delay(HomeworkSynchronizer.INTERVAL)
                    }
                }
                MonitorEngine(gateway, poll * 1000L,
                    excludedCourses = { graph.store.profile(key)?.excludedCourses.orEmpty() },
                    automationFactory = { sessionScope, user, access, emit ->
                        LessonAutomation(sessionScope, user, access, actions,
                            if (account.demo) AiAnswerer { q, _ -> if (q.type == 5) Answer.Text("演示答案") else Answer.Choices(q.options.take(1)) } else graph.ai,
                            { graph.store.state.value.configuration.effectiveCourseSettings(key, access.lesson.classroomId) },
                            { graph.store.state.value.configuration.ai }, emit, quickAnswerLedger = graph.quickAnswerLedger)
                    }).run(account) { update ->
                    if (graph.store.profile(key)?.account !== account) return@run
                    when (update) {
                        is MonitorUpdate.Poll -> graph.store.runtime(key) { it.copy(lessons = update.lessons, lastPoll = update.at) }
                        is MonitorUpdate.Connection -> graph.store.runtime(key) { state -> state.copy(connected = state.connected.toMutableSet().apply {
                            if (update.connected) add(update.lessonId) else remove(update.lessonId)
                        }) }
                        is MonitorUpdate.Event -> {
                            graph.store.event(update.event, key)
                            val runtime = graph.store.state.value.runtimes[key]
                            val course = runtime?.lessons?.firstOrNull { it.id == update.event.lessonId }?.classroomId
                            val config = graph.store.state.value.configuration
                            val settings = course?.let { config.effectiveCourseSettings(key, it) } ?: config.courseSettings
                            if (settings.enabled && settings.notification.accepts(update.event.kind)) notifications.event(update.event, account)
                            if (settings.enabled && settings.voice.accepts(update.event.kind)) voice.speak("${account.name}，${update.event.course}，${update.event.summary}")
                        }
                    }
                }
            }
        } catch (e: CancellationException) { throw e }
        catch (_: SessionExpired) {
            graph.store.expire(account)
            notifications.event(ClassroomEvent(EventKind.ERROR, "账号登录失效，请重新登录"), account)
        } catch (_: Exception) {
            graph.store.event(ClassroomEvent(EventKind.ERROR, "此账号监测已中断，请关闭并重新开启此账号的监测开关"), key)
            graph.store.editProfile(key) { it.copy(monitored = false) }
        }
    }
    private fun stopMonitoring(message: String) {
        coordinator?.cancel(); limit?.cancel()
        graph.store.update { it.copy(monitoring = false, runtimes = emptyMap(), message = message) }
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
    }
    override fun onTimeout(startId: Int, fgsType: Int) { stopMonitoring("系统已结束本次后台运行，请返回应用后重新开启") }
    override fun onDestroy() {
        scope.cancel(); voice.close()
        runCatching { wakeLock?.let { if (it.isHeld) it.release() } }
        graph.store.update { it.copy(monitoring = false, runtimes = emptyMap()) }
        super.onDestroy()
    }
    companion object {
        const val START = "cn.yukehelper.START"
        const val STOP = "cn.yukehelper.STOP"
        const val MAX_SESSION = 4 * 60 * 60 * 1000L
    }
}
