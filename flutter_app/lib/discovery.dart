import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:math';

import 'package:flutter/foundation.dart';
import 'package:universal_ble/universal_ble.dart';

import 'app_store.dart';
import 'linux_advertiser.dart';
import 'streetpass_crypto.dart';

const companyId = 0xffff;
const serviceUuid = '00005350-0000-1000-8000-00805f9b34fb';
const nicknameUuid = '00005351-0000-1000-8000-00805f9b34fb';
const proofUuid = '00005352-0000-1000-8000-00805f9b34fb';

class Sighting {
  Sighting(
    this.peerId,
    this.time,
    this.rssi, {
    this.nickname,
    this.verified = true,
  });
  final String peerId;
  final DateTime time;
  final int? rssi;
  final String? nickname;
  final bool verified;
}

abstract class BleTransport {
  Future<void> prepare();
  Future<void> scan(void Function(BleDevice) onPacket);
  Future<void> stopScan();
  Future<void> advertise(Uint8List packet);
  Future<void> stopAdvertising();
  Future<void> close();
}

class SystemBleTransport implements BleTransport {
  final _linux = Platform.isLinux ? LinuxAdvertiser() : null;
  StreamSubscription<BleDevice>? _packets;
  @override
  Future<void> prepare() async {
    if (Platform.isAndroid && !await UniversalBle.hasPermissions()) {
      throw StateError('Bluetooth permissions were not granted');
    }
    final availability = await UniversalBle.getBluetoothAvailabilityState();
    if (availability != AvailabilityState.poweredOn) {
      throw StateError('Bluetooth: ${availability.name}');
    }
  }

  @override
  Future<void> scan(void Function(BleDevice) onPacket) async {
    _packets ??= UniversalBle.scanStream.listen(onPacket);
    // Windows и BlueZ доставляют scan-response отдельно от главного пакета.
    // Там фильтр по service UUID теряет части подписи; отбор делаем по payload.
    await UniversalBle.startScan(
      scanFilter: Platform.isAndroid
          ? ScanFilter(
              withServices: const [serviceUuid, proofUuid, nicknameUuid],
              withManufacturerData: [
                ManufacturerDataFilter(
                  companyIdentifier: companyId,
                  payloadPrefix: Uint8List.fromList(
                    StreetPassCrypto.packetPrefix,
                  ),
                ),
              ],
            )
          : null,
    );
  }

  @override
  Future<void> stopScan() async {
    await _packets?.cancel();
    _packets = null;
    await UniversalBle.stopScan();
  }

  @override
  Future<void> advertise(Uint8List packet) async {
    if (_linux != null) {
      await _linux.publish(packet);
      return;
    }
    await UniversalBlePeripheral.startAdvertising(
      services: const [],
      manufacturerData: ManufacturerData(companyId, packet),
    );
    // Возврат startAdvertising означает принятие команды, а не выход в эфир.
    final deadline = DateTime.now().add(const Duration(seconds: 5));
    while (DateTime.now().isBefore(deadline)) {
      final state = await UniversalBlePeripheral.getAdvertisingState();
      if (state == PeripheralAdvertisingState.advertising) return;
      if (state == PeripheralAdvertisingState.error) {
        throw StateError('BLE advertising failed: $state');
      }
      await Future<void>.delayed(const Duration(milliseconds: 50));
    }
    throw TimeoutException('BLE advertising did not start');
  }

  @override
  Future<void> stopAdvertising() async {
    if (_linux != null) {
      await _linux.stopPacket();
      return;
    }
    await UniversalBlePeripheral.stopAdvertising();
  }

  @override
  Future<void> close() async {
    await _linux?.close();
  }
}

class DiscoveryService extends ChangeNotifier {
  DiscoveryService({BleTransport? transport})
    : transport = transport ?? SystemBleTransport();
  final BleTransport transport;
  bool running = false;
  bool scanning = false;
  bool broadcasting = false;
  String? error;
  DateTime? lastSignal;
  int receivedPackets = 0;
  int verifiedProofs = 0;
  int rejectedProofs = 0;
  int _session = 0;
  Future<void>? _rotation;
  Future<void>? _scanCycle;
  ProtocolReceiver? _receiver;
  Completer<void>? _wake;
  StreamSubscription<AvailabilityState>? _availability;
  AppStore? _store;
  void Function(Sighting)? _onSighting;

