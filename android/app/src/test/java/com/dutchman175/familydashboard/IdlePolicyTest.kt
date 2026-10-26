package com.dutchman175.familydashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IdlePolicyTest {
    private val homes = setOf("com.amazon.tv.launcher")
    private val us = "com.dutchman175.familydashboard"

    @Test
    fun staysOffUntilTheHomeScreenHasBeenIdle() {
        val start = 1_000_000L
        assertFalse(
            IdlePolicy.shouldOpen(
                enabled = true,
                idleMinutes = 5,
                screenOn = true,
                mediaPlaying = false,
                foregroundPackage = "com.amazon.tv.launcher",
                homeSinceMillis = start,
                nowMillis = start + 5 * 60_000L - 1,
                lastOpenMillis = 0,
                ourPackage = us,
                homePackages = homes
            )
        )
        assertTrue(
            IdlePolicy.shouldOpen(
                enabled = true,
                idleMinutes = 5,
                screenOn = true,
                mediaPlaying = false,
                foregroundPackage = "com.amazon.tv.launcher",
                homeSinceMillis = start,
                nowMillis = start + 5 * 60_000L,
                lastOpenMillis = 0,
                ourPackage = us,
                homePackages = homes
            )
        )
    }

    @Test
    fun doesNotInterruptVideoMusicOrAnotherApp() {
        val now = 10 * 60_000L
        assertFalse(open(now, media = true, foreground = "com.amazon.tv.launcher", since = 0))
        assertFalse(open(now, media = false, foreground = "com.netflix.ninja", since = 0))
        assertFalse(open(now, media = false, foreground = us, since = 0))
        assertFalse(open(now, media = false, foreground = null, since = 0))
        assertFalse(open(now, media = false, foreground = "com.amazon.tv.launcher", since = null))
    }

    @Test
    fun disabledScreenOffAndCooldownHoldItBack() {
        val now = 10 * 60_000L
        assertFalse(
            IdlePolicy.shouldOpen(
                false, 5, true, false, "com.amazon.tv.launcher", 0, now, 0, us, homes
            )
        )
        assertFalse(
            IdlePolicy.shouldOpen(
                true, 5, false, false, "com.amazon.tv.launcher", 0, now, 0, us, homes
            )
        )
        assertFalse(
            IdlePolicy.shouldOpen(
                true, 5, true, false, "com.amazon.tv.launcher", 0, now, now - 10_000L, us, homes
            )
        )
    }

    @Test
    fun homeClockStartsOnTheLauncherAndClearsElsewhere() {
        assertEquals(
            40L,
            IdlePolicy.nextHomeSince(null, "com.amazon.tv.launcher", 40, true, us, homes)
        )
        assertEquals(
            40L,
            IdlePolicy.nextHomeSince(40, "com.amazon.tv.launcher", 80, true, us, homes)
        )
        assertNull(IdlePolicy.nextHomeSince(50, "com.netflix.ninja", 80, true, us, homes))
        assertNull(IdlePolicy.nextHomeSince(50, "com.amazon.tv.launcher", 80, false, us, homes))
        assertNull(IdlePolicy.nextHomeSince(50, null, 80, true, us, homes))
    }

    @Test
    fun minutesStayInRange() {
        assertEquals(1, IdlePolicy.clampMinutes(0))
        assertEquals(5, IdlePolicy.clampMinutes(5))
        assertEquals(120, IdlePolicy.clampMinutes(500))
    }

    private fun open(now: Long, media: Boolean, foreground: String?, since: Long?): Boolean {
        return IdlePolicy.shouldOpen(
            enabled = true,
            idleMinutes = 5,
            screenOn = true,
            mediaPlaying = media,
            foregroundPackage = foreground,
            homeSinceMillis = since,
            nowMillis = now,
            lastOpenMillis = 0,
            ourPackage = us,
            homePackages = homes
        )
    }
}
