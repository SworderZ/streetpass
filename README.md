# StreetPass

An Android app that discovers other StreetPass users nearby over Bluetooth Low Energy
in the background and counts encounters. No GPS, no server, no accounts — everything
stays on the phone. The full specification is in `SPEC.md`, development rules are in
`CLAUDE.md`.

## Building

Requires JDK 17 and the Android SDK (platform 35, build-tools 35). Point
`local.properties` at the SDK (`sdk.dir=...`) and at the JDK via `JAVA_HOME` or
`org.gradle.java.home` in `~/.gradle/gradle.properties`.

```bash
./gradlew assembleDebug          # build
./gradlew installDebug           # install on a connected device
./gradlew lint test              # checks
adb logcat -s DiscoveryService BleScanner BleAdvertiser
```

BLE does not work in the emulator — test on physical devices only.

## Installing and updating

Ready-made APKs are on the [Releases](https://github.com/SworderZ/streetpass/releases)
page. Install those on phones: they are signed with the release key, so the app can
update itself in place via "Settings → Updates". A debug build from
`./gradlew installDebug` is signed with a different key — a release will not install
over it; uninstall and install again.

### Publishing a new version

1. Commit the changes to `master`.
2. Tag and push the tag: `git tag v0.2.0 && git push origin v0.2.0`.
3. GitHub Actions runs `lint test`, builds a signed `assembleRelease` (`versionName`
   comes from the tag, `versionCode` from the run number) and creates a release with
   `streetpass-v0.2.0.apk` attached.

A local release build needs `keystore.properties` in the project root (not committed):

```
storeFile=/path/to/streetpass-release.jks
storePassword=...
keyAlias=streetpass
keyPassword=...
```

The same key lives in the repository secrets (`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`,
`KEY_ALIAS`, `KEY_PASSWORD`). Losing the key means new versions can no longer update
old ones — keep a backup.

## How it works

- On first launch the app creates a signing key (P-256 in AndroidKeyStore; the private
  part never leaves the system key store). The anonymous ID is the first 8 bytes of the
  hash of the public key. It is broadcast as non-connectable BLE advertising (Service
  Data under the 16-bit UUID `0x5350`).
- So that an ID cannot be copied and broadcast on someone else's behalf, every
  5 minutes the phone signs "key + time" and sends the signature in chunks in the
  scan-response (UUID `0x5352`). Another phone counts an encounter only after it has
  collected and verified the signature: the key must produce exactly this ID, the
  signature must check out, and the time must be within 10 minutes of its own clock.
  Phones running older versions that sign nothing are not counted by default — this
  can be enabled in "Settings → Discovery → Accept unsigned IDs".
- Optionally you can set a nickname (Settings → Profile): it goes out in a second
  packet (scan-response, UUID `0x5351`) and is shown to other users instead of the ID.
  The nickname is visible to any Bluetooth scanner nearby — an empty nickname means
  full anonymity.
- The UI is in English and Russian; the language follows the system settings.
- A scanner filtered by the same UUID receives such packets from other phones. Only the
  ID and the signal strength are taken from a result; MAC addresses and device names
  are neither read nor stored.
- An encounter with the same ID counts no more often than once per "duplicate
  protection" window (60 minutes by default, configurable). Weak signals — people
  behind a wall — are dropped by an RSSI threshold.
- All of this is done by a foreground service with a persistent notification; scanning
  runs in cycles (power profiles in the settings).
- Tap any person in the history to mark them as a friend or give them a name. Both are
  local marks — the other person is not notified and nothing goes over the air.
- Achievements (Stats tab) are earned for people met (5 … 100), encounters (10 … 500),
  friends (1 … 10), meeting the same friend many times (10 … 100) and streaks of
  consecutive days (7, 30). They survive clearing the history.

## Known limitations

**Both sides count the encounter independently.** There is no confirmation between the
phones. If one phone missed the packet (it was in the pause of its scan cycle, lay in a
pocket screen-to-body, has a weaker antenna), the encounter is recorded only on the
other one. For a mutual count both devices have to stay nearby for a while — one scan
cycle is usually enough (up to 2 minutes in the "Saver" profile).

**The signature does not protect against real-time replay.** Someone can record another
person's packet and replay it within 10 minutes — without a connection between the
phones this cannot be fixed. The point of the protection is that a *permanent* ID
cannot be taken over for long.

**After updating from a build without signatures the ID changes once:** the old one was
random, the new one is derived from the key. Other users will record a "first meeting"
again. If the phone's clock is more than 10 minutes off from real time, other phones
will not accept its signature.

**Not every phone can do BLE advertising.** On some devices the Bluetooth chip does not
support advertising. Such a phone will see others, but nobody will see it — the app
shows a warning about this on the Home screen.

**Vendor firmware kills background services.** See below.

## If discovery stops on its own

Firmware from Xiaomi (MIUI/HyperOS), Huawei/Honor (EMUI), Oppo/Realme/OnePlus
(ColorOS), Vivo and Samsung (in "deep sleep" mode) stops foreground services against
the Android rules. StreetPass has to be excluded from battery optimisation by hand:

1. **Common step for everyone.** Settings → Apps → StreetPass → Battery →
   "Unrestricted" (or "Don't optimise").
2. **Xiaomi / Poco / Redmi.** Additionally: Settings → Apps → StreetPass →
   "Autostart" — enable; in the recent apps list pull the StreetPass card down and tap
   the lock.
3. **Huawei / Honor.** Settings → Battery → App launch → StreetPass → turn off
   "Manage automatically" and enable all three items manually.
4. **Oppo / Realme / OnePlus.** Settings → Battery → Advanced settings →
   "Sleep standby optimisation" — turn off; in the app permissions enable "Autostart"
   and "Run in background".
5. **Samsung.** Settings → Device care → Battery → Background usage limits → make sure
   StreetPass is not in the "Sleeping apps" or "Deep sleeping apps" lists.

Per-manufacturer instructions: dontkillmyapp.com.

## Privacy

- The location permission is not requested on Android 12+ (`BLUETOOTH_SCAN` with
  `neverForLocation`). On Android 11 and below the system itself requires it for BLE
  scanning — the app neither determines nor stores your location.
- The `INTERNET` permission is used only to check for updates from a button in the
  settings: one request to GitHub Releases and, if you agree, downloading the APK. The
  request contains nothing about encounters, your ID or your device. There are no other
  network calls in the code — this is easy to verify, all networking lives in
  `data/update/UpdateRepository.kt`.
- `allowBackup="false"`: the database does not go into cloud backup.
- The ID can be changed at any time in the settings; the history can be erased.
