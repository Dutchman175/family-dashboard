package com.dutchman175.familydashboard

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.provider.Settings

/**
 * Reads whether the TV is on the home screen and whether media is playing.
 * Usage access is off until adb allows it. Without that, the foreground
 * app is unknown and the dashboard stays where it is.
 */
internal object IdleWatch {
    private val KNOWN_HOMES = setOf(
        "com.amazon.tv.launcher",
        "com.amazon.firetv.launcher",
        "com.amazon.tv.leanbacklauncher",
        "com.amazon.device.launch",
        "com.google.android.tvlauncher",
        "com.google.android.apps.tv.launcherx",
        "com.google.android.leanbacklauncher"
    )

    fun usageGranted(context: Context): Boolean {
        val ops = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        @Suppress("DEPRECATION")
        val mode = ops.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun overlayGranted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 23) {
            return true
        }
        return Settings.canDrawOverlays(context)
    }

    fun screenOn(context: Context): Boolean {
        val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return power.isInteractive
    }

    fun mediaPlaying(context: Context): Boolean {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return audio.isMusicActive
    }

    fun homePackages(context: Context): Set<String> {
        val names = HashSet<String>(KNOWN_HOMES)
        val probe = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val found = context.packageManager.queryIntentActivities(probe, PackageManager.MATCH_DEFAULT_ONLY)
        for (info in found) {
            val name = info.activityInfo?.packageName
            if (!name.isNullOrBlank()) {
                names.add(name)
            }
        }
        names.remove(context.packageName)
        return names
    }

    fun foreground(context: Context): Front {
        if (!usageGranted(context)) {
            return Front(null, 0L)
        }
        val manager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return Front(null, 0L)
        return try {
            val now = System.currentTimeMillis()
            val events = manager.queryEvents(now - 12 * 60 * 60 * 1000L, now)
            val event = UsageEvents.Event()
            var last: String? = null
            var at = 0L
            val resumed = if (Build.VERSION.SDK_INT >= 29) UsageEvents.Event.ACTIVITY_RESUMED else -1
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                val type = event.eventType
                if (type == UsageEvents.Event.MOVE_TO_FOREGROUND || type == resumed) {
                    val name = event.packageName
                    if (!name.isNullOrBlank()) {
                        last = name
                        at = event.timeStamp
                    }
                }
            }
            Front(last, at)
        } catch (thrown: Exception) {
            Front(null, 0L)
        }
    }

    data class Front(val packageName: String?, val atMillis: Long)
}
