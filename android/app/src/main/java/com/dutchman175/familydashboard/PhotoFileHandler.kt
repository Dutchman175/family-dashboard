package com.dutchman175.familydashboard

import androidx.webkit.WebViewAssetLoader
import android.webkit.WebResourceResponse
import java.io.File
import java.io.FileInputStream
import java.io.IOException

/**
 * Serves one cached JPEG at a time to the WebView. Paths are a hash, never
 * the album link.
 */
internal class PhotoFileHandler(private val directory: File) : WebViewAssetLoader.PathHandler {
    override fun handle(path: String): WebResourceResponse? {
        val name = path.trim().trimStart('/')
        if (!NAME.matches(name)) {
            return null
        }
        val file = File(directory, name)
        if (!file.isFile) {
            return null
        }
        return try {
            WebResourceResponse("image/jpeg", null, FileInputStream(file))
        } catch (thrown: IOException) {
            null
        }
    }

    private companion object {
        val NAME = Regex("[a-f0-9]{32}\\.jpg")
    }
}
