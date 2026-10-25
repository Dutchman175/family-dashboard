package com.dutchman175.familydashboard;

import android.webkit.JavascriptInterface;

/**
 * Hands the page local photo addresses. The album link is not on this object.
 */
public final class PhotoBridge {
    private final PhotoAlbumRepository repository;

    public PhotoBridge(PhotoAlbumRepository repository) {
        this.repository = repository;
    }

    @JavascriptInterface
    public String getPlaylist() {
        return repository.playlistJson();
    }

    @JavascriptInterface
    public void setSeconds(int seconds) {
        repository.setPhotoSeconds(seconds);
    }
}
