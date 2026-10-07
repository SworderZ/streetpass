import 'dart:convert';
import 'dart:io';
import 'dart:math';

import 'package:flutter/foundation.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'streetpass_crypto.dart';

class AppSettings {
  bool advertise = true;
  bool scan = true;
  bool autoStart = true;
  bool active = false;
  bool acceptUnsigned = false;
  bool notifyFriends = true;
  bool storeRssi = true;
  bool shareStats = false;
  bool hideUpdatePrompt = false;
  bool desktopAutoStart = false;
  bool startMinimized = false;
  bool closeToTray = true;
  int cooldownMinutes = 60;
  int minRssi = -95;
  String powerMode = 'balanced';
  String language = 'system';
  String country = '';

  AppSettings();
  AppSettings.fromJson(Map<String, dynamic> data) {
    advertise = data['advertise'] ?? true;
    scan = data['scan'] ?? true;
    autoStart = data['autoStart'] ?? true;
    active = data['active'] ?? false;
    acceptUnsigned = data['acceptUnsigned'] ?? false;
    notifyFriends = data['notifyFriends'] ?? true;
    storeRssi = data['storeRssi'] ?? true;
    shareStats = data['shareStats'] ?? false;
    hideUpdatePrompt = data['hideUpdatePrompt'] ?? false;
    desktopAutoStart = data['desktopAutoStart'] ?? false;
    startMinimized = data['startMinimized'] ?? false;
    closeToTray = data['closeToTray'] ?? true;
    cooldownMinutes = (data['cooldownMinutes'] as int? ?? 60).clamp(5, 720);
    minRssi = (data['minRssi'] as int? ?? -95).clamp(-100, -40);
    powerMode = data['powerMode'] ?? 'balanced';
    language = data['language'] ?? 'system';
    country = data['country'] ?? '';
  }
  Map<String, dynamic> toJson() => {
    'advertise': advertise,
    'scan': scan,
    'autoStart': autoStart,
    'active': active,
    'acceptUnsigned': acceptUnsigned,
    'notifyFriends': notifyFriends,
    'storeRssi': storeRssi,
    'shareStats': shareStats,
    'hideUpdatePrompt': hideUpdatePrompt,
    'desktopAutoStart': desktopAutoStart,
    'startMinimized': startMinimized,
    'closeToTray': closeToTray,
    'cooldownMinutes': cooldownMinutes,
    'minRssi': minRssi,
    'powerMode': powerMode,
    'language': language,
    'country': country,
  };
}

class Peer {
  Peer(
    this.id, {
    this.nickname = '',
    this.alias = '',
    this.friend = false,
    this.firstSeen,
    this.lastSeen,
    this.lastCounted,
    this.rssi,
    this.count = 0,
  });
  final String id;
  String nickname;
  String alias;
  bool friend;
  DateTime? firstSeen;
  DateTime? lastSeen;
  DateTime? lastCounted;
  int? rssi;
  int count;
  String get name => alias.isNotEmpty
      ? alias
      : nickname.isNotEmpty
      ? nickname
      : id.substring(0, min(8, id.length));
  factory Peer.fromJson(Map<String, dynamic> data) => Peer(
    data['id'],
    nickname: data['nickname'] ?? '',
    alias: data['alias'] ?? '',
    friend: data['friend'] ?? false,
    firstSeen: _date(data['firstSeen']),
    lastSeen: _date(data['lastSeen']),
    lastCounted: _date(data['lastCounted']),
    rssi: data['rssi'],
    count: data['count'] ?? 0,
  );
  Map<String, dynamic> toJson() => {
    'id': id,
    'nickname': nickname,
    'alias': alias,
    'friend': friend,
    'firstSeen': firstSeen?.toIso8601String(),
    'lastSeen': lastSeen?.toIso8601String(),
    'lastCounted': lastCounted?.toIso8601String(),
    'rssi': rssi,
    'count': count,
  };
}

