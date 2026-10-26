# Family Dashboard for Android TV and Fire TV

A full-screen clock, Lowell weather, an optional upcoming calendar, an optional photo slideshow, and an optional message board. The page is inside the APK. The TV does not open a website. Weather still needs the internet (Open-Meteo). The calendar and the photo album are loaded on the TV from private iCloud links that are stored only on the device. Messages come from a private ntfy topic, also stored only on the device.

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

`webcal://` is stored as `https://`. The app fetches it about every 15 minutes, including when the screensaver starts, and shows the next 8 events in the coming week. Times are Central Time. Until a link is set, the Upcoming panel stays hidden. While you are arranging the boxes, that spot still shows so you can move it.

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

The app stores the album token in its private storage and downloads the largest JPEG derivative under about 4 MB (not a thumbnail). Photos already on the device are not downloaded again. The album is fetched when the app process starts and then about every 45 minutes. The slideshow waits 18 seconds between photos until that is changed on the remote: focus the photo and press OK, then left and right change the seconds. Holding OK opens Arrange instead. The screensaver uses the same timing. Logcat tag `FamilyDashPhotos` records each step and never includes the album link. With an album, the photo fills the left side and the clock, weather, and Upcoming stack on the right, unless you have arranged the boxes. Until a link is set, or if no photo loads, and you have not arranged the boxes, the screen looks like the earlier dashboard: a large clock, with weather and Upcoming side by side. After you arrange the boxes, a hidden photo leaves an empty space where that box sits.

To clear the album:

```
adb shell am start -n com.dutchman175.familydashboard/.MainActivity --es photo_album_url ""
```

If the TV ignores the empty value, use:

```
adb shell am start -n com.dutchman175.familydashboard/.MainActivity --es photo_album_url clear
```

## Message board

Messages show in a notes strip. On a new install that strip sits under the photo, the full width of that column. Each message is one line, such as `Cassie, 5 min ago: Soccer moved to 6.` Longer text is cut off with an ellipsis. The strip stays hidden when there are no messages. If it is still the row directly under the photo, the photo grows into that space. If you have moved the strip somewhere else, that space stays empty. Messages clear at midnight Central Time. A new one briefly outlines the box in gold. The boxes can be moved; see Arrange the boxes.

The TV listens to one topic on the free public service ntfy.sh. There is no account. The topic name is long and random, and it works like a password: anyone who knows it can post. Treat it like the calendar link and the album link. Do not put the real name in git, in a screenshot, in a chat, or in a log. The examples below use `PASTE-THE-TOPIC-NAME`. Replace that with a name you make up, 16 to 64 characters, using only letters, numbers, underscores, and hyphens.

Set it on the TV:

```
adb shell am start -n com.dutchman175.familydashboard/.MainActivity --es message_topic 'PASTE-THE-TOPIC-NAME'
```

The app keeps a live connection while the dashboard or the screensaver is on screen. If the connection drops, it retries with a longer pause between tries. When the TV turns on, it also picks up messages posted since midnight Central Time.

To turn the message board off:

```
adb shell am start -n com.dutchman175.familydashboard/.MainActivity --es message_topic ""
```

If the TV ignores the empty value, use:

```
adb shell am start -n com.dutchman175.familydashboard/.MainActivity --es message_topic clear
```

To wipe the messages that are on screen without turning the board off, send a message whose text is exactly `clear`.

### iPhone Shortcut

Andy and Cassie each build this once. The only difference is the Title, which is the name that shows on the TV. Use your own topic where the steps say `PASTE-THE-TOPIC-NAME`.

1. Open the Shortcuts app.
2. Tap the + button to make a new shortcut.
3. Tap Add Action, search for Ask for Input, and add it.
4. Set the prompt to `Message`. Leave Input Type as Text.
5. Tap Add Action, search for Get Contents of URL, and add it.
6. In the URL field, enter `https://ntfy.sh/PASTE-THE-TOPIC-NAME`.
7. Tap Show More.
8. Set Method to POST.
9. Under Headers, tap Add new header. Set the name to `Title` and the value to the sender's name, `Andy` or `Cassie`.
10. Under Request Body, choose Text. Tap the text field, then tap Provided Input so the shortcut sends whatever was typed in step 4.
11. Tap Done. Name the shortcut `Message the TV`.
12. Long-press the shortcut, tap Share, then tap Add to Home Screen. Add it.
13. To send with Siri, say "Hey Siri, Message the TV", then say the message.

The text can be about 200 characters. The TV keeps it as plain text. Sending the single word `clear` removes every message currently on the TV.

The free ntfy app from the App Store is another way to post. Install ntfy, choose the publish action, set the topic to the same secret name, set the title to `Andy` or `Cassie`, and type the message. You do not have to subscribe to the topic to publish.

## Arrange the boxes

The dashboard is a 12 by 8 grid. The starting arrangement keeps the v1.4.0 photo at the same size, with the notes strip under it. On the right are the clock, the weather, a Joke of the Day strip, and Upcoming. Upcoming is one row shorter than in v1.4.0 so the joke fits. The photo is not smaller. Hold OK on the remote for about half a second to arrange. A line at the bottom of the screen says `Hold OK to arrange`. The screensaver uses the saved arrangement and cannot open the editor.

While arranging, the arrow buttons move a gold outline from box to box. Down from the lowest box reaches Save, Cancel, and Reset to default. Left and right switch those three. Up goes back to the boxes. OK on a box picks it up in Move. The arrows then slide it by one square. OK again switches to Resize. In Resize, left and right change the width and up and down change the height. A box will not leave the screen and will not cover another box. The gold line at the top of the editor says which box you are on and whether you are in Move or Resize.

Back puts the box down. If you are not holding a box, Back leaves and throws away unsaved changes, the same as Cancel. OK on Save keeps the arrangement on the TV and on the screensaver. OK on Cancel throws the changes away. OK on Reset puts the preview back to the original layout. You still press Save to keep that.

The arrangement is stored on the device with the calendar link, the album link, the message topic, and the seconds-per-photo setting. Installing an update does not clear those.

A short press of OK is unchanged. On the photo, it opens seconds per photo. On Upcoming, it opens the month view. The joke box has no extra button. Hold OK to move it like the others.

## Joke of the Day

The joke box uses a list of kid-friendly jokes stored in the app. There is no joke website, so it works with the internet off and stays the same clean set. A new joke starts at midnight Central Time, the same clock the dashboard already shows. The dashboard and the screensaver pick the joke from that date, so they always match.

In the short strip under the weather, the question and the answer both show, with the answer in gold. If you make the box taller, the question shows first and the answer fades in after about four seconds. The words get bigger when the box gets bigger.

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
