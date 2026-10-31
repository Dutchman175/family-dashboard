package com.dutchman175.familydashboard

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import java.io.File

/**
 * Hands a verified APK to Android's installer. Android then shows its own
 * confirm screen. The APK must be signed with the same key as this app.
 */
internal object UpdateInstaller {
    const val ACTION = "com.dutchman175.familydashboard.INSTALL_STATUS"

    fun install(context: Context, apk: File): Boolean {
        if (!apk.isFile || apk.length() <= 0L || apk.length() > UpdateOffer.MAX_APK_BYTES) {
            return false
        }
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(context.packageName)
        params.setSize(apk.length())
        var session: PackageInstaller.Session? = null
        try {
            val sessionId = installer.createSession(params)
            val opened = installer.openSession(sessionId)
            session = opened
            opened.openWrite("update.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { input ->
                    val buf = ByteArray(8192)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) {
                            break
                        }
                        out.write(buf, 0, n)
                    }
                }
                opened.fsync(out)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            val broadcast = Intent(context, UpdateInstallReceiver::class.java)
            broadcast.action = ACTION
            val pending = PendingIntent.getBroadcast(context, sessionId, broadcast, flags)
            opened.commit(pending.intentSender)
            Log.i(TAG, "install committed")
            return true
        } catch (thrown: Exception) {
            Log.w(TAG, "install failed ${thrown.javaClass.simpleName}")
            try {
                session?.abandon()
            } catch (ignored: Exception) {
                // The session may already be closed.
            }
            return false
        } finally {
            try {
                session?.close()
            } catch (ignored: Exception) {
                // Closing after commit is best-effort.
            }
        }
    }

    private const val TAG = "FamilyDashUpdate"
}
