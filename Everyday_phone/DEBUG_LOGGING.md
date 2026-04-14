# Debug Logging for Everyday Phone App

## Overview
This app now writes debug logs to a file that you can easily retrieve from your phone.

## Log File Location
The log file is stored on your phone at:
```
/sdcard/Android/data/com.everyday.everyday_phone/files/app_debug.log
```

## How to Retrieve Logs

### Option 1: Use the Batch Script (Windows)
1. Connect your phone via USB with USB debugging enabled
2. Double-click `pull_logs.bat` in the `Everyday_phone` folder
3. The log file will be downloaded and opened in Notepad

### Option 2: Manual ADB Command
Open a terminal/command prompt and run:
```bash
adb pull /sdcard/Android/data/com.everyday.everyday_phone/files/app_debug.log
```

### Option 3: Using Android Studio
1. Open Android Studio
2. View → Tool Windows → Device File Explorer
3. Navigate to: `/sdcard/Android/data/com.everyday.everyday_phone/files/`
4. Right-click on `app_debug.log` and select "Save As..."

## What Gets Logged
- App startup timestamp
- Permission status
- Service initialization
- All logs are timestamped with millisecond precision

## Adding More Logs
To add more logs in your code, simply call:
```kotlin
fileLog("Your message here")
```

This will:
- Write to the log file with a timestamp
- Also log to Android logcat (if it's working)

## Notes
- The log file is cleared each time the app starts (only the most recent run is kept)
- The log file persists even after closing the app
- You can retrieve logs even if Android Studio's logcat isn't working
