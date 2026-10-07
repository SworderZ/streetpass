import 'dart:convert';
import 'dart:math';
import 'dart:typed_data';

import 'package:pointycastle/export.dart';

class StreetPassIdentity {
  StreetPassIdentity(this.privateKey, this.publicKey, this.peerId);
  final ECPrivateKey privateKey;
  final ECPublicKey publicKey;
  final Uint8List peerId;
}

class StreetPassCrypto {
  static final _domain = ascii.encode('StreetPass-ID-v1');
  static final _curve = ECCurve_secp256r1();

  static StreetPassIdentity create() {
    final random = _random();
    final generator = ECKeyGenerator()
      ..init(ParametersWithRandom(ECKeyGeneratorParameters(_curve), random));
    final pair = generator.generateKeyPair();
    final privateKey = pair.privateKey;
    final publicKey = pair.publicKey;
    return _identity(privateKey, publicKey);
  }

  static StreetPassIdentity fromPrivateScalarHex(String value) {
    final scalar = BigInt.parse(value, radix: 16);
    if (scalar <= BigInt.zero || scalar >= _curve.n) {
      throw const FormatException('Invalid P-256 private scalar');
    }
    final privateKey = ECPrivateKey(scalar, _curve);
    final publicKey = ECPublicKey(_curve.G * scalar, _curve);
    return _identity(privateKey, publicKey);
  }

  static String privateScalarHex(StreetPassIdentity identity) =>
      identity.privateKey.d!.toRadixString(16).padLeft(64, '0');

  static StreetPassIdentity _identity(
    ECPrivateKey privateKey,
    ECPublicKey publicKey,
  ) {
    final compressed = Uint8List.fromList(publicKey.Q!.getEncoded(true));
    return StreetPassIdentity(privateKey, publicKey, _id(compressed));
  }

  static Uint8List proof(StreetPassIdentity identity, {int? timestamp}) {
    final publicKey = Uint8List.fromList(
      identity.publicKey.Q!.getEncoded(true),
    );
    final seconds = timestamp ?? DateTime.now().millisecondsSinceEpoch ~/ 1000;
    final stamp = _u32(seconds);
    final message = Uint8List.fromList([..._domain, 1, ...publicKey, ...stamp]);
    final digest = SHA256Digest().process(message);
    final signer = ECDSASigner()
      ..init(
        true,
        ParametersWithRandom(
          PrivateKeyParameter<ECPrivateKey>(identity.privateKey),
          _random(),
        ),
      );
    final signature = signer.generateSignature(digest) as ECSignature;
    final raw = <int>[..._bigInt(signature.r), ..._bigInt(signature.s)];
    return Uint8List.fromList([1, ...publicKey, ...stamp, ...raw]);
  }

  static List<Uint8List> desktopProofPackets(
    StreetPassIdentity identity, {
    int generation = 0,
    int? timestamp,
  }) {
    final proofBytes = proof(identity, timestamp: timestamp);
    return List.generate(10, (index) {
      final from = index * 11;
      final length = (102 - from) < 11 ? (102 - from) : 11;
      final header = ((generation & 15) << 4) | index;
      return Uint8List.fromList([
        ...packetPrefix,
        1,
        ...identity.peerId,
        header,
        ...proofBytes.sublist(from, from + length),
      ]);
    });
  }

  static final packetPrefix = const [0x53, 0x50, 0x01];

  static Uint8List nicknameBytes(String value, {int maxBytes = 12}) {
    final output = <int>[];
    final clean = value
        .replaceAll(RegExp(r'[\x00-\x1f\x7f]'), '')
        .trim()
        .replaceAll(RegExp(r'\s+'), ' ');
    for (final rune in clean.runes) {
      final piece = utf8.encode(String.fromCharCode(rune));
      if (output.length + piece.length > maxBytes) break;
      output.addAll(piece);
    }
    return Uint8List.fromList(utf8.encode(utf8.decode(output).trim()));
  }

  static String invite(StreetPassIdentity identity, String nickname) {
    final bytes = nicknameBytes(nickname, maxBytes: 24);
    final body = Uint8List.fromList([
      1,
      ...identity.publicKey.Q!.getEncoded(true),
      bytes.length,
      ...bytes,
    ]);
    final signer = ECDSASigner(SHA256Digest(), HMac(SHA256Digest(), 64))
      ..init(true, PrivateKeyParameter<ECPrivateKey>(identity.privateKey));
    final signature = signer.generateSignature(
      Uint8List.fromList([...ascii.encode('StreetPass-invite-v1'), ...body]),
    ) as ECSignature;
    final payload = base64Url
        .encode([...body, ..._bigInt(signature.r), ..._bigInt(signature.s)])
        .replaceAll('=', '');
    return 'streetpass://friend?invite=$payload';
  }

