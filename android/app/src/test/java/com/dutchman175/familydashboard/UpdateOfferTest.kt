package com.dutchman175.familydashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateOfferTest {
    private val sha = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
    private val apk =
        "https://github.com/Dutchman175/family-dashboard-releases/releases/download/v1.5.0/FamilyDashboard.apk"

    @Test
    fun versionFilePointsAtThePublicReleasesRepo() {
        assertEquals(
            "https://github.com/Dutchman175/family-dashboard-releases/releases/latest/download/version.json",
            UpdateOffer.VERSION_URL
        )
        assertTrue(UpdateOffer.VERSION_URL.contains("/Dutchman175/family-dashboard-releases/"))
        assertFalse(UpdateOffer.VERSION_URL.contains("ntfy"))
        assertFalse(UpdateOffer.VERSION_URL.contains("icloud"))
        assertFalse(UpdateOffer.VERSION_URL.contains("webcal"))
        assertFalse(UpdateOffer.VERSION_URL.contains("token"))
    }

    @Test
    fun acceptsANewerBuildAndRejectsTheRest() {
        val newer = UpdateOffer.parse(manifest(9), 8)
        assertTrue(newer is UpdateOffer.Outcome.Newer)
        val ready = (newer as UpdateOffer.Outcome.Newer).ready
        assertEquals(9, ready.versionCode)
        assertEquals("1.6.0", ready.versionName)
        assertEquals(sha, ready.sha256)
        assertEquals(apk, ready.apkUrl)

        assertTrue(UpdateOffer.parse(manifest(8), 8) is UpdateOffer.Outcome.Current)
        assertTrue(UpdateOffer.parse(manifest(7), 8) is UpdateOffer.Outcome.Current)
        assertTrue(UpdateOffer.parse("not json", 8) is UpdateOffer.Outcome.Invalid)
        assertTrue(
            UpdateOffer.parse(manifest(9).replace(sha, "abcd"), 8) is UpdateOffer.Outcome.Invalid
        )
        assertTrue(
            UpdateOffer.parse(manifest(9).replace("https://", "http://"), 8) is UpdateOffer.Outcome.Invalid
        )
    }

    @Test
    fun apkUrlStaysOnThePublicReleasesRepo() {
        assertTrue(UpdateOffer.allowedApkUrl(apk))
        assertFalse(
            UpdateOffer.allowedApkUrl(
                "https://github.com/Dutchman175/family-dashboard/releases/download/v1.5.0/FamilyDashboard.apk"
            )
        )
        assertFalse(UpdateOffer.allowedApkUrl("https://evil.example/FamilyDashboard.apk"))
        assertFalse(UpdateOffer.allowedApkUrl("$apk?token=secret"))
        assertFalse(UpdateOffer.allowedApkUrl("https://user:pw@github.com/Dutchman175/family-dashboard-releases/a.apk"))

        val redirected =
            "https://release-assets.githubusercontent.com/github-production-release-asset/1/2?sig=abc"
        assertNull(UpdateOffer.allowedHttps(redirected, allowQuery = false))
        assertEquals(
            "release-assets.githubusercontent.com",
            UpdateOffer.allowedHttps(redirected, allowQuery = true)?.host
        )
    }

    @Test
    fun sha256MatchesAKnownValue() {
        assertEquals(sha, UpdateOffer.sha256Hex("abc".toByteArray()))
    }

    private fun manifest(code: Int): String {
        return """{"versionCode":$code,"versionName":"1.6.0","apkUrl":"$apk","sha256":"$sha"}"""
    }
}
