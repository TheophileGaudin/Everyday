# Project Notes For Agents

## Scope

- This repo contains two Android apps: `Everyday_glasses` and `Everyday_phone`.
- Treat `Everyday_glasses` as the primary AR-glasses runtime target.

- 

## Widgets

- Each widget type of `Everyday_glasses` is one package under `app/src/main/java/com/everyday/everyday_glasses/widgets/` and one line in `widgets/WidgetPlugins.kt`, whose KDoc says how to add one. `widgets/clock` is the smallest example.
- The container, the activity and the engine (`runtime/`, `persistence/`) name no widget type. Keep it that way: a widget reaches the app through `WidgetContext`, the app's data through `AppData` channels, and the activity through `WidgetHost`.

## Build And Run (Human Workflow)

- Primary workflow is Android Studio.
- Usual build/deploy path is: press the Run button (green triangle) in Android Studio.
- Prefer validating changes by running from Android Studio run configurations instead of assuming CLI Gradle wrappers are available.
- If a shell build is needed and no repo `gradlew.bat` exists, use Android Studio's bundled JBR with the downloaded Gradle 8.7 distribution:
  - `$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'; $env:Path="$env:JAVA_HOME\bin;$env:Path"; & "$env:USERPROFILE\.gradle\wrapper\dists\gradle-8.7-bin\bhs2wmbdwecv87pi65oeuq5iu\gradle-8.7\bin\gradle.bat" :app:assembleDebug`
  - Run that from `Everyday_glasses` or `Everyday_phone` as appropriate.
- Allow at least 5 minutes for a cold `Everyday_glasses` build. In the 0.9.1 release session, the first test/release build exceeded a 124-second shell timeout; the cached rerun completed in about 13 seconds. Do not treat the two-minute timeout alone as a Gradle failure.

## Target Device (RayNeo X3 Pro)

- Chip platform: Qualcomm Snapdragon AR1 Gen1
- Memory/storage: 4 GB RAM + 32 GB ROM
- Display: 0.36cc full-color MicroLED optical engine + diffractive waveguide
- Refresh rate: up to 60 Hz
- Resolution: 640x480
- FOV: 30 deg
- PPD: 27
- Brightness: peak 6000 nits, average 3500 nits
- Camera: 12 MP main camera (RGB) + spatial camera (VGA)
- Sensors: gyroscope, accelerometer, magnetometer, left-temple removal sensor
- Speakers: dual stereo speakers, reverse noise cancellation, stereo output
- Microphones: 3 total (left temple, right temple, front)
- Interaction: temple touch, voice, watch/phone interaction
- Weight: 76 g
- Dimensions: L153.16 mm x W45.65 mm x H168.6 mm
- Connectivity: Wi-Fi 6 (2.4G/5G, 802.11 a/b/g/n/ac), Bluetooth 5.2
- USB: USB 2.0 Type-C
- Battery/life: 245 mAh, about 3-5 hours moderate use
- Charging: 5 V, max 735 mA
- Myopia options: snap-on lenses, full-fit lenses
- Operating system: RayNeo AI OS 2.0

## Absolute North / Compass Conventions

- For a north-aligned top-down map, prefer Android `TYPE_GEOMAGNETIC_ROTATION_VECTOR`. On this device it preserves absolute magnetic orientation across display off/on sessions; the standard rotation vector was observed re-anchoring yaw between sessions. `TYPE_GAME_ROTATION_VECTOR` is smoother for relative head motion but cannot determine north.
- In the verified RayNeo sensor frame, local `+Y` is vertical and optical forward is local `-Z`. After converting the quaternion to Android's row-major rotation matrix, optical-forward east/north are `-matrix[2]` and `-matrix[5]`; compute magnetic heading with `atan2(east, north)`, convert to degrees, and normalize to `[0, 360)`.
- Add location-based `GeomagneticField.declination` only when true/geographic north is required. Do not use a fixed heading bias.
- The geomagnetic result is naturally noisier but was found to agree closely with a phone compass in a clean magnetic environment. Headphones near the glasses caused large, direction-dependent offsets and asymmetric rotation because of magnetic distortion. When diagnosing heading, first remove headphones and other magnets/metal, move away from computers, and compare both devices toward the same direction. A changing offset indicates interference/calibration, not an axis bias.
- Keep immediate visual smoothing separate from the absolute north measurement. If smoother motion is needed, use game-vector motion with slow geomagnetic north correction rather than redefining north at startup.