class Encounter {
  Encounter(this.peerId, this.time, this.number, {this.rssi});
  final String peerId;
  final DateTime time;
  final int number;
  final int? rssi;
  factory Encounter.fromJson(Map<String, dynamic> data) => Encounter(
    data['peerId'],
    DateTime.parse(data['time']),
    data['number'],
    rssi: data['rssi'],
  );
  Map<String, dynamic> toJson() => {
    'peerId': peerId,
    'time': time.toIso8601String(),
    'number': number,
    'rssi': rssi,
  };
}

class Achievement {
  const Achievement(this.kind, this.threshold);
  final String kind;
  final int threshold;
  String get id => '${kind}_$threshold';
  static final all = [
    for (final n in [5, 15, 30, 50, 100]) Achievement('people', n),
    for (final n in [10, 50, 100, 500]) Achievement('encounters', n),
    for (final n in [1, 5, 10]) Achievement('friends', n),
    for (final n in [10, 25, 50, 100]) Achievement('friend_encounters', n),
    for (final n in [7, 30]) Achievement('streak', n),
  ];
}

class AppStore extends ChangeNotifier {
  AppStore(this.prefs);
  final SharedPreferences prefs;
  static const storageKey = 'streetpass_data_v2';
  AppSettings settings = AppSettings();
  late StreetPassIdentity identity;
  String nickname = '';
  String installationId = '';
  final peers = <String, Peer>{};
  final encounters = <Encounter>[];
  final unlocked = <String, DateTime>{};
  final seenAchievements = <String>{};
  Future<void> _pending = Future.value();

  String get ownId => hex(identity.peerId);
  List<Peer> get friends =>
      peers.values.where((peer) => peer.friend).toList()
        ..sort((a, b) => a.name.compareTo(b.name));
  List<Peer> nearby(DateTime now) =>
      peers.values
          .where(
            (peer) =>
                peer.lastSeen != null &&
                now.difference(peer.lastSeen!) <= const Duration(minutes: 3),
          )
          .toList()
        ..sort((a, b) => b.lastSeen!.compareTo(a.lastSeen!));
  List<Encounter> between(DateTime from, DateTime until) => encounters
      .where((e) => !e.time.isBefore(from) && e.time.isBefore(until))
      .toList();
  int people(Iterable<Encounter> items) =>
      items.map((e) => e.peerId).toSet().length;
  int metric(String kind) => switch (kind) {
    'people' => peers.values.where((p) => p.count > 0).length,
    'encounters' => encounters.length,
    'friends' => friends.length,
    'friend_encounters' => friends.fold(0, (value, p) => max(value, p.count)),
    'streak' => _streak(),
    _ => 0,
  };
  int _streak() {
    final days =
        encounters
            .map((e) => DateTime(e.time.year, e.time.month, e.time.day))
            .toSet()
            .toList()
          ..sort();
    var longest = 0;
    var current = 0;
    DateTime? previous;
    for (final day in days) {
      current =
          previous != null &&
              day.difference(previous).inHours >= 23 &&
              day.difference(previous).inHours <= 25
          ? current + 1
          : 1;
      longest = max(longest, current);
      previous = day;
    }
    return longest;
  }

  Future<void> initialize() async {
    await reload();
    await save();
  }

  void changed() => notifyListeners();

