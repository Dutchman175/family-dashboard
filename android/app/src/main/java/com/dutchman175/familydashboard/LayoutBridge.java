package com.dutchman175.familydashboard;

import android.webkit.JavascriptInterface;

/**
 * Hands the page the saved box layout. Nothing secret is on this object.
 */
public final class LayoutBridge {
    private final LayoutEditor editor;

    public LayoutBridge(LayoutEditor editor) {
        this.editor = editor;
    }

    @JavascriptInterface
    public String getLayout() {
        return editor.stateJson();
    }
}
