package com.dutchman175.familydashboard

import biweekly.Biweekly
import biweekly.component.VEvent
import biweekly.parameter.Range
import biweekly.util.ICalDate
import java.io.IOException
import java.util.Calendar
import java.util.Date
import java.util.PriorityQueue
import java.util.TimeZone

/**
 * Expands an ICS feed down to the next few days. The parsed calendar is local
 * to this call so a multi-megabyte feed is not retained after it returns.
 * Recurrences are advanced to the window instead of walked from their start.
 */
internal object IcsWindow {
    const val LIMIT = 8
    const val DAYS = 7
    const val MONTH_LIMIT = 1500
    private const val MAX_CHARS = 4_000_000
    private const val MAX_SCAN = 5000
    private const val MAX_LOOKBACK_DAYS = 370

    class Row(
        val title: String,
        val startMillis: Long,
        val endMillis: Long,
        val allDay: Boolean,
        val headingDayMillis: Long
    ) {
        override fun toString(): String {
            return title + " allDay=" + allDay + " start=" + startMillis + " day=" + headingDayMillis
        }
    }

    /** Month grid rows plus the same upcoming list the dashboard panel shows. */
    class Covered(
        val month: List<Row>,
        val upcoming: List<Row>
    )

    fun upcoming(
        ics: String,
        now: Date,
        zone: TimeZone = ChicagoTime.ZONE,
        limit: Int = LIMIT,
        days: Int = DAYS
    ): List<Row> {
        if (limit <= 0 || days <= 0) {
            return emptyList()
        }
        val nowMillis = now.time
        val windowStart = ChicagoTime.startOfDay(nowMillis, zone)
        val windowEnd = ChicagoTime.addDays(windowStart, days, zone)
        val sink = Sink(windowStart, windowEnd, nowMillis, limit, dropEnded = true)
        val groups = readGroups(ics)
        expandGroups(groups, zone, windowStart, windowEnd) { start, duration, allDay, title ->
            emitRow(sink, start, duration, allDay, title, zone)
        }
        return sink.finish()
    }

    /**
     * Parses the feed once. Month rows cover the start of the previous month
     * through the end of the month two months ahead, including events that
     * already ended. Upcoming is still the next 8 events in the coming week.
     */
    fun covered(
        ics: String,
        now: Date,
        zone: TimeZone = ChicagoTime.ZONE
    ): Covered {
        val nowMillis = now.time
        val today = ChicagoTime.startOfDay(nowMillis, zone)
        val upcomingEnd = ChicagoTime.addDays(today, DAYS, zone)
        val monthStart = ChicagoTime.startOfMonth(nowMillis, -1, zone)
        val monthEnd = ChicagoTime.startOfMonth(nowMillis, 3, zone)
        val monthSink = Sink(monthStart, monthEnd, nowMillis, MONTH_LIMIT, dropEnded = false)
        val upcomingSink = Sink(today, upcomingEnd, nowMillis, LIMIT, dropEnded = true)
        val groups = readGroups(ics)
        expandGroups(groups, zone, monthStart, monthEnd) { start, duration, allDay, title ->
            emitRow(monthSink, start, duration, allDay, title, zone)
        }
        expandGroups(groups, zone, today, upcomingEnd) { start, duration, allDay, title ->
            emitRow(upcomingSink, start, duration, allDay, title, zone)
        }
        return Covered(monthSink.finish(), upcomingSink.finish())
    }