  Future<void> reload() async {
    await prefs.reload();
    final raw = prefs.getString(storageKey);
    final data = raw == null
        ? <String, dynamic>{}
        : jsonDecode(raw) as Map<String, dynamic>;
    final scalar =
        data['privateScalar'] as String? ??
        prefs.getString('identity_private_scalar');
    identity = scalar == null
        ? StreetPassCrypto.create()
        : StreetPassCrypto.fromPrivateScalarHex(scalar);
    nickname = data['nickname'] ?? prefs.getString('nickname') ?? '';
    settings = AppSettings.fromJson(
      Map<String, dynamic>.from(data['settings'] ?? {}),
    );
    if (settings.country.isEmpty) {
      final locale = Platform.localeName.split(RegExp('[_-]'));
      settings.country = locale.length > 1 ? locale[1].toUpperCase() : '';
    }
    installationId =
        data['installationId'] ??
        List.generate(
          32,
          (_) => Random.secure().nextInt(16).toRadixString(16),
        ).join();
    peers.clear();
    for (final row in data['peers'] ?? []) {
      final peer = Peer.fromJson(Map<String, dynamic>.from(row));
      peers[peer.id] = peer;
    }
    encounters
      ..clear()
      ..addAll(
        (data['encounters'] as List? ?? []).map(
          (row) => Encounter.fromJson(Map<String, dynamic>.from(row)),
        ),
      );
    unlocked.clear();
    (data['unlocked'] as Map? ?? {}).forEach(
      (id, time) => unlocked[id as String] = DateTime.parse(time),
    );
    seenAchievements
      ..clear()
      ..addAll(List<String>.from(data['seenAchievements'] ?? []));
    notifyListeners();
  }

  Future<void> save() {
    checkAchievements();
    final raw = jsonEncode({
      'privateScalar': StreetPassCrypto.privateScalarHex(identity),
      'nickname': nickname,
      'settings': settings.toJson(),
      'installationId': installationId,
      'peers': peers.values.map((p) => p.toJson()).toList(),
      'encounters': encounters.map((e) => e.toJson()).toList(),
      'unlocked': unlocked.map(
        (id, time) => MapEntry(id, time.toIso8601String()),
      ),
      'seenAchievements': seenAchievements.toList(),
    });
    _pending = _pending.catchError((_) {}).then((_) async {
      if (!await prefs.setString(storageKey, raw)) {
        throw StateError('Could not save local history');
      }
    });
    notifyListeners();
    return _pending;
  }

  bool record(String id, DateTime time, {String? name, int? rssi}) {
    if (id == ownId || (rssi != null && rssi < settings.minRssi)) return false;
    final peer = peers.putIfAbsent(id, () => Peer(id));
    if (name != null && name.isNotEmpty) peer.nickname = name;
    peer.firstSeen ??= time;
    peer.lastSeen = time;
    peer.rssi = settings.storeRssi ? rssi : null;
    if (peer.lastCounted != null &&
        time.difference(peer.lastCounted!) <
            Duration(minutes: settings.cooldownMinutes)) {
      return false;
    }
    peer.count++;
    peer.lastCounted = time;
    encounters.insert(0, Encounter(id, time, peer.count, rssi: peer.rssi));
    checkAchievements(time);
    return true;
  }

  void checkAchievements([DateTime? now]) {
    for (final item in Achievement.all) {
      if (metric(item.kind) >= item.threshold) {
        unlocked.putIfAbsent(item.id, () => now ?? DateTime.now());
      }
    }
  }

  Future<void> editPeer(
    String id, {
    String? alias,
    bool? friend,
    String? nickname,
  }) async {
    final peer = peers.putIfAbsent(id, () => Peer(id));
    if (alias != null) peer.alias = alias.trim();
    if (friend != null) peer.friend = friend;
    if (nickname != null) peer.nickname = nickname;
    await save();
  }

  Future<void> clearHistory() async {
    encounters.clear();
    peers.removeWhere((id, peer) => !peer.friend);
    for (final peer in peers.values) {
      peer.count = 0;
      peer.firstSeen = null;
      peer.lastSeen = null;
      peer.lastCounted = null;
      peer.rssi = null;
    }
    await save();
  }

  Future<void> rotateIdentity() async {
    identity = StreetPassCrypto.create();
    await save();
  }
}

String hex(List<int> bytes) =>
    bytes.map((b) => b.toRadixString(16).padLeft(2, '0')).join();
DateTime? _date(dynamic value) => value == null ? null : DateTime.parse(value);