  Future<void> start(AppStore store, void Function(Sighting) onSighting) async {
    await stop();
    _store = store;
    _onSighting = onSighting;
    error = null;
    if (!store.settings.scan && !store.settings.advertise) {
      throw StateError('Enable scanning or broadcasting first');
    }
    await transport.prepare();
    final session = ++_session;
    running = true;
    _wake = Completer<void>();
    _receiver = ProtocolReceiver(
      store.ownId,
      onSighting,
      acceptUnsigned: store.settings.acceptUnsigned,
      onProof: (valid) {
        valid ? verifiedProofs++ : rejectedProofs++;
        notifyListeners();
      },
    );
    if (store.settings.scan) {
      try {
        await _startScan();
      } catch (e) {
        running = false;
        error = e.toString();
        notifyListeners();
        rethrow;
      }
      _scanCycle = _cycleScan(session, store.settings.powerMode);
    }
    if (store.settings.advertise) {
      // Не запрещаем сканирование, если адаптер не поддерживает передачу.
      _rotation = _rotate(session, store);
    }
    _availability = UniversalBle.availabilityStream.listen((state) {
      if (state == AvailabilityState.poweredOff ||
          state == AvailabilityState.unauthorized) {
        scanning = false;
        broadcasting = false;
        error = 'Bluetooth: ${state.name}';
        notifyListeners();
      } else if (state == AvailabilityState.poweredOn &&
          running &&
          error != null) {
        unawaited(_restartAfterAdapterChange());
      }
    });
    notifyListeners();
  }

  void setUiActive(bool value) {
    running = value;
    notifyListeners();
  }

  Future<void> _restartAfterAdapterChange() async {
    final store = _store;
    final callback = _onSighting;
    if (store == null || callback == null) return;
    try {
      await start(store, callback);
    } catch (e) {
      error = e.toString();
      notifyListeners();
    }
  }

  Future<void> _startScan() async {
    await transport.scan((packet) {
      receivedPackets++;
      final before = _receiver!.acceptedPackets;
      _receiver!.accept(packet);
      if (_receiver!.acceptedPackets != before) lastSignal = DateTime.now();
      if (receivedPackets % 20 == 0 || _receiver!.acceptedPackets != before) {
        notifyListeners();
      }
    });
    scanning = true;
    notifyListeners();
  }

  Future<void> _cycleScan(int session, String mode) async {
    if (mode == 'max') return;
    final window = Duration(seconds: mode == 'saver' ? 8 : 10);
    final pause = Duration(seconds: mode == 'saver' ? 112 : 50);
    while (running && session == _session) {
      await _delay(window);
      if (session != _session) return;
      try {
        await transport.stopScan();
        scanning = false;
        notifyListeners();
      } catch (e) {
        error = e.toString();
        notifyListeners();
      }
      await _delay(pause);
      if (session != _session) return;
      try {
        await _startScan();
      } catch (e) {
        error = e.toString();
        notifyListeners();
      }
    }
  }

  Future<void> _delay(Duration duration) => Future.any([
    Future<void>.delayed(duration),
    if (_wake != null) _wake!.future,
  ]);
  Future<void> _rotate(int session, AppStore store) async {
    var generation = Random.secure().nextInt(16);
    var refreshed = DateTime.fromMillisecondsSinceEpoch(0);
    List<Uint8List> packets = [];
    var index = 0;
    while (running && session == _session) {
      try {
        if (packets.isEmpty ||
            DateTime.now().difference(refreshed) >=
                const Duration(minutes: 5)) {
          generation = (generation + 1) & 15;
          packets = StreetPassCrypto.desktopProofPackets(
            store.identity,
            generation: generation,
          );
          final nickname = StreetPassCrypto.nicknameBytes(store.nickname);
          if (nickname.isNotEmpty) {
            packets.add(
              Uint8List.fromList([
                ...StreetPassCrypto.packetPrefix,
                0,
                ...store.identity.peerId,
                ...nickname,
              ]),
            );
          }
          refreshed = DateTime.now();
          index = 0;
        }
        await transport.advertise(packets[index]);
        broadcasting = true;
        notifyListeners();
        // Отсчитываем время кадра после подтверждённого запуска рекламы.
        await _delay(Duration(milliseconds: 550 + Random().nextInt(150)));
        await transport.stopAdvertising();
        broadcasting = false;
        index = (index + 1) % packets.length;
      } catch (e) {
        broadcasting = false;
        error = 'Broadcast: $e';
        notifyListeners();
        try {
          await transport.stopAdvertising();
        } catch (_) {}
        // Повтор не должен превращаться в нескончаемую очередь start/stop.
        await _delay(const Duration(seconds: 10));
      }
    }
  }

  Future<void> stop() async {
    running = false;
    _session++;
    if (_wake != null && !_wake!.isCompleted) _wake!.complete();
    await _availability?.cancel();
    _availability = null;
    await _rotation;
    _rotation = null;
    await _scanCycle;
    _scanCycle = null;
    try {
      await transport.stopScan();
    } catch (e) {
      error = e.toString();
    }
    try {
      await transport.stopAdvertising();
    } catch (e) {
      error = e.toString();
    }
    await transport.close();
    scanning = false;
    broadcasting = false;
    notifyListeners();
  }
}

class ProtocolReceiver {
  ProtocolReceiver(
    this.ownId,
    this.onSighting, {
    this.acceptUnsigned = false,
    this.onProof,
  });
  final String ownId;
  final void Function(Sighting) onSighting;
  final void Function(bool)? onProof;
  final bool acceptUnsigned;
  final _peers = <String, _PeerAssembly>{};
  final _carriers = <String, (String, DateTime)>{};
  int acceptedPackets = 0;

