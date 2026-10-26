package com.dutchman175.familydashboard

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.util.Log
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Subscribes to one ntfy topic while a dashboard screen is open. The topic
 * stays in private preferences. It is never logged and never sent to the page.
 */
class MessageRepository(context: Context) {
    fun interface MessageListener {
        fun onMessages(json: String)
    }

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val timer = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "message-expire").apply { isDaemon = true }
    }
    private val listeners = ArrayList<MessageListener>()
    private val listenerLock = Any()
    private val state = Any()
    private val started = AtomicBoolean(false)
    private val messages = ArrayList<MessageBoard.Note>()

    @Volatile
    private var generation = 0

    @Volatile
    private var loopGen = -1

    @Volatile
    private var snapshot = MessageBoard.toJson(false, emptyList(), false)

    @Volatile
    private var lastNotified = ""

    @Volatile
    private var pulseUntil = 0L

    @Volatile
    private var active: HttpURLConnection? = null

    @Volatile
    private var loop: Thread? = null

    fun start() {
        if (!started.compareAndSet(false, true)) {
            return
        }
        synchronized(state) {
            messages.clear()
            messages.addAll(MessageBoard.expire(MessageBoard.decode(prefs.getString(KEY_ROWS, null)), System.currentTimeMillis()))
            if (messages.isEmpty()) {
                prefs.edit().remove(KEY_ROWS).commit()
            }
            snapshot = MessageBoard.toJson(hasTopic(), messages.toList(), false)
        }
        scheduleMidnight()
        timer.scheduleWithFixedDelay(
            {
                try {
                    publish(false)
                } catch (thrown: Exception) {
                    Log.w(TAG, "expire failed ${thrown.javaClass.simpleName}")
                }
            },
            60,
            60,
            TimeUnit.SECONDS
        )
    }

    fun applyIntent(intent: Intent?) {
        if (intent == null || !intent.hasExtra(MessageTopic.EXTRA)) {
            Log.i(TAG, "extra received=false")
            return
        }
        val raw = intent.getStringExtra(MessageTopic.EXTRA)
        when (val action = MessageTopic.interpret(raw)) {
            MessageTopic.Action.Clear -> {
                Log.i(TAG, "extra received=true action=clear")
                clearTopic()
            }
            MessageTopic.Action.Ignore -> {
                Log.i(TAG, "extra received=true action=ignore len=${raw?.length ?: 0}")
            }
            is MessageTopic.Action.Save -> {
                Log.i(TAG, "extra received=true action=save len=${action.topic.length}")
                setTopic(action.topic)
            }
        }
        intent.removeExtra(MessageTopic.EXTRA)
    }

    fun snapshotJson(): String = snapshot

    fun addListener(listener: MessageListener) {
        synchronized(listenerLock) {
            listeners.add(listener)
        }
        listener.onMessages(snapshot)
        ensureLoop()
    }

    fun removeListener(listener: MessageListener) {
        val empty = synchronized(listenerLock) {
            listeners.remove(listener)
            listeners.isEmpty()
        }
        if (empty) {
            synchronized(state) {
                generation++
                active?.disconnect()
            }
            Log.i(TAG, "loop idle")
        }
    }

    private fun setTopic(topic: String) {
        val same = topic == prefs.getString(KEY_TOPIC, null)
        val editor = prefs.edit().putString(KEY_TOPIC, topic)
        if (!same) {
            editor.remove(KEY_ROWS)
        }
        editor.commit()
        synchronized(state) {
            if (!same) {
                messages.clear()
                generation++
                pulseUntil = 0L
                active?.disconnect()
            }
            snapshot = MessageBoard.toJson(true, messages.toList(), false)
            lastNotified = ""
        }
        publish(false)
        ensureLoop()
    }

    private fun clearTopic() {
        prefs.edit().remove(KEY_TOPIC).remove(KEY_ROWS).commit()
        synchronized(state) {
            messages.clear()
            generation++
            pulseUntil = 0L
            active?.disconnect()
            snapshot = MessageBoard.toJson(false, emptyList(), false)
            lastNotified = ""
        }
        publish(false)
    }

    private fun hasTopic(): Boolean {
        val saved = prefs.getString(KEY_TOPIC, null) ?: return false
        return MessageTopic.interpret(saved) is MessageTopic.Action.Save
    }

    private fun topicOrNull(): String? {
        val saved = prefs.getString(KEY_TOPIC, null) ?: return null
        val action = MessageTopic.interpret(saved)
        return if (action is MessageTopic.Action.Save) action.topic else null
    }

    private fun ensureLoop() {
        val topic = topicOrNull() ?: return
        val watching = synchronized(listenerLock) { listeners.isNotEmpty() }
        if (!watching) {
            return
        }
        synchronized(state) {
            val current = loop
            if (current != null && current.isAlive && loopGen == generation) {
                return
            }
            val gen = generation
            loopGen = gen
            val thread = Thread({ runLoop(gen, topic) }, "message-board")
            thread.isDaemon = true
            loop = thread
            thread.start()
        }
    }

    private fun runLoop(gen: Int, topic: String) {
        var backoff = 2_000L
        Log.i(TAG, "loop start")
        while (gen == generation) {
            var healthy = false
            try {
                healthy = stream(gen, topic)
            } catch (thrown: Exception) {
                if (gen != generation) {
                    break
                }
                Log.w(TAG, "stream failed ${thrown.javaClass.simpleName}")
            }
            if (gen != generation) {
                break
            }
            val wait = if (healthy) 2_000L else backoff
            backoff = if (healthy) 2_000L else minOf(backoff * 2, 30_000L)
            Log.i(TAG, "reconnect in ${wait}ms")
            if (!sleepBackoff(wait, gen)) {
                break
            }
        }
        Log.i(TAG, "loop stop")
    }

    /**
     * One JSON stream. since= today's Chicago midnight delivers messages posted
     * while the TV was off today, then the same connection stays open for new
     * ones. Anything older is ignored when the line is read.
     */
    private fun stream(gen: Int, topic: String): Boolean {
        val conn = open(gen, topic)
        try {
            val code = conn.responseCode
            val url = conn.url
            if (url == null || url.protocol != "https" || url.host?.lowercase() != "ntfy.sh") {
                throw IOException("rejected")
            }
            if (code != 200) {
                Log.w(TAG, "stream status=$code")
                throw IOException("status")
            }
            Log.i(TAG, "stream open")
            val saw = readLines(conn, gen)
            Log.i(TAG, "stream end")
            return saw
        } finally {
            finish(conn)
        }
    }

    private fun open(gen: Int, topic: String): HttpURLConnection {
        val conn = URL(MessageBoard.endpoint(topic, System.currentTimeMillis())).openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = false
        conn.useCaches = false
        conn.connectTimeout = 15_000
        conn.readTimeout = 120_000
        conn.requestMethod = "GET"
        conn.setRequestProperty("Accept", "application/json")
        conn.setRequestProperty("User-Agent", "FamilyDashboard")
        conn.setRequestProperty("Cache-Control", "no-cache")
        synchronized(state) {
            if (gen != generation) {
                conn.disconnect()
                throw IOException("stopped")
            }
            active = conn
        }
        return conn
    }

    private fun finish(conn: HttpURLConnection) {
        synchronized(state) {
            if (active === conn) {
                active = null
            }
        }
        conn.disconnect()
    }

    private fun readLines(conn: HttpURLConnection, gen: Int): Boolean {
        var saw = false
        BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { reader ->
            while (gen == generation) {
                val line = readLineLimited(reader, MAX_LINE) ?: break
                if (line.isEmpty()) {
                    continue
                }
                saw = true
                onLine(line)
            }
        }
        return saw
    }

    private fun readLineLimited(reader: BufferedReader, max: Int): String? {
        val sb = StringBuilder()
        while (sb.length < max) {
            val c = reader.read()
            if (c < 0) {
                return if (sb.isEmpty()) null else sb.toString()
            }
            if (c == '\n'.code) {
                if (sb.isNotEmpty() && sb[sb.length - 1] == '\r') {
                    sb.setLength(sb.length - 1)
                }
                return sb.toString()
            }
            sb.append(c.toChar())
        }
        while (true) {
            val c = reader.read()
            if (c < 0 || c == '\n'.code) {
                break
            }
        }
        return ""
    }

    private fun onLine(line: String) {
        val now = System.currentTimeMillis()
        var pulse = false
        var notify = false
        synchronized(state) {
            val expired = MessageBoard.expire(messages.toList(), now)
            val result = MessageBoard.ingest(expired, line, now)
            val dropped = expired.size != messages.size
            if (!result.changed && !result.cleared && !dropped) {
                return
            }
            messages.clear()
            messages.addAll(result.messages)
            if (messages.isEmpty()) {
                prefs.edit().remove(KEY_ROWS).commit()
            } else {
                prefs.edit().putString(KEY_ROWS, MessageBoard.encode(messages)).commit()
            }
            if (result.cleared) {
                pulseUntil = 0L
                Log.i(TAG, "cleared")
            } else if (result.pulse) {
                pulseUntil = now + PULSE_MS
                pulse = true
            }
            Log.i(TAG, "message count=${messages.size}")
            notify = true
        }
        if (notify) {
            publish(pulse)
        }
    }

    /**
     * Clears the board at the next America/Chicago midnight without waiting
     * for a new message or an app restart, then arms the following night.
     */
    private fun scheduleMidnight() {
        val now = System.currentTimeMillis()
        val delay = (MessageBoard.nextMidnightMillis(now) - now).coerceAtLeast(1_000L)
        timer.schedule(
            {
                try {
                    publish(false)
                } catch (thrown: Exception) {
                    Log.w(TAG, "midnight refresh failed ${thrown.javaClass.simpleName}")
                }
                if (started.get()) {
                    scheduleMidnight()
                }
            },
            delay,
            TimeUnit.MILLISECONDS
        )
    }

    private fun publish(requestPulse: Boolean) {
        val json = synchronized(state) {
            val now = System.currentTimeMillis()
            val fresh = MessageBoard.expire(messages.toList(), now)
            if (fresh.size != messages.size) {
                messages.clear()
                messages.addAll(fresh)
                if (messages.isEmpty()) {
                    prefs.edit().remove(KEY_ROWS).commit()
                } else {
                    prefs.edit().putString(KEY_ROWS, MessageBoard.encode(messages)).commit()
                }
            }
            if (requestPulse) {
                pulseUntil = now + PULSE_MS
            }
            val showPulse = now < pulseUntil
            snapshot = MessageBoard.toJson(hasTopic(), messages.toList(), showPulse)
            snapshot
        }
        if (json == lastNotified) {
            return
        }
        lastNotified = json
        notifyListeners(json)
        if (json.contains("\"pulse\":true")) {
            timer.schedule(
                {
                    try {
                        publish(false)
                    } catch (thrown: Exception) {
                        Log.w(TAG, "pulse end failed ${thrown.javaClass.simpleName}")
                    }
                },
                PULSE_MS,
                TimeUnit.MILLISECONDS
            )
        }
    }

    private fun notifyListeners(json: String) {
        val copy = synchronized(listenerLock) { ArrayList(listeners) }
        for (listener in copy) {
            listener.onMessages(json)
        }
    }

    private fun sleepBackoff(ms: Long, gen: Int): Boolean {
        var left = ms
        while (left > 0 && gen == generation) {
            val step = minOf(left, 500L)
            try {
                Thread.sleep(step)
            } catch (thrown: InterruptedException) {
                return false
            }
            left -= step
        }
        return gen == generation
    }

    private companion object {
        const val TAG = "FamilyDashMessages"
        const val PREFS = "family_dashboard"
        const val KEY_TOPIC = "message_topic"
        const val KEY_ROWS = "message_rows"
        const val MAX_LINE = 16 * 1024
        const val PULSE_MS = 4_000L
    }
}
