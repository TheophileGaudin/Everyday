@echo off
echo Pulling app logs from phone...
adb pull /sdcard/Android/data/com.everyday.everyday_phone/files/app_debug.log app_debug.log
if %ERRORLEVEL% EQU 0 (
    echo Success! Log file saved to: app_debug.log
    echo Opening log file...
    notepad app_debug.log
) else (
    echo Failed to pull log file. Make sure your phone is connected via USB debugging.
    pause
)
