package com.dutchman175.familydashboard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Starts the idle watcher after a reboot when idle return or open-on-startup
 * was turned on. A force-stop clears this until the app is opened again.
 */
class IdleBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != ACTION_QUICKBOOT) {
            return
        }
        IdleReturnService.startFromBoot(context)
    }

    companion object {
        private const val ACTION_QUICKBOOT = "android.intent.action.QUICKBOOT_POWERON"
    }
}
