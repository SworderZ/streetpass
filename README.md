# StreetPass

StreetPass is a Flutter application for Android, Windows and Linux. It finds
nearby devices over Bluetooth Low Energy and stores only local encounter data.

The application source is in [`flutter_app`](flutter_app). The old separate
native Android project was removed, so there is one Android client to build and
test.

## Local checks

```bash
cd flutter_app
flutter pub get
flutter analyze
flutter test
flutter build apk --release
flutter build windows --release
flutter build linux --release
```

BLE must be tested on physical hardware. The Windows bundle must be kept
together and requires the official [Microsoft Visual C++ x64
Redistributable](https://aka.ms/vs/17/release/vc_redist.x64.exe). Run
`streetpass.exe` from the extracted bundle.

GitHub Actions builds Android, Windows and Linux artifacts on every relevant
push. Version tags (`v*`) publish a Flutter APK to GitHub Releases.

The public statistics website is in [`website`](website), the backend is in
[`backend`](backend), and the protocol notes are in [`SPEC.md`](SPEC.md).
