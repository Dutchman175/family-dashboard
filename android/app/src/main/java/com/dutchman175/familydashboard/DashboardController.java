package com.dutchman175.familydashboard;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.webkit.SslErrorHandler;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.net.http.SslError;

/**
 * Full-screen dashboard WebView shared by the launcher activity and the screensaver.
 * The page itself draws the clock and the weather. This class only loads it,
 * keeps it full screen, and retries when the network fails.
 */
final class DashboardController {
    static final String DASHBOARD_URL = "https://dutchman175.github.io/family-dashboard/";
    private static final String DASHBOARD_PREFIX = "https://dutchman175.github.io/family-dashboard";
    private static final long RETRY_MS = 15000L;

    private final Context context;
    private final WebView webView;
    private final View errorView;
    private final View retryButton;
    private final boolean interactive;
    private final Handler handler;
    private final Runnable retryRunnable;

    private boolean destroyed;
    private boolean loadFailed;
    private BroadcastReceiver networkReceiver;

    DashboardController(Context context, View root, boolean interactive) {
        this.context = context;
        this.interactive = interactive;
        this.webView = root.findViewById(R.id.web);
        this.errorView = root.findViewById(R.id.error);
        this.retryButton = root.findViewById(R.id.retry);
        this.handler = new Handler(Looper.getMainLooper());
        this.retryRunnable = new Runnable() {
            @Override
            public void run() {
                if (!destroyed && errorView.getVisibility() == View.VISIBLE) {
                    load();
                }
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
        registerNetwork();
        load();
    }

    void pause() {
        handler.removeCallbacks(retryRunnable);
        webView.onPause();
    }

    void resume() {
        webView.onResume();
        if (errorView.getVisibility() == View.VISIBLE) {
            load();
        }
    }

    void destroy() {
        destroyed = true;
        handler.removeCallbacks(retryRunnable);
        unregisterNetwork();
        webView.setWebViewClient(null);
        webView.stopLoading();
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
        if (Build.VERSION.SDK_INT >= 33) {
            settings.setAlgorithmicDarkeningAllowed(false);
        } else if (Build.VERSION.SDK_INT >= 29) {
            settings.setForceDark(WebSettings.FORCE_DARK_OFF);
        }

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
            if (!loadFailed && isDashboardUrl(url)) {
                hideError();
            }
        }

        @Override
        public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
            if (isDashboardUrl(failingUrl)) {
                failLoad();
            }
        }

        @Override
        public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
            handler.cancel();
            failLoad();
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            return !isDashboardUrl(url);
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
        public void onReceivedHttpError(WebView view, WebResourceRequest request, android.webkit.WebResourceResponse errorResponse) {
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

    private void load() {
        if (destroyed) {
            return;
        }
        handler.removeCallbacks(retryRunnable);
        loadFailed = false;
        WebSettings settings = webView.getSettings();
        if (isOnline()) {
            settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        } else {
            settings.setCacheMode(WebSettings.LOAD_CACHE_ELSE_NETWORK);
            showError();
            scheduleRetry();
        }
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

    private boolean isDashboardUrl(String url) {
        if (url == null) {
            return false;
        }
        return url.equals(DASHBOARD_PREFIX)
                || url.startsWith(DASHBOARD_PREFIX + "/")
                || url.startsWith(DASHBOARD_PREFIX + "?");
    }

    private boolean isOnline() {
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) {
            return false;
        }
        NetworkInfo info = cm.getActiveNetworkInfo();
        return info != null && info.isConnected();
    }

    private void registerNetwork() {
        if (networkReceiver != null) {
            return;
        }
        networkReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                if (!destroyed && isOnline() && errorView.getVisibility() == View.VISIBLE) {
                    load();
                }
            }
        };
        IntentFilter filter = new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION);
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(networkReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            context.registerReceiver(networkReceiver, filter);
        }
    }

    private void unregisterNetwork() {
        if (networkReceiver == null) {
            return;
        }
        try {
            context.unregisterReceiver(networkReceiver);
        } catch (IllegalArgumentException ignored) {
            // Already unregistered.
        }
        networkReceiver = null;
    }
}
