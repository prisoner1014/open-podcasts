# OpenPodcasts

[![Android](https://img.shields.io/badge/Platform-Android%207.0%2B%20(API%2024%2B)-3DDC84?style=for-the-badge&logo=android&logoColor=white)](https://android.com)
[![Android Auto](https://img.shields.io/badge/Car-Android%20Auto%20Ready-4285F4?style=for-the-badge&logo=androidauto&logoColor=white)](https://android.com/auto)
[![License](https://img.shields.io/badge/License-MIT-00B0FF?style=for-the-badge)](LICENSE)
[![Latest Release](https://img.shields.io/badge/Release-v1.4-green?style=for-the-badge)](VERSION_HISTORY.md)

A clean, privacy-first, open-source podcast player for Android and Android Auto — bringing back everything listeners loved about minimalist, distraction-free podcast players with modern automotive support and universal phone compatibility.

---

## 🌟 Key Features

### 📱 Universal Android Compatibility
- **All Form Factors**: Adapts seamlessly from compact budget devices (720x1520 HD+) to standard tall aspect ratio phones (1080x2400 @ 20:9), high-density flagships (1440x3120 QHD+), and foldable devices.
- **Edge-to-Edge & Notches**: Full safe-area inset compensation for punch-hole cameras, curved corners, and system navigation bars without content clipping.
- **Broad Android Support**: Compatible with Android 7.0 (Nougat / API 24) through Android 14+ (API 34).

### 🚗 Full Android Auto Car Integration
- **Native `MediaBrowserService`**: Browse subscriptions, playback queues, offline downloads, and recent releases directly on your car's dashboard screen.
- **Steering Wheel & Transport Controls**: Instant Play/Pause, 10s Rewind, 30s Fast-Forward, and track navigation via physical steering wheel buttons or dash knobs.
- **Hands-Free Voice Search**: Full voice command integration via Google Assistant / vehicle voice recognition.
- **Two-Way Synchronization**: Audio playing on your phone transitions seamlessly to your car speakers upon plugging in or connecting wirelessly.

### 🔒 100% Privacy & Zero Account Walls
- **No Sign-In Required**: Zero Google account or email required. No telemetry, no ad tracking, and no unsolicited algorithm recommendations.
- **Permanent Local Storage**: Subscriptions, custom show order, playback speeds, and listening queues are preserved locally across app updates via native Android `SharedPreferences`.

### ⚡ Smart Audio Engine
- **Trim Silence (Dynamic Pause Compression)**: Automatically compresses conversational pauses and dead air without pitch distortion, saving 15%+ listening time.
- **Granular 0.1x Speed Stepper (0.5x – 3.0x)**: Precision speed controls with one-tap preset chips and fine ±0.1x increments.
- **Continuous Auto-Queue**: Seamless transition to the next queued episode or chronological show episode when a track finishes.
- **Gentle Sleep Timer**: Flexible timers (5m to 45m or end-of-episode) with soft audio fade-out.

### 📡 Global Directory Search & Custom RSS
- **Live Search**: Instant query access to millions of worldwide podcasts.
- **Subscribe by RSS URL**: Direct support for private Patreon feeds, Substack audio, or custom publisher RSS/Atom feeds.
- **Authentic Listener Reviews**: Integrated directory reviews feed with real listener ratings, comments, and an interactive personal review composer.

### 🎛️ Home Screen Organization
- **Hold-to-Manage Gesture**: Long-press any podcast tile to instantly engage manage mode.
- **Custom Tile Ordering**: Reorder subscriptions with card movement arrows or a dedicated reordering sheet with A–Z sorting.
- **Collapsible Reviews**: Ratings and reviews stay collapsed by default for instant, direct access to the latest episodes.

---

## 📦 Download & Installation

You can download the pre-built signed APK directly:
- **[OpenPodcasts.apk](OpenPodcasts.apk)** (Latest v1.4)

To install via ADB:
```bash
adb install -r OpenPodcasts.apk
```

---

## 🛠️ Building from Source

### Prerequisites
- Android SDK (`build-tools 34.0.0`, `platforms;android-34`)
- JDK 17+
- Python 3.8+ (for automated build pipeline)

### Build Command
Run the automated build script:
```bash
python build_and_deploy.py
```

This compiles Android resources (`aapt2`), compiles Java sources (`javac`), dexes bytecodes (`d8`), aligns (`zipalign`), signs with debug keystore (`apksigner`), and deploys directly to connected ADB devices.

---

## 📑 Version History

Refer to [VERSION_HISTORY.md](VERSION_HISTORY.md) for full release notes and changelog tracking across all versions.

---

## 📄 License

This project is licensed under the [MIT License](LICENSE).