    private class Sink(
        val windowStart: Long,
        val windowEnd: Long,
        val nowMillis: Long,
        private val limit: Int,
        val dropEnded: Boolean
    ) {
        private val order = Comparator<Row> { a, b ->
            val byDay = a.headingDayMillis.compareTo(b.headingDayMillis)
            if (byDay != 0) {
                return@Comparator byDay
            }
            val byAllDay = (if (a.allDay) 0 else 1) - (if (b.allDay) 0 else 1)
            if (byAllDay != 0) {
                return@Comparator byAllDay
            }
            val byStart = a.startMillis.compareTo(b.startMillis)
            if (byStart != 0) {
                return@Comparator byStart
            }
            a.title.compareTo(b.title)
        }
        private val heap = PriorityQueue<Row>(limit + 1, Comparator { a, b -> order.compare(b, a) })

        fun finish(): List<Row> {
            val out = ArrayList(heap)
            out.sortWith(order)
            return out
        }

        fun offer(row: Row) {
            if (heap.size < limit) {
                heap.add(row)
                return
            }
            val worst = heap.peek() ?: return
            if (order.compare(row, worst) < 0) {
                heap.poll()
                heap.add(row)
            }
        }
    }

    private fun emitRow(
        sink: Sink,
        startMillis: Long,
        durationMs: Long,
        allDay: Boolean,
        title: String,
        zone: TimeZone
    ) {
        if (allDay) {
            val spanned = dayCount(durationMs)
            val eventStart = ChicagoTime.startOfDay(startMillis, zone)
            val roughEnd = eventStart + spanned.toLong() * ChicagoTime.DAY_MS + ChicagoTime.DAY_MS
            if (roughEnd <= sink.windowStart || eventStart >= sink.windowEnd) {
                return
            }
            var cursor = eventStart
            var index = 0
            if (cursor < sink.windowStart) {
                val skip = daysBetween(cursor, sink.windowStart, zone)
                if (skip > 0) {
                    cursor = ChicagoTime.addDays(cursor, skip, zone)
                    index = skip
                }
            }
            while (index < spanned && cursor < sink.windowEnd) {
                if (cursor >= sink.windowStart) {
                    sink.offer(
                        Row(
                            title,
                            cursor,
                            ChicagoTime.addDays(cursor, 1, zone),
                            true,
                            cursor
                        )
                    )
                }
                cursor = ChicagoTime.addDays(cursor, 1, zone)
                index++
            }
            return
        }
        val end = if (durationMs > 0) startMillis + durationMs else startMillis + 60_000L
        if (startMillis >= sink.windowEnd || end <= sink.windowStart) {
            return
        }
        if (sink.dropEnded && end <= sink.nowMillis) {
            return
        }
        val heading = if (startMillis < sink.windowStart) {
            sink.windowStart
        } else {
            ChicagoTime.startOfDay(startMillis, zone)
        }
        sink.offer(Row(title, startMillis, end, false, heading))
    }

    private fun readGroups(ics: String): LinkedHashMap<String, MutableList<VEvent>> {
        if (ics.length > MAX_CHARS) {
            throw IOException("calendar too large")
        }
        if (!ics.contains("BEGIN:VCALENDAR", ignoreCase = true)) {
            throw IOException("calendar parse failed")
        }
        val calendars = try {
            Biweekly.parse(ics).all()
        } catch (ex: Exception) {
            throw IOException("calendar parse failed")
        }
        if (calendars == null) {
            throw IOException("calendar parse failed")
        }
        val groups = LinkedHashMap<String, MutableList<VEvent>>()
        var solo = 0
        for (calendar in calendars) {
            val events = calendar.events ?: continue
            for (event in events) {
                val uid = event.uid?.value
                val key = if (uid.isNullOrEmpty()) {
                    solo++
                    "#" + solo
                } else {
                    uid
                }
                val list = groups.getOrPut(key) { ArrayList() }
                list.add(event)
            }
        }
        return groups
    }

    private fun expandGroups(
        groups: Map<String, MutableList<VEvent>>,
        zone: TimeZone,
        windowStart: Long,
        windowEnd: Long,
        emit: (Long, Long, Boolean, String) -> Unit
    ) {
        for (events in groups.values) {
            val masters = ArrayList<VEvent>()
            val overrides = ArrayList<VEvent>()
            for (event in events) {
                if (event.recurrenceId != null) {
                    overrides.add(event)
                } else {
                    masters.add(event)
                }
            }
            val master = masters.firstOrNull { it.recurrenceRule != null } ?: masters.firstOrNull()
            if (master == null) {
                for (override in overrides) {
                    emitStandalone(override, zone, emit)
                }
                continue
            }
            val recurring = master.recurrenceRule != null || master.recurrenceDates.isNotEmpty()
            if (!recurring && overrides.isEmpty()) {
                emitStandalone(master, zone, emit)
            } else {
                expand(master, overrides, zone, windowStart, windowEnd, emit)
            }
        }
    }

