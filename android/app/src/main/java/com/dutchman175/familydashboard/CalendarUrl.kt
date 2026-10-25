package com.dutchman175.familydashboard

/**
 * Turns the ADB extra into a stored https URL. The raw value is never written
 * to logs by callers. Nothing in this class belongs in the git repo as data.
 */
internal object CalendarUrl {
    const val EXTRA = "calendar_url"
    private const val MAX_LEN = 2000

    internal sealed class Action {
        data object Clear : Action()
        data object Ignore : Action()
        data class Save(val url: String) : Action()
    }

    fun interpret(raw: String?): Action {
        if (raw == null) {
            return Action.Clear
        }
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed.equals("clear", ignoreCase = true) || trimmed == "-") {
            return Action.Clear
        }
        if (trimmed.length > MAX_LEN) {
            return Action.Ignore
        }
        val https = toHttps(trimmed) ?: return Action.Ignore
        val rest = https.substring("https://".length)
        if (rest.isEmpty() || rest.startsWith("/") || rest.contains(" ") || rest.contains("\\")) {
            return Action.Ignore
        }
        return Action.Save(https)
    }

    private fun toHttps(value: String): String? {
        if (value.startsWith("https://")) {
            return value
        }
        if (value.regionMatches(0, "webcal://", 0, "webcal://".length, ignoreCase = true)) {
            return "https://" + value.substring("webcal://".length)
        }
        if (value.regionMatches(0, "webcals://", 0, "webcals://".length, ignoreCase = true)) {
            return "https://" + value.substring("webcals://".length)
        }
        return null
    }
}
