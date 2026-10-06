StreetPass for Linux

Run the `streetpass` executable from this folder. Keep the `lib` folder beside
the executable and keep the whole bundle together.

Before first launch, install the desktop and Bluetooth packages on Debian or
Ubuntu:

  sudo apt install libgtk-3-0 bluez

The Linux BLE backend can scan nearby devices. Peripheral advertising depends
on the BlueZ setup and is not enabled by the current universal_ble release.
