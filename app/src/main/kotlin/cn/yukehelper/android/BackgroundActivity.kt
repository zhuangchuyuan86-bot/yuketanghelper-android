package cn.yukehelper.android

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.LinearLayout
import android.widget.ScrollView
import java.util.Locale

/** Standard system intents work across brands, with app-details/system-settings fallbacks. */
class BackgroundActivity : Activity() {
    private lateinit var body: LinearLayout
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Notifications(this)
        val root = column()
        val header = row().apply { setPadding(dp(16), dp(12), dp(16), dp(8)) }
        header.addView(action("返回", false) { finish() }, touchParams(dp(76)))
        header.addView(label("后台运行设置", 20f, bold = true), LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(10) })
        root.addView(header)
        body = column().apply { setPadding(dp(20), dp(12), dp(20), dp(24)) }
        root.addView(ScrollView(this).apply { addView(body) }, LinearLayout.LayoutParams(-1, 0, 1f))
        showContent(root)
    }
    override fun onResume() { super.onResume(); render() }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 8) render()
    }
    private fun render() {
        body.removeAllViews()
        val manager = getSystemService(NotificationManager::class.java)
        val unrestricted = BackgroundAccess.allowed(this)
        val allowed = manager.areNotificationsEnabled()
        val classroom = manager.getNotificationChannel("classroom")?.importance != NotificationManager.IMPORTANCE_NONE
        val homework = manager.getNotificationChannel("homework")?.importance != NotificationManager.IMPORTANCE_NONE
        body.block(label("${Build.MODEL} · Android ${Build.VERSION.RELEASE}", 16f, bold = true))
        body.block(card().apply {
            addView(label("通知与提醒", 18f, bold = true)); gap(8)
            addView(label(when { !allowed -> "通知未开启"; !classroom -> "课堂通知已关闭"; !homework -> "作业通知已关闭"; else -> "通知已开启" }, 14f, Palette.muted)); gap()
            block(action("通知设置", false) {
                if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 8)
                } else openSystemSettings(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
            }, 8)
            block(action("课堂事件通知类别", false) {
                openSystemSettings(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                    .putExtra(Settings.EXTRA_CHANNEL_ID, "classroom"))
            }, 8)
            block(action("作业提醒通知类别", false) {
                openSystemSettings(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                    .putExtra(Settings.EXTRA_CHANNEL_ID, "homework"))
            }, 0)
        })
        body.block(card().apply {
            addView(label("电池与后台", 18f, bold = true)); gap(8)
            addView(label(if (unrestricted) "系统电池优化：已豁免" else "允许后台运行，减少锁屏断连。", 14f, Palette.muted)); gap()
            block(action(if (unrestricted) "已允许后台运行" else "允许后台运行", !unrestricted) {
                BackgroundAccess.request(this@BackgroundActivity)
            }.apply { isEnabled = !unrestricted }, 8)
            block(action("本应用后台设置", false) {
                openSystemSettings(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$packageName")))
            }, 8)
            addView(label(brandAdvice(), 14f, Palette.muted))
        })
        body.block(label("监测最长 4 小时，请勿清理应用后台。", 13f, Palette.muted))
    }
    private fun brandAdvice(): String {
        val brand = "${Build.MANUFACTURER} ${Build.BRAND}".lowercase(Locale.ROOT)
        return when {
            listOf("vivo", "iqoo").any(brand::contains) -> "vivo / iQOO：还需在系统中允许“后台高耗电”和“自启动”。"
            listOf("xiaomi", "redmi", "poco").any(brand::contains) -> "小米：省电策略选“无限制”，并允许后台自启动。"
            listOf("oppo", "realme", "oneplus").any(brand::contains) -> "请在系统中允许后台活动与自启动。"
            listOf("huawei", "honor").any(brand::contains) -> "应用启动管理中允许自启动和后台活动。"
            "samsung" in brand -> "请将本应用加入“从不自动休眠”列表。"
            else -> "请允许本应用后台活动与自启动。"
        }
    }
}
