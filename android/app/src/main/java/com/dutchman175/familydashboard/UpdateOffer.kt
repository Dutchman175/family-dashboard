package com.dutchman175.familydashboard

import org.json.JSONObject
import java.net.URI
import java.security.MessageDigest

/**
 * The public update pointer. It lives on a separate public repo that holds
 * only the APK and this small file. The family repo, the ntfy topic, the
 * calendar link, and the album link are not part of this address.
 */
internal object UpdateOffer {
    const val VERSION_URL =
        "https://github.com/Dutchman175/family-dashboard-releases/releases/latest/download/version.json"
    const val MAX_JSON_BYTES = 4096
    const val MAX_APK_BYTES = 32L * 1024L * 1024L

    private val HOSTS = setOf(
        "github.com",
        "release-assets.githubusercontent.com",
        "objects.githubusercontent.com",
        "github-releases.githubusercontent.com"
    )
    private val SHA = Regex("^[0-9a-f]{64}$")
    private val NAME = Regex("^[A-Za-z0-9._-]{1,32}$")

    data class Ready(
        val versionCode: Int,
        val versionName: String,
        val apkUrl: String,
        val sha256: String
    )

    sealed class Outcome {
        data object Invalid : Outcome()
        data object Current : Outcome()
        data class Newer(val ready: Ready) : Outcome()
    }

    fun parse(body: String, currentVersionCode: Int): Outcome {
        if (body.length > MAX_JSON_BYTES) {
            return Outcome.Invalid
        }
        val obj = try {
            JSONObject(body)
        } catch (thrown: Exception) {
            return Outcome.Invalid
        }
        if (!obj.has("versionCode")) {
            return Outcome.Invalid
        }
        val code = obj.optInt("versionCode", -1)
        if (code < 1 || code > 1_000_000) {
            return Outcome.Invalid
        }
        val name = obj.optString("versionName", "")
        if (!NAME.matches(name)) {
            return Outcome.Invalid
        }
        val sha = obj.optString("sha256", "").lowercase()
        if (!SHA.matches(sha)) {
            return Outcome.Invalid
        }
        val apkUrl = obj.optString("apkUrl", "")
        if (!allowedApkUrl(apkUrl)) {
            return Outcome.Invalid
        }
        if (code <= currentVersionCode) {
            return Outcome.Current
        }
        return Outcome.Newer(Ready(code, name, apkUrl, sha))
    }

    fun allowedApkUrl(raw: String): Boolean {
        val uri = allowedHttps(raw, allowQuery = false) ?: return false
        val path = uri.path ?: return false
        return path.contains("/family-dashboard-releases/")
    }

    fun allowedHttps(raw: String, allowQuery: Boolean): URI? {
        if (raw.length > 2048 || raw.any { it.isWhitespace() }) {
            return null
        }
        val uri = try {
            URI(raw)
        } catch (thrown: Exception) {
            return null
        }
        if (!uri.isAbsolute || uri.scheme?.lowercase() != "https") {
            return null
        }
        if (!uri.userInfo.isNullOrEmpty()) {
            return null
        }
        val host = uri.host?.lowercase() ?: return null
        if (!HOSTS.contains(host)) {
            return null
        }
        if (!allowQuery && (uri.rawQuery != null || uri.rawFragment != null)) {
            return null
        }
        if (uri.rawFragment != null) {
            return null
        }
        return uri
    }

    fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) {
            sb.append("%02x".format(b.toInt() and 0xff))
        }
        return sb.toString()
    }
}
