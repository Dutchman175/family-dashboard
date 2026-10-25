package com.dutchman175.familydashboard

import org.json.JSONObject
import java.security.MessageDigest

/**
 * Parses Apple's public shared-album responses. No network and no logging:
 * the album token never appears in these objects.
 */
internal object SharedStream {
    const val BOOTSTRAP_HOST = "p23-sharedstreams.icloud.com"
    const val TARGET_LONG_EDGE = 1920
    const val MAX_FILE_BYTES = 6_000_000L
    private val CHECKSUM = Regex("^[0-9a-fA-F]{8,128}$")
    private val GUID = Regex("^[A-Za-z0-9-]{8,80}$")
    private val PHOTO_FILE = Regex("[a-f0-9]{32}\\.jpg")

    data class PhotoChoice(
        val guid: String,
        val checksum: String,
        val width: Int,
        val height: Int
    )

    data class Derivative(
        val checksum: String,
        val width: Int,
        val height: Int,
        val fileSize: Long
    )

    fun fileName(guid: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(guid.toByteArray(Charsets.UTF_8))
        val hex = StringBuilder(32)
        for (i in 0 until 16) {
            hex.append(String.format("%02x", digest[i].toInt() and 0xff))
        }
        return hex.toString() + ".jpg"
    }

    fun isStreamHost(host: String): Boolean {
        val value = host.trim().lowercase()
        if (value.length > 80 || value.contains("..")) {
            return false
        }
        if (value.any { it !in 'a'..'z' && it !in '0'..'9' && it != '.' && it != '-' }) {
            return false
        }
        return value == "sharedstreams.icloud.com" || value.endsWith("-sharedstreams.icloud.com")
    }

    fun isCdnHost(host: String): Boolean {
        val value = host.trim().lowercase()
        if (value.isEmpty() || value.length > 120 || value.contains("..")) {
            return false
        }
        if (value.any { it !in 'a'..'z' && it !in '0'..'9' && it != '.' && it != '-' }) {
            return false
        }
        return value.endsWith(".icloud-content.com") || value.endsWith(".icloud.com")
    }

    /** Host from an HTTP 330 body, or null when the body is a normal album listing. */
    fun redirectHost(body: String): String? {
        val json = JSONObject(body)
        if (!json.has("X-Apple-MMe-Host")) {
            return null
        }
        val host = json.optString("X-Apple-MMe-Host", "").trim().lowercase()
        if (!isStreamHost(host)) {
            return null
        }
        return host
    }

    fun photos(body: String): List<PhotoChoice> {
        val photos = JSONObject(body).optJSONArray("photos") ?: return emptyList()
        val out = ArrayList<PhotoChoice>(photos.length())
        for (i in 0 until photos.length()) {
            val photo = photos.optJSONObject(i) ?: continue
            if (photo.optString("mediaAssetType") == "video") {
                continue
            }
            val guid = photo.optString("photoGuid", "")
            if (!GUID.matches(guid)) {
                continue
            }
            val derivatives = photo.optJSONObject("derivatives") ?: continue
            val picked = pick(derivatives) ?: continue
            out.add(PhotoChoice(guid, picked.checksum, picked.width, picked.height))
        }
        return out
    }

    /**
     * Smallest derivative whose long edge is at least 1920. If every size is
     * smaller, the largest of those. Oversized files are skipped so a full
     * original is never downloaded when a screen-sized copy exists.
     */
    fun pick(derivatives: JSONObject): Derivative? {
        val keys = derivatives.keys()
        var bestOver: Derivative? = null
        var bestUnder: Derivative? = null
        while (keys.hasNext()) {
            val key = keys.next()
            if (key == "PosterFrame") {
                continue
            }
            val item = derivatives.optJSONObject(key) ?: continue
            val checksum = item.optString("checksum", "").lowercase()
            if (!CHECKSUM.matches(checksum)) {
                continue
            }
            val width = jsonInt(item, "width")
            val height = jsonInt(item, "height")
            if (width <= 0 || height <= 0) {
                continue
            }
            val fileSize = jsonLong(item, "fileSize")
            if (fileSize > MAX_FILE_BYTES) {
                continue
            }
            val edge = maxOf(width, height)
            val derivative = Derivative(checksum, width, height, fileSize)
            if (edge >= TARGET_LONG_EDGE) {
                val overEdge = bestOver?.let { maxOf(it.width, it.height) } ?: Int.MAX_VALUE
                if (edge < overEdge) {
                    bestOver = derivative
                }
            } else {
                val underEdge = bestUnder?.let { maxOf(it.width, it.height) } ?: -1
                if (edge > underEdge) {
                    bestUnder = derivative
                }
            }
        }
        return bestOver ?: bestUnder
    }

    fun assetUrls(body: String, wanted: Set<String>): Map<String, String> {
        val json = JSONObject(body)
        val items = json.optJSONObject("items") ?: return emptyMap()
        val locations = json.optJSONObject("locations")
        val out = LinkedHashMap<String, String>()
        val keys = items.keys()
        while (keys.hasNext()) {
            val rawKey = keys.next()
            val checksum = rawKey.lowercase()
            if (checksum !in wanted) {
                continue
            }
            val item = items.optJSONObject(rawKey) ?: continue
            val location = item.optString("url_location", "").trim().lowercase()
            val path = item.optString("url_path", "")
            if (!isCdnHost(location) || !safePath(path)) {
                continue
            }
            if (schemeFor(locations, location) != "https") {
                continue
            }
            out[checksum] = "https://$location$path"
        }
        return out
    }

    fun playlistJson(configured: Boolean, fileNames: List<String>): String {
        if (!configured) {
            return """{"enabled":false,"photos":[]}"""
        }
        val safe = fileNames.filter { PHOTO_FILE.matches(it) }
        val sb = StringBuilder()
        sb.append("{\"enabled\":true,\"photos\":[")
        for (i in safe.indices) {
            if (i > 0) {
                sb.append(',')
            }
            sb.append("\"https://appassets.androidplatform.net/photos/")
            sb.append(safe[i])
            sb.append('"')
        }
        sb.append("]}")
        return sb.toString()
    }

    private fun schemeFor(locations: JSONObject?, host: String): String {
        if (locations == null) {
            return "https"
        }
        val match = locationEntry(locations, host) ?: return "https"
        val scheme = match.optString("scheme", "")
        return if (scheme.isEmpty()) "https" else scheme.lowercase()
    }

    private fun locationEntry(locations: JSONObject, host: String): JSONObject? {
        val keys = locations.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (key.lowercase() == host) {
                return locations.optJSONObject(key)
            }
        }
        return null
    }

    private fun safePath(path: String): Boolean {
        if (!path.startsWith("/") || path.contains("://") || path.contains("\\")) {
            return false
        }
        if (path.contains(" ") || path.contains("\n") || path.contains("\r") || path.contains("@")) {
            return false
        }
        return true
    }

    private fun jsonInt(obj: JSONObject, key: String): Int {
        val raw = obj.opt(key) ?: return 0
        return when (raw) {
            is Number -> raw.toInt()
            is String -> raw.toIntOrNull() ?: 0
            else -> 0
        }
    }

    private fun jsonLong(obj: JSONObject, key: String): Long {
        val raw = obj.opt(key) ?: return 0L
        return when (raw) {
            is Number -> raw.toLong()
            is String -> raw.toLongOrNull() ?: 0L
            else -> 0L
        }
    }
}
