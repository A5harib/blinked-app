---
name: debug-build-first
description: Test on the phone with the debug build + Metro first; only build/install the release APK once everything works
metadata:
  type: feedback
---

When testing ScrollSoul on the phone, install the debug build (`assembleDebug` / `installDebug`) and run Metro (`npx react-native start`) with `adb reverse tcp:8081 tcp:8081`. Iterate there with live reload and JS logs. Build and install the release APK only after everything works.

**Why:** The user wants a debuggable install while iterating. A release APK hides JS logs and forces a full rebuild for every JS change.

**How to apply:** For any on-device check, start with the debug build. Pass `-PreactNativeArchitectures=arm64-v8a` to keep native builds short (the Fold 7 is arm64). The phone is usually on adb over Wi-Fi (`adb connect <ip>:5555`), and `adb reverse` works over that link too.
