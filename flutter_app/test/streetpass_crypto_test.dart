import 'dart:convert';
import 'dart:typed_data';

import 'package:flutter_test/flutter_test.dart';
import 'package:pointycastle/export.dart';
import 'package:streetpass_cross_platform/streetpass_crypto.dart';

void main() {
  test('creates an Android compatible proof and desktop envelopes', () {
    final identity = StreetPassCrypto.create();
    final proof = StreetPassCrypto.proof(identity, timestamp: 1234567890);
    final packets = StreetPassCrypto.desktopProofPackets(identity);

    expect(proof, hasLength(102));
    expect(proof.first, 1);
    expect(
      StreetPassCrypto.verifyProof(
        proof,
        identity.peerId,
        nowSeconds: 1234567890,
      ),
      isTrue,
    );
    expect(packets, hasLength(10));
    expect(packets.first.sublist(0, 12), [
      0x53,
      0x50,
      0x01,
      1,
      ...identity.peerId,
    ]);
    expect(packets.first.length, 24);
    expect(packets.last.length, 16);

    final publicKey = Uint8List.fromList(
      identity.publicKey.Q!.getEncoded(true),
    );
    final stamp = proof.sublist(34, 38);
    final message = Uint8List.fromList([
      ...ascii.encode('StreetPass-ID-v1'),
      1,
      ...publicKey,
      ...stamp,
    ]);
    final signature = ECSignature(
      _bigInt(proof.sublist(38, 70)),
      _bigInt(proof.sublist(70, 102)),
    );
    final verifier = ECDSASigner()
      ..init(false, PublicKeyParameter(identity.publicKey));

    expect(
      verifier.verifySignature(SHA256Digest().process(message), signature),
      isTrue,
    );
  });
}

BigInt _bigInt(List<int> bytes) {
  var value = BigInt.zero;
  for (final byte in bytes) {
    value = (value << 8) | BigInt.from(byte);
  }
  return value;
}
