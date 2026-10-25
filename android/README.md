# Family Dashboard for Android TV and Fire TV

A full-screen clock, Lowell weather, and an optional upcoming calendar. The page is inside the APK. The TV does not open a website. Weather still needs the internet (Open-Meteo). The calendar is loaded on the TV from a private iCloud link that is stored only on the device.

The same APK includes a screensaver (Android dream) that shows the dashboard.

It runs on Fire OS sticks and TVs (Android 5.1 and newer) and on the TiVo Stream 4K. It does not run on a Fire TV Stick 4K Select: that model uses Vega OS, not Fire OS, and cannot install Android apps. Check Settings, My Fire TV, About. If the software is Fire OS, this app can install.

The signed release APK is attached to the GitHub release:

https://github.com/Dutchman175/family-dashboard/releases/latest/download/FamilyDashboard.apk

After the repo is private, download it from a computer that is already logged in with `gh auth login`, then install it with adb. The signing key is `family-dashboard.keystore` in this folder. It is a public sideload key (password `familydashboard`) so a later build can update the installed app. Do not replace the keystore if the app is already installed.

## Install or update from a computer

Turn on ADB on the TV once:

1. **Settings → My Fire TV → About** (on a TiVo: **Settings → Device Preferences → About**). Select the device name, or **Android TV OS build**, and press Select 7 times until developer options turn on.
2. **Developer options → ADB debugging → On**. On Fire OS 5 or 6 also turn on **Apps from Unknown Sources**. On Fire OS 7 or newer, unknown-app install is per app; adb does not need the Downloader switch.
3. Note the TV's IP address under **About → Network**.

