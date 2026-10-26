package com.dutchman175.familydashboard;

import android.service.dreams.DreamService;

/**
 * Screensaver that shows the same dashboard. On Android TV it can be chosen
 * under Settings, Device Preferences, Screen saver. Fire TV hides that list;
 * see android/README.md for the ADB steps.
 */
public class DashboardDreamService extends DreamService {
    private DashboardController controller;

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);
        setFullscreen(true);
        setScreenBright(true);
        setContentView(R.layout.activity_dashboard);
        controller = new DashboardController(this, findViewById(R.id.root), false);
        controller.start();
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        if (controller != null) {
            controller.resume();
        }
        DashboardApp app = (DashboardApp) getApplication();
        app.getUpdates().checkFromScreensaver();
    }

    @Override
    public void onDreamingStopped() {
        if (controller != null) {
            controller.pause();
        }
        super.onDreamingStopped();
    }

    @Override
    public void onDetachedFromWindow() {
        if (controller != null) {
            controller.destroy();
            controller = null;
        }
        super.onDetachedFromWindow();
    }
}
