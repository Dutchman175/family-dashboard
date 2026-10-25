package com.dutchman175.familydashboard;

import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.webkit.SslErrorHandler;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.net.http.SslError;

import androidx.webkit.WebViewAssetLoader;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Full-screen dashboard WebView shared by the launcher activity and the screensaver.
 * The page is bundled in the APK. Weather and the photo album use the network.
 * Cached photos are served from app storage, one file at a time.
 */
final class DashboardController {
    private static final String DASHBOARD_URL =
            "https://appassets.androidplatform.net/assets/dashboard/index.html";
    private static final String DASHBOARD_PREFIX =
            "https://appassets.androidplatform.net/assets/dashboard";
    private static final long RETRY_MS = 15000L;
    private static final long MONTH_IDLE_MS = 120000L;

    private final WebView webView;
    private final View errorView;
    private final View retryButton;
    private final boolean interactive;
    private final Handler handler;
    private final Runnable retryRunnable;
    private final WebViewAssetLoader assetLoader;
    private final CalendarRepository calendar;
    private final CalendarRepository.SnapshotListener snapshotListener;
    private final PhotoAlbumRepository photos;
    private final PhotoAlbumRepository.PlaylistListener playlistListener;

    private boolean destroyed;
    private boolean loadFailed;
    private boolean monthOpen;
    private boolean calendarFocused;
    private final Runnable idleClose;

