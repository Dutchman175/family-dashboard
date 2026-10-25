package com.dutchman175.familydashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CalendarUrlTest {
    @Test
    fun webcalBecomesHttps() {
        val saved = CalendarUrl.interpret("  webcal://cal.example/published/2/abc  ")
        assertTrue(saved is CalendarUrl.Action.Save)
        assertEquals("https://cal.example/published/2/abc", (saved as CalendarUrl.Action.Save).url)
    }

    @Test
    fun webcalsAndExistingHttps() {
        val secure = CalendarUrl.interpret("WEBCALS://cal.example/secret")
        assertEquals("https://cal.example/secret", (secure as CalendarUrl.Action.Save).url)
        val https = CalendarUrl.interpret("https://cal.example/secret")
        assertEquals("https://cal.example/secret", (https as CalendarUrl.Action.Save).url)
    }

    @Test
    fun clearAndIgnore() {
        assertTrue(CalendarUrl.interpret(null) is CalendarUrl.Action.Clear)
        assertTrue(CalendarUrl.interpret("") is CalendarUrl.Action.Clear)
        assertTrue(CalendarUrl.interpret("   ") is CalendarUrl.Action.Clear)
        assertTrue(CalendarUrl.interpret("clear") is CalendarUrl.Action.Clear)
        assertTrue(CalendarUrl.interpret("CLEAR") is CalendarUrl.Action.Clear)
        assertTrue(CalendarUrl.interpret("-") is CalendarUrl.Action.Clear)
        assertTrue(CalendarUrl.interpret("http://cal.example/nope") is CalendarUrl.Action.Ignore)
        assertTrue(CalendarUrl.interpret("javascript:alert(1)") is CalendarUrl.Action.Ignore)
        assertTrue(CalendarUrl.interpret("https://") is CalendarUrl.Action.Ignore)
        assertTrue(CalendarUrl.interpret("https://" + "a".repeat(3000)) is CalendarUrl.Action.Ignore)
    }
}
