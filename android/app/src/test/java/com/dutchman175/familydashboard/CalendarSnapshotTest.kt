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
    fun monthRowsSitBesideTheSameUpcomingEvents() {
        val zone = ChicagoTime.ZONE
        val cal = Calendar.getInstance(zone)
        cal.clear()
        cal.set(2026, Calendar.OCTOBER, 25, 15, 0, 0)
        val now = cal.timeInMillis
        val today = ChicagoTime.startOfDay(now, zone)
        val september = ChicagoTime.midnight(2026, 9, 1, zone)
        val rows = listOf(
            IcsWindow.Row("Game on now", today + 3_600_000L, today + 7_200_000L, false, today)
        )
        val month = listOf(
            IcsWindow.Row("September kickoff", september, ChicagoTime.addDays(september, 1, zone), true, september),
            rows[0]
        )
        val json = CalendarSnapshot.snapshot(true, false, false, rows, now, zone, month)
        assertTrue(json.contains("\"today\":\"2026-10-25\""))
        assertTrue(json.contains("\"heading\":\"Today\""))
        assertTrue(json.contains("\"events\":[{\"title\":\"Game on now\""))
        val monthAt = json.indexOf("\"month\":[")
        assertTrue(monthAt > json.indexOf("\"events\":["))
        val monthJson = json.substring(monthAt)
        assertTrue(monthJson.contains("\"title\":\"September kickoff\""))
        assertTrue(monthJson.contains("\"allDay\":true"))
        assertTrue(monthJson.contains("\"day\":\"2026-09-01\""))
        assertTrue(monthJson.contains("\"time\":\"All day\""))
        assertFalse(monthJson.contains("\"heading\""))
    }

    @Test
    fun hiddenWhenUnset() {
        assertEquals(
            "{\"configured\":false,\"stale\":false,\"loading\":false,\"events\":[]}",
            CalendarSnapshot.snapshot(false, true, true, emptyList(), 0L, ChicagoTime.ZONE)
        )
    }
}
