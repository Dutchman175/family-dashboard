package com.dutchman175.familydashboard

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Downloads a shared iCloud album off the main thread and keeps a small set of
 * JPEG files on disk. The album token stays in private preferences. Image bytes
 * are not decoded here, so the slideshow does not hold a bitmap per photo.
 */
class PhotoAlbumRepository(context: Context) {
    fun interface PlaylistListener {
        fun onPlaylist(json: String)
    }

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val cacheDir: File = File(context.applicationContext.filesDir, "photo-cache").apply {
        mkdirs()
    }
    private val scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "photo-fetch").apply { isDaemon = true }
    }
    private val listeners = ArrayList<PlaylistListener>()
    private val listenerLock = Any()
    private val started = AtomicBoolean(false)

    @Volatile
    private var generation = 0

    fun photoDirectory(): File = cacheDir

    fun start() {
        if (!started.compareAndSet(false, true)) {
            return
        }
        scheduler.scheduleWithFixedDelay(
            { safeFetch() },
            0,
            REFRESH_MINUTES,
            TimeUnit.MINUTES
        )
    }

    fun applyIntent(intent: Intent?) {
        if (intent == null || !intent.hasExtra(PhotoAlbumUrl.EXTRA)) {
            Log.i(TAG, "extra received=false")
            return
        }
        val raw = intent.getStringExtra(PhotoAlbumUrl.EXTRA)
        when (val action = PhotoAlbumUrl.interpret(raw)) {
            PhotoAlbumUrl.Action.Clear -> {
                Log.i(TAG, "extra received=true action=clear")
                clear()
            }
            PhotoAlbumUrl.Action.Ignore -> {
                Log.i(TAG, "extra received=true action=ignore len=${raw?.length ?: 0}")
            }
            is PhotoAlbumUrl.Action.Save -> {
                Log.i(TAG, "extra received=true action=save kind=${action.kind.name} len=${action.token.length}")
                setToken(action.token, action.kind)
            }
        }
        intent.removeExtra(PhotoAlbumUrl.EXTRA)
    }

    fun requestRefresh() {
        scheduler.execute { safeFetch() }
    }

    fun playlistJson(): String {
        val configured = !prefs.getString(KEY_TOKEN, null).isNullOrEmpty()
        val names = cacheDir.listFiles()
            ?.map { it.name }
            ?.filter { it.endsWith(".jpg") && !it.endsWith(".part") }
            ?.sorted()
            ?: emptyList()
        return SharedStream.playlistJson(configured, names)
    }

    fun addListener(listener: PlaylistListener) {
        synchronized(listenerLock) {
            listeners.add(listener)
        }
        listener.onPlaylist(playlistJson())
    }

    fun removeListener(listener: PlaylistListener) {
        synchronized(listenerLock) {
            listeners.remove(listener)
        }
    }

    private fun setToken(token: String, kind: PhotoAlbumUrl.Kind) {
        generation++
        val same = token == prefs.getString(KEY_TOKEN, null) &&
            kind.name == prefs.getString(KEY_KIND, PhotoAlbumUrl.Kind.LEGACY.name)
        prefs.edit().putString(KEY_TOKEN, token).putString(KEY_KIND, kind.name).commit()
        Log.i(TAG, "saved kind=${kind.name} len=${token.length}")
        notifyListeners()
        scheduler.execute {
            if (!same) {
                deleteAllPhotos()
            }
            safeFetch()
        }
    }

    private fun clear() {
        generation++
        prefs.edit().remove(KEY_TOKEN).remove(KEY_KIND).commit()
        notifyListeners()
        scheduler.execute { deleteAllPhotos() }
    }

    private fun safeFetch() {
        val gen = generation
        try {
            fetchOnce(gen)
        } catch (thrown: Throwable) {
            Log.w(TAG, "fetch failed ${thrown.javaClass.simpleName}")
            if (gen == generation) {
                notifyListeners()
            }
        }
    }

    private fun fetchOnce(gen: Int) {
        val token = prefs.getString(KEY_TOKEN, null)
        val kindName = prefs.getString(KEY_KIND, PhotoAlbumUrl.Kind.LEGACY.name)
        if (token.isNullOrEmpty() || !PhotoAlbumUrl.isToken(token)) {
            Log.i(TAG, "fetch start configured=false")
            if (gen != generation) {
                return
            }
            if (!token.isNullOrEmpty()) {
                prefs.edit().remove(KEY_TOKEN).remove(KEY_KIND).commit()
            }
            deleteAllPhotos()
            notifyListeners()
            return
        }
        Log.i(TAG, "fetch start configured=true kind=$kindName")
        val cloudKit = kindName == PhotoAlbumUrl.Kind.CLOUDKIT.name
        val page = if (cloudKit) null else loadPage(token)
        val photos = if (cloudKit) loadCloudKit(token) else page!!.photos
        Log.i(TAG, "photos listed=${photos.size}")
        if (gen != generation) {
            return
        }
        val keep = selectKeep(photos)
        val keepNames = keep.map { SharedStream.fileName(it.guid) }.toSet()
        deleteExcept(keepNames)
        val missing = keep.filter { !File(cacheDir, SharedStream.fileName(it.guid)).isFile }
        if (missing.isNotEmpty()) {
            val urls = if (page == null) {
                emptyMap()
            } else {
                assetUrls(page.host, token, missing)
            }
            for (photo in missing) {
                if (gen != generation || cacheBytes() > MAX_CACHE_BYTES) {
                    break
                }
                val url = photo.directUrl ?: urls[photo.checksum] ?: continue
                download(url, SharedStream.fileName(photo.guid), 0)
            }
        }
        if (gen != generation) {
            return
        }
        Log.i(TAG, "cached=${jpegCount()} kept=${keep.size}")
        notifyListeners()
    }

    private fun selectKeep(photos: List<SharedStream.PhotoChoice>): List<SharedStream.PhotoChoice> {
        val fresh = ArrayList<SharedStream.PhotoChoice>()
        val cached = ArrayList<SharedStream.PhotoChoice>()
        for (photo in photos) {
            if (File(cacheDir, SharedStream.fileName(photo.guid)).isFile) {
                cached.add(photo)
            } else {
                fresh.add(photo)
            }
        }
        val ordered = ArrayList<SharedStream.PhotoChoice>(fresh.size + cached.size)
        ordered.addAll(fresh)
        ordered.addAll(cached)
        return if (ordered.size <= MAX_PHOTOS) ordered else ordered.subList(0, MAX_PHOTOS)
    }

    private data class Page(val host: String, val photos: List<SharedStream.PhotoChoice>)

    private fun loadPage(token: String): Page {
        var host = SharedStream.BOOTSTRAP_HOST
        var reply = postJson(host, token, "webstream", "{\"streamCtag\":null}")
        if (shouldFollow(reply)) {
            val next = SharedStream.redirectHost(reply.body) ?: throw IOException("photo fetch failed")
            if (next == host) {
                throw IOException("photo fetch failed")
            }
            host = next
            reply = postJson(host, token, "webstream", "{\"streamCtag\":null}")
        }
        if (reply.code !in 200..299) {
            throw IOException("photo fetch failed")
        }
        return Page(host, SharedStream.photos(reply.body))
    }

    private fun loadCloudKit(token: String): List<SharedStream.PhotoChoice> {
        val resolveUrl = URL(
            "https",
            CloudKitAlbum.RESOLVE_HOST,
            "/database/1/com.apple.photos.cloud/production/public/records/resolve?" +
                CloudKitAlbum.resolveQuery(token)
        )
        val resolved = postAbsolute(resolveUrl, CloudKitAlbum.resolveBody(token))
        if (resolved.code !in 200..299) {
            throw IOException("photo fetch failed")
        }
        val share = CloudKitAlbum.parseResolve(resolved.body) ?: throw IOException("photo fetch failed")
        val photos = ArrayList<SharedStream.PhotoChoice>()
        var marker: String? = null
        var pages = 0
        while (pages < 20) {
            val queryUrl = URL(
                "https",
                share.partitionHost,
                "/database/1/com.apple.photos.cloud/production/shared/records/query?" +
                    CloudKitAlbum.recordsQuery(share.authToken)
            )
            val page = postAbsolute(queryUrl, CloudKitAlbum.queryBody(share, marker))
            if (page.code !in 200..299) {
                throw IOException("photo fetch failed")
            }
            photos.addAll(CloudKitAlbum.photos(page.body))
            marker = CloudKitAlbum.continuation(page.body)
            pages++
            if (marker == null) {
                break
            }
        }
        return photos
    }

    private fun postAbsolute(url: URL, json: String): Reply {
        val host = url.host?.lowercase() ?: throw IOException("photo fetch failed")
        if (url.protocol != "https" || !CloudKitAlbum.isCkHost(host)) {
            throw IOException("photo fetch failed")
        }
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 20_000
            connection.readTimeout = 30_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "text/plain")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Origin", "https://photos.icloud.com")
            connection.setRequestProperty("User-Agent", "FamilyDashboard/1.2.1")
            connection.outputStream.use { it.write(json.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            Log.i(TAG, "http POST host=$host status=$code")
            val stream = openBody(connection, code)
            val body = if (stream == null) "" else readLimited(stream, MAX_META_BYTES)
            return Reply(code, body)
        } catch (thrown: IOException) {
            Log.w(TAG, "http POST host=$host failed ${thrown.javaClass.simpleName}")
            throw IOException("photo fetch failed")
        } finally {
            connection.disconnect()
        }
    }

    private fun shouldFollow(reply: Reply): Boolean {
        if (reply.code == 330) {
            return true
        }
        if (reply.code !in 200..299) {
            return false
        }
        if (SharedStream.redirectHost(reply.body) == null) {
            return false
        }
        return !reply.body.contains("\"photos\"")
    }

    private fun assetUrls(
        host: String,
        token: String,
        photos: List<SharedStream.PhotoChoice>
    ): Map<String, String> {
        val out = HashMap<String, String>()
        var index = 0
        while (index < photos.size) {
            val end = minOf(index + BATCH, photos.size)
            val batch = photos.subList(index, end)
            val payload = StringBuilder("{\"photoGuids\":[")
            for (i in batch.indices) {
                if (i > 0) {
                    payload.append(',')
                }
                payload.append('"')
                payload.append(batch[i].guid)
                payload.append('"')
            }
            payload.append("]}")
            val reply = postJson(host, token, "webasseturls", payload.toString())
            if (reply.code !in 200..299) {
                throw IOException("photo fetch failed")
            }
            out.putAll(SharedStream.assetUrls(reply.body, batch.map { it.checksum }.toSet()))
            index = end
        }
        return out
    }

    private class Reply(val code: Int, val body: String)

    private fun postJson(host: String, token: String, endpoint: String, json: String): Reply {
        if (!SharedStream.isStreamHost(host) || !PhotoAlbumUrl.isToken(token)) {
            throw IOException("photo fetch failed")
        }
        val url = URL("https", host, "/$token/sharedstreams/$endpoint")
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 20_000
            connection.readTimeout = 20_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "FamilyDashboard/1.2.1")
            connection.outputStream.use { it.write(json.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            Log.i(TAG, "http POST host=$host status=$code")
            val stream = openBody(connection, code)
            val body = if (stream == null) "" else readLimited(stream, MAX_META_BYTES)
            return Reply(code, body)
        } catch (thrown: IOException) {
            Log.w(TAG, "http POST host=$host failed ${thrown.javaClass.simpleName}")
            throw IOException("photo fetch failed")
        } finally {
            connection.disconnect()
        }
    }

    private fun openBody(connection: HttpURLConnection, code: Int): InputStream? {
        return try {
            if (code in 200..299) connection.inputStream else connection.errorStream
        } catch (thrown: IOException) {
            connection.errorStream
        }
    }

    private fun download(urlString: String, destName: String, hops: Int) {
        if (hops > 2) {
            return
        }
        if (!destName.endsWith(".jpg") || destName.contains("/") || destName.contains("..")) {
            return
        }
        val url = try {
            URL(urlString)
        } catch (thrown: Exception) {
            Log.i(TAG, "download skip reason=bad-url")
            return
        }
        val host = url.host?.lowercase() ?: return
        if (url.protocol != "https" || !SharedStream.isCdnHost(host)) {
            Log.i(TAG, "download skip reason=host")
            return
        }
        val connection = url.openConnection() as HttpURLConnection
        var temp: File? = null
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 20_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("Accept", "image/jpeg")
            connection.setRequestProperty("User-Agent", "FamilyDashboard/1.2.1")
            val code = connection.responseCode
            Log.i(TAG, "http GET host=$host status=$code")
            if (code in 300..399) {
                val location = connection.getHeaderField("Location") ?: return
                download(location, destName, hops + 1)
                return
            }
            if (code !in 200..299) {
                return
            }
            val advertised = connection.contentLengthLong
            if (advertised > SharedStream.MAX_FILE_BYTES) {
                return
            }
            temp = File(cacheDir, "$destName.part")
            var total = 0
            var header = ByteArray(0)
            connection.inputStream.use { input ->
                FileOutputStream(temp).use { out ->
                    val buf = ByteArray(8192)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) {
                            break
                        }
                        total += n
                        if (total > SharedStream.MAX_FILE_BYTES) {
                            throw IOException("photo too large")
                        }
                        if (header.size < 3) {
                            val take = minOf(3 - header.size, n)
                            header += buf.copyOfRange(0, take)
                        }
                        out.write(buf, 0, n)
                    }
                }
            }
            if (header.size < 3 || header[0] != 0xFF.toByte() || header[1] != 0xD8.toByte() || header[2] != 0xFF.toByte()) {
                Log.i(TAG, "download skip reason=not-jpeg host=$host")
                temp?.delete()
                return
            }
            val dest = File(cacheDir, destName)
            if (dest.exists()) {
                dest.delete()
            }
            if (!temp!!.renameTo(dest)) {
                temp!!.copyTo(dest, overwrite = true)
                temp!!.delete()
            }
        } catch (thrown: IOException) {
            Log.w(TAG, "http GET host=$host failed ${thrown.javaClass.simpleName}")
            temp?.delete()
        } finally {
            connection.disconnect()
        }
    }

    private fun cacheBytes(): Long {
        val files = cacheDir.listFiles() ?: return 0L
        var total = 0L
        for (file in files) {
            if (file.isFile) {
                total += file.length()
            }
        }
        return total
    }

    private fun deleteExcept(keep: Set<String>) {
        val files = cacheDir.listFiles() ?: return
        for (file in files) {
            if (file.name !in keep) {
                file.delete()
            }
        }
    }

    private fun deleteAllPhotos() {
        val files = cacheDir.listFiles() ?: return
        for (file in files) {
            file.delete()
        }
    }

    private fun readLimited(input: InputStream, max: Int): String {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var total = 0
        input.use { stream ->
            while (true) {
                val n = stream.read(buf)
                if (n < 0) {
                    break
                }
                total += n
                if (total > max) {
                    throw IOException("photo fetch failed")
                }
                out.write(buf, 0, n)
            }
        }
        return out.toString("UTF-8")
    }

    private fun jpegCount(): Int {
        val files = cacheDir.listFiles() ?: return 0
        var count = 0
        for (file in files) {
            if (file.isFile && file.name.endsWith(".jpg") && !file.name.endsWith(".part")) {
                count++
            }
        }
        return count
    }

    private fun notifyListeners() {
        val json = playlistJson()
        val enabled = json.startsWith("{\"enabled\":true")
        var count = 0
        var at = 0
        while (true) {
            val found = json.indexOf(".jpg", at)
            if (found < 0) {
                break
            }
            count++
            at = found + 4
        }
        Log.i(TAG, "playlist sent enabled=$enabled count=$count")
        val copy = synchronized(listenerLock) { ArrayList(listeners) }
        for (listener in copy) {
            listener.onPlaylist(json)
        }
    }

    private companion object {
        const val TAG = "FamilyDashPhotos"
        const val PREFS = "family_dashboard"
        const val KEY_TOKEN = "photo_album_token"
        const val KEY_KIND = "photo_album_kind"
        const val REFRESH_MINUTES = 45L
        const val MAX_PHOTOS = 40
        const val BATCH = 20
        const val MAX_CACHE_BYTES = 80L * 1024L * 1024L
        const val MAX_META_BYTES = 4 * 1024 * 1024
    }
}
