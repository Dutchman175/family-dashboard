package com.dutchman175.familydashboard

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Wall-clock helpers for America/Chicago. API 22 has no java.time. */
internal object ChicagoTime {
    val ZONE: TimeZone = TimeZone.getTimeZone("America/Chicago")
    const val DAY_MS = 86_400_000L

    private val WEEKDAYS = arrayOf(
        "",
        "Sunday",
        "Monday",
        "Tuesday",
        "Wednesday",
        "Thursday",
        "Friday",
        "Saturday"
    )

    fun midnight(year: Int, month: Int, day: Int, zone: TimeZone): Long {
        val cal = Calendar.getInstance(zone)
        cal.clear()
        cal.set(Calendar.YEAR, year)
        cal.set(Calendar.MONTH, month - 1)
        cal.set(Calendar.DAY_OF_MONTH, day)
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    fun startOfDay(millis: Long, zone: TimeZone): Long {
        val cal = Calendar.getInstance(zone)
        cal.timeInMillis = millis
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    fun addDays(millis: Long, days: Int, zone: TimeZone): Long {
        val cal = Calendar.getInstance(zone)
        cal.timeInMillis = millis
        cal.add(Calendar.DATE, days)
        return cal.timeInMillis
    }

    /** Days from the UTC civil calendar, so all-day lengths do not depend on DST. */
    fun civilDay(year: Int, month: Int, day: Int): Long {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.clear()
        cal.set(year, month - 1, day, 0, 0, 0)
        return cal.timeInMillis / DAY_MS
    }

    fun dayKey(millis: Long, zone: TimeZone): String {
        val cal = Calendar.getInstance(zone)
        cal.timeInMillis = millis
        return "%04d-%02d-%02d".format(
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH) + 1,
            cal.get(Calendar.DAY_OF_MONTH)
        )
    }

    fun formatTime(millis: Long, zone: TimeZone): String {
        val fmt = SimpleDateFormat("h:mm a", Locale.US)
        fmt.timeZone = zone
        return fmt.format(Date(millis))
    }

    fun heading(dayStartMillis: Long, nowMillis: Long, zone: TimeZone): String {
        val today = startOfDay(nowMillis, zone)
        if (dayStartMillis == today) {
            return "Today"
        }
        val tomorrow = addDays(today, 1, zone)
        if (dayStartMillis == tomorrow) {
            return "Tomorrow"
        }
        val cal = Calendar.getInstance(zone)
        cal.timeInMillis = dayStartMillis
        val index = cal.get(Calendar.DAY_OF_WEEK)
        if (index < 1 || index >= WEEKDAYS.size) {
            return ""
        }
        return WEEKDAYS[index]
    }
}
