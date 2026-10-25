package com.dutchman175.familydashboard

import java.io.IOException
import java.util.Calendar
import java.util.Date
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class IcsWindowTest {
    private val zone = ChicagoTime.ZONE
    private val now = chicago(2026, 10, 25, 15, 0)

    @Test
    fun weeklyRuleLandsOnThisSunday() {
        val rows = upcoming(
            """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//test//EN
            BEGIN:VEVENT
            UID:pickup@test
            DTSTART;TZID=America/Chicago:20261004T153000
            DTEND;TZID=America/Chicago:20261004T163000
            RRULE:FREQ=WEEKLY;BYDAY=SU
            SUMMARY:Sunday pickup
            END:VEVENT
            END:VCALENDAR
            """.trimIndent()
        )
        assertEquals("2026-10-25 3:30 PM Sunday pickup", describe(rows))
    }

    @Test
    fun exdateRemovesTheInstanceInsideTheWindow() {
        val rows = upcoming(
            """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//test//EN
            BEGIN:VEVENT
            UID:pickup@test
            DTSTART;TZID=America/Chicago:20261004T153000
            DTEND;TZID=America/Chicago:20261004T163000
            RRULE:FREQ=WEEKLY;BYDAY=SU
            EXDATE;TZID=America/Chicago:20261025T153000
            SUMMARY:Sunday pickup
            END:VEVENT
            END:VCALENDAR
            """.trimIndent()
        )
        assertEquals("", describe(rows))
    }

    @Test
    fun recurrenceIdReplacesThatInstance() {
        val rows = upcoming(
            """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//test//EN
            BEGIN:VEVENT
            UID:pickup@test
            DTSTART;TZID=America/Chicago:20261004T153000
            DTEND;TZID=America/Chicago:20261004T163000
            RRULE:FREQ=WEEKLY;BYDAY=SU
            SUMMARY:Sunday pickup
            END:VEVENT
            BEGIN:VEVENT
            UID:pickup@test
            RECURRENCE-ID;TZID=America/Chicago:20261025T153000
            DTSTART;TZID=America/Chicago:20261025T170000
            DTEND;TZID=America/Chicago:20261025T180000
            SUMMARY:Sunday pickup moved
            END:VEVENT
            END:VCALENDAR
            """.trimIndent()
        )
        assertEquals("2026-10-25 5:00 PM Sunday pickup moved", describe(rows))
    }

    @Test
    fun thisAndFutureShiftsLaterInstances() {
        val rows = upcoming(
            """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//test//EN
            BEGIN:VEVENT
            UID:stand@test
            DTSTART;TZID=America/Chicago:20261025T090000
            DTEND;TZID=America/Chicago:20261025T100000
            RRULE:FREQ=DAILY
            SUMMARY:Morning standup
            END:VEVENT
            BEGIN:VEVENT
            UID:stand@test
            RECURRENCE-ID;RANGE=THISANDFUTURE;TZID=America/Chicago:20261027T090000
            DTSTART;TZID=America/Chicago:20261027T103000
            DTEND;TZID=America/Chicago:20261027T113000
            SUMMARY:Standup moved
            END:VEVENT
            END:VCALENDAR
            """.trimIndent()
        )
        // 9:00 AM today has already ended at 3:00 PM, so it is not upcoming.
        assertEquals(
            """
            2026-10-26 9:00 AM Morning standup
            2026-10-27 10:30 AM Standup moved
            2026-10-28 10:30 AM Standup moved
            2026-10-29 10:30 AM Standup moved
            2026-10-30 10:30 AM Standup moved
            2026-10-31 10:30 AM Standup moved
            """.trimIndent(),
            describe(rows)
        )
    }

    @Test
    fun allDaySpanStaysOnThoseChicagoDatesFromAWestCoastDevice() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//test//EN
            BEGIN:VEVENT
            UID:trip@test
            DTSTART;VALUE=DATE:20261026
            DTEND;VALUE=DATE:20261028
            SUMMARY:Park trip
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()
        val fromLa = inDeviceZone("America/Los_Angeles") { IcsWindow.upcoming(ics, now, zone) }
        val fromTokyo = inDeviceZone("Asia/Tokyo") { IcsWindow.upcoming(ics, now, zone) }
        val expected = """
            2026-10-26 All day Park trip
            2026-10-27 All day Park trip
        """.trimIndent()
        assertEquals(expected, describe(fromLa))
        assertEquals(expected, describe(fromTokyo))
    }

    @Test
    fun longAllDayThatAlreadyStartedStillShowsThisWeek() {
        val rows = inDeviceZone("America/Los_Angeles") {
            IcsWindow.upcoming(
                """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//test//EN
                BEGIN:VEVENT
                UID:long@test
                DTSTART;VALUE=DATE:20261020
                DTEND;VALUE=DATE:20261028
                SUMMARY:At the lake
                END:VEVENT
                END:VCALENDAR
                """.trimIndent(),
                now,
                zone
            )
        }
        assertEquals(
            """
            2026-10-25 All day At the lake
            2026-10-26 All day At the lake
            2026-10-27 All day At the lake
            """.trimIndent(),
            describe(rows)
        )
    }

    @Test
    fun filtersEndedCancelledAndFarEventsAndKeepsInProgress() {
        val rows = upcoming(
            """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//test//EN
            BEGIN:VEVENT
            UID:breakfast@test
            DTSTART;TZID=America/Chicago:20261025T080000
            DTEND;TZID=America/Chicago:20261025T090000
            SUMMARY:Breakfast
            END:VEVENT
            BEGIN:VEVENT
            UID:game@test
            DTSTART;TZID=America/Chicago:20261025T140000
            DTEND;TZID=America/Chicago:20261025T160000
            SUMMARY:Game on now
            END:VEVENT
            BEGIN:VEVENT
            UID:dinner@test
            DTSTART;TZID=America/Chicago:20261025T180000
            DURATION:PT1H
            SUMMARY:Family din
             ner
            END:VEVENT
            BEGIN:VEVENT
            UID:cancel@test
            DTSTART;TZID=America/Chicago:20261025T160000
            DTEND;TZID=America/Chicago:20261025T170000
            SUMMARY:Cancelled thing
            STATUS:CANCELLED
            END:VEVENT
            BEGIN:VEVENT
            UID:far@test
            DTSTART;TZID=America/Chicago:20261105T120000
            DTEND;TZID=America/Chicago:20261105T130000
            SUMMARY:Too far
            END:VEVENT
            BEGIN:VEVENT
            UID:ny@test
            DTSTART;TZID=America/New_York:20261026T150000
            DTEND;TZID=America/New_York:20261026T160000
            SUMMARY:Call from New York
            END:VEVENT
            BEGIN:VEVENT
            UID:utc@test
            DTSTART:20261027T230000Z
            DTEND:20261028T000000Z
            SUMMARY:UTC lesson
            END:VEVENT
            END:VCALENDAR
            """.trimIndent()
        )
        assertEquals(
            """
            2026-10-25 2:00 PM Game on now
            2026-10-25 6:00 PM Family dinner
            2026-10-26 2:00 PM Call from New York
            2026-10-27 6:00 PM UTC lesson
            """.trimIndent(),
            describe(rows)
        )
    }

    @Test
    fun keepsOnlyTheSoonestEight() {
        val sb = StringBuilder()
        sb.append("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//test//EN\r\n")
        var i = 0
        while (i < 10) {
            val hour = 16 + i
            val day = 25 + hour / 24
            val h = hour % 24
            sb.append("BEGIN:VEVENT\r\n")
            sb.append("UID:slot-").append(i).append("@test\r\n")
            sb.append("DTSTART;TZID=America/Chicago:202610")
            append2(sb, day)
            sb.append('T')
            append2(sb, h)
            sb.append("0000\r\n")
            sb.append("DTEND;TZID=America/Chicago:202610")
            append2(sb, day)
            sb.append('T')
            append2(sb, h)
            sb.append("3000\r\n")
            sb.append("SUMMARY:Slot ").append(i).append("\r\n")
            sb.append("END:VEVENT\r\n")
            i++
        }
        sb.append("END:VCALENDAR\r\n")
        val rows = upcoming(sb.toString())
        assertEquals(8, rows.size)
        assertEquals("Slot 0", rows[0].title)
        assertEquals("Slot 7", rows[7].title)
        assertFalse(describe(rows).contains("Slot 8"))
        assertFalse(describe(rows).contains("Slot 9"))
    }

    @Test
    fun rejectsAPageThatIsNotACalendar() {
        try {
            IcsWindow.upcoming("<html><body>not a calendar</body></html>", now, zone)
            fail("html should not count as a successful parse")
        } catch (ex: IOException) {
            assertTrue(ex.message!!.contains("parse"))
        }
    }

    @Test
    fun emptyCalendarIsAQuietWeek() {
        val rows = upcoming(
            """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//test//EN
            END:VCALENDAR
            """.trimIndent()
        )
        assertTrue(rows.isEmpty())
    }

    @Test
    fun largeFeedKeepsOnlyTheWindow() {
        val built = buildLargeFeed()
        assertTrue("feed was " + built.ics.length, built.ics.length > 800_000)
        assertEquals(3045, built.events)
        assertEquals(67, built.rules)
        val started = System.currentTimeMillis()
        val rows = inDeviceZone("UTC") { IcsWindow.upcoming(built.ics, now, zone) }
        val elapsed = System.currentTimeMillis() - started
        println("large ICS parse+window ms=" + elapsed + " bytes=" + built.ics.length + " kept=" + rows.size)
        assertTrue("took " + elapsed + "ms\n" + describe(rows), elapsed < 30_000)
        assertTrue(rows.size <= IcsWindow.LIMIT)
        assertEquals(7, rows.count { it.title == "Daily stand-up" })
        assertTrue(rows.any { it.title == "Piano lesson" })
        assertTrue(rows.none { it.title.startsWith("Old ") })
        assertTrue(CalendarSnapshot.encodeRows(rows).length < 4000)
    }

    private fun upcoming(ics: String): List<IcsWindow.Row> {
        return inDeviceZone("UTC") { IcsWindow.upcoming(ics, now, zone) }
    }

    private fun <T> inDeviceZone(id: String, block: () -> T): T {
        val previous = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone(id))
        try {
            return block()
        } finally {
            TimeZone.setDefault(previous)
        }
    }

    private fun chicago(year: Int, month: Int, day: Int, hour: Int, minute: Int): Date {
        val cal = Calendar.getInstance(zone)
        cal.clear()
        cal.set(year, month - 1, day, hour, minute, 0)
        return cal.time
    }

    private fun describe(rows: List<IcsWindow.Row>): String {
        return rows.joinToString("\n") { row ->
            ChicagoTime.dayKey(row.headingDayMillis, zone) + " " +
                (if (row.allDay) "All day" else ChicagoTime.formatTime(row.startMillis, zone)) +
                " " + row.title
        }
    }

    private class LargeFeed(val ics: String, val events: Int, val rules: Int)

    /**
     * About the same shape as the real Home calendar: ~1 MB, ~3,045 events,
     * 67 of them recurring. Almost all of them sit outside the week so the
     * result has to throw the rest away.
     */
    private fun buildLargeFeed(): LargeFeed {
        val sb = StringBuilder(1_200_000)
        sb.append("BEGIN:VCALENDAR\r\n")
        sb.append("VERSION:2.0\r\n")
        sb.append("PRODID:-//FamilyDashboardTest//EN\r\n")
        sb.append("X-WR-CALNAME:Home\r\n")
        val padding = "Padding for a large family calendar feed. ".repeat(4)
        var events = 0
        var rules = 0
        var i = 0
        while (i < 2977) {
            val day = (i % 28) + 1
            val month = (i % 12) + 1
            val year = 2020 + (i % 4)
            val hour = 8 + (i % 9)
            sb.append("BEGIN:VEVENT\r\n")
            sb.append("UID:old-").append(i).append("@test\r\n")
            sb.append("DTSTART;TZID=America/Chicago:")
            sb.append(year)
            append2(sb, month)
            append2(sb, day)
            sb.append('T')
            append2(sb, hour)
            sb.append("0000\r\n")
            sb.append("DTEND;TZID=America/Chicago:")
            sb.append(year)
            append2(sb, month)
            append2(sb, day)
            sb.append('T')
            append2(sb, hour + 1)
            sb.append("0000\r\n")
            sb.append("SUMMARY:Old errand ").append(i).append("\r\n")
            sb.append("DESCRIPTION:").append(padding).append(i).append("\r\n")
            sb.append("END:VEVENT\r\n")
            events++
            i++
        }
        var w = 0
        while (w < 66) {
            sb.append("BEGIN:VEVENT\r\n")
            sb.append("UID:weekly-").append(w).append("@test\r\n")
            sb.append("DTSTART;TZID=America/Chicago:20220103T090000\r\n")
            sb.append("DTEND;TZID=America/Chicago:20220103T100000\r\n")
            sb.append("RRULE:FREQ=WEEKLY;BYDAY=MO;UNTIL=20240101T000000Z\r\n")
            sb.append("SUMMARY:Old weekly ").append(w).append("\r\n")
            sb.append("END:VEVENT\r\n")
            events++
            rules++
            w++
        }
        sb.append("BEGIN:VEVENT\r\n")
        sb.append("UID:daily@test\r\n")
        sb.append("DTSTART;TZID=America/Chicago:20100101T160000\r\n")
        sb.append("DTEND;TZID=America/Chicago:20100101T170000\r\n")
        sb.append("RRULE:FREQ=DAILY\r\n")
        sb.append("SUMMARY:Daily stand-up\r\n")
        sb.append("END:VEVENT\r\n")
        events++
        rules++
        sb.append("BEGIN:VEVENT\r\n")
        sb.append("UID:piano@test\r\n")
        sb.append("DTSTART;TZID=America/Chicago:20261025T170000\r\n")
        sb.append("DTEND;TZID=America/Chicago:20261025T180000\r\n")
        sb.append("SUMMARY:Piano lesson\r\n")
        sb.append("END:VEVENT\r\n")
        events++
        sb.append("END:VCALENDAR\r\n")
        return LargeFeed(sb.toString(), events, rules)
    }
}

private fun append2(sb: StringBuilder, value: Int) {
    if (value < 10) {
        sb.append('0')
    }
    sb.append(value)
}
