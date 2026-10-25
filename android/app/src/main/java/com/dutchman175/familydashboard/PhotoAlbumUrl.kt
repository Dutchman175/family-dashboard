package com.dutchman175.familydashboard

/**
 * Turns the ADB extra into an album token. The link itself is never logged
 * and never handed to the WebView. Callers store only the token.
 */
internal object PhotoAlbumUrl {
    const val EXTRA = "photo_album_url"
    private const val MAX_LEN = 500
    private val TOKEN = Regex("^[A-Za-z0-9_-]{8,128}$")

    internal sealed class Action {
        data object Clear : Action()
        data object Ignore : Action()
        data class Save(val token: String) : Action()
    }

    fun isToken(value: String): Boolean = TOKEN.matches(value)

    fun interpret(raw: String?): Action {
        if (raw == null) {
            return Action.Clear
        }
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed.equals("clear", ignoreCase = true) || trimmed == "-") {
            return Action.Clear
        }
        if (trimmed.length > MAX_LEN || trimmed.contains(" ") || trimmed.contains("\\")) {
            return Action.Ignore
        }
        val token = extractToken(trimmed) ?: return Action.Ignore
        return Action.Save(token)
    }

    private fun extractToken(value: String): String? {
        if (isToken(value) && !value.contains("://")) {
            return value
        }
        if (!value.startsWith("https://")) {
            return null
        }
        val rest = value.substring("https://".length)
        if (rest.contains("@") || rest.startsWith("/")) {
            return null
        }
        val hash = rest.indexOf('#')
        if (hash < 0) {
            return null
        }
        val token = rest.substring(hash + 1)
        if (!isToken(token)) {
            return null
        }
        val before = rest.substring(0, hash)
        val slash = before.indexOf('/')
        val hostPort = if (slash < 0) before else before.substring(0, slash)
        val path = if (slash < 0) "" else before.substring(slash)
        val host = hostPort.substringBefore(':').lowercase()
        if (host != "www.icloud.com" && host != "icloud.com") {
            return null
        }
        val pathOnly = path.substringBefore('?')
        if (pathOnly.contains("..")) {
            return null
        }
        if (pathOnly != "/sharedalbum" && pathOnly != "/sharedalbum/" && !pathOnly.startsWith("/sharedalbum/")) {
            return null
        }
        return token
    }
}
