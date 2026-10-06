import 'dart:async';
import 'dart:convert';
import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:universal_ble/universal_ble.dart';

import 'streetpass_crypto.dart';

const companyId = 0xffff;
const packetPrefix = <int>[0x53, 0x50, 0x01];
const serviceUuid = '00005350-0000-1000-8000-00805f9b34fb';
const nicknameUuid = '00005351-0000-1000-8000-00805f9b34fb';
const proofUuid = '00005352-0000-1000-8000-00805f9b34fb';

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
    await service.initializeIdentity(prefs);
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
    try {
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
    } catch (error) {
      await service.stop();
      if (!mounted) return;
      setState(() => running = false);
      ScaffoldMessenger.of(context)
          .showSnackBar(SnackBar(content: Text(_bleError(error))));
    }
  }

  static String _bleError(Object error) {
    final message = error.toString().replaceFirst('Bad state: ', '');
    if (message.contains('Bluetooth is off')) {
      return 'Включите Bluetooth и попробуйте ещё раз';
    }
    if (message.contains('peripheral advertising')) {
      return 'Этот Bluetooth-адаптер не умеет BLE-рекламу';
    }
    return 'Не удалось запустить Bluetooth: $message';
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
          : Builder(
              builder: (homeContext) => HomePage(
                running: running,
                meetings: meetings,
                nickname: nickname,
                onToggle: _toggle,
                onOpenSettings: () => showDialog<void>(
                  context: homeContext,
                  builder: (_) =>
                      SettingsDialog(nickname: nickname, onSave: _saveNickname),
                ),
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
  void dispose() {
    controller.dispose();
    super.dispose();
  }

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
  StreetPassIdentity? identity;
  bool advertising = false;
  Timer? _advertisementRotation;
  List<Uint8List> _advertisementPackets = const [];
  int _advertisementIndex = 0;
  bool _publishingAdvertisement = false;

  Future<void> initializeIdentity(SharedPreferences prefs) async {
    final stored = prefs.getString('identity_private_scalar');
    if (stored != null) {
      try {
        identity = StreetPassCrypto.fromPrivateScalarHex(stored);
        return;
      } catch (_) {}
    }
    identity = StreetPassCrypto.create();
    await prefs.setString(
      'identity_private_scalar',
      StreetPassCrypto.privateScalarHex(identity!),
    );
  }

  Future<void> start(String value, void Function(Meeting) onMeeting) async {
    nickname = value;
    final currentIdentity = identity ??= StreetPassCrypto.create();
    await UniversalBle.requestPermissions(withAndroidFineLocation: true);
    final availability = await UniversalBle.getBluetoothAvailabilityState();
    if (availability != AvailabilityState.poweredOn) {
      throw StateError('Bluetooth is off or unavailable: ${availability.name}');
    }
    final capabilities = await UniversalBlePeripheral.getCapabilities();
    if (!capabilities.supportsPeripheralMode ||
        !capabilities.supportsManufacturerDataInAdvertisement) {
      throw StateError('peripheral advertising is unavailable');
    }

    final nextScanner = DesktopScanner(
      onMeeting,
      ignoredPeerId: currentIdentity.peerId,
    );
    scanner = nextScanner;
    try {
      await nextScanner.start();
      _advertisementPackets = StreetPassCrypto.desktopProofPackets(
        currentIdentity,
      );
      if (nickname.trim().isNotEmpty) {
        final nicknameBytes = utf8.encode(nickname.trim()).take(12).toList();
        _advertisementPackets = [
          ..._advertisementPackets,
          Uint8List.fromList([
            ...packetPrefix,
            0,
            ...currentIdentity.peerId,
            ...nicknameBytes,
          ]),
        ];
      }
      _advertisementIndex = 0;
      await _publishNextAdvertisement();
      if (!advertising) {
        throw StateError('peripheral advertising failed');
      }
      _advertisementRotation = Timer.periodic(
        const Duration(milliseconds: 350),
        (_) => unawaited(_publishNextAdvertisement()),
      );
    } catch (_) {
      await stop();
      rethrow;
    }
  }

  Future<void> stop() async {
    _advertisementRotation?.cancel();
    _advertisementRotation = null;
    await scanner?.stop();
    scanner = null;
    if (advertising) {
      try {
        await UniversalBlePeripheral.stopAdvertising();
      } catch (_) {}
      advertising = false;
    }
  }

  Future<void> _publishNextAdvertisement() async {
    if (_advertisementPackets.isEmpty || _publishingAdvertisement) return;
    _publishingAdvertisement = true;
    try {
      if (advertising) {
        try {
          await UniversalBlePeripheral.stopAdvertising();
        } catch (_) {}
      }
      final packet = _advertisementPackets[_advertisementIndex];
      _advertisementIndex =
          (_advertisementIndex + 1) % _advertisementPackets.length;
      await UniversalBlePeripheral.startAdvertising(
        services: const [],
        manufacturerData: ManufacturerData(companyId, packet),
      );
      advertising = true;
    } catch (_) {
      advertising = false;
    } finally {
      _publishingAdvertisement = false;
    }
  }
}

class DesktopScanner {
  DesktopScanner(this.onMeeting, {required this.ignoredPeerId});
  final void Function(Meeting) onMeeting;
  final Uint8List ignoredPeerId;
  final _desktopAssemblies = <String, _ProofAssembly>{};
  final _androidAssemblies = <String, _ProofAssembly>{};
  final _nicknames = <String, String>{};
  final _lastMeetings = <String, DateTime>{};

  Future<void> start() async {
    UniversalBle.onScanResult = (result) {
      final now = DateTime.now();
      for (final data in result.manufacturerDataList) {
        if (data.companyId == companyId &&
            data.payload.length >= 12 &&
            _hasPrefix(data.payload)) {
          _handleDesktop(data.payload, now);
        }
      }
      _handleAndroid(result.serviceData, now);
    };
    await UniversalBle.startScan(
      scanFilter: ScanFilter(
        withServices: const [serviceUuid],
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
    _desktopAssemblies.clear();
    _androidAssemblies.clear();
    _nicknames.clear();
    _lastMeetings.clear();
  }

  void _handleDesktop(Uint8List payload, DateTime now) {
    final peerId = Uint8List.fromList(payload.sublist(4, 12));
    if (_sameId(peerId, ignoredPeerId)) return;
    final key = _hex(peerId);
    final kind = payload[3];
    if (kind == 0) {
      try {
        final value = utf8
            .decode(payload.sublist(12), allowMalformed: false)
            .trim();
        if (value.isNotEmpty) _nicknames[key] = value;
      } catch (_) {}
      return;
    }
    if (kind != 1 || payload.length < 14) return;
    final header = payload[12];
    final index = header & 0x0f;
    final generation = header >> 4;
    final length = index == 9 ? 3 : 11;
    if (index >= 10 || payload.length != 13 + length) return;
    final proof = _accept(
      _desktopAssemblies,
      key,
      generation,
      index,
      payload.sublist(13),
      11,
      10,
    );
    if (proof != null && StreetPassCrypto.verifyProof(proof, peerId)) {
      _notify(key, _nicknames[key] ?? 'StreetPass device', now);
    }
  }

  void _handleAndroid(Map<String, Uint8List> data, DateTime now) {
    final peerId = data[serviceUuid];
    if (peerId == null ||
        peerId.length != 8 ||
        _sameId(peerId, ignoredPeerId)) {
      return;
    }
    final key = _hex(peerId);
    final nickname = data[nicknameUuid];
    if (nickname != null) {
      try {
        final value = utf8.decode(nickname, allowMalformed: false).trim();
        if (value.isNotEmpty) _nicknames[key] = value;
      } catch (_) {}
    }
    final frame = data[proofUuid];
    if (frame == null || frame.length < 2) return;
    final header = frame[0];
    final index = header & 0x07;
    final generation = header >> 3;
    final length = index == 3 ? 24 : 26;
    if (index >= 4 || frame.length != length + 1) return;
    final proof = _accept(
      _androidAssemblies,
      key,
      generation,
      index,
      frame.sublist(1),
      26,
      4,
    );
    if (proof != null && StreetPassCrypto.verifyProof(proof, peerId)) {
      _notify(key, _nicknames[key] ?? 'StreetPass device', now);
    }
  }

  Uint8List? _accept(
    Map<String, _ProofAssembly> assemblies,
    String key,
    int generation,
    int index,
    List<int> bytes,
    int chunkBytes,
    int chunkCount,
  ) {
    final assembly = assemblies.putIfAbsent(
      key,
      () => _ProofAssembly(chunkBytes: chunkBytes, chunkCount: chunkCount),
    );
    if (assembly.generation != generation) assembly.reset(generation);
    assembly.add(index, bytes);
    return assembly.complete ? assembly.proof() : null;
  }

  void _notify(String key, String name, DateTime now) {
    final previous = _lastMeetings[key];
    if (previous != null &&
        now.difference(previous) < const Duration(seconds: 10)) {
      return;
    }
    _lastMeetings[key] = now;
    onMeeting(Meeting(name, now));
  }

  static bool _sameId(List<int> left, List<int> right) {
    if (left.length != right.length) return false;
    for (var i = 0; i < left.length; i++) {
      if (left[i] != right[i]) return false;
    }
    return true;
  }

  static String _hex(List<int> bytes) =>
      bytes.map((byte) => byte.toRadixString(16).padLeft(2, '0')).join();

  static bool _hasPrefix(Uint8List data) =>
      data.length >= 3 &&
      data[0] == packetPrefix[0] &&
      data[1] == packetPrefix[1] &&
      data[2] == packetPrefix[2];
}

class _ProofAssembly {
  _ProofAssembly({required this.chunkBytes, required this.chunkCount});

  final int chunkBytes;
  final int chunkCount;
  int generation = -1;
  int received = 0;
  final buffer = Uint8List(102);

  void reset(int value) {
    generation = value;
    received = 0;
    buffer.fillRange(0, buffer.length, 0);
  }

  void add(int index, List<int> bytes) {
    final offset = index * chunkBytes;
    if (offset + bytes.length > buffer.length) return;
    buffer.setRange(offset, offset + bytes.length, bytes);
    received |= 1 << index;
  }

  bool get complete => received == (1 << chunkCount) - 1;

  Uint8List proof() => Uint8List.fromList(buffer);
}