On the computer, install [Android platform-tools](https://developer.android.com/tools/releases/platform-tools). Download `FamilyDashboard.apk` from the release above, then:

```
adb connect TV_IP
adb install -r FamilyDashboard.apk
```

`-r` replaces the copy that is already installed and keeps the calendar link. Open **Family Dashboard** from the apps row. Back leaves the app.

Some TiVo Stream 4K units on Android 12 remove sideloaded apps. A system app named CoreService does that. If the dashboard disappears after it is installed, connect with adb and run:

```
adb shell pm disable-user com.nes.coreservice
```

Then `adb install -r FamilyDashboard.apk` again. A few people have to run the disable command twice, then reboot, before it sticks.

## Calendar

In the iCloud calendar app, share the Home calendar and copy the public link. It looks like `webcal://...`. Do not put that link in git, in a screenshot, or on a web page. Set it only on the TV:

```
adb shell am start -n com.dutchman175.familydashboard/.MainActivity --es calendar_url 'webcal://PASTE-THE-LINK-HERE'
```

`webcal://` is stored as `https://`. The app fetches it about every 15 minutes, including when the screensaver starts, and shows the next 8 events in the coming week. Times are Central Time. Until a link is set, the Upcoming panel stays hidden and the clock and weather use the full width.

To clear the link:

```
adb shell am start -n com.dutchman175.familydashboard/.MainActivity --es calendar_url ""
```

If the TV ignores the empty value, use:

```
adb shell am start -n com.dutchman175.familydashboard/.MainActivity --es calendar_url clear
```

If iCloud cannot be reached, the last events stay on screen with a small "Calendar not updated" note.

## Photo slideshow

Share an iCloud album and copy its link. A current album looks like `https://photos.icloud.com/shared/album/PASTE-THE-TOKEN`. An older album looks like `https://www.icloud.com/sharedalbum/#PASTE-THE-TOKEN`. Do not put that link in git, in a screenshot, or in the web page. Set it only on the TV. Pass the link as-is. Quotes keep a `#` from being treated as a comment:

```
adb shell am start -n com.dutchman175.familydashboard/.MainActivity --es photo_album_url 'https://photos.icloud.com/shared/album/PASTE-THE-TOKEN'
```

```
adb shell "am start -n com.dutchman175.familydashboard/.MainActivity --es photo_album_url 'https://www.icloud.com/sharedalbum/#PASTE-THE-TOKEN'"
```

The app stores the album token in its private storage, downloads a screen-sized copy of each photo (not the original), and keeps those files on the device. The slideshow crossfades about every 18 seconds, including in the screensaver. It refreshes the album about every 45 minutes. Logcat tag `FamilyDashPhotos` records each step and never includes the album link. With an album, the photo fills a large panel on the left and the clock, weather, and Upcoming stack in a narrower column on the right. Until a link is set, or if no photo loads, the dashboard looks the same as before: dark background, large clock, Lowell weather, and Upcoming side by side.

To clear the album:

```
adb shell am start -n com.dutchman175.familydashboard/.MainActivity --es photo_album_url ""
```

If the TV ignores the empty value, use:

```
adb shell am start -n com.dutchman175.familydashboard/.MainActivity --es photo_album_url clear
```

## Screensaver on a TiVo Stream 4K

1. Install the app first.
2. Open **Settings → Device Preferences → Screen saver**. On Android 12 this may instead be **Settings → System → Screen saver**.
3. Choose **Family Dashboard**.
4. Set **When to start** to how long the TV should sit idle first.

Any key on the remote closes the screensaver. If the list of screensavers is missing, use the Fire TV commands below. Skip the Amazon Ambient line.

## Screensaver on Fire TV

Amazon does not show third-party screensavers in Settings. You set this one with ADB. The app cannot turn itself on as the screensaver.

1. Turn on Developer options the same way as the install steps (device name, 7 presses).
2. **Settings → My Fire TV → Developer options → ADB debugging → On**.
3. **Settings → My Fire TV → About → Network**. Write down the IP address.
4. On a computer, install [Android platform-tools](https://developer.android.com/tools/releases/platform-tools) and connect. The Fire TV asks you to allow the computer. Choose OK.

```
adb connect FIRE_TV_IP
adb shell settings put secure screensaver_components com.dutchman175.familydashboard/.DashboardDreamService
adb shell settings put secure screensaver_default_component com.dutchman175.familydashboard/.DashboardDreamService
adb shell settings put secure screensaver_enabled 1
adb shell settings put secure screensaver_activate_on_sleep 1
adb shell settings put secure contextual_screen_off_timeout 300000
```

`300000` is 5 minutes. Use `600000` for 10 minutes. You can also set the normal sleep timer:

```
adb shell settings put system screen_off_timeout 300000
```

Check that it stuck:

```
adb shell settings get secure screensaver_components
```

It should print `com.dutchman175.familydashboard/.DashboardDreamService`.

**Fire OS 8** adds an Ambient Experience screensaver that ignores other dreams until it is turned off. Then reboot:

```
adb shell settings put secure amazon_ambient_enabled 0
adb reboot
```

That extra command was confirmed on Fire OS 8.1 in 2025. It is still not a setting Amazon supports, and a software update can turn Amazon’s screensaver back on. If the dashboard stops appearing when the TV is idle, run the commands again.

To go back to Amazon’s photos screensaver:

```
adb shell settings put secure screensaver_components com.amazon.bueller.photos/.daydream.ScreenSaverService
adb shell settings put secure screensaver_default_component com.amazon.bueller.photos/.daydream.ScreenSaverService
adb shell settings put secure amazon_ambient_enabled 1
adb reboot
```

That Amazon class name is the usual one. If it does not match your Fire OS version, clear the custom values and reboot, and Amazon’s default comes back:

```
adb shell settings delete secure screensaver_components
adb shell settings delete secure screensaver_default_component
adb reboot
```

If the screensaver never stays set, open the Family Dashboard app and leave it up. The screen stays on while the app is in front. Back or Home leaves it.

## Build

Java 21 and Android SDK 34. The Gradle build copies `index.html`, `styles.css`, and `app.js` from the repo root into the APK. Do not keep a second copy of those files under `android/`.

```
cd android
./gradlew assembleRelease
```

The APK is `android/app/build/outputs/apk/release/FamilyDashboard.apk`.
