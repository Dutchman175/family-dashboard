package com.dutchman175.familydashboard

/**
 * Turns the ADB extra into an ntfy topic name. The name is a secret, like the
 * calendar link. This class never logs it, and callers must not log it either.
 */
internal object MessageTopic {
    const val EXTRA = "message_topic"
    private const val MAX_LEN = 200
    private val TOPIC = Regex("^[A-Za-z0-9_-]{16,64}$")

    internal sealed class Action {
        data object Clear : Action()
        data object Ignore : Action()
        data class Save(val topic: String) : Action()
    }

    fun interpret(raw: String?): Action {
        if (raw == null) {
            return Action.Clear
        }
        var trimmed = raw.trim()
        if (trimmed.length >= 2) {
            val quote = trimmed.first()
            if ((quote == '\'' || quote == '"') && trimmed.last() == quote) {
                trimmed = trimmed.substring(1, trimmed.length - 1).trim()
            }
        }
        if (trimmed.isEmpty() || trimmed.equals("clear", ignoreCase = true) || trimmed == "-") {
            return Action.Clear
        }
        if (trimmed.length > MAX_LEN || trimmed.contains(" ") || trimmed.contains("\\")) {
            return Action.Ignore
        }
        val topic = if (trimmed.startsWith("https://")) {
            fromUrl(trimmed) ?: return Action.Ignore
        } else if (trimmed.contains("://") || trimmed.contains("/")) {
            return Action.Ignore
        } else {
            trimmed
        }
        if (!TOPIC.matches(topic)) {
            return Action.Ignore
        }
        return Action.Save(topic)
    }

    private fun fromUrl(value: String): String? {
        val rest = value.substring("https://".length)
        if (rest.isEmpty() || rest.contains("@") || rest.contains("\\") || rest.contains(" ")) {
            return null
        }
        val slash = rest.indexOf('/')
        if (slash <= 0) {
            return null
        }
        val host = rest.substring(0, slash).substringBefore(':').lowercase()
        if (host != "ntfy.sh") {
            return null
        }
        val path = rest.substring(slash + 1).substringBefore('?').substringBefore('#')
        val first = path.trim('/').substringBefore('/')
        if (first.isEmpty() || first == "json" || first == "sse" || first == "ws") {
            return null
        }
        return first
    }
}
