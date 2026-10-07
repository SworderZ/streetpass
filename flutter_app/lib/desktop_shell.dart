import 'dart:convert';
import 'dart:io';
import 'dart:ui';

import 'package:launch_at_startup/launch_at_startup.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:tray_manager/tray_manager.dart';
import 'package:window_manager/window_manager.dart';

import 'app_store.dart';

class DesktopShellController with TrayListener, WindowListener {
  DesktopShellController._();

  static final DesktopShellController instance = DesktopShellController._();

  static bool get supported => Platform.isWindows || Platform.isLinux;

  bool _initialized = false;
  bool _closeToTray = true;
  bool _allowClose = false;

  static Future<void> initialize() => instance._initialize();

  static Future<void> applySettings(AppSettings settings) =>
      instance._applySettings(settings);

  Future<void> _initialize() async {
    if (!supported || _initialized) return;

    var startMinimized = false;
    var closeToTray = true;
    try {
      final prefs = await SharedPreferences.getInstance();
      final raw = prefs.getString(AppStore.storageKey);
      if (raw != null) {
        final data = jsonDecode(raw) as Map<String, dynamic>;
        final settings = Map<String, dynamic>.from(data['settings'] ?? {});
        startMinimized = settings['startMinimized'] == true;
        closeToTray = settings['closeToTray'] as bool? ?? true;
      }
    } catch (_) {
      // A first launch has no settings yet. Use a visible window.
    }

    _closeToTray = closeToTray;
    await windowManager.ensureInitialized();
    windowManager.addListener(this);
    trayManager.addListener(this);

    final options = WindowOptions(
      size: const Size(960, 720),
      minimumSize: const Size(720, 560),
      center: true,
      title: 'StreetPass',
    );
    windowManager.waitUntilReadyToShow(options, () async {
      if (startMinimized) {
        await windowManager.hide();
      } else {
        await windowManager.show();
        await windowManager.focus();
      }
    });

    try {
      await _initializeTray();
    } catch (_) {
      // Some Linux desktop shells do not expose a tray host. The window and
      // BLE client must still work without the optional tray icon.
    }
    _initialized = true;
  }

  Future<void> _initializeTray() async {
    final iconPath = _findIconPath();
    if (iconPath != null) {
      try {
        await trayManager.setIcon(iconPath);
      } catch (_) {}
    }
    try {
      await trayManager.setToolTip('StreetPass');
    } catch (_) {}
    await trayManager.setContextMenu(
      Menu(
        items: [
          MenuItem(key: 'show', label: 'Открыть StreetPass'),
          MenuItem.separator(),
          MenuItem(key: 'exit', label: 'Выйти'),
        ],
      ),
    );
  }

  Future<void> _applySettings(AppSettings settings) async {
    if (!supported) return;
    _closeToTray = settings.closeToTray;
    try {
      launchAtStartup.setup(
        appName: 'StreetPass',
        appPath: Platform.resolvedExecutable,
        packageName: 'space.megaworld.streetpass.crossplatform',
      );
      if (settings.desktopAutoStart) {
        await launchAtStartup.enable();
      } else {
        await launchAtStartup.disable();
      }
    } catch (_) {
      // Startup registration is optional and must not block BLE discovery.
    }
    await windowManager.setPreventClose(_closeToTray);
  }

  String? _findIconPath() {
    final separator = Platform.isWindows ? r'\' : '/';
    final executableDir = File(Platform.resolvedExecutable).parent.path;
    final candidates = [
      '$executableDir${separator}app_icon.ico',
      '${Directory.current.path}${separator}windows${separator}runner${separator}resources${separator}app_icon.ico',
    ];
    for (final path in candidates) {
      if (File(path).existsSync()) return path;
    }
    return null;
  }

  @override
  void onTrayIconMouseDown() => _showWindow();

  @override
  void onTrayMenuItemClick(MenuItem menuItem) {
    if (menuItem.key == 'show') _showWindow();
    if (menuItem.key == 'exit') _exit();
  }

  void _showWindow() {
    windowManager.show();
    windowManager.focus();
  }

  Future<void> _exit() async {
    _allowClose = true;
    await trayManager.destroy();
    await windowManager.destroy();
  }

  @override
  void onWindowClose() {
    if (_allowClose || !_closeToTray) {
      _exit();
    } else {
      windowManager.hide();
    }
  }
}
