package cn.yukehelper.android

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/** Request the standard Android exemption; vendor high-power switches remain system-owned. */
internal object BackgroundAccess {
    fun allowed(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

    fun requestOnce(activity: Activity) {
        if (allowed(activity) || activity.isFinishing || activity.isDestroyed) return
        val preferences = activity.getSharedPreferences("background_access", Context.MODE_PRIVATE)
        if (preferences.getBoolean("battery_request_shown", false)) return
        // Remember only that we asked. Permission status always comes from PowerManager.
        preferences.edit().putBoolean("battery_request_shown", true).apply()
        request(activity)
    }

    @SuppressLint("BatteryLife") // Continuous classroom WebSocket monitoring needs background connectivity.
    fun request(activity: Activity) {
        if (allowed(activity)) return
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${activity.packageName}"))
        try { activity.startActivity(direct); return } catch (_: RuntimeException) { }
        activity.openSystemSettings(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }
}
