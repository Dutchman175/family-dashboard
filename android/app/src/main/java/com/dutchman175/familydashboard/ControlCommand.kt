package com.dutchman175.familydashboard

/**
 * Remote settings posted on the same private ntfy topic as family notes.
 * The topic is the secret. This prefix only keeps a normal note from changing
 * a setting. Calendar, album, and topic changes are not accepted here.
 */
internal object ControlCommand {
    private val SECONDS = intArrayOf(5, 10, 15, 18, 20, 30, 45, 60, 90, 120, 300, 600)

    sealed class Parsed {
        data object NotCommand : Parsed()
        data object Ignore : Parsed()
        data class Apply(
            val seconds: Int?,
            val resetLayout: Boolean,
            val id: String = ""
        ) : Parsed()
    }

    fun interpret(raw: String): Parsed {
        val text = raw.replace('\r', ' ').replace('\n', ' ').trim()
        if (text.length > 200) {
            return if (text.startsWith("#cfg")) Parsed.Ignore else Parsed.NotCommand
        }
        if (!text.startsWith("#cfg")) {
            return Parsed.NotCommand
        }
        if (!text.startsWith("#cfg ")) {
            return Parsed.Ignore
        }
        val rest = text.removePrefix("#cfg ").trim()
        if (rest.isEmpty()) {
            return Parsed.Ignore
        }
        var seconds: Int? = null
        var resetLayout = false
        var sawSeconds = false
        var sawLayout = false
        for (part in rest.split(' ').filter { it.isNotEmpty() }) {
            val eq = part.indexOf('=')
            if (eq <= 0 || eq != part.lastIndexOf('=') || eq == part.length - 1) {
                return Parsed.Ignore
            }
            val key = part.substring(0, eq)
            val value = part.substring(eq + 1)
            when (key) {
                "seconds" -> {
                    if (sawSeconds) {
                        return Parsed.Ignore
                    }
                    val parsed = value.toIntOrNull()
                    if (parsed == null || !SECONDS.contains(parsed)) {
                        return Parsed.Ignore
                    }
                    sawSeconds = true
                    seconds = parsed
                }
                "layout" -> {
                    if (sawLayout || value != "reset") {
                        return Parsed.Ignore
                    }
                    sawLayout = true
                    resetLayout = true
                }
                else -> return Parsed.Ignore
            }
        }
        if (!sawSeconds && !sawLayout) {
            return Parsed.Ignore
        }
        return Parsed.Apply(seconds, resetLayout)
    }
}

internal object ControlIds {
    data class Remember(val ids: List<String>, val fresh: Boolean)

    fun remember(existing: List<String>, id: String, max: Int = 40): Remember {
        if (id.isEmpty() || existing.contains(id)) {
            return Remember(existing, fresh = false)
        }
        val next = ArrayList(existing)
        next.add(id)
        while (next.size > max) {
            next.removeAt(0)
        }
        return Remember(next, fresh = true)
    }
}
