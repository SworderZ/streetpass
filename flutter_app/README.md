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

Для запуска готовой Windows-сборки на другом компьютере установите официальный
[Microsoft Visual C++ x64 Redistributable](https://aka.ms/vs/17/release/vc_redist.x64.exe),
распакуйте весь архив и запускайте `streetpass.exe` рядом с папками `data` и DLL.
BLE-сон и постоянный фон требуют отдельной настройки для конкретной ОС.