    private fun emitStandalone(
        event: VEvent,
        zone: TimeZone,
        emit: (Long, Long, Boolean, String) -> Unit
    ) {
        if (isCancelled(event)) {
            return
        }
        val start = event.dateStart?.value ?: return
        val allDay = !start.hasTime()
        val startMillis = if (allDay) allDayMillis(start, zone) else start.time
        emit(startMillis, durationMillis(event), allDay, titleOf(event))
    }

    private fun expand(
        master: VEvent,
        overrides: List<VEvent>,
        zone: TimeZone,
        windowStart: Long,
        windowEnd: Long,
        emit: (Long, Long, Boolean, String) -> Unit
    ) {
        val startProp = master.dateStart?.value ?: return
        val masterAllDay = !startProp.hasTime()
        val masterDuration = durationMillis(master)
        val masterTitle = titleOf(master)
        val masterCancelled = isCancelled(master)
        val compiled = ArrayList<Compiled>()
        for (override in overrides) {
            compileOverride(override, zone)?.let { compiled.add(it) }
        }
        val used = HashSet<Compiled>()
        val iterator = try {
            master.getDateIterator(iteratorZone(master, zone))
        } catch (ex: RuntimeException) {
            null
        }
        if (iterator != null) {
            val lookback = lookbackMillis(masterDuration)
            try {
                iterator.advanceTo(Date(windowStart - lookback))
                var scanned = 0
                while (iterator.hasNext() && scanned < MAX_SCAN) {
                    val occ = iterator.next()
                    scanned++
                    if (masterAllDay) {
                        val occStart = allDayMillisFromIterator(occ, zone)
                        if (occStart >= windowEnd) {
                            break
                        }
                    } else if (occ.time >= windowEnd) {
                        break
                    }
                    val adjusted = adjust(
                        occ,
                        masterAllDay,
                        masterDuration,
                        masterTitle,
                        masterCancelled,
                        compiled,
                        used,
                        zone
                    ) ?: continue
                    emit(adjusted.startMillis, adjusted.durationMs, adjusted.allDay, adjusted.title)
                }
            } catch (ex: RuntimeException) {
                // One bad rule should not drop the rest of the feed.
            }
        }
        for (override in compiled) {
            if (!override.thisAndFuture && override !in used && !override.cancelled) {
                emit(override.startMillis, override.durationMs, override.allDay, override.title)
            }
        }
    }

    private class Adjustment(
        val startMillis: Long,
        val durationMs: Long,
        val allDay: Boolean,
        val title: String
    )

    private fun adjust(
        occ: Date,
        masterAllDay: Boolean,
        masterDuration: Long,
        masterTitle: String,
        masterCancelled: Boolean,
        compiled: List<Compiled>,
        used: MutableSet<Compiled>,
        zone: TimeZone
    ): Adjustment? {
        val key = occKey(occ, masterAllDay)
        for (override in compiled) {
            if (override.thisAndFuture || override.key != key) {
                continue
            }
            used.add(override)
            if (override.cancelled) {
                return null
            }
            return Adjustment(override.startMillis, override.durationMs, override.allDay, override.title)
        }
        var future: Compiled? = null
        for (override in compiled) {
            if (!override.thisAndFuture) {
                continue
            }
            val applies = if (masterAllDay) {
                key >= override.key
            } else {
                occ.time >= override.recurrenceMillis
            }
            if (!applies) {
                continue
            }
            if (future == null || override.recurrenceMillis >= future.recurrenceMillis) {
                future = override
            }
        }
        if (future == null) {
            if (masterCancelled) {
                return null
            }
            val start = if (masterAllDay) allDayMillisFromIterator(occ, zone) else occ.time
            return Adjustment(start, masterDuration, masterAllDay, masterTitle)
        }
        used.add(future)
        if (future.cancelled) {
            return null
        }
        val onAnchor = key == future.key || (!masterAllDay && occ.time == future.recurrenceMillis)
        if (onAnchor) {
            return Adjustment(future.startMillis, future.durationMs, future.allDay, future.title)
        }
        if (masterAllDay && future.allDay) {
            val occStart = allDayMillisFromIterator(occ, zone)
            val shifted = ChicagoTime.addDays(occStart, future.deltaDays, zone)
            return Adjustment(shifted, future.durationMs, true, future.title)
        }
        return Adjustment(occ.time + future.deltaMillis, future.durationMs, future.allDay, future.title)
    }

