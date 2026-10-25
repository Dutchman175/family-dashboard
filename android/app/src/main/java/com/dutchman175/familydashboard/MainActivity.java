package com.dutchman175.familydashboard;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;

/**
 * Home-screen launcher. Opens the dashboard full screen and stays awake
 * until Back is pressed.
 */
public class MainActivity extends Activity {
    private static final int FULLSCREEN =
            View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE;

    private DashboardController controller;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_dashboard);
        hideSystemUi();
        Log.i(PHOTO_LOG, "onCreate");
        applyLaunchIntent(getIntent());
        controller = new DashboardController(this, findViewById(R.id.root), true);
        controller.start();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        Log.i(PHOTO_LOG, "onNewIntent");
        applyLaunchIntent(intent);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            hideSystemUi();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemUi();
        if (controller != null) {
            controller.resume();
        }
    }

    @Override
    protected void onPause() {
        if (controller != null) {
            controller.pause();
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (controller != null) {
            controller.destroy();
            controller = null;
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        finish();
    }

    private void applyLaunchIntent(Intent intent) {
        DashboardApp app = (DashboardApp) getApplication();
        app.getCalendar().applyIntent(intent);
        app.getPhotos().applyIntent(intent);
    }

    private static final String PHOTO_LOG = "FamilyDashPhotos";

    private void hideSystemUi() {
        View decor = getWindow().getDecorView();
        decor.setSystemUiVisibility(FULLSCREEN);
    }
}