  void accept(BleDevice device, {DateTime? now}) {
    final time = now ?? DateTime.now();
    _peers.removeWhere(
      (id, peer) => time.difference(peer.touched) > const Duration(minutes: 10),
    );
    _carriers.removeWhere(
      (id, peer) => time.difference(peer.$2) > const Duration(seconds: 30),
    );
    while (_peers.length > 512) {
      _peers.remove(_peers.keys.first);
    }
    while (_carriers.length > 512) {
      _carriers.remove(_carriers.keys.first);
    }
    for (final data in device.manufacturerDataList) {
      final payload = data.payload;
      if (data.companyId != companyId ||
          payload.length < 12 ||
          payload[0] != 0x53 ||
          payload[1] != 0x50 ||
          payload[2] != 1) {
        continue;
      }
      final id = hex(payload.sublist(4, 12));
      if (id == ownId) continue;
      if (payload[3] == 0) {
        if (payload.length <= 12 || payload.length > 24) continue;
        try {
          _peer(id, time).nickname = utf8.decode(payload.sublist(12));
        } catch (_) {
          continue;
        }
        _sighting(id, time, device.rssi);
        continue;
      }
      if (payload[3] != 1 || payload.length < 14) continue;
      final index = payload[12] & 15;
      if (index >= 10 || payload.length != 13 + min(11, 102 - index * 11)) {
        continue;
      }
      acceptedPackets++;
      _proof(
        id,
        time,
        device.rssi,
        payload[12] >> 4,
        index,
        payload.sublist(13),
        11,
        10,
      );
    }
    final idBytes = device.serviceData[serviceUuid];
    String? id;
    if (idBytes != null && idBytes.length == 8) {
      id = hex(idBytes);
      // Адрес нужен только для связывания отдельного scan-response, не сохраняется.
      _carriers[device.deviceId] = (id, time);
    } else {
      id = _carriers[device.deviceId]?.$1;
    }
    if (id == null || id == ownId) return;
    final nickname = device.serviceData[nicknameUuid];
    if (nickname != null && nickname.length <= 24) {
      try {
        _peer(id, time).nickname = utf8.decode(nickname);
      } catch (_) {}
    }
    final frame = device.serviceData[proofUuid];
    if (frame != null && frame.length >= 2) {
      final index = frame[0] & 7;
      if (index < 4 && frame.length == 1 + min(26, 102 - index * 26)) {
        acceptedPackets++;
        _proof(
          id,
          time,
          device.rssi,
          frame[0] >> 3,
          index,
          frame.sublist(1),
          26,
          4,
        );
      }
    }
    _sighting(id, time, device.rssi);
  }

  _PeerAssembly _peer(String id, DateTime time) =>
      _peers.putIfAbsent(id, () => _PeerAssembly(time))..touched = time;
  void _proof(
    String id,
    DateTime time,
    int? rssi,
    int generation,
    int index,
    Uint8List bytes,
    int size,
    int count,
  ) {
    final peer = _peer(id, time);
    if (peer.generation != generation || peer.chunkSize != size) {
      peer.generation = generation;
      peer.chunkSize = size;
      peer.received = 0;
    }
    peer.buffer.setRange(index * size, index * size + bytes.length, bytes);
    peer.received |= 1 << index;
    if (peer.received != (1 << count) - 1) return;
    final valid = StreetPassCrypto.verifyProof(
      peer.buffer,
      Uint8List.fromList([
        for (var i = 0; i < id.length; i += 2)
          int.parse(id.substring(i, i + 2), radix: 16),
      ]),
      nowSeconds: time.millisecondsSinceEpoch ~/ 1000,
    );
    peer.received = 0;
    onProof?.call(valid);
    if (valid) {
      final timestamp = ByteData.sublistView(peer.buffer, 34, 38).getUint32(0);
      peer.verifiedAt = DateTime.fromMillisecondsSinceEpoch(timestamp * 1000);
      _sighting(id, time, rssi);
    }
  }

  void _sighting(String id, DateTime time, int? rssi) {
    final peer = _peer(id, time);
    final trusted =
        peer.verifiedAt != null &&
        time.difference(peer.verifiedAt!).abs() <= const Duration(minutes: 10);
    if (!trusted && !acceptUnsigned) return;
    if (peer.emitted != null &&
        time.difference(peer.emitted!) < const Duration(seconds: 10)) {
      return;
    }
    peer.emitted = time;
    onSighting(
      Sighting(id, time, rssi, nickname: peer.nickname, verified: trusted),
    );
  }
}

class _PeerAssembly {
  _PeerAssembly(this.touched);
  DateTime touched;
  int generation = -1;
  int chunkSize = 0;
  int received = 0;
  final buffer = Uint8List(102);
  DateTime? verifiedAt;
  DateTime? emitted;
  String? nickname;
}
