package com.dutchman175.familydashboard

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Checks the public version file on launch, about every 6 hours, and when
 * the screensaver asks. A newer APK is downloaded and checked against the
 * sha256 in that file before the screen offers to install it.
 */
class UpdateRepository(context: Context) {
    fun interface UpdateListener {
        fun onUpdate(versionName: String, versionCode: Int, ready: Boolean)
    }

    fun interface ManualCheckListener {
        fun onStatus(kind: String, versionName: String)
    }

    private val appContext = context.applicationContext
    private val prefs: SharedPreferences =
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val dir = File(appContext.filesDir, "updates").apply { mkdirs() }
    private val scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "update-check").apply { isDaemon = true }
    }
    private val main = Handler(Looper.getMainLooper())
    private val listeners = ArrayList<UpdateListener>()
    private val listenerLock = Any()
    private val started = AtomicBoolean(false)
    private val manualRunning = AtomicBoolean(false)

    @Volatile
    private var readyCode = 0

    @Volatile
    private var readyName = ""

    @Volatile
    private var lastNotified = ""

    @Volatile
    private var lastScreensaverCheck = 0L

    fun start() {
        if (!started.compareAndSet(false, true)) {
            return
        }
        scheduler.scheduleWithFixedDelay(
            {
                try {
                    check()
                } catch (thrown: Exception) {
                    Log.w(TAG, "check failed ${thrown.javaClass.simpleName}")
                }
            },
            0,
            INTERVAL_HOURS,
            TimeUnit.HOURS
        )
    }

    fun checkFromScreensaver() {
        val now = System.currentTimeMillis()
        if (now - lastScreensaverCheck < SCREENSAVER_GAP_MS) {
            return
        }
        lastScreensaverCheck = now
        scheduler.execute {
            try {
                check()
            } catch (thrown: Exception) {
                Log.w(TAG, "check failed ${thrown.javaClass.simpleName}")
            }
        }
    }

    /**
     * A settings-screen check. It does not wait for the 6-hour interval or
     * the screensaver gap, and a dismissed banner for this version can show
     * again when a newer build is actually downloaded.
     */
    fun checkNow(listener: ManualCheckListener) {
        if (!manualRunning.compareAndSet(false, true)) {
            return
        }
        val installed = installedVersionName()
        main.post { listener.onStatus("checking", installed) }
        scheduler.execute {
            try {
                manualCheck(listener, installed)
            } catch (thrown: Exception) {
                Log.w(TAG, "manual check failed ${thrown.javaClass.simpleName}")
                main.post { listener.onStatus("error", "") }
            } finally {
                manualRunning.set(false)
            }
        }
    }

    fun installedVersionName(): String {
        return try {
            val info = packageInfo()
            val name = info.versionName
            if (name.isNullOrBlank()) "" else name
        } catch (thrown: Exception) {
            ""
        }
    }

    fun apkFile(): File = File(dir, "pending.apk")

    fun dismiss() {
        val code = readyCode
        if (code <= 0) {
            return
        }
        prefs.edit()
            .putInt(KEY_DISMISS_CODE, code)
            .putLong(KEY_DISMISS_UNTIL, System.currentTimeMillis() + DISMISS_MS)
            .commit()
        notify(ready = false)
    }

    fun addListener(listener: UpdateListener) {
        synchronized(listenerLock) {
            listeners.add(listener)
        }
        val code = readyCode
        if (code > 0 && shouldShow(code)) {
            listener.onUpdate(readyName, code, true)
        }
    }

    fun removeListener(listener: UpdateListener) {
        synchronized(listenerLock) {
            listeners.remove(listener)
        }
    }

    private fun manualCheck(listener: ManualCheckListener, installed: String) {
        val current = currentVersionCode()
        val body = try {
            fetchText(UpdateOffer.VERSION_URL, UpdateOffer.MAX_JSON_BYTES)
        } catch (thrown: Exception) {
            Log.w(TAG, "manual version fetch failed ${thrown.javaClass.simpleName}")
            main.post { listener.onStatus("error", "") }
            return
        }
        when (val outcome = UpdateOffer.parse(body, current)) {
            UpdateOffer.Outcome.Invalid -> {
                Log.w(TAG, "manual version file rejected")
                main.post { listener.onStatus("error", "") }
            }
            UpdateOffer.Outcome.Current -> {
                Log.i(TAG, "manual version current")
                main.post { listener.onStatus("current", installed) }
            }
            is UpdateOffer.Outcome.Newer -> {
                val offer = outcome.ready
                main.post { listener.onStatus("downloading", offer.versionName) }
                prefs.edit().remove(KEY_DISMISS_CODE).remove(KEY_DISMISS_UNTIL).commit()
                downloadIfNeeded(offer, current)
                if (readyCode == offer.versionCode) {
                    lastNotified = ""
                    notify(ready = true)
                    main.post { listener.onStatus("ready", offer.versionName) }
                } else {
                    main.post { listener.onStatus("error", "") }
                }
            }
        }
    }

    private fun check() {
        val current = currentVersionCode()
        restoreReady(current)
        val body = try {
            fetchText(UpdateOffer.VERSION_URL, UpdateOffer.MAX_JSON_BYTES)
        } catch (thrown: Exception) {
            Log.w(TAG, "version fetch failed ${thrown.javaClass.simpleName}")
            if (readyCode > current) {
                notify(ready = true)
            }
            return
        }
        when (val outcome = UpdateOffer.parse(body, current)) {
            UpdateOffer.Outcome.Invalid -> {
                Log.w(TAG, "version file rejected")
                if (readyCode > current) {
                    notify(ready = true)
                }
            }
            UpdateOffer.Outcome.Current -> {
                Log.i(TAG, "version current")
                clearReady()
            }
            is UpdateOffer.Outcome.Newer -> downloadIfNeeded(outcome.ready, current)
        }
    }

    private fun restoreReady(current: Int) {
        val code = prefs.getInt(KEY_CODE, 0)
        val name = prefs.getString(KEY_NAME, null) ?: return
        val sha = prefs.getString(KEY_SHA, null) ?: return
        val file = apkFile()
        if (code <= current || !file.isFile || !shaEquals(file, sha)) {
            if (code in 1..current) {
                clearReady()
            }
            return
        }
        readyCode = code
        readyName = name
        notify(ready = true)
    }

    private fun downloadIfNeeded(offer: UpdateOffer.Ready, current: Int) {
        if (offer.versionCode <= current) {
            clearReady()
            return
        }
        val file = apkFile()
        if (readyCode == offer.versionCode && file.isFile && shaEquals(file, offer.sha256)) {
            readyName = offer.versionName
            notify(ready = true)
            return
        }
        Log.i(TAG, "download code=${offer.versionCode}")
        val part = File(dir, "pending.apk.part")
        try {
            download(offer.apkUrl, part, offer.sha256)
            if (file.exists() && !file.delete()) {
                throw IOException("replace")
            }
            if (!part.renameTo(file)) {
                throw IOException("rename")
            }
        } catch (thrown: Exception) {
            part.delete()
            Log.w(TAG, "download failed ${thrown.javaClass.simpleName}")
            return
        }
        if (!shaEquals(file, offer.sha256)) {
            file.delete()
            Log.w(TAG, "sha mismatch")
            return
        }
        prefs.edit()
            .putInt(KEY_CODE, offer.versionCode)
            .putString(KEY_NAME, offer.versionName)
            .putString(KEY_SHA, offer.sha256)
            .commit()
        readyCode = offer.versionCode
        readyName = offer.versionName
        Log.i(TAG, "update ready code=${offer.versionCode}")
        notify(ready = true)
    }

    private fun clearReady() {
        readyCode = 0
        readyName = ""
        apkFile().delete()
        File(dir, "pending.apk.part").delete()
        prefs.edit().remove(KEY_CODE).remove(KEY_NAME).remove(KEY_SHA).commit()
        notify(ready = false)
    }

    private fun shouldShow(code: Int): Boolean {
        if (prefs.getInt(KEY_DISMISS_CODE, -1) != code) {
            return true
        }
        return System.currentTimeMillis() >= prefs.getLong(KEY_DISMISS_UNTIL, 0L)
    }

    private fun notify(ready: Boolean) {
        val code = readyCode
        val show = ready && code > 0 && shouldShow(code)
        val token = if (show) "$code|$readyName" else ""
        if (token == lastNotified) {
            return
        }
        lastNotified = token
        val name = readyName
        main.post {
            val copy = synchronized(listenerLock) { ArrayList(listeners) }
            for (listener in copy) {
                listener.onUpdate(name, code, show)
            }
        }
    }

    private fun fetchText(url: String, maxBytes: Int): String {
        val conn = open(url)
        try {
            val length = contentLength(conn)
            if (length > maxBytes) {
                throw IOException("large")
            }
            conn.inputStream.use { input ->
                val buf = ByteArray(maxBytes + 1)
                var total = 0
                while (total <= maxBytes) {
                    val n = input.read(buf, total, buf.size - total)
                    if (n < 0) {
                        break
                    }
                    total += n
                }
                if (total > maxBytes) {
                    throw IOException("large")
                }
                return String(buf, 0, total, Charsets.UTF_8)
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun download(url: String, dest: File, expectedSha: String) {
        val conn = open(url)
        try {
            val length = contentLength(conn)
            if (length > UpdateOffer.MAX_APK_BYTES) {
                throw IOException("large")
            }
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            FileOutputStream(dest).use { out ->
                conn.inputStream.use { input ->
                    val buf = ByteArray(8192)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) {
                            break
                        }
                        total += n
                        if (total > UpdateOffer.MAX_APK_BYTES) {
                            throw IOException("large")
                        }
                        digest.update(buf, 0, n)
                        out.write(buf, 0, n)
                    }
                }
            }
            val actual = hex(digest.digest())
            if (!actual.equals(expectedSha, ignoreCase = true)) {
                dest.delete()
                throw IOException("sha")
            }
        } catch (thrown: Exception) {
            dest.delete()
            throw thrown
        } finally {
            conn.disconnect()
        }
    }

    private fun open(startUrl: String): HttpURLConnection {
        var current = startUrl
        for (hop in 0 until 5) {
            if (UpdateOffer.allowedHttps(current, allowQuery = hop > 0) == null) {
                throw IOException("rejected")
            }
            val conn = URL(current).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.useCaches = false
            conn.connectTimeout = 15_000
            conn.readTimeout = 60_000
            conn.requestMethod = "GET"
            conn.setRequestProperty("Accept", "*/*")
            conn.setRequestProperty("User-Agent", "FamilyDashboard")
            conn.setRequestProperty("Cache-Control", "no-cache")
            val code = conn.responseCode
            if (code in 300..399) {
                val location = conn.getHeaderField("Location")
                conn.disconnect()
                if (location.isNullOrEmpty()) {
                    throw IOException("redirect")
                }
                current = java.net.URI(current).resolve(location).toString()
                continue
            }
            if (code != 200) {
                conn.disconnect()
                Log.w(TAG, "http status=$code")
                throw IOException("status")
            }
            return conn
        }
        throw IOException("redirects")
    }

    private fun shaEquals(file: File, expected: String): Boolean {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buf = ByteArray(8192)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) {
                        break
                    }
                    digest.update(buf, 0, n)
                }
            }
            hex(digest.digest()).equals(expected, ignoreCase = true)
        } catch (thrown: Exception) {
            false
        }
    }

    private fun contentLength(conn: HttpURLConnection): Long {
        return if (Build.VERSION.SDK_INT >= 24) {
            conn.contentLengthLong
        } else {
            conn.contentLength.toLong()
        }
    }

    private fun packageInfo() = if (Build.VERSION.SDK_INT >= 33) {
        appContext.packageManager.getPackageInfo(
            appContext.packageName,
            PackageManager.PackageInfoFlags.of(0)
        )
    } else {
        @Suppress("DEPRECATION")
        appContext.packageManager.getPackageInfo(appContext.packageName, 0)
    }

    private fun currentVersionCode(): Int {
        val info = packageInfo()
        return if (Build.VERSION.SDK_INT >= 28) {
            info.longVersionCode.toInt()
        } else {
            @Suppress("DEPRECATION")
            info.versionCode
        }
    }

    private fun hex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            sb.append("%02x".format(b.toInt() and 0xff))
        }
        return sb.toString()
    }

    private companion object {
        const val TAG = "FamilyDashUpdate"
        const val PREFS = "family_dashboard"
        const val KEY_CODE = "update_ready_code"
        const val KEY_NAME = "update_ready_name"
        const val KEY_SHA = "update_ready_sha"
        const val KEY_DISMISS_CODE = "update_dismiss_code"
        const val KEY_DISMISS_UNTIL = "update_dismiss_until"
        const val INTERVAL_HOURS = 6L
        const val DISMISS_MS = 6L * 60L * 60L * 1000L
        const val SCREENSAVER_GAP_MS = 60_000L
    }
}
