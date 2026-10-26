package com.dutchman175.familydashboard

import org.json.JSONObject

/**
 * Pure message rules: parse one ntfy JSON line, drop anything older than 24
 * hours, and build the WebView snapshot. The topic name is never read and
 * never written into the snapshot.
 */
internal object MessageBoard {
    const val TTL_MS = 24L * 60 * 60 * 1000
    const val MAX_TEXT = 200
    const val MAX_SENDER = 40
    const val MAX_STORED = 40
    const val MAX_SHOWN = 3
    private const val PULSE_WINDOW_MS = 45_000L
    private val SAFE_ID = Regex("^[A-Za-z0-9_-]{1,64}$")

    internal data class Note(
        val id: String,
        val sender: String,
        val text: String,
        val atMillis: Long
    )

    internal data class Ingest(
        val messages: List<Note>,
        val changed: Boolean,
        val pulse: Boolean,
        val cleared: Boolean
    )

    fun endpoint(topic: String): String {
        return "https://ntfy.sh/$topic/json?since=24h"
    }

    fun ingest(existing: List<Note>, line: String, nowMillis: Long): Ingest {
        val obj = try {
            JSONObject(line)
        } catch (thrown: Exception) {
            return unchanged(existing)
        }
        if (obj.optString("event") != "message") {
            return unchanged(existing)
        }
        if (!obj.has("message") || obj.isNull("message")) {
            return unchanged(existing)
        }
        val rawText = obj.optString("message", "")
        if (rawText.trim() == "clear") {
            val had = existing.isNotEmpty()
            return Ingest(emptyList(), changed = had, pulse = false, cleared = true)
        }
        val text = plain(rawText, MAX_TEXT)
        if (text.isEmpty()) {
            return unchanged(existing)
        }
        val senderPlain = plain(obj.optString("title", ""), MAX_SENDER)
        val sender = if (senderPlain.isEmpty()) "Family" else senderPlain
        val at = messageTime(obj.optLong("time", 0L), nowMillis)
        if (nowMillis - at >= TTL_MS) {
            return unchanged(existing)
        }
        val id = safeId(obj.optString("id", ""), at, text)
        if (existing.any { it.id == id }) {
            return unchanged(existing)
        }
        val next = expire(existing + Note(id, sender, text, at), nowMillis)
            .sortedBy { it.atMillis }
            .takeLast(MAX_STORED)
        val age = nowMillis - at
        val pulse = age in 0..PULSE_WINDOW_MS
        return Ingest(next, changed = true, pulse = pulse, cleared = false)
    }

    fun expire(messages: List<Note>, nowMillis: Long): List<Note> {
        return messages.filter { nowMillis - it.atMillis < TTL_MS }
    }

    fun toJson(configured: Boolean, messages: List<Note>, pulse: Boolean): String {
        if (!configured) {
            return "{\"configured\":false,\"pulse\":false,\"messages\":[]}"
        }
        val shown = messages
            .sortedWith(compareByDescending<Note> { it.atMillis }.thenByDescending { it.id })
            .take(MAX_SHOWN)
        val sb = StringBuilder()
        sb.append("{\"configured\":true,\"pulse\":")
        sb.append(if (pulse) "true" else "false")
        sb.append(",\"messages\":[")
        for (i in shown.indices) {
            if (i > 0) {
                sb.append(',')
            }
            val note = shown[i]
            sb.append("{\"id\":")
            sb.append(jsonString(note.id))
            sb.append(",\"sender\":")
            sb.append(jsonString(note.sender))
            sb.append(",\"text\":")
            sb.append(jsonString(note.text))
            sb.append(",\"at\":")
            sb.append(note.atMillis)
            sb.append('}')
        }
        sb.append("]}")
        return sb.toString()
    }

    fun encode(messages: List<Note>): String {
        val sb = StringBuilder()
        for (note in messages) {
            sb.append(note.id.replace('\t', ' ').replace('\n', ' '))
            sb.append('\t')
            sb.append(note.atMillis)
            sb.append('\t')
            sb.append(note.sender.replace('\t', ' ').replace('\n', ' '))
            sb.append('\t')
            sb.append(note.text.replace('\t', ' ').replace('\n', ' '))
            sb.append('\n')
        }
        return sb.toString()
    }

    fun decode(stored: String?): List<Note> {
        if (stored.isNullOrEmpty()) {
            return emptyList()
        }
        val out = ArrayList<Note>()
        var start = 0
        while (start <= stored.length) {
            val nl = stored.indexOf('\n', start)
            val end = if (nl < 0) stored.length else nl
            if (end > start) {
                val parts = stored.substring(start, end).split('\t', limit = 4)
                if (parts.size >= 4) {
                    val at = parts[1].toLongOrNull()
                    if (at != null && parts[0].isNotEmpty() && parts[3].isNotEmpty()) {
                        val sender = parts[2].ifEmpty { "Family" }
                        out.add(Note(parts[0], sender, parts[3], at))
                    }
                }
            }
            if (nl < 0) {
                break
            }
            start = nl + 1
        }
        return out
    }

    private fun unchanged(existing: List<Note>): Ingest {
        return Ingest(existing, changed = false, pulse = false, cleared = false)
    }

    private fun messageTime(raw: Long, nowMillis: Long): Long {
        val millis = when {
            raw <= 0L -> nowMillis
            raw > 10_000_000_000L -> raw
            else -> raw * 1000L
        }
        return if (millis > nowMillis + 5_000L) nowMillis else millis
    }

    private fun safeId(raw: String, atMillis: Long, text: String): String {
        if (SAFE_ID.matches(raw)) {
            return raw
        }
        val hash = text.hashCode().toUInt().toString(16)
        return "m$atMillis$hash"
    }

    private fun plain(value: String, maxChars: Int): String {
        val sb = StringBuilder()
        var count = 0
        var i = 0
        while (i < value.length && count < maxChars) {
            val cp = value.codePointAt(i)
            i += Character.charCount(cp)
            if (cp == 0x9 || cp == 0xA || cp == 0xD || cp == 0xA0) {
                sb.append(' ')
                count++
                continue
            }
            if (cp < 0x20 || cp == 0x7F) {
                continue
            }
            if (cp in 0x202A..0x202E || cp in 0x2066..0x2069) {
                continue
            }
            if (cp == 0xFEFF || cp == 0x200B || cp == 0x200C || cp == 0x200D || cp == 0x200E || cp == 0x200F) {
                continue
            }
            sb.appendCodePoint(cp)
            count++
        }
        return sb.toString().replace(Regex(" +"), " ").trim()
    }

    private fun jsonString(value: String): String {
        val sb = StringBuilder(value.length + 2)
        sb.append('"')
        for (ch in value) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '<' -> sb.append("\\u003c")
                '>' -> sb.append("\\u003e")
                '&' -> sb.append("\\u0026")
                '\u2028' -> sb.append("\\u2028")
                '\u2029' -> sb.append("\\u2029")
                else -> if (ch.code < 0x20) {
                    sb.append("\\u%04x".format(ch.code))
                } else {
                    sb.append(ch)
                }
            }
        }
        sb.append('"')
        return sb.toString()
    }
}
