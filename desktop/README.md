# StreetPass Desktop

Клиенты для портативных устройств на Windows и Linux. Android-приложение и
desktop-клиенты используют один BLE-протокол:

- основной Service Data UUID `0x5350`: 8 байт peer ID;
- scan-response UUID `0x5351`: никнейм;
- scan-response UUID `0x5352`: части доказательства `secp256r1`;
- реклама неподключаемая, GATT-подключение не используется.

План транспорта:

- Windows: `Windows.Devices.Bluetooth.Advertisement` и фоновый tray/service-клиент;
- Linux/Steam Deck: BlueZ D-Bus и user systemd service;
- общий слой: генерация ID, ECDSA-доказательств, сборка кадров, SQLite и антидубль.

Для разработки:

```text
python -m venv .venv
.venv/Scripts/pip install -e .       # Windows
.venv/bin/pip install -e .           # Linux
```

Пока BLE-транспорт подключается нативно для каждой ОС; протокол в
`streetpass_protocol.py` намеренно не зависит от Windows, Linux или Android.
