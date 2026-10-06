"""Platform independent StreetPass BLE packet and proof primitives."""

from __future__ import annotations

import hashlib
import struct
import time
from dataclasses import dataclass, field

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.asymmetric.utils import decode_dss_signature, encode_dss_signature
from cryptography.hazmat.primitives.serialization import Encoding, PublicFormat

SERVICE_UUID = "00005350-0000-1000-8000-00805f9b34fb"
NICKNAME_UUID = "00005351-0000-1000-8000-00805f9b34fb"
PROOF_UUID = "00005352-0000-1000-8000-00805f9b34fb"
PROOF_BYTES = 102
PROOF_FRAME_BYTES = 27
CHUNK_DATA_BYTES = PROOF_FRAME_BYTES - 1
CHUNK_COUNT = (PROOF_BYTES + CHUNK_DATA_BYTES - 1) // CHUNK_DATA_BYTES
DESKTOP_MAGIC = b"SP"
DESKTOP_VERSION = 1
DESKTOP_COMPANY_ID = 0xFFFF
DESKTOP_HEADER_BYTES = 2 + 1 + 1 + 8
DESKTOP_CHUNK_DATA_BYTES = 14
DOMAIN = b"StreetPass-ID-v1"


def compressed_public_key(public_key: ec.EllipticCurvePublicKey) -> bytes:
    return public_key.public_bytes(Encoding.X962, PublicFormat.CompressedPoint)


def peer_id(public_key_bytes: bytes) -> bytes:
    return hashlib.sha256(public_key_bytes).digest()[:8]


def create_identity() -> tuple[ec.EllipticCurvePrivateKey, bytes, bytes]:
    private = ec.generate_private_key(ec.SECP256R1())
    public = compressed_public_key(private.public_key())
    return private, public, peer_id(public)


def sign_proof(private: ec.EllipticCurvePrivateKey, timestamp: int | None = None) -> bytes:
    public = compressed_public_key(private.public_key())
    stamp = int(time.time() if timestamp is None else timestamp).to_bytes(4, "big")
    message = DOMAIN + b"\x01" + public + stamp
    der = private.sign(message, ec.ECDSA(hashes.SHA256()))
    r, s = decode_dss_signature(der)
    signature = r.to_bytes(32, "big") + s.to_bytes(32, "big")
    return b"\x01" + public + stamp + signature


def verify_proof(proof: bytes, expected_id: bytes, now: int | None = None, max_skew: int = 600) -> bool:
    if len(proof) != PROOF_BYTES or proof[0] != 1:
        return False
    public = proof[1:34]
    stamp = proof[34:38]
    if peer_id(public) != expected_id:
        return False
    if abs(int(time.time() if now is None else now) - int.from_bytes(stamp, "big")) > max_skew:
        return False
    try:
        key = ec.EllipticCurvePublicKey.from_encoded_point(ec.SECP256R1(), public)
        r = int.from_bytes(proof[38:70], "big")
        s = int.from_bytes(proof[70:102], "big")
        key.verify(encode_dss_signature(r, s), DOMAIN + b"\x01" + public + stamp, ec.ECDSA(hashes.SHA256()))
        return True
    except (ValueError, TypeError):
        return False


def proof_chunks(proof: bytes, generation: int) -> list[bytes]:
    if len(proof) != PROOF_BYTES:
        raise ValueError("proof must contain 102 bytes")
    result = []
    for index in range(CHUNK_COUNT):
        start = index * CHUNK_DATA_BYTES
        length = min(CHUNK_DATA_BYTES, PROOF_BYTES - start)
        header = ((generation & 0x1F) << 3) | index
        result.append(bytes([header]) + proof[start : start + length])
    return result


