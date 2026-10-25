package com.dutchman175.familydashboard

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Date
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Downloads the calendar off the main thread and keeps only the small window
 * of upcoming events. The ICS URL is never logged or handed to the WebView.
 */
class CalendarRepository(context: Context) {
    fun interface SnapshotListener {
        fun onSnapshot(json: String)
    }

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "calendar-fetch").apply { isDaemon = true }
    }
    private val listeners = ArrayList<SnapshotListener>()
    private val listenerLock = Any()
    private val started = AtomicBoolean(false)

    @Volatile
    private var generation = 0

    @Volatile
    private var snapshot = CalendarSnapshot.UNCONFIGURED

    @Volatile
    private var lastEnqueue = 0L

    fun start() {
        if (!started.compareAndSet(false, true)) {
            return
        }
        publishInitial()
        lastEnqueue = System.currentTimeMillis()
        scheduler.scheduleWithFixedDelay(
            { safeFetch() },
            0,
            REFRESH_MINUTES,
            TimeUnit.MINUTES
        )
    }

    fun applyIntent(intent: Intent?) {
        if (intent == null || !intent.hasExtra(CalendarUrl.EXTRA)) {
            return
        }
        when (val action = CalendarUrl.interpret(intent.getStringExtra(CalendarUrl.EXTRA))) {
            CalendarUrl.Action.Clear -> clear()
            CalendarUrl.Action.Ignore -> Unit
            is CalendarUrl.Action.Save -> setUrl(action.url)
        }
        intent.removeExtra(CalendarUrl.EXTRA)
    }

    fun requestRefresh() {
        val now = System.currentTimeMillis()
        if (now - lastEnqueue < THROTTLE_MS) {
            return
        }
        lastEnqueue = now
        scheduler.execute { safeFetch() }
    }

    fun snapshotJson(): String = snapshot

    fun addListener(listener: SnapshotListener) {
        synchronized(listenerLock) {
            listeners.add(listener)
        }
        listener.onSnapshot(snapshot)
    }

    fun removeListener(listener: SnapshotListener) {
        synchronized(listenerLock) {
            listeners.remove(listener)
        }
    }

    private fun setUrl(url: String) {
        generation++
        val same = url == prefs.getString(KEY_URL, null)
        val editor = prefs.edit().putString(KEY_URL, url)
        if (!same) {
            editor.remove(KEY_ROWS).remove(KEY_MONTH)
        }
        editor.commit()
        val rows = if (same) storedRows() else emptyList()
        val month = if (same) storedMonth() else emptyList()
        remember(stale = false, loading = rows.isEmpty(), rows = rows, month = month)
        lastEnqueue = System.currentTimeMillis()
        notifyListeners()
        scheduler.execute { safeFetch() }
    }

    private fun clear() {
        generation++
        prefs.edit().remove(KEY_URL).remove(KEY_ROWS).remove(KEY_MONTH).commit()
        snapshot = CalendarSnapshot.UNCONFIGURED
        notifyListeners()
    }

    private fun publishInitial() {
        val url = prefs.getString(KEY_URL, null)
        if (url.isNullOrEmpty()) {
            snapshot = CalendarSnapshot.UNCONFIGURED
            return
        }
        val rows = storedRows()
        remember(stale = false, loading = rows.isEmpty(), rows = rows, month = storedMonth())
    }

    private fun safeFetch() {
        val gen = generation
        try {
            fetchOnce(gen)
        } catch (thrown: Throwable) {
            markStale(gen)
        }
    }

    private fun fetchOnce(gen: Int) {
        val url = prefs.getString(KEY_URL, null)
        if (url.isNullOrEmpty()) {
            if (generation != gen) {
                return
            }
            snapshot = CalendarSnapshot.UNCONFIGURED
            notifyListeners()
            return
        }
        val ics = download(url)
        val covered = IcsWindow.covered(ics, Date(), ChicagoTime.ZONE)
        if (generation != gen) {
            return
        }
        prefs.edit()
            .putString(KEY_ROWS, CalendarSnapshot.encodeRows(covered.upcoming))
            .putString(KEY_MONTH, CalendarSnapshot.encodeRows(covered.month))
            .commit()
        if (generation != gen) {
            return
        }
        remember(stale = false, loading = false, rows = covered.upcoming, month = covered.month)
        notifyListeners()
    }

    private fun markStale(gen: Int) {
        if (generation != gen) {
            return
        }
        val url = prefs.getString(KEY_URL, null)
        snapshot = if (url.isNullOrEmpty()) {
            CalendarSnapshot.UNCONFIGURED
        } else {
            CalendarSnapshot.snapshot(
                configured = true,
                stale = true,
                loading = false,
                rows = storedRows(),
                nowMillis = System.currentTimeMillis(),
                zone = ChicagoTime.ZONE,
                monthRows = storedMonth()
            )
        }
        notifyListeners()
    }

    private fun storedRows(): List<IcsWindow.Row> {
        return CalendarSnapshot.decodeRows(prefs.getString(KEY_ROWS, null))
    }

    private fun storedMonth(): List<IcsWindow.Row> {
        return CalendarSnapshot.decodeRows(prefs.getString(KEY_MONTH, null))
    }

    private fun remember(
        stale: Boolean,
        loading: Boolean,
        rows: List<IcsWindow.Row>,
        month: List<IcsWindow.Row>
    ) {
        snapshot = CalendarSnapshot.snapshot(
            configured = true,
            stale = stale,
            loading = loading,
            rows = rows,
            nowMillis = System.currentTimeMillis(),
            zone = ChicagoTime.ZONE,
            monthRows = month
        )
    }

    private fun notifyListeners() {
        val json = snapshot
        val copy = synchronized(listenerLock) { ArrayList(listeners) }
        for (listener in copy) {
            listener.onSnapshot(json)
        }
    }

    /**
     * Reads at most [MAX_BYTES]. The returned text is for the parser only and
     * must not be logged: iCloud paths are the secret.
     */
    private fun download(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = true
            connection.connectTimeout = 20_000
            connection.readTimeout = 30_000
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "text/calendar, text/plain, */*")
            connection.setRequestProperty("User-Agent", "FamilyDashboard/1.1")
            val code = connection.responseCode
            val protocol = connection.url?.protocol
            if (protocol != "https") {
                throw java.io.IOException("calendar was not https")
            }
            if (code !in 200..299) {
                throw java.io.IOException("calendar http " + code)
            }
            return readLimited(connection.inputStream, MAX_BYTES)
        } finally {
            connection.disconnect()
        }
    }

    private fun readLimited(input: InputStream, max: Int): String {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var total = 0
        input.use { stream ->
            while (true) {
                val n = stream.read(buf)
                if (n < 0) {
                    break
                }
                total += n
                if (total > max) {
                    throw java.io.IOException("calendar too large")
                }
                out.write(buf, 0, n)
            }
        }
        return out.toString("UTF-8")
    }

    private companion object {
        const val PREFS = "family_dashboard"
        const val KEY_URL = "calendar_url"
        const val KEY_ROWS = "calendar_rows"
        const val KEY_MONTH = "calendar_month_rows"
        const val REFRESH_MINUTES = 15L
        const val THROTTLE_MS = 30_000L
        const val MAX_BYTES = 4 * 1024 * 1024
    }
}
