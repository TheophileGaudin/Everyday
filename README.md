# Everyday
<p align=center><img width="543" height="480" alt="image" src="https://github.com/user-attachments/assets/d3983318-7e97-4f06-8819-c9ea965f6f07" /></p>

A lightweight, fully customizable HUD / dashboard for RayNeo X3 Pro.

The most important thing we expect from AR glasses is **not to obstruct**. What we want displayed on the screen, most of the time, is nothing. Everyday is built around that idea: you can let it run all day long while wearing the glasses, and it will mostly stay out of your view, except when you need it.

Everyday is structured around draggable, resizable, minimizable widgets. You choose what appears in your FOV. A phone companion app handles interaction with the phone, but the glasses app can work standalone too.

## Widgets

1. **Time / Date / Battery**: time, date, and phone / glasses battery. Double-tap to configure what's shown.
2. **Location / Weather**: current city and weather with an icon. Double-tap to configure.
3. **Screen mirror**: mirrors your phone screen over wifi.
4. **Web browser**: supports tabs, full screen video, and Google services. Hover the bottom right corner to access a YouTube shortcut.
5. **Text editor**: text can be formatted and arranged in columns. Paste directly from your phone clipboard.
6. **Image display**: open an image from phone or glasses storage.
7. **News**: RSS feed for your country. Titles auto-update and can be expanded.
8. **Finance**: follow a stock market in real time. Defaults to your country's market.
9. **Speedometer**: shows your current speed. Has an adjustable threshold below which it disappears.
10. **Google Calendar**: see what's next on your agenda. Tap to browse upcoming events, double-tap to go back. Requires Google sign-in (see below).

## Other features

- *Heads-up gesture*: the display wakes up briefly when you look up. The angle, trigger time, and switch-off time are all adjustable in settings.
- *Dimmer*: makes the glasses comfortable to use at night.
- *Adaptive brightness*: optionally adapts display brightness to ambient light rather than keeping it fixed.
- *3dof mode*: pin the dashboard relative to head rotation.
- *Google OAuth*: authenticate with your Google account via your phone. Opt-in via settings. Required for the Calendar widget.

## Phone companion app

The phone companion app is not strictly necessary, but most features that rely on phone data require it.

Via bluetooth, the phone acts as a trackpad and a keyboard. All text typed on the phone is sent to widget in focus if it can accept text. The phone clipboard is also synced, so you can paste, say, a grocery list into the text editor rather than typing it letter by letter.

The phone app also handles screen mirroring over wifi, location data for the weather widget, and the Google authentication flow.

## Installation

APKs are available in [Releases](https://github.com/TheophileGaudin/Everyday/releases).

**On glasses**

If not already done, get [adb](https://developer.android.com/tools/adb) and enable [developer permissions](https://leiniao-ibg.feishu.cn/wiki/WzQ4w5SIuip8qMk3WddcHCvynZe) on your glasses (only needs to be done once).

Then:

1. Plug glasses to computer
2. Open a terminal
3. Navigate to where the APK is
4. Run `adb install everyday_0.5.apk`

**On phone**

Install `everyday_0.5_phone.apk` via sideloading on your Android phone.

See the [InformalTech tutorial](https://www.youtube.com/watch?v=l3wu7x14LKY) for a full walkthrough.

## License

Dual licensed under GPLv3 and a commercial license. Free to use and fork with source disclosure under GPLv3. For proprietary use, contact [gaudin.theophile@gmail.com](mailto:gaudin.theophile@gmail.com). See `Everyday_glasses/LICENSE` and `Everyday_phone/LICENSE` for details.

## Support

This app is provided as a hobby project. If you'd like to keep me motivated to work on it, feel free to support me on BuyMeACoffee!

[![Buy me a coffee](https://img.shields.io/badge/Buy%20me%20a%20coffee-FFDD00?style=for-the-badge&logo=buy-me-a-coffee&logoColor=black)](https://buymeacoffee.com/Glxblt76)
