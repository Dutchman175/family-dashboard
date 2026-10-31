package com.dutchman175.familydashboard

/**
 * Decides when the dashboard may come forward on its own.
 * It opens only after a home screen has been in front, with no media
 * playing, for the chosen number of minutes. Another app, including a
 * paused video, is left alone.
 */
internal object IdlePolicy {
    const val DEFAULT_MINUTES = 5
    const val MIN_MINUTES = 1
    const val MAX_MINUTES = 120
    const val CHECK_MS = 30_000L
    const val COOLDOWN_MS = 120_000L
    const val BOOT_DELAY_MS = 20_000L

    fun clampMinutes(minutes: Int): Int {
        if (minutes < MIN_MINUTES) {
            return MIN_MINUTES
        }
        if (minutes > MAX_MINUTES) {
            return MAX_MINUTES
        }
        return minutes
    }

    fun shouldOpen(
        enabled: Boolean,
        idleMinutes: Int,
        screenOn: Boolean,
        mediaPlaying: Boolean,
        foregroundPackage: String?,
        homeSinceMillis: Long?,
        nowMillis: Long,
        lastOpenMillis: Long,
        ourPackage: String,
        homePackages: Set<String>
    ): Boolean {
        if (!enabled || !screenOn || mediaPlaying) {
            return false
        }
        if (nowMillis - lastOpenMillis < COOLDOWN_MS) {
            return false
        }
        if (foregroundPackage.isNullOrBlank() || foregroundPackage == ourPackage) {
            return false
        }
        if (!homePackages.contains(foregroundPackage)) {
            return false
        }
        val since = homeSinceMillis ?: return false
        val wait = clampMinutes(idleMinutes) * 60_000L
        return nowMillis - since >= wait
    }

    /**
     * Keeps the moment the home screen became the front app.
     * A blank package means we could not tell, so the clock does not run.
     */
    fun nextHomeSince(
        previous: Long?,
        foregroundPackage: String?,
        eventAtMillis: Long,
        screenOn: Boolean,
        ourPackage: String,
        homePackages: Set<String>
    ): Long? {
        if (!screenOn || foregroundPackage.isNullOrBlank()) {
            return null
        }
        if (foregroundPackage == ourPackage || !homePackages.contains(foregroundPackage)) {
            return null
        }
        return previous ?: eventAtMillis
    }
}
