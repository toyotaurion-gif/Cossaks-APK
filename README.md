# Cossacks 1.52 — Android Client (community project)

GitHub-ready source package reconstructed from the project archives saved on 11 Oct 2026.

- **Android client:** v0.9.2 source, Android Studio / Gradle
- **Bundled Web UI:** v0.8.4, with visible double-tap `DEL ×2` confirmation (550 ms window)
- **Java package:** `com.cossacks.mobile`
- **CI:** `.github/workflows/build-apk.yml` (manual or push-triggered debug APK build)

This repository contains the custom Android wrapper and web interface; **it does not contain the proprietary game resources**, including `dmcr.wasm` / `dmcr.js`, required for gameplay. The Android client is designed to download/import a separate full web game archive.

## Build

Open the root directory in Android Studio and use **Build > Build APK(s)**. Alternatively run the GitHub Actions workflow **Build Android APK** after pushing these files. The generated APK will be a debug build, not a production release.

**Status:** untested in this environment; installation, WebView, audio, and game resources must be tested on Android. For details, see [README_RU.md](README_RU.md).

## Folders

- `app/` — Android application and embedded `dmcr.html`
- `web-ui/` — standalone update package source v0.8.4
- `.github/workflows/` — CI build workflow

Based on the community port: https://github.com/MisterCoderman/cossacks-1.52-platform
