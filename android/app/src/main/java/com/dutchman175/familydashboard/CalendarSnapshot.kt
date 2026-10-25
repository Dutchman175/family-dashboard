package com.dutchman175.familydashboard

import java.util.TimeZone

/** JSON for the WebView, and the small on-device cache of those same rows. */
internal object CalendarSnapshot {
    const val UNCONFIGURED = "{\"configured\":false,\"stale\":false,\"loading\":false,\"events\":[]}"

    fun encodeRows(rows: List<IcsWindow.Row>): String {
        val sb = StringBuilder()
        for (row in rows) {
            sb.append(row.title.replace('\t', ' ').replace('\n', ' ').replace('\r', ' '))
            sb.append('\t')
            sb.append(row.startMillis)
            sb.append('\t')
            sb.append(row.endMillis)
            sb.append('\t')
            sb.append(if (row.allDay) '1' else '0')
            sb.append('\t')
            sb.append(row.headingDayMillis)
            sb.append('\n')
        }
        return sb.toString()
    }

    fun decodeRows(stored: String?): List<IcsWindow.Row> {
        if (stored.isNullOrEmpty()) {
            return emptyList()
        }
        val rows = ArrayList<IcsWindow.Row>()
        var start = 0
        while (start <= stored.length) {
            val nl = stored.indexOf('\n', start)
            val end = if (nl < 0) stored.length else nl
            if (end > start) {
                val line = stored.substring(start, end)
                val parts = line.split('\t')
                if (parts.size >= 5) {
                    val startMs = parts[1].toLongOrNull()
                    val endMs = parts[2].toLongOrNull()
                    val heading = parts[4].toLongOrNull()
                    if (startMs != null && endMs != null && heading != null) {
                        rows.add(
                            IcsWindow.Row(
                                parts[0],
                                startMs,
                                endMs,
                                parts[3] == "1",
                                heading
                            )
                        )
                    }
                }
            }
            if (nl < 0) {
                break
            }
            start = nl + 1
        }
        return rows
    }

    fun snapshot(
        configured: Boolean,
        stale: Boolean,
        loading: Boolean,
        rows: List<IcsWindow.Row>,
        nowMillis: Long,
        zone: TimeZone
    ): String {
        if (!configured) {
            return UNCONFIGURED
        }
        val sb = StringBuilder()
        sb.append("{\"configured\":true,\"stale\":")
        sb.append(if (stale) "true" else "false")
        sb.append(",\"loading\":")
        sb.append(if (loading) "true" else "false")
        sb.append(",\"events\":[")
        for (i in rows.indices) {
            if (i > 0) {
                sb.append(',')
            }
            val row = rows[i]
            sb.append("{\"title\":")
            sb.append(jsonString(row.title))
            sb.append(",\"allDay\":")
            sb.append(if (row.allDay) "true" else "false")
            sb.append(",\"heading\":")
            sb.append(jsonString(ChicagoTime.heading(row.headingDayMillis, nowMillis, zone)))
            sb.append(",\"time\":")
            sb.append(jsonString(if (row.allDay) "All day" else ChicagoTime.formatTime(row.startMillis, zone)))
            sb.append(",\"day\":")
            sb.append(jsonString(ChicagoTime.dayKey(row.headingDayMillis, zone)))
            sb.append('}')
        }
        sb.append("]}")
        return sb.toString()
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
