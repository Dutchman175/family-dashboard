# Family Dashboard for Android TV and Fire TV

A small full-screen app that shows https://dutchman175.github.io/family-dashboard/. There is no browser bar. The screen stays on while the app is open. Back exits.

The same APK includes a screensaver (Android dream) that shows the dashboard.

It runs on Fire OS sticks and TVs (Android 5.1 and newer) and on the TiVo Stream 4K. It does not run on a Fire TV Stick 4K Select: that model uses Vega OS, not Fire OS, and cannot install Android apps. Check Settings, My Fire TV, About. If the software is Fire OS, this app can install.

The signed release APK is attached to the GitHub release:

https://github.com/Dutchman175/family-dashboard/releases/latest/download/FamilyDashboard.apk

The signing key is `family-dashboard.keystore` in this folder. It is a public sideload key (password `familydashboard`) so a later build can update the installed app. Do not replace the keystore if the app is already installed.

## Install on a Fire TV Stick

1. From the home screen, search the Amazon Appstore for **Downloader** (by AFTVnews) and install it. Do not open it yet.
2. Go to **Settings → My Fire TV → About**. Select the device name at the top and press Select 7 times, until it says you are a developer.
3. Press Back. Open **Developer options**.
   - Fire OS 7 or newer: **Install unknown apps → Downloader → On**.
   - Fire OS 5 or 6: **Apps from Unknown Sources → On**.
4. Open Downloader. If it asks to access files, choose **Allow**.
5. Enter this address and choose **Go**:

   `https://github.com/Dutchman175/family-dashboard/releases/latest/download/FamilyDashboard.apk`

6. When it finishes, choose **Install**, then **Done**.
7. Open **Family Dashboard** from **Your Apps & Channels**. It fills the screen. Back leaves the app.

If Downloader is missing from the Install unknown apps list, restart the Fire TV and look again. That is a known Fire OS glitch.

## Install on a TiVo Stream 4K

1. Open the Google Play Store, search for **Downloader** (by AFTVnews), and install it.
2. Allow it to install apps:
   - **Settings → Apps → Special app access → Install unknown apps → Downloader → Allow**
   - On older software: **Settings → Device Preferences → Security & restrictions → Unknown sources**.
3. Open Downloader, enter the same address as above, and install.
4. Open **Family Dashboard** from the Apps row.

Some TiVo Stream 4K units updated to Android 12 uninstall Downloader (and some other sideloaded tools) as soon as they are installed. A system app named CoreService does that. If it happens, turn on Developer options (**Settings → Device Preferences → About**, select **Android TV OS build** 7 times), turn on **USB debugging**, then from a computer on the same Wi-Fi run:

```
adb connect TIVO_IP_ADDRESS
adb shell pm disable-user com.nes.coreservice
```

Install Downloader again, then the dashboard APK. A few people have to run the disable command twice, then reboot, before it sticks.

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

Java 21 and Android SDK 34.

```
cd android
./gradlew assembleRelease
```

The APK is `android/app/build/outputs/apk/release/FamilyDashboard.apk`.