    DashboardController(Context context, View root, boolean interactive) {
        this.interactive = interactive;
        this.webView = root.findViewById(R.id.web);
        this.errorView = root.findViewById(R.id.error);
        this.retryButton = root.findViewById(R.id.retry);
        this.handler = new Handler(Looper.getMainLooper());
        this.idleClose = new Runnable() {
            @Override
            public void run() {
                if (!destroyed && monthOpen) {
                    closeMonth();
                }
            }
        };
        this.retryRunnable = new Runnable() {
            @Override
            public void run() {
                if (!destroyed && errorView.getVisibility() == View.VISIBLE) {
                    load();
                }
            }
        };
        Context appContext = context.getApplicationContext();
        DashboardApp app = (DashboardApp) appContext;
        this.photos = app.getPhotos();
        this.assetLoader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(appContext))
                .addPathHandler("/photos/", new PhotoFileHandler(photos.photoDirectory()))
                .build();
        this.calendar = app.getCalendar();
        this.snapshotListener = new CalendarRepository.SnapshotListener() {
            @Override
            public void onSnapshot(String json) {
                pushSnapshot(json);
            }
        };
        this.playlistListener = new PhotoAlbumRepository.PlaylistListener() {
            @Override
            public void onPlaylist(String json) {
                pushPlaylist(json);
            }
        };
        if (!interactive) {
            retryButton.setVisibility(View.GONE);
        } else {
            retryButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    load();
                }
            });
        }
        configureWebView();
    }

    void start() {
        calendar.addListener(snapshotListener);
        calendar.requestRefresh();
        photos.addListener(playlistListener);
        photos.requestRefresh();
        load();
    }

    void pause() {
        handler.removeCallbacks(retryRunnable);
        handler.removeCallbacks(idleClose);
        webView.onPause();
    }

    void resume() {
        webView.onResume();
        if (monthOpen) {
            resetIdle();
        }
        if (errorView.getVisibility() == View.VISIBLE) {
            load();
        }
    }

    void destroy() {
        destroyed = true;
        handler.removeCallbacks(retryRunnable);
        handler.removeCallbacks(idleClose);
        calendar.removeListener(snapshotListener);
        photos.removeListener(playlistListener);
        webView.setWebViewClient(null);
        webView.stopLoading();
        webView.removeJavascriptInterface("FamilyCalendar");
        webView.removeJavascriptInterface("FamilyPhotos");
        ViewParent parent = webView.getParent();
        if (parent instanceof ViewGroup) {
            ((ViewGroup) parent).removeView(webView);
        }
        webView.destroy();
    }

    private void configureWebView() {
        webView.setBackgroundColor(0xFF0E131B);
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        webView.setVerticalScrollBarEnabled(false);
        webView.setHorizontalScrollBarEnabled(false);
        webView.setFocusable(false);
        webView.setFocusableInTouchMode(false);
        webView.setLongClickable(false);
        webView.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                return true;
            }
        });
        webView.setHapticFeedbackEnabled(false);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setLoadsImagesAutomatically(true);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(false);
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setTextZoom(100);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
        if (Build.VERSION.SDK_INT >= 21) {
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        }
        if (Build.VERSION.SDK_INT >= 33) {
            settings.setAlgorithmicDarkeningAllowed(false);
        } else if (Build.VERSION.SDK_INT >= 29) {
            settings.setForceDark(WebSettings.FORCE_DARK_OFF);
        }

        webView.addJavascriptInterface(new CalendarBridge(calendar), "FamilyCalendar");
        webView.addJavascriptInterface(new PhotoBridge(photos), "FamilyPhotos");
        if (Build.VERSION.SDK_INT >= 23) {
            webView.setWebViewClient(new ModernClient());
        } else {
            webView.setWebViewClient(new BaseClient());
        }
    }

    private class BaseClient extends WebViewClient {
        @Override
        public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
            loadFailed = false;
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            if (!loadFailed && isMainDocument(url)) {
                hideError();
                pushSnapshot(calendar.snapshotJson());
                pushPlaylist(photos.playlistJson());
                pushTvState();
            }
        }

        @Override
        public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
            if (isMainDocument(failingUrl)) {
                failLoad();
            }
        }

        @Override
        public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
            handler.cancel();
            if (error != null && isMainDocument(error.getUrl())) {
                failLoad();
            }
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            return !isDashboardUrl(url);
        }

        @Override
        @SuppressWarnings("deprecation")
        public WebResourceResponse shouldInterceptRequest(WebView view, String url) {
            return interceptUrl(url);
        }

        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            if (request == null || request.getUrl() == null) {
                return null;
            }
            return interceptUrl(request.getUrl().toString());
        }
    }

    /**
     * API 23+ callbacks. Kept in a separate class so an older Fire OS WebView
     * never has to load WebResourceError.
     */
    private class ModernClient extends BaseClient {
        @Override
        public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
            if (request != null && request.isForMainFrame()) {
                failLoad();
            }
        }

        @Override
        public void onReceivedHttpError(
                WebView view,
                WebResourceRequest request,
                WebResourceResponse errorResponse) {
            if (request != null && request.isForMainFrame()) {
                int status = errorResponse == null ? 0 : errorResponse.getStatusCode();
                if (status >= 400) {
                    failLoad();
                }
            }
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            if (request == null || request.getUrl() == null) {
                return true;
            }
            return !isDashboardUrl(request.getUrl().toString());
        }
    }

    private WebResourceResponse interceptUrl(String url) {
        if (url == null) {
            return null;
        }
        WebResourceResponse response = assetLoader.shouldInterceptRequest(Uri.parse(url));
        return uncached(response);
    }

    private WebResourceResponse uncached(WebResourceResponse response) {
        if (response == null) {
            return null;
        }
        InputStream data = response.getData();
        if (data == null) {
            return null;
        }
        Map<String, String> headers = new HashMap<String, String>();
        headers.put("Cache-Control", "no-store");
        String mime = response.getMimeType();
        if (mime == null) {
            mime = "application/octet-stream";
        }
        return new WebResourceResponse(mime, response.getEncoding(), 200, "OK", headers, data);
    }

    /**
     * TV remote keys for the launcher only. The screensaver constructs this
     * controller with interactive false and never receives input.
     */
    boolean handleKey(KeyEvent event) {
        if (!interactive || event == null || destroyed) {
            return false;
        }
        if (errorView.getVisibility() == View.VISIBLE) {
            return false;
        }
        int action = event.getAction();
        int code = event.getKeyCode();
        if (code == KeyEvent.KEYCODE_BACK) {
            if (!monthOpen) {
                return false;
            }
            if (action == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                closeMonth();
            }
            return true;
        }
        if (action != KeyEvent.ACTION_DOWN) {
            return false;
        }
        if (monthOpen && !calendarConfigured()) {
            closeMonth();
            return false;
        }
        if (monthOpen) {
            resetIdle();
            if (isPreviousMonth(code)) {
                injectKey("prev");
                return true;
            }
            if (isNextMonth(code)) {
                injectKey("next");
                return true;
            }
            if (isDpad(code) || isOk(code)) {
                return true;
            }
            return false;
        }
        if (event.getRepeatCount() > 0) {
            return false;
        }
        if (isDpad(code)) {
            focusCalendar();
            return calendarFocused;
        }
        if (isOk(code) && calendarFocused) {
            openMonth();
            return true;
        }
        return false;
    }

    boolean consumeBack() {
        if (!interactive || !monthOpen) {
            return false;
        }
        closeMonth();
        return true;
    }

    private void focusCalendar() {
        if (!calendarConfigured()) {
            calendarFocused = false;
            return;
        }
        calendarFocused = true;
        injectKey("focus");
    }

    private void openMonth() {
        if (!calendarFocused || monthOpen || !calendarConfigured()) {
            return;
        }
        monthOpen = true;
        injectKey("open");
        resetIdle();
    }

    private void closeMonth() {
        if (!monthOpen) {
            return;
        }
        monthOpen = false;
        handler.removeCallbacks(idleClose);
        injectKey("close");
    }

    private void resetIdle() {
        handler.removeCallbacks(idleClose);
        if (monthOpen && !destroyed) {
            handler.postDelayed(idleClose, MONTH_IDLE_MS);
        }
    }

    private boolean calendarConfigured() {
        String json = calendar.snapshotJson();
        return json != null && json.indexOf("\"configured\":true") >= 0;
    }

    private void injectKey(final String name) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (destroyed) {
                    return;
                }
                webView.evaluateJavascript(
                        "window.onDashKey&&window.onDashKey('" + name + "')",
                        null);
            }
        });
    }

    private void pushTvState() {
        if (!interactive) {
            return;
        }
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (destroyed) {
                    return;
                }
                StringBuilder cmd = new StringBuilder("window.__tvKeys=true;");
                if (calendarFocused) {
                    cmd.append("window.onDashKey&&window.onDashKey('focus');");
                }
                if (monthOpen) {
                    cmd.append("window.onDashKey&&window.onDashKey('open');");
                }
                webView.evaluateJavascript(cmd.toString(), null);
            }
        });
    }

    private static boolean isDpad(int code) {
        return code == KeyEvent.KEYCODE_DPAD_UP
                || code == KeyEvent.KEYCODE_DPAD_DOWN
                || code == KeyEvent.KEYCODE_DPAD_LEFT
                || code == KeyEvent.KEYCODE_DPAD_RIGHT;
    }

    private static boolean isOk(int code) {
        return code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER;
    }

    private static boolean isPreviousMonth(int code) {
        return code == KeyEvent.KEYCODE_DPAD_LEFT
                || code == KeyEvent.KEYCODE_CHANNEL_DOWN
                || code == KeyEvent.KEYCODE_PAGE_UP;
    }

    private static boolean isNextMonth(int code) {
        return code == KeyEvent.KEYCODE_DPAD_RIGHT
                || code == KeyEvent.KEYCODE_CHANNEL_UP
                || code == KeyEvent.KEYCODE_PAGE_DOWN;
    }

    private void pushSnapshot(final String json) {
        if (destroyed || json == null || json.length() == 0 || json.charAt(0) != '{') {
            return;
        }
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (destroyed) {
                    return;
                }
                webView.evaluateJavascript(
                        "window.onCalendarSnapshot&&window.onCalendarSnapshot(" + json + ")",
                        null);
            }
        });
    }

    private void pushPlaylist(final String json) {
        if (destroyed || json == null || json.length() == 0 || json.charAt(0) != '{') {
            return;
        }
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (destroyed) {
                    return;
                }
                webView.evaluateJavascript(
                        "window.onPhotoPlaylist&&window.onPhotoPlaylist(" + json + ")",
                        null);
            }
        });
    }

    private void load() {
        if (destroyed) {
            return;
        }
        handler.removeCallbacks(retryRunnable);
        loadFailed = false;
        webView.loadUrl(DASHBOARD_URL);
    }

    private void failLoad() {
        if (destroyed) {
            return;
        }
        loadFailed = true;
        showError();
        scheduleRetry();
    }

    private void scheduleRetry() {
        handler.removeCallbacks(retryRunnable);
        handler.postDelayed(retryRunnable, RETRY_MS);
    }

    private void showError() {
        errorView.setVisibility(View.VISIBLE);
        if (interactive) {
            retryButton.setVisibility(View.VISIBLE);
            retryButton.requestFocus();
        }
    }

    private void hideError() {
        handler.removeCallbacks(retryRunnable);
        errorView.setVisibility(View.GONE);
    }

    private static boolean isDashboardUrl(String url) {
        return url != null && url.startsWith(DASHBOARD_PREFIX);
    }

    private static boolean isMainDocument(String url) {
        if (url == null) {
            return false;
        }
        int cut = url.length();
        int query = url.indexOf('?');
        if (query >= 0) {
            cut = query;
        }
        int hash = url.indexOf('#');
        if (hash >= 0 && hash < cut) {
            cut = hash;
        }
        String bare = url.substring(0, cut);
        return bare.endsWith("/dashboard/index.html")
                || bare.endsWith("/dashboard/")
                || bare.endsWith("/dashboard");
    }
}
