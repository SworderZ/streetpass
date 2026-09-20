package space.megaworld.streetpass.core

import android.os.ParcelUuid

object BleConstants {
    // 16-битный UUID 0x5350, развёрнутый по Bluetooth Base UUID. Именно 16-битный:
    // 128-битный занял бы 18 байт, и Service Data уже не поместилась бы в 31 байт
    // legacy-рекламы. Менять нельзя — ломает совместимость с установленными сборками.
    val SERVICE_UUID: ParcelUuid = ParcelUuid.fromString("00005350-0000-1000-8000-00805f9b34fb")

    const val PEER_ID_BYTES = 8

    const val DEBOUNCE_WINDOW_MS = 10_000L
    const val DEBOUNCE_STALE_MS = 10 * 60_000L
    const val DEBOUNCE_MAX_ENTRIES = 512
}
