package com.dutchman175.familydashboard;

import android.webkit.JavascriptInterface;

/**
 * Hands the page the current messages. The ntfy topic is not on this object.
 */
public final class MessageBridge {
    private final MessageRepository repository;

    public MessageBridge(MessageRepository repository) {
        this.repository = repository;
    }

    @JavascriptInterface
    public String getMessages() {
        return repository.snapshotJson();
    }
}