@dataclass
class ProofAssembler:
    generation: int = -1
    received: int = 0
    buffer: bytearray = field(default_factory=lambda: bytearray(PROOF_BYTES))

    def accept(self, frame: bytes) -> bytes | None:
        if len(frame) < 2 or len(frame) > PROOF_FRAME_BYTES:
            return None
        index = frame[0] & 0x07
        generation = frame[0] >> 3
        if index >= CHUNK_COUNT:
            return None
        length = min(CHUNK_DATA_BYTES, PROOF_BYTES - index * CHUNK_DATA_BYTES)
        if len(frame) - 1 != length:
            return None
        if generation != self.generation:
            self.generation, self.received = generation, 0
            self.buffer = bytearray(PROOF_BYTES)
        self.buffer[index * CHUNK_DATA_BYTES : index * CHUNK_DATA_BYTES + length] = frame[1:]
        self.received |= 1 << index
        if self.received == (1 << CHUNK_COUNT) - 1:
            return bytes(self.buffer)
        return None


def desktop_proof_chunks(proof: bytes, generation: int = 0) -> list[bytes]:
    """Split proof for a 27-byte Windows manufacturer-data envelope."""
    if len(proof) != PROOF_BYTES:
        raise ValueError("proof must contain 102 bytes")
    result = []
    count = (PROOF_BYTES + DESKTOP_CHUNK_DATA_BYTES - 1) // DESKTOP_CHUNK_DATA_BYTES
    for index in range(count):
        start = index * DESKTOP_CHUNK_DATA_BYTES
        result.append(bytes([((generation & 0x0F) << 4) | index]) + proof[start : start + DESKTOP_CHUNK_DATA_BYTES])
    return result


@dataclass
class DesktopProofAssembler:
    generation: int = -1
    received: int = 0
    buffer: bytearray = field(default_factory=lambda: bytearray(PROOF_BYTES))

    def accept(self, frame: bytes) -> bytes | None:
        if len(frame) < 2:
            return None
        index = frame[0] & 0x0F
        generation = frame[0] >> 4
        count = (PROOF_BYTES + DESKTOP_CHUNK_DATA_BYTES - 1) // DESKTOP_CHUNK_DATA_BYTES
        if index >= count:
            return None
        expected = min(DESKTOP_CHUNK_DATA_BYTES, PROOF_BYTES - index * DESKTOP_CHUNK_DATA_BYTES)
        if len(frame) - 1 != expected:
            return None
        if generation != self.generation:
            self.generation, self.received = generation, 0
            self.buffer = bytearray(PROOF_BYTES)
        start = index * DESKTOP_CHUNK_DATA_BYTES
        self.buffer[start : start + expected] = frame[1:]
        self.received |= 1 << index
        return bytes(self.buffer) if self.received == (1 << count) - 1 else None


@dataclass(frozen=True)
class DesktopPacket:
    peer_id: bytes
    nickname: str | None = None
    proof_frame: bytes | None = None


def desktop_packet(peer: bytes, *, frame: bytes | None = None, nickname: str = "") -> bytes:
    if len(peer) != 8:
        raise ValueError("peer ID must contain 8 bytes")
    if frame is not None and nickname:
        raise ValueError("a packet carries either proof or nickname")
    if frame is not None:
        payload = DESKTOP_MAGIC + bytes([DESKTOP_VERSION, 1]) + peer + frame
    else:
        encoded = nickname.encode("utf-8")[:15]
        payload = DESKTOP_MAGIC + bytes([DESKTOP_VERSION, 0]) + peer + encoded
    if len(payload) > 27:
        raise ValueError("manufacturer packet exceeds 27 bytes")
    return payload


def parse_desktop_packet(company_id: int, payload: bytes) -> DesktopPacket | None:
    if company_id != DESKTOP_COMPANY_ID or len(payload) < DESKTOP_HEADER_BYTES:
        return None
    if payload[:2] != DESKTOP_MAGIC or payload[2] != DESKTOP_VERSION:
        return None
    peer = payload[4:12]
    kind = payload[3]
    body = payload[12:]
    if kind == 1:
        return DesktopPacket(peer_id=peer, proof_frame=body)
    if kind == 0 and len(body) <= 15:
        try:
            nickname = body.decode("utf-8").strip() or None
        except UnicodeDecodeError:
            return None
        return DesktopPacket(peer_id=peer, nickname=nickname)
    return None


def service_data(uuid16: int, payload: bytes) -> tuple[str, bytes]:
    """Return the UUID/payload pair expected by native BLE adapters."""
    return f"0000{uuid16:04x}-0000-1000-8000-00805f9b34fb", payload
