"""StreetPass BLE client for Windows and Linux."""

from __future__ import annotations

import argparse
import asyncio
import logging
import signal
import sqlite3
import time
from pathlib import Path

from desktop_transport import DesktopAdvertiser, DesktopScanner
from streetpass_protocol import DesktopProofAssembler, create_identity, desktop_proof_chunks, sign_proof, verify_proof

COOLDOWN_SECONDS = 60 * 60
LOG = logging.getLogger("streetpass")


class EncounterStore:
    def __init__(self, path: Path) -> None:
        self.db = sqlite3.connect(path, check_same_thread=False)
        self.db.execute("CREATE TABLE IF NOT EXISTS encounters (peer_id TEXT PRIMARY KEY, last_seen INTEGER NOT NULL, count INTEGER NOT NULL DEFAULT 1, nickname TEXT)")
        self.db.commit()

    def record(self, peer_id: bytes, nickname: str | None) -> bool:
        key, now = peer_id.hex(), int(time.time())
        row = self.db.execute("SELECT last_seen,count FROM encounters WHERE peer_id=?", (key,)).fetchone()
        if row and now - row[0] < COOLDOWN_SECONDS:
            return False
        if row:
            self.db.execute("UPDATE encounters SET last_seen=?,count=count+1,nickname=? WHERE peer_id=?", (now, nickname, key))
        else:
            self.db.execute("INSERT INTO encounters(peer_id,last_seen,nickname) VALUES(?,?,?)", (key, now, nickname))
        self.db.commit()
        return True


class StreetPassService:
    def __init__(self, data_dir: Path, nickname: str) -> None:
        self.data_dir, self.nickname = data_dir, nickname[:24]
        self.data_dir.mkdir(parents=True, exist_ok=True)
        self.private_key, self.public_key, self.peer_id = self._identity()
        self.store = EncounterStore(data_dir / "streetpass.db")
        self.assemblers: dict[str, DesktopProofAssembler] = {}
        proof = sign_proof(self.private_key)
        self.scanner = DesktopScanner(self.on_packet)
        self.advertiser = DesktopAdvertiser(self.peer_id, desktop_proof_chunks(proof), self.nickname)
        self.stop_event = asyncio.Event()

    def _identity(self):
        from cryptography.hazmat.primitives import serialization
        from cryptography.hazmat.primitives.serialization import Encoding, NoEncryption, PrivateFormat
        from streetpass_protocol import compressed_public_key, peer_id
        path = self.data_dir / "identity.pem"
        if path.exists():
            key = serialization.load_pem_private_key(path.read_bytes(), password=None)
            public = compressed_public_key(key.public_key())
            return key, public, peer_id(public)
        key, public, identifier = create_identity()
        path.write_bytes(key.private_bytes(Encoding.PEM, PrivateFormat.PKCS8, NoEncryption()))
        return key, public, identifier

    async def on_packet(self, peer_id: bytes, rssi: int, nickname: str | None, frame: bytes | None) -> None:
        if peer_id == self.peer_id or frame is None:
            return
        key = peer_id.hex()
        assembler = self.assemblers.setdefault(key, DesktopProofAssembler())
        proof = assembler.accept(frame)
        if proof is not None and verify_proof(proof, peer_id) and self.store.record(peer_id, nickname):
            LOG.info("Встреча с %s, RSSI %d%s", key, rssi, f", ник: {nickname}" if nickname else "")

    async def run(self) -> None:
        await self.scanner.start()
        advertiser_task = asyncio.create_task(self.advertiser.run())
        LOG.info("StreetPass запущен, ID %s", self.peer_id.hex())
        try:
            await self.stop_event.wait()
        finally:
            await self.scanner.stop()
            await self.advertiser.stop()
            advertiser_task.cancel()


def main() -> None:
    parser = argparse.ArgumentParser(description="StreetPass BLE client for Windows/Linux")
    parser.add_argument("--data-dir", type=Path, default=Path.home() / ".streetpass")
    parser.add_argument("--nickname", default="")
    parser.add_argument("--log-level", default="INFO")
    args = parser.parse_args()
    logging.basicConfig(level=getattr(logging, args.log_level.upper(), logging.INFO), format="%(asctime)s %(levelname)s %(message)s")
    service = StreetPassService(args.data_dir, args.nickname)
    loop = asyncio.new_event_loop()
    asyncio.set_event_loop(loop)
    for sig in (signal.SIGINT, signal.SIGTERM):
        loop.add_signal_handler(sig, service.stop_event.set)
    try:
        loop.run_until_complete(service.run())
    finally:
        loop.close()


if __name__ == "__main__":
    main()
