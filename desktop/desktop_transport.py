"""Cross-platform BLE transport for Windows and Linux desktop clients."""

from __future__ import annotations

import asyncio
import logging
import platform
from typing import Awaitable, Callable

from bleak import BleakScanner

from streetpass_protocol import parse_desktop_packet

LOG = logging.getLogger("streetpass.ble")
PacketCallback = Callable[[bytes, int, str | None, bytes | None], Awaitable[None]]


class DesktopScanner:
    def __init__(self, callback: PacketCallback) -> None:
        self.callback = callback
        self.scanner: BleakScanner | None = None

    async def start(self) -> None:
        # Manufacturer data is not filterable by a portable Bleak API, so the
        # small StreetPass envelope is validated after every received packet.
        self.scanner = BleakScanner(detection_callback=self._on_detection)
        await self.scanner.start()

    async def stop(self) -> None:
        if self.scanner is not None:
            await self.scanner.stop()
            self.scanner = None

    async def _on_detection(self, _device, data) -> None:
        for company_id, payload in data.manufacturer_data.items():
            packet = parse_desktop_packet(company_id, bytes(payload))
            if packet is not None:
                await self.callback(packet.peer_id, data.rssi or -100, packet.nickname, packet.proof_frame)


class DesktopAdvertiser:
    """Rotate proof chunks as manufacturer packets on Windows and Linux."""

    def __init__(self, peer_id: bytes, proof_frames: list[bytes], nickname: str = "") -> None:
        self.peer_id = peer_id
        self.proof_frames = proof_frames
        self.nickname = nickname
        self._stop = asyncio.Event()
        self._publisher = None

    async def run(self) -> None:
        if platform.system() == "Windows":
            await self._run_windows()
        elif platform.system() == "Linux":
            await self._run_linux()
        else:
            raise RuntimeError("StreetPass Desktop поддерживает только Windows и Linux")

    async def stop(self) -> None:
        self._stop.set()
        if self._publisher is not None:
            await self._publisher.stop()
            self._publisher = None

    def _packets(self) -> list[bytes]:
        from streetpass_protocol import desktop_packet
        packets = [desktop_packet(self.peer_id, frame=frame) for frame in self.proof_frames]
        if self.nickname:
            packets.append(desktop_packet(self.peer_id, nickname=self.nickname))
        return packets

    async def _run_windows(self) -> None:
        try:
            from winrt.windows.devices.bluetooth.advertisement import (
                BluetoothLEAdvertisement,
                BluetoothLEAdvertisementDataSection,
                BluetoothLEAdvertisementPublisher,
            )
            from winrt.windows.storage.streams import DataWriter
        except ImportError as exc:
            raise RuntimeError("Установите desktop[windows] для рекламы BLE в Windows") from exc

        while not self._stop.is_set():
            for payload in self._packets():
                if self._stop.is_set():
                    return
                advertisement = BluetoothLEAdvertisement()
                section = BluetoothLEAdvertisementDataSection(0xFF)
                writer = DataWriter()
                writer.write_bytes(payload)
                section.data = writer.detach_buffer()
                advertisement.data_sections.append(section)
                publisher = BluetoothLEAdvertisementPublisher(advertisement)
                self._publisher = _WinPublisher(publisher)
                await self._publisher.start()
                await asyncio.sleep(0.35)
                await self._publisher.stop()

    async def _run_linux(self) -> None:
        try:
            from dbus_next import MessageBus, Variant
            from dbus_next.aio import MessageBus as AioMessageBus
            from dbus_next.service import ServiceInterface, dbus_property, method
        except ImportError as exc:
            raise RuntimeError("Установите desktop[linux] для рекламы BLE в Linux") from exc

        bus = await AioMessageBus().connect()
        introspection = await bus.introspect("org.bluez", "/org/bluez/hci0")
        manager = bus.get_proxy_object("org.bluez", "/org/bluez/hci0", introspection).get_interface(
            "org.bluez.LEAdvertisingManager1"
        )
        path = "/com/streetpass/advertisement"

        class Advertisement(ServiceInterface):
            def __init__(self, payload: bytes) -> None:
                super().__init__("org.bluez.LEAdvertisement1")
                self.payload = payload

            @dbus_property()
            def Type(self) -> "s":
                return "broadcast"

            @dbus_property()
            def ManufacturerData(self) -> "a{qv}":
                return {0xFFFF: Variant("ay", self.payload)}

            @method()
            def Release(self) -> None:
                pass

        try:
            while not self._stop.is_set():
                for payload in self._packets():
                    if self._stop.is_set():
                        break
                    advertisement = Advertisement(payload)
                    bus.export(path, advertisement)
                    await manager.call_register_advertisement(path, {})
                    try:
                        await asyncio.sleep(0.35)
                    finally:
                        await manager.call_unregister_advertisement(path)
                        bus.unexport(path, advertisement)
        finally:
            bus.disconnect()


class _WinPublisher:
    def __init__(self, publisher) -> None:
        self.publisher = publisher

    async def start(self) -> None:
        self.publisher.start()

    async def stop(self) -> None:
        self.publisher.stop()
