package com.dutchman175.familydashboard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Wakes every half minute while idle return is on. If the home screen has
 * been in front with nothing playing, it brings the dashboard forward.
 * The check is a short usage and audio read. It does not hold a wake lock.
 */
class IdleReturnService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "idle-watch").apply { isDaemon = true }
    }
    private val busy = AtomicBoolean(false)
    private var homeSince: Long? = null
    private var lastOpen = 0L
    private var homes: Set<String> = emptySet()

    private val tick = Runnable {
        if (busy.compareAndSet(false, true)) {
            worker.execute {
                val reading = read()
                handler.post {
                    busy.set(false)
                    if (!running) {
                        return@post
                    }
                    apply(reading)
                }
            }
        }
    }

    @Volatile
    private var running = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground()
        running = true
        val settings = IdleSettings(this)
        val boot = intent?.getBooleanExtra(EXTRA_BOOT, false) == true
        if (boot && settings.openOnBoot) {
            handler.removeCallbacks(bootOpen)
            handler.postDelayed(bootOpen, IdlePolicy.BOOT_DELAY_MS)
        }
        if (!settings.idleReturn) {
            handler.removeCallbacks(tick)
            if (!boot || !settings.openOnBoot) {
                stopSelf()
                return START_NOT_STICKY
            }
            handler.postDelayed({
                if (!IdleSettings(this).idleReturn) {
                    stopSelf()
                }
            }, IdlePolicy.BOOT_DELAY_MS + 10_000L)
            return START_NOT_STICKY
        }
        homes = IdleWatch.homePackages(this)
        handler.removeCallbacks(tick)
        handler.postDelayed(tick, IdlePolicy.CHECK_MS)
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        handler.removeCallbacksAndMessages(null)
        worker.shutdownNow()
        super.onDestroy()
    }

    private val bootOpen = Runnable {
        if (!running || !IdleSettings(this).openOnBoot) {
            return@Runnable
        }
        if (IdleWatch.mediaPlaying(this)) {
            Log.i(TAG, "boot open skipped, media is playing")
            return@Runnable
        }
        bringForward("boot")
    }

    private fun read(): Reading {
        val front = IdleWatch.foreground(this)
        return Reading(
            screenOn = IdleWatch.screenOn(this),
            media = IdleWatch.mediaPlaying(this),
            foreground = front.packageName,
            foregroundAt = front.atMillis,
            enabled = IdleSettings(this).idleReturn,
            minutes = IdleSettings(this).minutes
        )
    }

    private fun apply(reading: Reading) {
        if (!reading.enabled) {
            stopSelf()
            return
        }
        val now = System.currentTimeMillis()
        val at = if (reading.foregroundAt > 0L) reading.foregroundAt else now
        homeSince = IdlePolicy.nextHomeSince(
            homeSince,
            reading.foreground,
            at,
            reading.screenOn,
            packageName,
            homes
        )
        if (
            IdlePolicy.shouldOpen(
                reading.enabled,
                reading.minutes,
                reading.screenOn,
                reading.media,
                reading.foreground,
                homeSince,
                now,
                lastOpen,
                packageName,
                homes
            )
        ) {
            bringForward("idle")
            homeSince = null
        }
    }

    private fun bringForward(reason: String) {
        lastOpen = System.currentTimeMillis()
        try {
            val open = Intent(this, MainActivity::class.java)
            open.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            )
            startActivity(open)
            Log.i(TAG, "opened dashboard $reason")
        } catch (thrown: Exception) {
            Log.w(TAG, "open failed ${thrown.javaClass.simpleName}")
        }
    }

    private fun startInForeground() {
        val notification = notification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTE_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTE_ID, notification)
        }
    }

    private fun notification(): Notification {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(CHANNEL, "Idle", NotificationManager.IMPORTANCE_MIN)
            channel.setSound(null, null)
            channel.enableVibration(false)
            manager.createNotificationChannel(channel)
        }
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle(getString(R.string.idle_note_title))
            .setContentText(getString(R.string.idle_note_body))
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setOngoing(true)
            .build()
    }

    private data class Reading(
        val screenOn: Boolean,
        val media: Boolean,
        val foreground: String?,
        val foregroundAt: Long,
        val enabled: Boolean,
        val minutes: Int
    )

    companion object {
        const val EXTRA_BOOT = "boot"
        private const val NOTE_ID = 41
        private const val CHANNEL = "idle"
        private const val TAG = "FamilyDashIdle"

        fun sync(context: Context) {
            val app = context.applicationContext
            val settings = IdleSettings(app)
            val intent = Intent(app, IdleReturnService::class.java)
            if (!settings.idleReturn) {
                app.stopService(intent)
                return
            }
            try {
                if (Build.VERSION.SDK_INT >= 26) {
                    app.startForegroundService(intent)
                } else {
                    app.startService(intent)
                }
            } catch (thrown: Exception) {
                Log.w(TAG, "sync failed ${thrown.javaClass.simpleName}")
            }
        }

        fun startFromBoot(context: Context) {
            val app = context.applicationContext
            val settings = IdleSettings(app)
            if (!settings.idleReturn && !settings.openOnBoot) {
                return
            }
            val intent = Intent(app, IdleReturnService::class.java)
            intent.putExtra(EXTRA_BOOT, true)
            try {
                if (Build.VERSION.SDK_INT >= 26) {
                    app.startForegroundService(intent)
                } else {
                    app.startService(intent)
                }
            } catch (thrown: Exception) {
                Log.w(TAG, "boot start failed ${thrown.javaClass.simpleName}")
            }
        }
    }
}
