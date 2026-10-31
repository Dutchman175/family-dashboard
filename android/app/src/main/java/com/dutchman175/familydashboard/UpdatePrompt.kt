package com.dutchman175.familydashboard

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.KeyEvent
import android.view.View
import android.widget.TextView
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Small banner on the dashboard. OK installs. Back hides it for about 6 hours.
 * If Android still blocks unknown-app installs, a one-time explanation opens
 * that settings page.
 */
class UpdatePrompt(private val activity: Activity, root: View) {
    private val repository = (activity.application as DashboardApp).updates
    private val banner = root.findViewById<View>(R.id.update_banner)
    private val permission = root.findViewById<View>(R.id.update_permission)
    private val permissionBody = root.findViewById<TextView>(R.id.update_permission_body)
    private val handler = Handler(Looper.getMainLooper())
    private val installing = AtomicBoolean(false)
    private val listener = UpdateRepository.UpdateListener { _, _, ready ->
        activity.runOnUiThread {
            if (!permissionShown) {
                banner.visibility = if (ready) View.VISIBLE else View.GONE
            }
        }
    }

    private var permissionShown = false
    private var destroyed = false

    fun resume() {
        if (destroyed) {
            return
        }
        if (permissionShown && !needsUnknownSources()) {
            permissionShown = false
            permission.visibility = View.GONE
        }
        repository.removeListener(listener)
        repository.addListener(listener)
    }

    fun pause() {
        repository.removeListener(listener)
    }

    fun destroy() {
        destroyed = true
        handler.removeCallbacksAndMessages(null)
        repository.removeListener(listener)
    }

    fun handleKey(event: KeyEvent?): Boolean {
        if (destroyed || event == null) {
            return false
        }
        if (permissionShown) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                if (isOk(event.keyCode)) {
                    openSettings()
                } else if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                    hidePermission()
                }
            }
            return true
        }
        if (banner.visibility != View.VISIBLE) {
            return false
        }
        val ok = isOk(event.keyCode)
        val back = event.keyCode == KeyEvent.KEYCODE_BACK
        if (!ok && !back) {
            return false
        }
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            if (ok) {
                onInstallPressed()
            } else {
                dismiss()
            }
        }
        return true
    }

    fun consumeBack(): Boolean {
        if (permissionShown) {
            hidePermission()
            return true
        }
        if (banner.visibility == View.VISIBLE) {
            dismiss()
            return true
        }
        return false
    }

    private fun onInstallPressed() {
        if (needsUnknownSources()) {
            showPermission()
            return
        }
        if (!installing.compareAndSet(false, true)) {
            return
        }
        val file: File = repository.apkFile()
        val started = UpdateInstaller.install(activity, file)
        if (!started) {
            installing.set(false)
            return
        }
        handler.postDelayed({ installing.set(false) }, 5_000L)
    }

    private fun showPermission() {
        permissionShown = true
        permissionBody.setText(R.string.update_permission_body)
        banner.visibility = View.GONE
        permission.visibility = View.VISIBLE
    }

    private fun hidePermission() {
        permissionShown = false
        permission.visibility = View.GONE
        repository.removeListener(listener)
        repository.addListener(listener)
    }

    private fun openSettings() {
        val intent = if (Build.VERSION.SDK_INT >= 26) {
            Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:" + activity.packageName)
            )
        } else {
            Intent(Settings.ACTION_SECURITY_SETTINGS)
        }
        try {
            activity.startActivity(intent)
        } catch (thrown: ActivityNotFoundException) {
            permissionBody.setText(R.string.update_permission_manual)
        }
    }

    private fun dismiss() {
        banner.visibility = View.GONE
        repository.dismiss()
    }

    private fun needsUnknownSources(): Boolean {
        if (Build.VERSION.SDK_INT >= 26) {
            return !activity.packageManager.canRequestPackageInstalls()
        }
        return Settings.Secure.getInt(
            activity.contentResolver,
            Settings.Secure.INSTALL_NON_MARKET_APPS,
            0
        ) != 1
    }

    private fun isOk(code: Int): Boolean {
        return RemoteKeys.isOk(code)
    }
}
