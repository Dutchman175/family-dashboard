package com.dutchman175.familydashboard;

import android.view.KeyEvent;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RemoteKeysTest {
    @Test
    public void selectAndEnterCountAsOk() {
        assertTrue(RemoteKeys.isOk(KeyEvent.KEYCODE_DPAD_CENTER));
        assertTrue(RemoteKeys.isOk(KeyEvent.KEYCODE_ENTER));
        assertTrue(RemoteKeys.isOk(KeyEvent.KEYCODE_NUMPAD_ENTER));
        assertFalse(RemoteKeys.isOk(KeyEvent.KEYCODE_MENU));
        assertFalse(RemoteKeys.isOk(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE));
    }

    @Test
    public void menuIsTheThreeLineButton() {
        assertTrue(RemoteKeys.isMenu(KeyEvent.KEYCODE_MENU));
        assertFalse(RemoteKeys.isMenu(KeyEvent.KEYCODE_DPAD_CENTER));
        assertFalse(RemoteKeys.isMenu(KeyEvent.KEYCODE_BACK));
    }

    @Test
    public void monthsChangeWithoutChannelOrPageButtons() {
        assertTrue(RemoteKeys.isPreviousMonth(KeyEvent.KEYCODE_DPAD_LEFT));
        assertTrue(RemoteKeys.isPreviousMonth(KeyEvent.KEYCODE_MEDIA_REWIND));
        assertTrue(RemoteKeys.isNextMonth(KeyEvent.KEYCODE_DPAD_RIGHT));
        assertTrue(RemoteKeys.isNextMonth(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD));
        assertFalse(RemoteKeys.isPreviousMonth(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD));
        assertFalse(RemoteKeys.isNextMonth(KeyEvent.KEYCODE_MEDIA_REWIND));
        assertFalse(RemoteKeys.isNextMonth(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE));
    }
}
