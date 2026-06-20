# Everyday
<p align=center><img width="543" height="480" alt="image" src="https://github.com/user-attachments/assets/d3983318-7e97-4f06-8819-c9ea965f6f07" /></p>

A lightweight, fully customizable HUD / dashboard for RayNeo X3 Pro.

The most important thing we expect from AR glasses is **not to obstruct**. What we want displayed on the screen, most of the time, is nothing. Everyday is built around that idea: you can let it run all day long while wearing the glasses, and it will mostly stay out of your view, except when you need it.

Everyday is structured around draggable, resizable, minimizable widgets. You choose what appears in your FOV. A phone companion app handles interaction with the phone, but the glasses app can work standalone too. The phone companion app can also receive shared links from Android and search queries and send them directly to the glasses browser.

Everyday also includes a configurable hover shortcut control, "the lemon", for quickly switching between app features such as layouts, subtitling, mirroring, and other shortcuts.

## Widgets

| Widget                | Description                                                                                                                                                                                                                                                                                                  |
| --------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| Time / Date / Battery | Time, date, and phone / glasses battery. Double-tap to configure what's shown.                                                                                                                                                                                                                               |
| Location / Weather    | Current city and weather with an icon. Double-tap to configure.                                                                                                                                                                                                                                              |
| Screen mirror         | Mirrors your phone screen over Wi-Fi.                                                                                                                                                                                                                                                                        |
| Web browser           | Supports tabs, full-screen video, Google services, cookies / multi-window authentication flows, and direct link or search launch from the phone companion app.                                                                                                                                               |
| Text editor           | Text can be formatted and arranged in columns. Paste directly from your phone clipboard.                                                                                                                                                                                                                     |
| Image display         | Open an image from phone or glasses storage.                                                                                                                                                                                                                                                                 |
| News                  | RSS feed for your country. Titles auto-update and can be expanded.                                                                                                                                                                                                                                           |
| Finance               | Follow stocks, currencies, cryptocurrencies, and multiple charts, including candle charts. Double-tap to open full screen and configure / order charts. Finance data are experimental and indicative only. Do not rely on them for financial decisions. The app developer does not provide financial advice. |
| Speedometer           | Shows your current speed. Has an adjustable threshold below which it disappears.                                                                                                                                                                                                                             |
| Google Calendar       | See what's next on your agenda. Tap to browse upcoming events, double-tap to go back. Requires Google sign-in.                                                                                                                                                                                               |
| Subtitles             | Subtitle the audio content from your phone. Useful for hearing disabilities or when ambient noise is high.                                                                                                                                                                                                   |
| Notifications         | Access notifications stored from your phone. The widget displays the latest ones, and tapping on it lets you browse them.                                                                                                                                                                                    |


## Other features

| Feature             | Description                                                                                                                                                                                                               |
| ------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| The lemon           | A configurable hover shortcut control. Move the cursor to bottom left corner, tap, slide to a slice, and release to open or cancel the selected shortcut. Slices can be configured in settings, including custom layouts. |
| Heads-up gesture    | The display wakes up briefly when you look up. The angle, trigger time, and switch-off time are all adjustable in settings.                                                                                               |
| Dimmer              | Makes the glasses comfortable to use at night.                                                                                                                                                                            |
| Adaptive brightness | Optionally adapts display brightness to ambient light rather than keeping it fixed.                                                                                                                                       |
| 3dof mode           | Pins the dashboard relative to head rotation.                                                                                                                                                                             |
| Google OAuth        | Authenticate with your Google account via your phone. Opt-in via settings. Required for the Calendar widget.                                                                                                              |
| Alignment guides    | Helps to align widgets relative to each other and to the screen.                                                                                                                                                          |


## Phone companion app

The phone companion app is not strictly necessary, but most features that rely on phone data require it.

Via bluetooth, the phone acts as a trackpad and a keyboard. All text typed on the phone is sent to widget in focus if it can accept text. The phone clipboard is also synced, so you can paste, say, a grocery list into the text editor rather than typing it letter by letter. The phone companion app also appears in the Android Share Sheet, so links can be shared from apps such as YouTube directly to the glasses browser. You can also paste a link or enter a search string in the phone companion app to open it on the glasses browser.

The phone app also handles screen mirroring over wifi, location data for the weather widget, and the Google authentication flow.

Finally, the companion app includes a refresh control to force-refresh data and collect shareable debug information when something goes wrong with data from external APIs or WebSockets.

## Installation

APKs are available in [Releases](https://github.com/TheophileGaudin/Everyday/releases).

**On glasses**

If not already done, get [adb](https://developer.android.com/tools/adb) and enable [developer permissions](https://leiniao-ibg.feishu.cn/wiki/WzQ4w5SIuip8qMk3WddcHCvynZe) on your glasses (only needs to be done once).

Then:

1. Plug glasses to computer
2. Open a terminal
3. Navigate to where the APK is
4. Run `adb install everyday0.8_glasses.apk`

**On phone**

Install `everyday0.8_phone.apk` via sideloading on your Android phone.

See the [InformalTech tutorial](https://www.youtube.com/watch?v=l3wu7x14LKY) for a full walkthrough.

## License

Dual licensed under GPLv3 and a commercial license. Free to use and fork with source disclosure under GPLv3. For proprietary use, contact [gaudin.theophile@gmail.com](mailto:gaudin.theophile@gmail.com). See `Everyday_glasses/LICENSE` and `Everyday_phone/LICENSE` for details.

## Support

This app is provided as a hobby project. If you'd like to keep me motivated to work on it, feel free to support me on BuyMeACoffee!

[![Buy me a coffee](https://img.shields.io/badge/Buy%20me%20a%20coffee-FFDD00?style=for-the-badge&logo=buy-me-a-coffee&logoColor=black)](https://buymeacoffee.com/Glxblt76)