    private class Compiled(
        val key: String,
        val thisAndFuture: Boolean,
        val cancelled: Boolean,
        val startMillis: Long,
        val recurrenceMillis: Long,
        val durationMs: Long,
        val allDay: Boolean,
        val title: String,
        val deltaDays: Int,
        val deltaMillis: Long
    )

    private fun compileOverride(event: VEvent, zone: TimeZone): Compiled? {
        val rid = event.recurrenceId?.value ?: return null
        val start = event.dateStart?.value ?: return null
        val allDay = !start.hasTime()
        val ridAllDay = !rid.hasTime()
        val startMillis = if (allDay) allDayMillis(start, zone) else start.time
        val recurrenceMillis = if (ridAllDay) allDayMillis(rid, zone) else rid.time
        val deltaDays = if (allDay && ridAllDay) {
            (civilDay(start) - civilDay(rid)).toInt()
        } else {
            0
        }
        return Compiled(
            dateKey(rid),
            event.recurrenceId.range == Range.THIS_AND_FUTURE,
            isCancelled(event),
            startMillis,
            recurrenceMillis,
            durationMillis(event),
            allDay,
            titleOf(event),
            deltaDays,
            startMillis - recurrenceMillis
        )
    }

    private fun iteratorZone(event: VEvent, fallback: TimeZone): TimeZone {
        val tzid = event.dateStart?.parameters?.timezoneId
        if (tzid.isNullOrEmpty()) {
            return fallback
        }
        val resolved = TimeZone.getTimeZone(tzid)
        val unknown = resolved.id == "GMT" && !tzid.equals("GMT", ignoreCase = true) &&
            !tzid.equals("UTC", ignoreCase = true) && tzid != "Etc/GMT"
        return if (unknown) fallback else resolved
    }

    private fun isCancelled(event: VEvent): Boolean {
        val status = event.status ?: return false
        return status.isCancelled
    }

    private fun titleOf(event: VEvent): String {
        val raw = event.summary?.value ?: return "Busy"
        val sb = StringBuilder(raw.length.coerceAtMost(80))
        var pendingSpace = false
        for (ch in raw) {
            if (ch.isWhitespace()) {
                if (sb.isNotEmpty()) {
                    pendingSpace = true
                }
            } else {
                if (pendingSpace) {
                    sb.append(' ')
                    pendingSpace = false
                }
                sb.append(ch)
                if (sb.length >= 80) {
                    break
                }
            }
        }
        return if (sb.isEmpty()) "Busy" else sb.toString()
    }

    private fun durationMillis(event: VEvent): Long {
        val start = event.dateStart?.value ?: return 0L
        val allDay = !start.hasTime()
        val prop = event.duration?.value
        if (prop != null) {
            if (allDay && !prop.hasTime()) {
                val days = (prop.weeks ?: 0) * 7 + (prop.days ?: 0)
                if (days > 0) {
                    return days.toLong() * ChicagoTime.DAY_MS
                }
            }
            val ms = prop.toMillis()
            if (ms > 0) {
                return ms
            }
        }
        val end = event.dateEnd?.value
        if (end != null) {
            if (allDay && !end.hasTime()) {
                val days = civilDay(end) - civilDay(start)
                if (days > 0) {
                    return days * ChicagoTime.DAY_MS
                }
            } else {
                val ms = end.time - start.time
                if (ms > 0) {
                    return ms
                }
            }
        }
        return if (allDay) ChicagoTime.DAY_MS else 0L
    }

