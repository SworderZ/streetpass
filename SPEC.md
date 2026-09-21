# StreetPass — specification

## 1. Goal

An Android app that discovers other users of the same app over Bluetooth Low
Energy in the background and records an encounter. At the end of the day the
user opens the app and sees how many people they have met.

An analogue of StreetPass from the Nintendo 3DS, but without profile exchange.

**MVP goal:** two physical Android phones with the app installed discover each
other over BLE, and each one gets one registered encounter in its statistics.

Out of MVP scope: server, accounts, cloud sync, iOS, profile exchange.

## 2. Privacy constraints (hard)

- GPS and any Location API are not used. On Android 12+ the location permission
  is not requested at all (`BLUETOOTH_SCAN` + `neverForLocation`). On Android 11
  and below `ACCESS_FINE_LOCATION` is requested out of necessity — the old
  system requires it for BLE scanning; explain this in the UI.
- No personal data is collected. The identifier is derived from a random
  signing key and is not tied to an account, IMEI, MAC or phone number.
- Data never leaves the device. The `INTERNET` permission is used only to check
  for updates: one GET to the GitHub Releases API on a button press and, if the
  user wants, downloading the APK. The request contains nothing about
  encounters, the ID or the device. There must be no other network calls in the
  app.
- `android:allowBackup="false"` — the database does not go into cloud backup.
- MAC addresses and device names are neither stored nor logged.

## 3. Stack

Kotlin, Jetpack Compose (Material 3), BLE (advertising + scanning), Room,
DataStore Preferences, coroutines/Flow, foreground service.
minSdk 26 (java.time without desugaring), targetSdk 35.

No DI framework: `AppContainer` in the `Application` class as a service locator.

## 4. Discovery protocol

### 4.1 Identifier

The first 8 bytes of SHA-256 over the compressed P-256 public key (33 bytes),
cached in DataStore as hex. The key is primary: it is created on first launch in
AndroidKeyStore (the private part never leaves the system key store), or, when
the Keystore is unavailable or broken, in software with storage in DataStore.
"Change ID" in the settings means creating a new key. Details in 4.2.2.

### 4.2 Advertising packet

Legacy advertising, payload limit 31 bytes. 19 are used:

| Field | Bytes | Value |
|---|---|---|
| Flags | 3 | added by the stack automatically |
| Complete 16-bit Service UUID | 4 | `0x5350` |
| Service Data (16-bit) | 12 | 2 header + 2 UUID + 8 bytes of ID |

`SERVICE_UUID = 00005350-0000-1000-8000-00805f9b34fb` — a 16-bit UUID expanded
with the Bluetooth Base UUID. 16-bit on purpose: a 128-bit one would take
18 bytes and the Service Data would no longer fit.

Advertising settings: `setConnectable(false)`, `setTimeout(0)`,
`setIncludeDeviceName(false)`, `setIncludeTxPowerLevel(false)`.
Non-connectable advertising means no GATT server is started and nobody can
connect to the device — only the 8 ID bytes go out.

### 4.2.1 Nickname (scan-response)

The user can set a nickname (up to 24 bytes of UTF-8; letters, digits, space
and `_-.!?'`). It goes out in a separate scan-response packet as Service Data
under `NICKNAME_UUID = 0x5351`. The main packet with the ID does not change —
builds without nickname support keep counting encounters. With a scan-response
the stack switches the advertising to `ADV_SCAN_IND`: it stays non-connectable.
The nickname is sent in the clear and the UI warns about it; an empty nickname
means full anonymity.

On receive, `scanRecord.getServiceData(NICKNAME_UUID)` is decoded and sanitised
as strictly as the user's own input; garbage is dropped. The nickname is stored
in `peers.nickname` and updated on every received packet that carries one; a
packet without a scan-response does not overwrite the old value. In the UI the
nickname is shown instead of the short ID, with the ID itself in small text next
to it.

### 4.2.2 ID signature (protection against copying)

The ID is visible to any scanner, and without protection it could be copied and
advertised on someone else's behalf. Therefore the ID is bound to a key (4.1),
and ownership of the key is proven with a signature on the air.

**Proof** (`IdentityProof`, 102 bytes):

