package com.dutchman175.familydashboard

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URL
import java.net.URLEncoder

/**
 * Public CloudKit calls used by the photos.icloud.com shared-album page.
 * The album token and the short-lived access token stay out of logs and out
 * of anything handed to the WebView.
 */
internal object CloudKitAlbum {
    const val BUILD = "2638BuildBeta15"
    const val RESOLVE_HOST = "ckdatabasews.icloud.com"
    private const val RECORD_TYPE = "CPLAssetAndMasterByAssetDateWithoutHiddenOrDeleted"
    private const val PAGE_SIZE = 50
    private val RECORD_NAME = Regex("^[A-Za-z0-9+/=._:-]{8,200}$")
    private val ZONE_ID = Regex("^[A-Za-z0-9._:-]{1,80}$")
    private val PREFIXES = arrayOf(
        "resJPEGThumb",
        "resJPEGMed",
        "resJPEGLarge",
        "resJPEGFull",
        "resOriginal"
    )

    data class Share(
        val zoneName: String,
        val ownerRecordName: String,
        val zoneType: String,
        val partitionHost: String,
        val authToken: String
    )

    fun resolveQuery(token: String): String {
        return "remapEnums=true&getCurrentSyncToken=true" +
            "&clientBuildNumber=$BUILD&clientMasteringNumber=$BUILD" +
            "&sharing_url_key=" + encode(token)
    }

    fun recordsQuery(authToken: String): String {
        return "remapEnums=true&getCurrentSyncToken=true" +
            "&clientBuildNumber=$BUILD&clientMasteringNumber=$BUILD" +
            "&publicAccessAuthToken=" + encode(authToken)
    }

    fun resolveBody(token: String): String {
        return JSONObject().put("shortGUIDs", JSONArray().put(JSONObject().put("value", token))).toString()
    }

    fun queryBody(share: Share, continuation: String?): String {
        val filterValue = JSONObject().put("value", "DESCENDING").put("type", "STRING")
        val filter = JSONObject()
            .put("fieldName", "direction")
            .put("comparator", "EQUALS")
            .put("fieldValue", filterValue)
        val query = JSONObject()
            .put("recordType", RECORD_TYPE)
            .put("filterBy", JSONArray().put(filter))
        val zone = JSONObject()
            .put("zoneName", share.zoneName)
            .put("ownerRecordName", share.ownerRecordName)
            .put("zoneType", share.zoneType)
        val root = JSONObject()
            .put("query", query)
            .put("zoneID", zone)
            .put("resultsLimit", PAGE_SIZE)
        if (!continuation.isNullOrEmpty()) {
            root.put("continuationMarker", continuation)
        }
        return root.toString()
    }

    fun parseResolve(body: String): Share? {
        val first = JSONObject(body).optJSONArray("results")?.optJSONObject(0) ?: return null
        val zone = first.optJSONObject("zoneID") ?: return null
        val zoneName = zone.optString("zoneName", "")
        val owner = zone.optString("ownerRecordName", "")
        val zoneType = zone.optString("zoneType", "REGULAR_CUSTOM_ZONE")
        if (!ZONE_ID.matches(zoneName) || !ZONE_ID.matches(owner) || !ZONE_ID.matches(zoneType)) {
            return null
        }
        val access = first.optJSONObject("anonymousPublicAccess") ?: return null
        val auth = access.optString("token", "")
        if (auth.length !in 20..4000 || auth.any { it.isWhitespace() }) {
            return null
        }
        val host = partitionHost(access.optString("databasePartition", "")) ?: return null
        return Share(zoneName, owner, zoneType, host, auth)
    }

    fun continuation(body: String): String? {
        val marker = JSONObject(body).optString("continuationMarker", "")
        return marker.ifBlank { null }
    }

