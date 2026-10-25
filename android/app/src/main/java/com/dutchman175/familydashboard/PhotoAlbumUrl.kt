package com.dutchman175.familydashboard

/**
 * Turns the ADB extra into an album token. The link itself is never logged
 * and never handed to the WebView. Callers store only the token and which
 * public album API it belongs to.
 */
internal object PhotoAlbumUrl {
    const val EXTRA = "photo_album_url"
    private const val MAX_LEN = 500
    private val TOKEN = Regex("^[A-Za-z0-9_-]{8,128}$")

    internal enum class Kind {
        LEGACY,
        CLOUDKIT
    }

    internal sealed class Action {
        data object Clear : Action()
        data object Ignore : Action()
        data class Save(val token: String, val kind: Kind) : Action()
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
        val parsed = extract(trimmed) ?: return Action.Ignore
        return Action.Save(parsed.first, parsed.second)
    }

    private fun extract(value: String): Pair<String, Kind>? {
        if (isToken(value) && !value.contains("://") && !value.contains("/")) {
            return value to Kind.LEGACY
        }
        if (!value.startsWith("https://") || value.contains("@") || value.contains("\\")) {
            return null
        }
        val rest = value.substring("https://".length)
        val hash = rest.indexOf('#')
        val before = if (hash < 0) rest else rest.substring(0, hash)
        val fragment = if (hash < 0) "" else rest.substring(hash + 1)
        val slash = before.indexOf('/')
        val hostPort = if (slash < 0) before else before.substring(0, slash)
        val path = if (slash < 0) "" else before.substring(slash).substringBefore('?')
        if (hostPort.contains("@") || path.contains("..")) {
            return null
        }
        val host = hostPort.substringBefore(':').lowercase()
        if (host == "photos.icloud.com") {
            val parts = path.trim('/').split('/')
            if (parts.size == 3 && parts[0] == "shared" && parts[1] == "album" && isToken(parts[2])) {
                return parts[2] to Kind.CLOUDKIT
            }
            return null
        }
        if (host != "www.icloud.com" && host != "icloud.com") {
            return null
        }
        if (!isToken(fragment)) {
            return null
        }
        if (path != "/sharedalbum" && path != "/sharedalbum/" && !path.startsWith("/sharedalbum/")) {
            return null
        }
        return fragment to Kind.LEGACY
    }
}
