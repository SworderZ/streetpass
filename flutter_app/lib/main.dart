import 'dart:async';
import 'dart:math';
import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:universal_ble/universal_ble.dart';

const companyId = 0xffff;
const packetPrefix = <int>[0x53, 0x50, 0x01];

void main() => runApp(const StreetPassApp());

class StreetPassApp extends StatefulWidget {
  const StreetPassApp({super.key});
  @override
  State<StreetPassApp> createState() => _StreetPassAppState();
}

class _StreetPassAppState extends State<StreetPassApp> {
  final service = DesktopStreetPassService();
  bool running = false;
  bool loading = true;
  String nickname = '';
  List<Meeting> meetings = [];

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    final prefs = await SharedPreferences.getInstance();
    nickname = prefs.getString('nickname') ?? '';
    final count = prefs.getInt('meeting_count') ?? 0;
    meetings = List.generate(
      count,
      (i) => Meeting(
        'StreetPass device',
        DateTime.now().subtract(Duration(hours: i + 1)),
      ),
    );
    setState(() => loading = false);
  }

  Future<void> _toggle(bool value) async {
    if (value) {
      await service.start(nickname, (meeting) async {
        final prefs = await SharedPreferences.getInstance();
        await prefs.setInt('meeting_count', meetings.length + 1);
        if (mounted) setState(() => meetings = [meeting, ...meetings]);
      });
    } else {
      await service.stop();
    }
    if (mounted) setState(() => running = value);
  }

  Future<void> _saveNickname(String value) async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString('nickname', value);
    service.nickname = value;
    setState(() => nickname = value);
  }

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      debugShowCheckedModeBanner: false,
      theme: ThemeData(
        brightness: Brightness.dark,
        scaffoldBackgroundColor: const Color(0xff101012),
        colorScheme: ColorScheme.fromSeed(
          seedColor: const Color(0xffa8c7fa),
          brightness: Brightness.dark,
        ),
        cardTheme: const CardThemeData(color: Color(0xff171719)),
      ),
      home: loading
          ? const Scaffold(body: Center(child: CircularProgressIndicator()))
          : HomePage(
              running: running,
              meetings: meetings,
              nickname: nickname,
              onToggle: _toggle,
              onOpenSettings: () => showDialog<void>(
                context: context,
                builder: (_) =>
                    SettingsDialog(nickname: nickname, onSave: _saveNickname),
              ),
            ),
    );
  }
}

class HomePage extends StatelessWidget {
  const HomePage({
    super.key,
    required this.running,
    required this.meetings,
    required this.nickname,
    required this.onToggle,
    required this.onOpenSettings,
  });
  final bool running;
  final List<Meeting> meetings;
  final String nickname;
  final ValueChanged<bool> onToggle;
  final VoidCallback onOpenSettings;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Scaffold(
      appBar: AppBar(
        title: const Text('StreetPass'),
        actions: [
          IconButton(
            onPressed: onOpenSettings,
            icon: const Icon(Icons.settings_outlined),
          ),
        ],
      ),
      body: ListView(
        padding: const EdgeInsets.fromLTRB(20, 12, 20, 32),
        children: [
          Text('Обнаружение', style: theme.textTheme.headlineSmall),
          const SizedBox(height: 6),
          Text(
            running ? 'Работает в фоне' : 'Выключено',
            style: TextStyle(color: theme.colorScheme.onSurfaceVariant),
          ),
          const SizedBox(height: 20),
          Card(
            shape: RoundedRectangleBorder(
              borderRadius: BorderRadius.circular(16),
            ),
            child: Padding(
              padding: const EdgeInsets.all(18),
              child: Column(
                children: [
                  Row(
                    children: [
                      const Expanded(
                        child: Text(
                          'Искать людей рядом',
                          style: TextStyle(
                            fontSize: 17,
                            fontWeight: FontWeight.w600,
                          ),
                        ),
                      ),
                      Switch(value: running, onChanged: onToggle),
                    ],
                  ),
                  const SizedBox(height: 18),
                  Row(
                    children: [
                      _Stat(value: '${meetings.length}', label: 'встречи'),
                      _Stat(
                        value: nickname.isEmpty ? '—' : nickname,
                        label: 'ваш ник',
                      ),
                    ],
                  ),
                ],
              ),
            ),
          ),
          const SizedBox(height: 24),
          Text('Последние встречи', style: theme.textTheme.titleLarge),
          const SizedBox(height: 10),
          if (meetings.isEmpty)
            const Card(
              child: Padding(
                padding: EdgeInsets.all(18),
                child: Text(
                  'Пока встреч нет. Включите обнаружение и держите устройство рядом с другими участниками.',
                ),
              ),
            )
          else
            ...meetings.map(
              (m) => Card(
                margin: const EdgeInsets.only(bottom: 8),
                child: ListTile(
                  leading: const CircleAvatar(
                    child: Icon(Icons.waving_hand_outlined),
                  ),
                  title: Text(m.name),
                  subtitle: Text(_time(m.time)),
                  trailing: const Icon(Icons.chevron_right),
                ),
              ),
            ),
        ],
      ),
    );
  }

  static String _time(DateTime t) =>
      '${t.day.toString().padLeft(2, '0')}.${t.month.toString().padLeft(2, '0')} ${t.hour.toString().padLeft(2, '0')}:${t.minute.toString().padLeft(2, '0')}';
}

