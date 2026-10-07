import 'dart:async';
import 'dart:io';

import 'package:flutter/services.dart';
import 'package:flutter_foreground_task/flutter_foreground_task.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'app_store.dart';
import 'discovery.dart';

@pragma('vm:entry-point')
void foregroundCallback() {
  FlutterForegroundTask.setTaskHandler(StreetPassTaskHandler());
}

class StreetPassTaskHandler extends TaskHandler {
  AppStore? store;
  DiscoveryService? discovery;

  @override
  Future<void> onStart(DateTime timestamp, TaskStarter starter) async {
    final prefs = await SharedPreferences.getInstance();
    store = AppStore(prefs);
    await store!.initialize();
    if (!store!.settings.autoStart || !store!.settings.active) {
      await FlutterForegroundTask.stopService();
      return;
    }
    discovery = DiscoveryService();
    try {
      await discovery!.start(store!, (sighting) async {
        if (!sighting.verified && !store!.settings.acceptUnsigned) return;
        if (store!.record(
          sighting.peerId,
          sighting.time,
          name: sighting.nickname,
          rssi: sighting.rssi,
        )) {
          await store!.save();
          FlutterForegroundTask.sendDataToMain({'type': 'meeting'});
        }
        _sendStatus();
      });
      _sendStatus();
    } catch (error) {
      FlutterForegroundTask.sendDataToMain({
        'type': 'status',
        'running': false,
        'scanning': false,
        'broadcasting': false,
        'error': error.toString(),
      });
      await FlutterForegroundTask.stopService();
    }
  }

  @override
  void onRepeatEvent(DateTime timestamp) {
    FlutterForegroundTask.updateService(
      notificationText: 'StreetPass ищет людей рядом',
    );
    _sendStatus();
  }

  void _sendStatus() {
    final current = discovery;
    if (current == null) return;
    FlutterForegroundTask.sendDataToMain({
      'type': 'status',
      'running': current.running,
      'scanning': current.scanning,
      'broadcasting': current.broadcasting,
      'receivedPackets': current.receivedPackets,
      'verifiedProofs': current.verifiedProofs,
      'rejectedProofs': current.rejectedProofs,
      'error': current.error,
    });
  }

  @override
  Future<void> onDestroy(DateTime timestamp, bool isTimeout) async {
    await discovery?.stop();
  }

  @override
  void onReceiveData(Object data) {
    if (data is Map && data['command'] == 'stop') {
      unawaited(discovery?.stop());
    }
  }
}

class ForegroundController {
  static const _permissionChannel = MethodChannel(
    'space.megaworld.streetpass/permissions',
  );

  static void initialize() {
    if (!Platform.isAndroid) return;
    FlutterForegroundTask.initCommunicationPort();
    FlutterForegroundTask.init(
      androidNotificationOptions: AndroidNotificationOptions(
        channelId: 'streetpass_discovery',
        channelName: 'StreetPass',
        channelDescription: 'Обнаружение пользователей рядом',
        onlyAlertOnce: true,
      ),
      iosNotificationOptions: const IOSNotificationOptions(
        showNotification: false,
        playSound: false,
      ),
      foregroundTaskOptions: ForegroundTaskOptions(
        eventAction: ForegroundTaskEventAction.repeat(
          const Duration(minutes: 1).inMilliseconds,
        ),
        autoRunOnBoot: true,
        autoRunOnMyPackageReplaced: true,
        allowWakeLock: true,
        allowAutoRestart: true,
      ),
    );
  }

  static Future<ServiceRequestResult?> start() async {
    if (!Platform.isAndroid) return null;
    final permissions =
        await _permissionChannel.invokeMethod<bool>(
          'requestBluetoothPermissions',
        ) ??
        false;
    if (!permissions) {
      throw StateError('Bluetooth permissions were not granted');
    }
    final permission =
        await FlutterForegroundTask.checkNotificationPermission();
    if (permission != NotificationPermission.granted) {
      await FlutterForegroundTask.requestNotificationPermission();
    }
    return FlutterForegroundTask.startService(
      serviceId: 5350,
      serviceTypes: const [ForegroundServiceTypes.connectedDevice],
      notificationTitle: 'StreetPass',
      notificationText: 'Запуск обнаружения…',
      callback: foregroundCallback,
    );
  }

  static Future<ServiceRequestResult?> stop() async {
    if (!Platform.isAndroid) return null;
    return FlutterForegroundTask.stopService();
  }

  static bool get supported => Platform.isAndroid;
}
