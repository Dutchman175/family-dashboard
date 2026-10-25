package com.dutchman175.familydashboard

import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CalendarSnapshotTest {
    @Test
    fun headingsAndEscaping() {
        val zone = ChicagoTime.ZONE
        val cal = Calendar.getInstance(zone)
        cal.clear()
        cal.set(2026, Calendar.OCTOBER, 25, 15, 0, 0)
        val now = cal.timeInMillis
        val today = ChicagoTime.startOfDay(now, zone)
        val tomorrow = ChicagoTime.addDays(today, 1, zone)
        val tuesday = ChicagoTime.addDays(today, 2, zone)
        val rows = listOf(
            IcsWindow.Row("Say \"hi\" <tag>", today + 3_600_000L, today + 7_200_000L, false, today),
            IcsWindow.Row("Park trip", tomorrow, ChicagoTime.addDays(tomorrow, 1, zone), true, tomorrow),
            IcsWindow.Row("Practice", tuesday + 36_000_000L, tuesday + 39_600_000L, false, tuesday)
        )
        val json = CalendarSnapshot.snapshot(true, true, false, rows, now, zone)
        assertTrue(json.contains("\\u003ctag\\u003e"))
        assertTrue(json.contains("Say \\\"hi\\\""))
        assertTrue(json.contains("\"heading\":\"Today\""))
        assertTrue(json.contains("\"heading\":\"Tomorrow\""))
        assertTrue(json.contains("\"heading\":\"Tuesday\""))
        assertTrue(json.contains("\"time\":\"All day\""))
        assertTrue(json.contains("\"stale\":true"))
        assertFalse(json.contains("<tag>"))

        val stored = CalendarSnapshot.encodeRows(rows)
        val back = CalendarSnapshot.decodeRows(stored)
        assertEquals(3, back.size)
        assertEquals(rows[0].title, back[0].title)
        assertEquals(rows[1].allDay, back[1].allDay)
        assertEquals(rows[2].headingDayMillis, back[2].headingDayMillis)
        assertTrue(CalendarSnapshot.decodeRows(null).isEmpty())
        assertTrue(CalendarSnapshot.decodeRows("not-a-row").isEmpty())
    }

    @Test
    fun hiddenWhenUnset() {
        assertEquals(
            "{\"configured\":false,\"stale\":false,\"loading\":false,\"events\":[]}",
            CalendarSnapshot.snapshot(false, true, true, emptyList(), 0L, ChicagoTime.ZONE)
        )
    }
}