    fun photos(body: String): List<SharedStream.PhotoChoice> {
        val records = JSONObject(body).optJSONArray("records") ?: return emptyList()
        val out = ArrayList<SharedStream.PhotoChoice>()
        for (i in 0 until records.length()) {
            val record = records.optJSONObject(i) ?: continue
            if (record.optString("recordType") != "CPLMaster") {
                continue
            }
            val name = record.optString("recordName", "")
            if (!RECORD_NAME.matches(name)) {
                continue
            }
            val fields = record.optJSONObject("fields") ?: continue
            if (!isImage(fields)) {
                continue
            }
            val picked = pick(fields) ?: continue
            out.add(
                SharedStream.PhotoChoice(
                    guid = name,
                    checksum = SharedStream.fileName(name).removeSuffix(".jpg"),
                    width = picked.width,
                    height = picked.height,
                    directUrl = picked.url
                )
            )
        }
        return out
    }

    fun isCkHost(host: String): Boolean {
        val value = host.trim().lowercase().substringBefore(':')
        if (value.length > 80 || value.contains("..")) {
            return false
        }
        if (value.any { it !in 'a'..'z' && it !in '0'..'9' && it != '.' && it != '-' }) {
            return false
        }
        return value == "ckdatabasews.icloud.com" || value.endsWith("-ckdatabasews.icloud.com")
    }

    private fun partitionHost(raw: String): String? {
        val uri = try {
            URI(raw)
        } catch (thrown: Exception) {
            return null
        }
        if (uri.scheme != "https" || uri.userInfo != null) {
            return null
        }
        val host = uri.host?.lowercase() ?: return null
        return if (isCkHost(host)) host else null
    }

    private fun isImage(fields: JSONObject): Boolean {
        val itemType = fields.optJSONObject("itemType")?.optString("value", "")?.lowercase() ?: ""
        if (itemType.isEmpty()) {
            return true
        }
        if (itemType.contains("movie") || itemType.contains("video") || itemType.contains("mpeg")) {
            return false
        }
        return itemType.contains("heic") ||
            itemType.contains("jpeg") ||
            itemType.contains("jpg") ||
            itemType.contains("png") ||
            itemType.contains("gif") ||
            itemType.contains("tiff") ||
            itemType.contains("image") ||
            itemType.contains("webp")
    }

    private data class SizedUrl(val url: String, val width: Int, val height: Int)

    /**
     * Smallest JPEG whose long edge is at least 1920, otherwise the largest
     * smaller one. Originals are used only when nothing closer to the screen exists.
     */
    private fun pick(fields: JSONObject): SizedUrl? {
        var bestOver: SizedUrl? = null
        var bestUnder: SizedUrl? = null
        for (prefix in PREFIXES) {
            val value = fields.optJSONObject(prefix + "Res")?.optJSONObject("value") ?: continue
            val download = value.optString("downloadURL", "")
            if (!allowedDownload(download)) {
                continue
            }
            val size = jsonLong(value, "size")
            if (size > SharedStream.MAX_FILE_BYTES) {
                continue
            }
            val width = jsonInt(fields.optJSONObject(prefix + "Width"), "value")
            val height = jsonInt(fields.optJSONObject(prefix + "Height"), "value")
            if (width <= 0 || height <= 0) {
                continue
            }
            val edge = maxOf(width, height)
            val item = SizedUrl(download, width, height)
            if (edge >= SharedStream.TARGET_LONG_EDGE) {
                val over = bestOver?.let { maxOf(it.width, it.height) } ?: Int.MAX_VALUE
                if (edge < over) {
                    bestOver = item
                }
            } else {
                val under = bestUnder?.let { maxOf(it.width, it.height) } ?: -1
                if (edge > under) {
                    bestUnder = item
                }
            }
        }
        return bestOver ?: bestUnder
    }

    private fun allowedDownload(url: String): Boolean {
        if (url.isEmpty() || url == "null" || url.length > 4000) {
            return false
        }
        if (url.any { it.isISOControl() || it == ' ' || it == '\\' }) {
            return false
        }
        // Apple's signed URLs contain braces. java.net.URI rejects those, so
        // the parser here is URL, which still yields the host.
        val parsed = try {
            URL(url)
        } catch (thrown: Exception) {
            return false
        }
        if (parsed.protocol != "https" || parsed.userInfo != null) {
            return false
        }
        val host = parsed.host?.lowercase() ?: return false
        return SharedStream.isCdnHost(host)
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun jsonInt(obj: JSONObject?, key: String): Int {
        if (obj == null) {
            return 0
        }
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
