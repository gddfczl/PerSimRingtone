# Per-SIM Ringtone

**Per-SIM ringtone for dual-SIM Android phones — an LSPosed module.**

Android only offers a single incoming-call ringtone setting, even with two SIMs
active. This module hooks the system Telecom service so **each SIM card can have
its own ringtone** (or be muted) via a simple settings UI.

Tested on: Motorola pstar · LineageOS 23.2 (Android 13 base / "16" build) · LSPosed

---

## Features

- 🎵 Per-SIM custom ringtones (SIM 1 and SIM 2 independent)
- 🔇 Per-SIM mute option
- 📁 Ringtones are copied into the module's private storage — survives reboots,
  works without any storage permission for the system process
- 🛟 Fail-safe: any hook error silently falls back to the system default ringtone.
  It can never cause a missed call or a crash.
- 🔒 No network access, no tracking

## Requirements

- Rooted device with **Magisk**
- **LSPosed** (Zygisk variant recommended)
- Android 10 – 16

## Installation

1. Install the APK (`app-debug.apk` from Releases, or build it yourself).
2. Open the app once and grant the **Phone** permission.
3. In LSPosed: enable the module and check BOTH scopes:
   - **System (android)**
   - **Android Telephony Service (com.android.server.telecom)**
4. **Reboot** (required — telecom is a system service).
5. Open the app, pick a ringtone per SIM. Done.

> **Tip:** open the app once after every reboot so its process stays alive;
> otherwise the very first incoming call may briefly play the default tone.

## How it works

The Telecom service resolves the incoming-call ringtone in a single place:
`com.android.server.telecom.RingtoneFactory.getRingtone()`. This module:

1. Hooks that method and identifies the SIM (subscription id) of the incoming call
   via `PhoneAccountHandle`.
2. Looks up your per-SIM choice through this module's ContentProvider
   (ringtones are copied into private storage on selection, so the system process
   never needs external-storage access).
3. Returns a `Ringtone` built from your chosen file instead of the default —
   matching whatever result shape the ROM uses (`Ringtone`, `Uri`, or
   `Pair<Uri, Ringtone>` on Motorola builds).
4. Additionally forces `RingerAttributes.mLetDialerHandleRinging = false` because
   some ROMs delegate ringing to the dialer app, bypassing telecom entirely.
5. Hooks both possible hosts (`android` / `com.android.server.telecom`) since the
   telecom service lives in different processes across Android versions.

## Building

Requires JDK 17 and an Android SDK (compileSdk 35):

```bash
git clone https://github.com/<you>/PerSimRingtone.git
cd PerSimRingtone
echo "sdk.dir=$HOME/android-sdk" > local.properties
gradle assembleDebug          # or ./gradlew if you add the wrapper
# APK: app/build/outputs/apk/debug/app-debug.apk
```

## Troubleshooting

```bash
adb logcat -d | grep -i persimringtone     # module status / call-time logs
adb shell ps -A | grep telecom             # where telecom runs on your ROM
adb logcat -d -s MediaPlayer:W             # playback errors
adb shell dumpsys dropbox --print | grep -A30 system_server_crash  # crashes
```

Common issues:

| Symptom | Fix |
|---|---|
| Module not listed in LSPosed | Reinstall; reboot; check `assets/xposed_init` exists in APK |
| Ringtone not applied | Check both scopes are checked; reboot; open the app once |
| First call after boot plays default tone | Open the app once to keep the process alive |

## License

MIT — see [LICENSE](LICENSE).

## Acknowledgments

Built with [LSPosed](https://github.com/LSPosed/LSPosed). Thanks to the Xposed
community for the API and tooling.