    private fun dayCount(durationMs: Long): Int {
        if (durationMs <= 0L) {
            return 1
        }
        val days = ((durationMs + ChicagoTime.DAY_MS - 1) / ChicagoTime.DAY_MS).toInt()
        return if (days < 1) 1 else days
    }

    private fun lookbackMillis(durationMs: Long): Long {
        val span = if (durationMs > ChicagoTime.DAY_MS) durationMs else ChicagoTime.DAY_MS
        val cap = MAX_LOOKBACK_DAYS.toLong() * ChicagoTime.DAY_MS
        return if (span > cap) cap else span
    }

    private fun daysBetween(startMillis: Long, endMillis: Long, zone: TimeZone): Int {
        val start = ChicagoTime.startOfDay(startMillis, zone)
        val end = ChicagoTime.startOfDay(endMillis, zone)
        if (end <= start) {
            return 0
        }
        val cal = Calendar.getInstance(zone)
        cal.timeInMillis = start
        val startDay = ChicagoTime.civilDay(
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH) + 1,
            cal.get(Calendar.DAY_OF_MONTH)
        )
        cal.timeInMillis = end
        val endDay = ChicagoTime.civilDay(
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH) + 1,
            cal.get(Calendar.DAY_OF_MONTH)
        )
        val delta = (endDay - startDay).toInt()
        return if (delta < 0) 0 else delta
    }

    /**
     * Date-only values are parsed in the device's default timezone. Read the
     * year/month/day back out of that same zone, then treat the civil date as
     * Chicago so an all-day event does not slide to the previous evening.
     */
    private fun allDayMillis(date: ICalDate, zone: TimeZone): Long {
        val raw = date.rawComponents
        if (raw != null) {
            return ChicagoTime.midnight(raw.year, raw.month, raw.date, zone)
        }
        return allDayMillisFromIterator(date, zone)
    }

    private fun allDayMillisFromIterator(date: Date, zone: TimeZone): Long {
        val local = Calendar.getInstance()
        local.time = date
        return ChicagoTime.midnight(
            local.get(Calendar.YEAR),
            local.get(Calendar.MONTH) + 1,
            local.get(Calendar.DAY_OF_MONTH),
            zone
        )
    }

    private fun civilDay(date: ICalDate): Long {
        val raw = date.rawComponents
        if (raw != null && !date.hasTime()) {
            return ChicagoTime.civilDay(raw.year, raw.month, raw.date)
        }
        val local = Calendar.getInstance()
        local.time = date
        return ChicagoTime.civilDay(
            local.get(Calendar.YEAR),
            local.get(Calendar.MONTH) + 1,
            local.get(Calendar.DAY_OF_MONTH)
        )
    }

    private fun dateKey(date: ICalDate): String {
        if (!date.hasTime()) {
            val raw = date.rawComponents
            if (raw != null) {
                return "d:%04d-%02d-%02d".format(raw.year, raw.month, raw.date)
            }
        }
        if (!date.hasTime()) {
            val local = Calendar.getInstance()
            local.time = date
            return "d:%04d-%02d-%02d".format(
                local.get(Calendar.YEAR),
                local.get(Calendar.MONTH) + 1,
                local.get(Calendar.DAY_OF_MONTH)
            )
        }
        return "t:" + date.time
    }

    private fun occKey(date: Date, allDay: Boolean): String {
        if (!allDay) {
            return "t:" + date.time
        }
        val local = Calendar.getInstance()
        local.time = date
        return "d:%04d-%02d-%02d".format(
            local.get(Calendar.YEAR),
            local.get(Calendar.MONTH) + 1,
            local.get(Calendar.DAY_OF_MONTH)
        )
    }
}
