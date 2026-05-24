# Project Notes For Agents

## Scope
- This repo contains two Android apps: `Everyday_glasses` and `Everyday_phone`.
- Treat `Everyday_glasses` as the primary AR-glasses runtime target.

## Build And Run (Human Workflow)
- Primary workflow is Android Studio.
- Usual build/deploy path is: press the Run button (green triangle) in Android Studio.
- Prefer validating changes by running from Android Studio run configurations instead of assuming CLI Gradle wrappers are available.
- If a shell build is needed and no repo `gradlew.bat` exists, use Android Studio's bundled JBR with the downloaded Gradle 8.7 distribution:
  - `$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'; $env:Path="$env:JAVA_HOME\bin;$env:Path"; & "$env:USERPROFILE\.gradle\wrapper\dists\gradle-8.7-bin\bhs2wmbdwecv87pi65oeuq5iu\gradle-8.7\bin\gradle.bat" :app:assembleDebug`
  - Run that from `Everyday_glasses` or `Everyday_phone` as appropriate.

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
