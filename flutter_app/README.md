# StreetPass cross-platform client

Flutter-клиент с единым интерфейсом для Android, Windows и Linux.

Сейчас реализованы общий экран обнаружения, никнейм, локальные встречи,
BLE-сканирование и manufacturer-data реклама через `universal_ble`.
Windows и Linux используют один Dart-код; нативный BLE предоставляется плагином.

Проверки:

```text
flutter analyze
flutter test
flutter build apk --debug
```

Нативная проверка Windows требует Visual Studio с Desktop development with C++,
Linux-сборка выполняется на Linux-хосте. BLE-сон и постоянный фон требуют
отдельной настройки foreground/systemd для конкретной ОС.
