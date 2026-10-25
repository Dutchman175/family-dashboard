package com.dutchman175.familydashboard;

import android.webkit.JavascriptInterface;

/**
 * Hands the page a snapshot of upcoming events. The calendar URL is not on
 * this object.
 */
public final class CalendarBridge {
    private final CalendarRepository repository;

    public CalendarBridge(CalendarRepository repository) {
        this.repository = repository;
    }

    @JavascriptInterface
    public String getSnapshot() {
        return repository.snapshotJson();
    }
}
