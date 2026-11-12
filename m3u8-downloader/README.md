# M3U8 Downloader (Android)

Paste a page URL (or use Share → this app from your browser). The app loads the page in a WebView,
catches the `.m3u8` manifest request, then downloads the stream and saves it as an MP4 in Downloads.

## Build the APK

**Option A – Android Studio:** File → Open this folder → Build → Build APK(s).
APK appears in `app/build/outputs/apk/debug/app-debug.apk`.

**Option B – GitHub (no install):** push this folder to a new GitHub repo, open the Actions tab,
run "Build APK", download the `m3u8-downloader-apk` artifact.

Install the APK on your phone (allow "install unknown apps").

## Notes
- Works for unencrypted / standard HLS streams. DRM-protected streams (Widevine etc.) can't be saved.
- Only download content you have the right to save.
- If a dependency version is rejected, bump `media3`, AGP, or Kotlin versions in the Gradle files.