class _Stat extends StatelessWidget {
  const _Stat({required this.value, required this.label});
  final String value;
  final String label;
  @override
  Widget build(BuildContext context) => Expanded(
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(
          value,
          style: Theme.of(context).textTheme.headlineMedium
              ?.copyWith(color: Theme.of(context).colorScheme.primary),
        ),
        Text(
          label,
          style: TextStyle(
            color: Theme.of(context).colorScheme.onSurfaceVariant,
          ),
        ),
      ],
    ),
  );
}

class Meeting {
  Meeting(this.name, this.time);
  final String name;
  final DateTime time;
}

class SettingsDialog extends StatefulWidget {
  const SettingsDialog({
    super.key,
    required this.nickname,
    required this.onSave,
  });
  final String nickname;
  final ValueChanged<String> onSave;
  @override
  State<SettingsDialog> createState() => _SettingsDialogState();
}

class _SettingsDialogState extends State<SettingsDialog> {
  late final controller = TextEditingController(text: widget.nickname);
  @override
  Widget build(BuildContext context) => AlertDialog(
    title: const Text('Настройки'),
    content: TextField(
      controller: controller,
      decoration: const InputDecoration(
        labelText: 'Никнейм',
        helperText: 'Виден рядом стоящим участникам',
      ),
    ),
    actions: [
      TextButton(
        onPressed: () => Navigator.pop(context),
        child: const Text('Отмена'),
      ),
      FilledButton(
        onPressed: () {
          widget.onSave(controller.text.trim());
          Navigator.pop(context);
        },
        child: const Text('Сохранить'),
      ),
    ],
  );
}

class DesktopStreetPassService {
  String nickname = '';
  DesktopScanner? scanner;
  Uint8List peerId = Uint8List.fromList(
    List.generate(8, (_) => _random.nextInt(256)),
  );
  bool advertising = false;
  static final _random = Random.secure();

  Future<void> start(String value, void Function(Meeting) onMeeting) async {
    nickname = value;
    await UniversalBle.requestPermissions();
    scanner = DesktopScanner(onMeeting);
    await scanner!.start();
    try {
      final packet = Uint8List.fromList([...packetPrefix, 1, ...peerId, 0]);
      await UniversalBlePeripheral.startAdvertising(
        services: const [],
        manufacturerData: ManufacturerData(companyId, packet),
      );
      advertising = true;
    } catch (_) {}
  }

  Future<void> stop() async {
    await scanner?.stop();
    scanner = null;
    if (advertising) {
      try {
        await UniversalBlePeripheral.stopAdvertising();
      } catch (_) {}
      advertising = false;
    }
  }
}

class DesktopScanner {
  DesktopScanner(this.onMeeting);
  final void Function(Meeting) onMeeting;
  Future<void> start() async {
    UniversalBle.onScanResult = (result) {
      for (final data in result.manufacturerDataList) {
        if (data.companyId == companyId &&
            data.payload.length >= 12 &&
            _hasPrefix(data.payload)) {
          onMeeting(Meeting('StreetPass device', DateTime.now()));
          break;
        }
      }
    };
    await UniversalBle.startScan(
      scanFilter: ScanFilter(
        withManufacturerData: [
          ManufacturerDataFilter(
            companyIdentifier: companyId,
            payloadPrefix: Uint8List.fromList(packetPrefix),
          ),
        ],
      ),
    );
  }

  Future<void> stop() async {
    UniversalBle.onScanResult = null;
    await UniversalBle.stopScan();
  }

  static bool _hasPrefix(Uint8List data) =>
      data.length >= 3 &&
      data[0] == packetPrefix[0] &&
      data[1] == packetPrefix[1] &&
      data[2] == packetPrefix[2];
}
