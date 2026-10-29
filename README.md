# Everyday
<p align=center><img width="543" height="480" alt="image" src="https://github.com/user-attachments/assets/d3983318-7e97-4f06-8819-c9ea965f6f07" /></p>

A lightweight, fully customizable HUD / dashboard for RayNeo X3 Pro.

The most important thing we expect from AR glasses is **not to obstruct**. What we want displayed on the screen, most of the time, is nothing. Everyday is built around that idea: you can let it run all day long while wearing the glasses, and it will mostly stay out of your view, except when you need it.

Everyday is structured around draggable, resizable, minimizable widgets. You choose what appears in your FOV. A phone companion app handles interaction with the phone, but the glasses app can work standalone too. The phone companion app can also receive shared links from Android and search queries and send them directly to the glasses browser.

A voice-control agent (come with  [your API key](https://github.com/TheophileGaudin/Everyday/wiki/Set-up-voice-control-agent)) can help you make the most out of your glasses, even though it is not required to run the app.

## Widgets

| Widget                | Description                                                                                                                                                                                                                                                                                                  |
| --------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| Time / Date / Battery | Time, date, and phone / glasses battery. Double-tap to configure what's shown.                                                                                                                                                                                                                               |
| Minimap               | Minimap that follows compass orientation and shows your current position as well as surrounding Points of Interest. Can show turn by turn navigation to a selected destination.                                                                                                                                                                                                                                         |
| Calendar       | See what's next on your agenda. Tap to browse upcoming events, double-tap to go back. Requires Google sign-in.                                                                                                                                                                                               |
| Location / Weather    | Current city and weather with an icon. Double-tap to configure.                                                                                                                                                                                                                                              |
| Screen mirror         | Mirrors your phone screen over Wi-Fi. Control your phone from your glasses.                                                                                                                                                                                                                                                                       |
| Web browser           | Supports tabs, full-screen video, Google services, cookies / multi-window authentication flows, and direct link or search launch from the phone companion app.                                                                                                                                               |
| Text editor           | Text can be formatted and arranged in columns. Paste directly from your phone clipboard.                                                                                                                                                                                                                     |
| File browser          | Open an image from phone or glasses storage. On the glasses, also open pdfs and videos.                                                                                                                                                                                                                                                                |
| News                  | RSS feed for your country. Titles auto-update and can be expanded.  Can change RSS feed.                                                                                                                                                                                                                                          |
| Finance               | Follow stocks, currencies, cryptocurrencies, and multiple charts, including candle charts. Double-tap to open full screen and configure / order charts. Finance data are experimental and indicative only. Do not rely on them for financial decisions. The app developer does not provide financial advice. |
| Speedometer           | Shows your current speed. Has an adjustable threshold below which it disappears.                                                                                                                                                                                                                             |
| Subtitles             | Subtitle the audio content from your phone. Useful for hearing disabilities or when ambient noise is high.                                                                                                                                                                                                   |
| Notifications         | Access notifications stored from your phone. The widget displays the latest ones, and tapping on it lets you browse them.                                                                                                                                                                                    |
## Voice control agent

**How it works:** Come with your API key (see Wiki for [easy set-up instructions](https://github.com/TheophileGaudin/Everyday/wiki/Set-up-voice-control-agent)). Then, it is push-to-talk (the phone's button). Alternatively on the glasses a hover control bottom right should be tapped once to switch on and once to switch off. Gemini acts and shows short text answers but doesn't speak back. It works on the glasses' Wi-Fi or through the phone's internet connection.

| Area | What you can ask | Example | Notes |
|---|---|---|---|
| **Questions & answers** | Ask a factual or current question and get a short text answer on the glasses | "Who won the match last night?" | Answers are checked against a web search first, shown in a framed text box for a set time |
| **Your view** | Ask what you're looking at: read, identify, describe or explain it | "What am I looking at?", "Read this sign" | One photo is taken and shown to Gemini, not kept |
| **Photos** | Take a photo | "Take a picture" | Saved to the glasses' gallery (DCIM/Everyday) and copied to the phone's gallery when connected. "Photo taken" notice |
| **Videos** | Record a video with sound (10 s by default, up to 60 s) | "Record a 20-second video" | Saved to Movies/Everyday on the glasses and the phone. Can be described on request ("film this and tell me what happens") |
| **Video analysis** | Ask about the video open in the video player | "What happens in this video?" | Available while a video is open |
| **Navigation** | Get walking directions to a place or address, shown on the map. Stops the map too. | "Directions to the train station", "Stop navigation" | Walking routes only |
| **Map** | Choose which places the map shows (categories, nearest, distance) | "Show cafés and pharmacies on the map" | |
| **News** | Change the news source or country | "Show me BBC news" | |
| **Finance** | Change what a finance tile tracks | "Track the S&P 500 in the first tile" | |
| **Widgets** | Show or hide widgets: clock, calendar, map, news, finance, notifications, speedometer, status, location/weather, subtitles, phone mirror, create a text box or open a browser | "Hide the calendar", "Open a text box" | |
| **Layouts** | Switch to a saved layout, save the current one, lock or unlock widget positions | "Switch to my walking layout", "Save the layout" | |
| **Web browsing** | Open a web page, or have the assistant carry out a task in the browser | "Open wikipedia.org", "Find the opening hours on this site" | Sensitive steps ask for your confirmation on the glasses first |
| **Files** | Open the file picker | "Open a file" | |
| **Text** | Type into the focused text field or box | "Type my groceries list: milk, egg, sugar, chocolate" | Only when you explicitly ask to type or write |
| **Display & dashboard** | Set brightness, scroll, open settings, close the app, look at what's on screen | "Brightness to 50%", "Scroll down" | |



## Other features

| Feature             | Description                                                                                                                                                                                                               |
| ------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| The lemon           | A configurable hover shortcut control. Move the cursor to bottom left corner, tap, slide to a slice, and release to open or cancel the selected shortcut. Slices can be configured in settings, including custom layouts. |
| Heads-up gesture    | The display wakes up briefly when you look up. The angle, trigger time, and switch-off time are all adjustable in settings.                                                                                               |
| Dimmer              | Makes the glasses comfortable to use at night.                                                                                                                                                                            |
| Adaptive brightness | Optionally adapts display brightness to ambient light rather than keeping it fixed.                                                                                                                                       |
| 3dof mode           | Pins the dashboard relative to head rotation.                                                                                                                                                                             |
| Google OAuth        | Authenticate with your Google account via your phone. Opt-in via settings. Required for the Calendar widget.                                                                                                              |
| Gemini API Key        | Create your free API key on [AI Studio](https://aistudio.google.com/api-keys). Paste it in the settings to activate the voice control agent.                                                                                                              |
| Alignment guides    | Helps to align widgets relative to each other and to the screen.                                                                                                                                                          |
| System of units     | Auto-detects the default system of units based on your location. Temperature and speed can be configured individually, or the app can be forced to Metric or Imperial.                                              |

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
4. Run `adb install everyday0.9_glasses.apk`

**On phone**

Install `everyday0.9_phone.apk` via sideloading on your Android phone.

See the [InformalTech tutorial](https://www.youtube.com/watch?v=l3wu7x14LKY) for a full walkthrough.

## License

Dual licensed under GPLv3 and a commercial license. Free to use and fork with source disclosure under GPLv3. For proprietary use, contact [gaudin.theophile@gmail.com](mailto:gaudin.theophile@gmail.com). See `Everyday_glasses/LICENSE` and `Everyday_phone/LICENSE` for details.

## Support

This app is provided as a hobby project. If you'd like to keep me motivated to work on it, feel free to support me on BuyMeACoffee!

[![Buy me a coffee](https://img.shields.io/badge/Buy%20me%20a%20coffee-FFDD00?style=for-the-badge&logo=buy-me-a-coffee&logoColor=black)](https://buymeacoffee.com/Glxblt76)