  static ({String peerId, String nickname})? parseInvite(String text) {
    final match = RegExp(r'(?:[#?&])invite=([A-Za-z0-9_-]+)').firstMatch(text);
    if (match == null || text.length > 8192) return null;
    try {
      final bytes = base64Url.decode(base64Url.normalize(match[1]!));
      if (bytes.length < 99 || bytes[0] != 1) return null;
      final count = bytes[34];
      final bodyLength = 35 + count;
      if (count > 24 || bytes.length != bodyLength + 64) return null;
      final key = bytes.sublist(1, 34);
      if (key[0] != 2 && key[0] != 3) return null;
      final point = _curve.curve.decodePoint(key);
      if (point == null || point.isInfinity) return null;
      final signature = ECSignature(
        _fromBytes(bytes.sublist(bodyLength, bodyLength + 32)),
        _fromBytes(bytes.sublist(bodyLength + 32)),
      );
      final verifier = ECDSASigner(SHA256Digest())
        ..init(false, PublicKeyParameter(ECPublicKey(point, _curve)));
      if (!verifier.verifySignature(
        Uint8List.fromList([
          ...ascii.encode('StreetPass-invite-v1'),
          ...bytes.sublist(0, bodyLength),
        ]),
        signature,
      )) {
        return null;
      }
      final nickname = utf8.decode(bytes.sublist(35, bodyLength));
      return (
        peerId: _id(key).map((b) => b.toRadixString(16).padLeft(2, '0')).join(),
        nickname: nickname,
      );
    } catch (_) {
      return null;
    }
  }

  static bool verifyProof(
    Uint8List proof,
    Uint8List expectedPeerId, {
    int? nowSeconds,
    int maxSkewSeconds = 600,
  }) {
    if (proof.length != 102 || proof.first != 1 || expectedPeerId.length != 8) {
      return false;
    }
    final publicKeyBytes = proof.sublist(1, 34);
    if (publicKeyBytes[0] != 2 && publicKeyBytes[0] != 3) return false;
    final calculatedPeerId = _id(publicKeyBytes);
    for (var i = 0; i < calculatedPeerId.length; i++) {
      if (calculatedPeerId[i] != expectedPeerId[i]) return false;
    }
    final timestamp = _readU32(proof.sublist(34, 38));
    final current = nowSeconds ?? DateTime.now().millisecondsSinceEpoch ~/ 1000;
    if ((current - timestamp).abs() > maxSkewSeconds) return false;

    try {
      final point = _curve.curve.decodePoint(publicKeyBytes);
      if (point == null) return false;
      final publicKey = ECPublicKey(point, _curve);
      final message = Uint8List.fromList([
        ..._domain,
        1,
        ...publicKeyBytes,
        ...proof.sublist(34, 38),
      ]);
      final signature = ECSignature(
        _fromBytes(proof.sublist(38, 70)),
        _fromBytes(proof.sublist(70, 102)),
      );
      final verifier = ECDSASigner()
        ..init(false, PublicKeyParameter<ECPublicKey>(publicKey));
      return verifier.verifySignature(
        SHA256Digest().process(message),
        signature,
      );
    } catch (_) {
      return false;
    }
  }

  static SecureRandom _random() {
    final random = FortunaRandom();
    final source = Random.secure();
    final seed = Uint8List.fromList(
      List.generate(32, (_) => source.nextInt(256)),
    );
    random.seed(KeyParameter(seed));
    return random;
  }

  static Uint8List _id(List<int> publicKey) => Uint8List.fromList(
    SHA256Digest().process(Uint8List.fromList(publicKey)).sublist(0, 8),
  );
  static List<int> _u32(int value) => [
    (value >> 24) & 0xff,
    (value >> 16) & 0xff,
    (value >> 8) & 0xff,
    value & 0xff,
  ];

  static int _readU32(List<int> bytes) =>
      (bytes[0] << 24) | (bytes[1] << 16) | (bytes[2] << 8) | bytes[3];

  static BigInt _fromBytes(List<int> bytes) {
    var value = BigInt.zero;
    for (final byte in bytes) {
      value = (value << 8) | BigInt.from(byte);
    }
    return value;
  }

  static List<int> _bigInt(BigInt value) {
    final hex = value.toRadixString(16).padLeft(64, '0');
    return List.generate(
      32,
      (i) => int.parse(hex.substring(i * 2, i * 2 + 2), radix: 16),
    );
  }
}
