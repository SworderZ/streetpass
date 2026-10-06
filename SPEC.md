# StreetPass — current specification

## Goal

StreetPass is one Flutter client for Android, Windows and Linux. It discovers
nearby StreetPass clients over Bluetooth Low Energy and records local encounters
without accounts, GPS or a server-side profile.

The main supported pairing is an Android phone with another Android phone or a
Windows laptop. Linux can scan with the current BLE plugin; advertising depends
on the host BlueZ configuration.

## Privacy

- No GPS, location history, MAC addresses or device names are stored.
- The anonymous peer ID is the first eight bytes of SHA-256 over a compressed
  P-256 public key.
- The private key stays in local application storage and is never sent over the
  network.
- Nicknames are optional and are broadcast in clear text over BLE.
- Encounter history and the stable identity are stored locally.
- The website receives only aggregate statistics after the user opts in.

## Discovery protocol

The stable service UUIDs are:

- `00005350-0000-1000-8000-00805f9b34fb` — peer ID service data;
- `00005351-0000-1000-8000-00805f9b34fb` — optional nickname;
- `00005352-0000-1000-8000-00805f9b34fb` — signed proof chunks.

The proof is 102 bytes:

```text
version(1) | compressed P-256 public key(33) | Unix time, BE(4) | ECDSA r||s(64)
```

It signs `ASCII("StreetPass-ID-v1") || version || publicKey || timestamp`.
Receivers check the public-key hash, signature and a ten-minute time window
before recording an encounter.

Desktop clients use company ID `0xffff` with the envelope prefix `53 50 01`.
The envelope carries the proof in ten chunks so the same signed proof fits in a
legacy manufacturer advertisement. The Flutter scanner understands both this
envelope and the standard service-data frames.

## Build and test

```bash
cd flutter_app
flutter pub get
flutter analyze
flutter test
flutter build apk --release
flutter build windows --release
flutter build linux --release
```

BLE must be tested on physical hardware. Android requires Bluetooth permissions;
Windows requires an adapter that supports BLE scanning and advertising. A
Windows bundle also needs the official Microsoft Visual C++ x64 Redistributable.

GitHub Actions validates the Flutter client on every relevant push. Tags named
`v*` publish a Flutter APK to GitHub Releases.
