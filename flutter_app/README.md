# StreetPass cross-platform client

Единый Flutter-клиент StreetPass для Android, Windows и Linux. В репозитории больше
нет отдельного нативного Android-проекта: исходник приложения находится в этой
папке.

Реализованы BLE-сканирование и реклама, подписанные рукопожатия, никнейм,
сохранение ID устройства и локальная история встреч. На Linux текущий BLE-
плагин поддерживает сканирование, но режим рекламы зависит от BlueZ и пока не
включён по умолчанию.

Проверки:

```text
flutter pub get
flutter analyze
flutter test
flutter build apk --release
flutter build windows --release
flutter build linux --release
```

Для Windows установите официальный [Microsoft Visual C++ x64
Redistributable](https://aka.ms/vs/17/release/vc_redist.x64.exe), распакуйте
весь bundle и запускайте `streetpass.exe` рядом с `data` и DLL.
