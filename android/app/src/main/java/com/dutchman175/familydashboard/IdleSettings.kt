package com.dutchman175.familydashboard

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences

/**
 * Idle return and open-on-startup. Both are off until an adb extra or the
 * settings screen turns them on. Nothing secret is stored here.
 */
class IdleSettings(context: Context) {
    private val appContext = context.applicationContext
    private val prefs: SharedPreferences =
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    val idleReturn: Boolean
        get() = prefs.getBoolean(KEY_RETURN, false)

    val minutes: Int
        get() = IdlePolicy.clampMinutes(prefs.getInt(KEY_MINUTES, IdlePolicy.DEFAULT_MINUTES))

    val openOnBoot: Boolean
        get() = prefs.getBoolean(KEY_BOOT, false)

    fun applyIntent(intent: Intent?) {
        if (intent == null) {
            return
        }
        var changed = false
        if (intent.hasExtra(EXTRA_RETURN)) {
            putReturn(intent.getBooleanExtra(EXTRA_RETURN, false))
            intent.removeExtra(EXTRA_RETURN)
            changed = true
        }
        if (intent.hasExtra(EXTRA_MINUTES)) {
            putMinutes(intent.getIntExtra(EXTRA_MINUTES, IdlePolicy.DEFAULT_MINUTES))
            intent.removeExtra(EXTRA_MINUTES)
            changed = true
        }
        if (intent.hasExtra(EXTRA_BOOT)) {
            putBoot(intent.getBooleanExtra(EXTRA_BOOT, false))
            intent.removeExtra(EXTRA_BOOT)
            changed = true
        }
        if (changed) {
            IdleReturnService.sync(appContext)
        }
    }

    fun setIdleReturn(on: Boolean) {
        putReturn(on)
        IdleReturnService.sync(appContext)
    }

    fun setMinutes(minutes: Int) {
        putMinutes(minutes)
        IdleReturnService.sync(appContext)
    }

    fun setOpenOnBoot(on: Boolean) {
        putBoot(on)
        IdleReturnService.sync(appContext)
    }

    private fun putReturn(on: Boolean) {
        prefs.edit().putBoolean(KEY_RETURN, on).apply()
    }

    private fun putMinutes(minutes: Int) {
        prefs.edit().putInt(KEY_MINUTES, IdlePolicy.clampMinutes(minutes)).apply()
    }

    private fun putBoot(on: Boolean) {
        prefs.edit().putBoolean(KEY_BOOT, on).apply()
    }

    companion object {
        const val EXTRA_RETURN = "idle_return"
        const val EXTRA_MINUTES = "idle_minutes"
        const val EXTRA_BOOT = "open_on_boot"
        private const val PREFS = "idle_return"
        private const val KEY_RETURN = "on"
        private const val KEY_MINUTES = "minutes"
        private const val KEY_BOOT = "boot"
    }
}
