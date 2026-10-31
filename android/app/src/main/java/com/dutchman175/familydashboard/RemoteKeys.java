package com.dutchman175.familydashboard;

import android.view.KeyEvent;

/**
 * Buttons that exist on a Fire TV remote and on the TiVo remote.
 * A Fire TV Stick has a D-pad, Select, Back, Menu, play/pause, rewind,
 * and fast-forward. It has no channel or page buttons.
 */
final class RemoteKeys {
    private RemoteKeys() {
    }

    static boolean isOk(int code) {
        return code == KeyEvent.KEYCODE_DPAD_CENTER
                || code == KeyEvent.KEYCODE_ENTER
                || code == KeyEvent.KEYCODE_NUMPAD_ENTER;
    }

    static boolean isMenu(int code) {
        return code == KeyEvent.KEYCODE_MENU;
    }

    static boolean isPlayPause(int code) {
        return code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                || code == KeyEvent.KEYCODE_MEDIA_PLAY
                || code == KeyEvent.KEYCODE_MEDIA_PAUSE;
    }

    static boolean isPreviousMonth(int code) {
        return code == KeyEvent.KEYCODE_DPAD_LEFT
                || code == KeyEvent.KEYCODE_MEDIA_REWIND
                || code == KeyEvent.KEYCODE_CHANNEL_DOWN
                || code == KeyEvent.KEYCODE_PAGE_UP;
    }

    static boolean isNextMonth(int code) {
        return code == KeyEvent.KEYCODE_DPAD_RIGHT
                || code == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD
                || code == KeyEvent.KEYCODE_CHANNEL_UP
                || code == KeyEvent.KEYCODE_PAGE_DOWN;
    }
}
