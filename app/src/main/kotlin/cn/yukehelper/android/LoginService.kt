package cn.yukehelper.android

import android.app.*
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import cn.yukehelper.core.*
import kotlinx.coroutines.*

/** Short, user-started protection while they confirm the QR code in WeChat. Not automatically restarted. */
class LoginService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val graph get() = application as HelperApplication
    private lateinit var manager: NotificationManager
    private var wakeLock: PowerManager.WakeLock? = null
    private var watched: Job? = null

    override fun onCreate() {
        super.onCreate()
        manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "扫码登录", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == CANCEL) {
            graph.qrLogin.cancel(intent.getLongExtra("attempt", -1), "登录已取消，可重新获取二维码")
            if (!graph.qrLogin.state.value.active) stopLogin()
            return START_NOT_STICKY
        }
        try { startForeground(ID, notification(graph.qrLogin.state.value)) }
        catch (_: RuntimeException) { stopSelf(); return START_NOT_STICKY }
        if (!graph.qrLogin.state.value.active) { stopLogin(); return START_NOT_STICKY }
        if (watched?.isActive == true) return START_NOT_STICKY
        try {
            wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:qr-login").apply {
                setReferenceCounted(false); acquire(QrLoginController.WAIT_MILLIS + 60_000)
            }
        } catch (_: RuntimeException) { /* Notification remains useful when the device refuses a wake lock. */ }
        watched = scope.launch {
            graph.qrLogin.state.collect { state ->
                if (state.active) manager.notify(ID, notification(state)) else stopLogin()
            }
        }
        return START_NOT_STICKY
    }

    private fun notification(state: QrLoginSnapshot): Notification {
        val open = PendingIntent.getActivity(this, 71, Intent(this, LoginActivity::class.java).apply {
            putExtra("domain", state.domain); putExtra("mode", "qr")
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val cancel = PendingIntent.getService(this, 72, Intent(this, LoginService::class.java).setAction(CANCEL)
            .putExtra("attempt", state.attempt), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_notification)
            .apply { if (Build.VERSION.SDK_INT >= 31) setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE) }
            .setContentTitle("雨课助手 · 正在等待登录")
            .setContentText(state.message.ifBlank { "微信确认后请返回助手；最长等待 5 分钟" })
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).setCategory(Notification.CATEGORY_SERVICE)
            .addAction(Notification.Action.Builder(null, "取消登录", cancel).build()).build()
    }

    private fun stopLogin() { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
    override fun onTimeout(startId: Int, fgsType: Int) {
        graph.qrLogin.cancel(message = "系统已结束登录等待，请返回助手重新获取二维码")
        stopLogin()
    }
    override fun onDestroy() {
        scope.cancel()
        runCatching { wakeLock?.let { if (it.isHeld) it.release() } }
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
    companion object {
        private const val CHANNEL = "qr_login"
        private const val ID = 71
        const val CANCEL = "cn.yukehelper.CANCEL_LOGIN"
    }
}
