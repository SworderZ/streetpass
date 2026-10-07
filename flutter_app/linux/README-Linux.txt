StreetPass for Linux

Run the `streetpass` executable from this folder. Keep the `lib` folder beside
the executable and keep the whole bundle together.

Before first launch, install the desktop, tray and Bluetooth packages on Debian
or Ubuntu:

  sudo apt install libgtk-3-0 libayatana-appindicator3-1 bluez

On CachyOS/Arch, use:

  sudo pacman -S gtk3 libayatana-appindicator bluez bluez-utils
  sudo systemctl enable --now bluetooth

The client uses BlueZ D-Bus for both scanning and manufacturer-data advertising.