| Field | Bytes | Value |
|---|---|---|
| version | 1 | `0x01` |
| public key | 33 | P-256, compressed point (`0x02`/`0x03` + X) |
| timestamp | 4 | unix time of signing in seconds, big-endian |
| signature | 64 | ECDSA/SHA-256 raw `r || s` |

The signed message is `"StreetPass-ID-v1" || version || public key || timestamp`.

This does not fit into legacy advertising as a whole (20 bytes are free in the
main packet, 27 in the scan-response), extended advertising needs BLE 5 on both
sides, and GATT would need `BLUETOOTH_CONNECT`. So the proof is cut into 4
chunks of 26 data bytes and sent in the scan-response as Service Data under
`PROOF_UUID = 0x5352`: 1 header byte (5 bits of generation + 3 bits of index)
and data. The chunks and the nickname frame take turns every 260–340 ms
(random, so the cycle does not resonate with the neighbour's scan windows) via
`AdvertisingSet.setScanResponseData` — without restarting advertising. The main
packet and the nickname frame are unchanged: old builds keep counting
encounters with new ones.

The signature is refreshed every 5 minutes; the generation number is
incremented so that a receiver does not mix chunks of different signatures. The
starting generation is random.

**Receiving.** The service collects chunks per peerId (`ProofAssembler`, in any
order, with duplicates, accumulating across scan windows) and, once all four are
in, verifies (`IdentityProof.verify`): version, `SHA-256(key)[0..8] == peerId`
from the main packet, `|now - timestamp| <= 10 min`, signature. After a
successful check the peer is trusted for as long as the time of its last
signature stays within the same 10 minutes; the neighbour re-signs twice as
often, so trust is not interrupted. A failed check resets the assembly — the
chunks go round in a loop, and an honest re-assembly fixes a mixed buffer.

**Policy.** Packets from a peer without a valid proof reach neither the
debounce nor the database: a copied ID is not counted. The exception is the
"Accept unsigned IDs" setting (off by default): compatibility with builds from
before signatures, at the price of the protection.

**What is not protected.** Recording and replaying someone's air within
10 minutes: without a connection and challenge-response this cannot be fixed.
Real-time relaying. A clock more than 10 minutes off makes the phone invisible
to strict mode — on Android the time is normally synchronised automatically.

### 4.3 Why not MAC

Android periodically rotates the MAC address (LE Privacy / RPA). It can neither
identify a person nor count them reliably. Identity is carried exclusively in
Service Data.

### 4.4 Scanning

Always with a `ScanFilter` on `SERVICE_UUID`. This is not an optimisation but a
requirement: starting with Android 8.1 the system does not deliver unfiltered
scan results while the screen is off. As a bonus the filter is offloaded to the
Bluetooth controller and saves battery.

`CALLBACK_TYPE_ALL_MATCHES`, `MATCH_MODE_AGGRESSIVE`, `reportDelay = 0`.

From a `ScanResult` only `scanRecord.getServiceData(SERVICE_UUID)`, the
scan-response service data (`NICKNAME_UUID`, `PROOF_UUID`) and `rssi` are
taken. Everything else is ignored.

## 5. Encounter counting logic

### 5.1 Debounce (in the service, in-memory)

Advertising arrives several times a second from each device. Without a filter
the database would get hundreds of requests a minute. Repeated packets from the
same `peerId` are ignored for 10 seconds. The map is purged of entries older
than 10 minutes once it exceeds 512 elements.

### 5.2 Duplicate protection (in the repository, persistent)

A repeat encounter with the same `peerId` counts no sooner than
`cooldownMinutes` (60 by default, configurable 5 min – 12 h in 5-minute steps) after the previous
**counted** encounter. `lastSeenAt` is still updated on every accepted packet —
the database shows real activity, the counter is not inflated.

### 5.3 Signal threshold

Packets with RSSI below `minRssi` (−95 dBm by default, configurable −100…−40)
are dropped before touching the database. Otherwise people behind a wall or a
floor below would be counted.

### 5.4 Packet processing result

```
Registered(peerId, firstMeeting) — encounter recorded
Cooldown                         — nearby, but already counted in the current window
TooWeak                          — signal below the threshold
```

### 5.5 Friends and achievements

**Friends** are a local mark on a peer (`peers.friendSince`) plus an optional
local name (`peers.alias`, sanitised like a nickname). Nothing goes over the
air and the other person is not notified — a mutual "friendship" would need a
connection, which the protocol deliberately does not have. The display name is
`alias ?: nickname ?: short ID`; friends get a heart badge. A peer dialog
(friend toggle, alias) opens from any encounter row, the top-5 list and the
friends list.

**Invites.** A friend can also be added without ever having met: the peer
dialog is not needed, the invite carries the identity. `FriendInvite` payload:

```
version(1) | public key, compressed(33) | nickname length(1) | nickname UTF-8(0..24) | ECDSA r||s (64)
```

signed with the domain `"StreetPass-invite-v1"` (different from the ID proof
domain, so the two signatures are not interchangeable). No timestamp: an invite
is meant to be stored and forwarded. It travels as base64url in the
`invite=` parameter. The primary form is the app's own scheme,
`streetpass://friend?invite=<payload>` (intent filter on scheme + host): it
opens only in the app, with no browser disambiguation; the QR code (ZXing) and
the share text carry this form, the share text adds the releases page for
people without the app. `https://github.com/<repo>#invite=<payload>` with the
same payload is also accepted. The receiver verifies the signature,
derives the ID from the key and creates the peer with zero encounters (or marks
an existing one); the first real encounter is recorded as "first meeting"
(`encounterCount == 0`).

Ways in: the in-app scanner (CameraX + ZXing, `CAMERA` permission requested
only on that screen), the `ACTION_VIEW` intent filters for `streetpass://friend`
and for the project URL (the domain is not ours, so there is no verified App
Link and on Android 12+ the https form opens in the browser unless the user
allows it in the app's settings), the
`ACTION_SEND text/plain` filter ("Share → StreetPass" from a messenger), and a
plain text field to paste the link. All four end in the same confirmation
dialog (`AppContainer.pendingInvite`), shown over any tab. Own invite is
rejected.

**Achievements** are defined in code (`Achievement.ALL`; ids are stored in the
database, so never rename them) and evaluated from the accumulated database,
not from events — `AchievementRepository.check(now)` is idempotent and is called
after every registered encounter and after a friend-list change:

| Kind | Metric | Tiers |
|---|---|---|
| PEOPLE | unique peers | 5, 15, 30, 50, 100 |
| ENCOUNTERS | encounters total | 10, 50, 100, 500 |
| FRIENDS | peers marked as friends | 1, 5, 10 |
| FRIEND_ENCOUNTERS | max `encounterCount` among friends | 10, 25, 50, 100 |
| STREAK | consecutive days with encounters (today, or ending yesterday if today has none yet) | 7, 30 |

An unlock stores `unlockedAt` once (INSERT OR IGNORE) so the date never moves.
Unseen unlocks show as a card on Home until dismissed (`seenAt`); the grid
with progress bars lives at the bottom of Stats, next to the friends list. Per
kind it shows the unlocked tiers plus only the next locked one
(`visibleProgress`) — "500 encounters" is not shown until 100 is done.

"Clear history" deletes encounters and non-friend peers; friends stay with
their counters reset to zero, achievements are untouched.

## 6. Data (Room)

```
peers
  peerId          TEXT PK
  firstSeenAt     INTEGER
  lastSeenAt      INTEGER   updated on every packet
  lastEncounterAt INTEGER   base for duplicate protection
  encounterCount  INTEGER
  lastRssi        INTEGER
  bestRssi        INTEGER
  nickname        TEXT NULL  (v2, migration ALTER TABLE ADD COLUMN)
  friendSince     INTEGER NULL  (v3) local friend mark
  alias           TEXT NULL     (v3) local name

achievements  (v3)
  id            TEXT PK    from Achievement.ALL
  unlockedAt    INTEGER
  seenAt        INTEGER NULL

encounters
  id            INTEGER PK autoincrement
  peerId        TEXT FK -> peers.peerId ON DELETE CASCADE
  timestamp     INTEGER
  rssi          INTEGER
  firstMeeting  INTEGER (bool)
  INDEX(peerId), INDEX(timestamp)
```

Recording an encounter and updating the peer happen in one transaction
(`db.withTransaction`).

Day boundaries are computed via `java.time.LocalDate` in the local time zone,
not by subtracting 24 hours. The "today" Flow re-subscribes at midnight,
otherwise in a long-open app the date sticks to yesterday.

## 7. Background work

A foreground service of type `connectedDevice`. Reason: starting with Android 8
background processes are put to sleep, and the system stops BLE scanning from
the background without a visible notification. A persistent notification is the
only legal way to keep the scanner alive for long.

Notification: channel `IMPORTANCE_LOW`, no vibration, `setOngoing(true)`,
`setSilent(true)`, text "Encounters today: N", action "Turn off", tap opens the
app.

A second channel `friends` (`IMPORTANCE_DEFAULT`): when a registered encounter
is with a peer marked as a friend, a separate "<name> is nearby" notification
is posted (id derived from peerId, auto-cancel). It fires at most once per
duplicate-protection window per friend because it follows `Registered`, not
raw packets. Off via the "Notify about friends" setting; skipped when
notifications are disabled.

### 7.3 Home screen widget

`TodayWidgetProvider` (RemoteViews, no Glance): encounters today + people
today, tap opens the app. Refreshed by the service on every change of the
today counter and by the system every 30 minutes (`updatePeriodMillis`), so
the number does not stay stale past midnight when discovery is off. Refresh
is skipped when no widget instance exists.

### 7.1 Duty cycle

Continuous scanning drains the battery. Profiles:

| Profile | Window | Pause | Scan mode |
|---|---|---|---|
| Saver | 8 s | 112 s | BALANCED |
| Balanced (default) | 10 s | 50 s | BALANCED |
| Maximum | continuous | — | LOW_LATENCY |

All profiles stay below the system limit of 5 scan starts per 30 seconds —
beyond it Android silently stops delivering results. Advertising runs
continuously; it costs almost nothing.

### 7.2 Reacting to events

- `BluetoothAdapter.ACTION_STATE_CHANGED`: on turning off — stop the radios,
  show the reason in the UI; on turning on — restart. The receiver is registered
  at runtime with `RECEIVER_NOT_EXPORTED`.
- A settings change or an ID (i.e. key) change — restart the radios without
  stopping the service (`ACTION_REFRESH`).
- `BOOT_COMPLETED` and `MY_PACKAGE_REPLACED`: if autostart is enabled and
  discovery was active — bring the service up. Wrap the start in try/catch: on
  Android 15 and on some firmware starting a foreground service from
  BOOT_COMPLETED may be forbidden; that is no reason to crash.
- `onTaskRemoved`: the service keeps working after the app is swiped away.

## 8. Permissions

| Version | Permissions |
|---|---|
| ≤ 11 | `BLUETOOTH`, `BLUETOOTH_ADMIN` (install-time), `ACCESS_FINE_LOCATION` |
| 12+ | `BLUETOOTH_SCAN` with `neverForLocation`, `BLUETOOTH_ADVERTISE` |
| 13+ | additionally `POST_NOTIFICATIONS` |
| any | `CAMERA` — only for the QR invite scanner, requested on that screen |

Plus `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CONNECTED_DEVICE`,
`RECEIVE_BOOT_COMPLETED`.

The legacy `BLUETOOTH`/`BLUETOOTH_ADMIN`/`ACCESS_FINE_LOCATION` are declared
with `android:maxSdkVersion="30"`.

`POST_NOTIFICATIONS` does not block operation: without it the service works,
it just does not show the notification.

Turning Bluetooth on: `ACTION_REQUEST_ENABLE` needs `BLUETOOTH_CONNECT`, which
the app needs for nothing else. Instead of the dialog open
`Settings.ACTION_BLUETOOTH_SETTINGS`.

Re-check the state of permissions and Bluetooth on `ON_RESUME` — the user may
have changed them in the system settings.

## 9. Screens

Bottom navigation, four tabs. `navigation-compose` is not needed — the state of
the selected tab is enough.

### Home
- Discovery card: toggle, status (broadcasting / scanning indicators), time of
  the last received signal.
- Warnings: no permissions / Bluetooth off / BLE error — with an action button.
- Tiles: encounters today, people today, unique people total, encounters total.
- "Nearby now": peers whose packet was accepted in the last 3 minutes
  (`peers.lastSeenAt`, re-queried every 15 s so people drop off without new
  writes), with the last RSSI and distance hint; shown whenever discovery is
  running. Tap opens the peer dialog.
- Last 5 encounters, link to the history; tap on a row opens the peer dialog.
- "New achievement" card while there are unseen unlocks, with a dismiss button.
- Own anonymous ID with an explanation of what it means.

### History
A list of encounters grouped by date, header "Today / Yesterday / 3 March
2026 · N". Each row: name (alias, nickname or short ID), "First meeting" or
"Meeting #N", time; tap opens the peer dialog. Query
limit 500 — history on screen, not a database dump into memory. An empty state
explaining what to do.

### Stats
- Today: encounters, people.
- Last 7 days: encounters, people + a bar chart by day (draw it yourself with
  Box, do not pull in a chart library).
- All time: encounters, unique people, average encounters per person.
- Top 5 most frequent peers.
- Friends: "My invite" (QR + share link) and "Add friend" (scanner + paste
  field) buttons, then the list with encounter counts; tap opens the peer
  dialog.
- Achievements: a two-column grid of tiles with icon, title, progress bar and
  "N / M" or the unlock date.

### Localisation
Strings live in resources: English by default (`values/`), Russian
(`values-ru/`). The system picks the language; on Android 13+ it can be changed
per app (`localeConfig`). Dates and numbers are formatted for
`Locale.getDefault()`.

### Settings
- Profile: nickname field with a byte counter, "Save" button, a warning that the
  nickname is visible to any scanner.
- Discovery: broadcast my ID / look for others / accept unsigned IDs (off by
  default) / notify about friends (on by default) / start after reboot.
- Power profile: three radio buttons with descriptions.
- Duplicate protection: slider 5 min – 12 h.
- RSSI threshold: slider −100…−40 dBm with a distance hint.
- Privacy: "store RSSI" toggle; own ID; full-width "Change ID" and "Clear
  history" buttons (both with confirmation). What is and is not collected is
  documented in the README, not repeated on the screen.
- Updates: current version, "Check for updates" button. Request to
  `api.github.com/repos/<owner>/<repo>/releases/latest`, compare `tag_name`
  with `BuildConfig.VERSION_NAME`. If newer — release notes, buttons
  "Download and install" (DownloadManager → system installer, needs
  `REQUEST_INSTALL_PACKAGES`) and "Open on GitHub". Show network errors as text,
  do not crash. A permanent "All releases on GitHub" link is always there: the
  in-app check and download do not work on every phone.

### 9.1 Releases

GitHub Actions on a `v*` tag runs `lint test`, builds `assembleRelease` with the
key from the repository secrets (`versionName` from the tag, `versionCode` from
the run number) and attaches the APK to the release. An update installs over the
old version only if the signature matches: install release builds on phones,
not debug ones.

## 10. Project structure

```
core/          BleConstants, Hex, TimeRanges, Streaks, IdentityProof (ID signature), FriendInvite (signed invite)
data/db/       Entities, Daos, AppDatabase
data/settings/ SettingsRepository, AppSettings, PowerMode
data/identity/ IdentityRepository, IdentityKeys (key in AndroidKeyStore / software)
data/achievements/ Achievement (definitions), AchievementRepository
data/          AppDataStore, EncounterRepository (duplicate protection, friends)
data/update/   UpdateRepository (GitHub Releases, DownloadManager)
ble/           BleAdvertiser, BleScanner, DiscoveryService, BootReceiver
ui/            AppRoot, AppViewModels, Permissions
ui/theme/      Theme
ui/components/ StatTile, StatusDot, LabeledRow, BarColumn, SectionTitle, AchievementTile, EncounterItem
ui/peer/       PeerDialog + PeerViewModel (opened from several screens)
ui/friends/    FriendInvites (my QR, scanner dialog, confirmation) + QrScanner (CameraX)
ui/widget/     TodayWidgetProvider (home screen widget)
ui/home|history|stats|settings/   screen + its ViewModel in one file
```

## 11. MVP acceptance criteria

1. `./gradlew assembleDebug lint` passes without errors.
2. The app installs on two physical devices and launches.
3. On Android 12+ granting permissions does **not** ask for location.
4. Turning the toggle on shows a persistent notification, the "broadcasting"
   and "scanning" indicators become active.
5. Two devices next to each other discover one another within one duty cycle,
   each gets one encounter with the correct time.
6. Repeated discovery within the duplicate-protection window creates no new
   encounter.
7. With `cooldown = 5 min` a second encounter appears after the window expires.
8. "Today / week / all time" statistics agree with the history contents.
9. After the app is killed from the task list the service keeps working,
   encounters keep being recorded.
10. Turning Bluetooth off does not crash the app: the reason is shown, after
    turning it on discovery recovers on its own.
11. Changing the ID and clearing the history work and do not break the current
    discovery session.
12. `logcat` has no `SecurityException` and no `SCAN_FAILED_*`.
13. A phone advertising someone else's ID without the key (e.g. an old build
    with a substituted `peer_id`) is not counted in strict mode; with "Accept
    unsigned IDs" on — it is counted.
14. A signing neighbour becomes trusted within 2–3 scan cycles in the
    "Balanced" profile at most (logcat: `proof verified for ...`).
15. Marking a peer as a friend unlocks "1 friend" immediately and shows the
    "New achievement" card on Home; clearing the history keeps the friend and
    the achievement.
16. Phone B scans phone A's "My invite" QR: A appears in B's friends with zero
    encounters; the next real encounter is recorded as "First meeting". The
    same link pasted into "Add friend" or shared from a messenger to StreetPass
    gives the same confirmation dialog; a modified link is rejected.

## 12. Work order

1. Project skeleton: Gradle, manifest, resources, theme, `StreetPassApp` +
   `AppContainer`.
2. Data layer: Room (entities, DAOs, database), DataStore (settings, identity).
3. `EncounterRepository` with the duplicate-protection logic + unit tests for it
   (in-memory Room, fixed `now` as a parameter).
4. `BleAdvertiser` and `BleScanner` in isolation.
5. `DiscoveryService`: foreground, duty cycle, reaction to settings and adapter
   state. `BootReceiver`.
6. UI: the Home screen with the toggle and permissions — the minimum for a
   hardware check. Verify acceptance criteria 1–6.
7. History, stats, settings.
8. Run through the full acceptance list.

## 13. Pitfalls

- **The emulator does not support BLE.** Test on physical devices only.
- **Not every chip supports advertising.** On some devices
  `bluetoothLeAdvertiser` returns `null` — such a device can only find others.
  This is not an app bug, but it must be shown to the user.
- **Vendor firmware** (MIUI, EMUI, ColorOS) kills foreground services against
  the Android rules. The README must have instructions for lifting battery
  optimisation by hand.
- **Scan restart limit:** more than 5 starts in 30 seconds and the system
  silently stops delivering results, with no error in the callback.
- **BLE callbacks do not arrive on the main thread.** Move heavy work and
  database access to `Dispatchers.IO`.
- **A permission can be revoked at runtime**, and the adapter can be turned off
  between the check and the call — hence the mandatory try/catch for
  `SecurityException` and `IllegalStateException` around every BLE API call.
- **Both sides count the encounter independently.** There is no confirmation;
  if one side loses the packet, one of the users gets no encounter. This is
  acceptable for the MVP but must be in the README.

## 14. Possible future work (not now)

- Scheduled ID rotation with a local correspondence table — protection against
  long-term tracking by a permanent identifier. With signatures this means key
  rotation.
- A "verified by signature" flag on the peer in the database and a badge in the
  history — right now in strict mode every recorded peer is verified, and the
  distinction only matters with unsigned IDs accepted.
- Exchange of a short profile (nickname, emoji) over GATT when close.
- History export to CSV/JSON.
- A debug screen with raw scan results.
- Auto-deletion of encounters older than N days.
- iOS compatibility: needs a GATT server and the overflow area.
