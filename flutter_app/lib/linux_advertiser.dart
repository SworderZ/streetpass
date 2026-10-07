import 'dart:typed_data';

import 'package:dbus/dbus.dart';

const _managerInterface = 'org.bluez.LEAdvertisingManager1';
const _advertisementInterface = 'org.bluez.LEAdvertisement1';

class LinuxAdvertiser {
  DBusClient? _bus;
  DBusRemoteObject? _manager;
  _Advertisement? _advertisement;
  int _sequence = 0;

  Future<void> initialize() async {
    _bus ??= DBusClient.system();
    final objects = DBusRemoteObject(
      _bus!,
      name: 'org.bluez',
      path: DBusObjectPath('/'),
    );
    final response = await objects.callMethod(
      'org.freedesktop.DBus.ObjectManager',
      'GetManagedObjects',
      [],
      replySignature: DBusSignature('a{oa{sa{sv}}}'),
    );
    final managed = response.returnValues.first as DBusDict;
    for (final entry in managed.children.entries) {
      final interfaces = entry.value as DBusDict;
      if (interfaces.children.keys.any(
        (key) => key.asString() == _managerInterface,
      )) {
        final adapter = DBusRemoteObject(
          _bus!,
          name: 'org.bluez',
          path: entry.key as DBusObjectPath,
        );
        final powered = await adapter.getProperty(
          'org.bluez.Adapter1',
          'Powered',
        );
        if (!powered.asBoolean()) continue;
        _manager = adapter;
        return;
      }
    }
    throw StateError('BlueZ: no powered adapter with LEAdvertisingManager1');
  }

  Future<void> publish(Uint8List payload) async {
    if (_manager == null) await initialize();
    await stopPacket();
    final object = _Advertisement(
      DBusObjectPath('/space/megaworld/streetpass/ad${_sequence++}'),
      payload,
    );
    await _bus!.registerObject(object);
    try {
      await _manager!.callMethod(_managerInterface, 'RegisterAdvertisement', [
        object.path,
        DBusDict.stringVariant({}),
      ]);
      _advertisement = object;
    } catch (_) {
      await _bus!.unregisterObject(object);
      rethrow;
    }
  }

  Future<void> stopPacket() async {
    final object = _advertisement;
    _advertisement = null;
    if (object == null) return;
    try {
      await _manager?.callMethod(_managerInterface, 'UnregisterAdvertisement', [
        object.path,
      ]);
    } finally {
      await _bus?.unregisterObject(object);
    }
  }

  Future<void> close() async {
    try {
      await stopPacket();
    } finally {
      await _bus?.close();
      _bus = null;
      _manager = null;
    }
  }
}

class _Advertisement extends DBusObject {
  _Advertisement(super.path, this.payload);
  final Uint8List payload;
  Map<String, DBusValue> get properties => {
    'Type': DBusString('broadcast'),
    'ManufacturerData': DBusDict(DBusSignature('q'), DBusSignature('v'), {
      DBusUint16(0xffff): DBusVariant(DBusArray.byte(payload)),
    }),
  };
  @override
  List<DBusIntrospectInterface> introspect() => [
    DBusIntrospectInterface(
      _advertisementInterface,
      methods: [DBusIntrospectMethod('Release')],
      properties: [
        DBusIntrospectProperty(
          'Type',
          DBusSignature('s'),
          access: DBusPropertyAccess.read,
        ),
        DBusIntrospectProperty(
          'ManufacturerData',
          DBusSignature('a{qv}'),
          access: DBusPropertyAccess.read,
        ),
      ],
    ),
  ];
  @override
  Future<DBusMethodResponse> getAllProperties(String interface) async =>
      interface == _advertisementInterface
      ? DBusGetAllPropertiesResponse(properties)
      : DBusMethodErrorResponse.unknownInterface();
  @override
  Future<DBusMethodResponse> getProperty(String interface, String name) async {
    final value = interface == _advertisementInterface
        ? properties[name]
        : null;
    return value == null
        ? DBusMethodErrorResponse.unknownProperty()
        : DBusGetPropertyResponse(value);
  }

  @override
  Future<DBusMethodResponse> handleMethodCall(DBusMethodCall call) async =>
      call.interface == _advertisementInterface && call.name == 'Release'
      ? DBusMethodSuccessResponse()
      : DBusMethodErrorResponse.unknownMethod();
}
