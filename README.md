# BandWatchLTE7

Native Android LTE monitor — early MVP.

## Current MVP
- Reads the registered LTE cell using Android Telephony APIs.
- Detects LTE Band 3 and Band 7 from the EARFCN.
- Displays EARFCN, RSRP, RSRQ and SINR when Android provides them.
- Runs a user-started foreground service and keeps a notification visible.
- Sends a notification with sound/vibration when the phone enters Band 7.
- GitHub Actions workflow builds a debug APK and uploads it as an artifact.

## Build
GitHub Actions runs on pushes to `android-native-mvp` and `main`, or manually from the Actions tab. Download the `BandWatch-debug-apk` artifact from a successful run.

## Important limitations
- This is an early code scaffold and has not yet been verified on a physical Samsung phone.
- Android, the carrier, and device firmware control which cell details are exposed. LTE readings may be unavailable even after permissions are granted.
- Location Services must be enabled and the app needs Location and Phone permissions.
- Android 13+ also asks for notification permission.
- The current MVP does not yet include a saved connection-history screen, configurable alert settings, or a persistent status-bar band label beyond the foreground notification.
- RSSI is intentionally not shown: Android's public LTE signal API exposes RSRP, RSRQ and RSSNR/SINR, but does not provide a reliable separate LTE RSSI reading here.

## Project
- Package: `com.justaskaspire.bandwatch`
- Minimum Android: 8.0 (API 26)
- Target Android: API 35
- Working branch: `android-native-mvp`

This project is not yet a release-ready APK. The first priority is to get a successful CI build, then test the app on the target phone and refine compatibility.
