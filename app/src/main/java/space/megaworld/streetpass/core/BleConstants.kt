package space.megaworld.streetpass.core

import android.os.ParcelUuid

object BleConstants {
    // 16-битный UUID 0x5350, развёрнутый по Bluetooth Base UUID. Именно 16-битный:
    // 128-битный занял бы 18 байт, и Service Data уже не поместилась бы в 31 байт
    // legacy-рекламы. Менять нельзя — ломает совместимость с установленными сборками.
    val SERVICE_UUID: ParcelUuid = ParcelUuid.fromString("00005350-0000-1000-8000-00805f9b34fb")

    // Ник идёт отдельной Service Data в scan-response пакете: основной пакет с ID
    // не меняется, старые сборки просто не увидят ника. Свой UUID нужен, потому что
    // Service Data с одинаковым UUID из двух пакетов стек сливает в одну запись.
    val NICKNAME_UUID: ParcelUuid = ParcelUuid.fromString("00005351-0000-1000-8000-00805f9b34fb")

    // Куски подписанного доказательства ID (см. IdentityProof) чередуются с ником в
    // scan-response под своим UUID: сборки без поддержки подписи их просто не увидят.
    val PROOF_UUID: ParcelUuid = ParcelUuid.fromString("00005352-0000-1000-8000-00805f9b34fb")

    const val PEER_ID_BYTES = 8

    /** 31 байт scan-response минус 4 байта заголовка Service Data — жёсткий предел кадра. */
    const val PROOF_FRAME_BYTES = 27

    /** Подпись обновляется чаще, чем протухает: сосед не выпадает из доверия между циклами. */
    const val PROOF_REFRESH_MS = 5 * 60_000L

    /** Допустимый разброс часов двух телефонов и одновременно окно повтора чужого эфира. */
    const val PROOF_MAX_SKEW_MS = 10 * 60_000L

    // Кадр держится не меньше интервала рекламы BALANCED (250 мс + до 10 мс джиттера),
    // иначе часть кадров не уйдёт в эфир вовсе. Длительность случайная, чтобы цикл кадров
    // не попадал в резонанс с окнами сканирования соседа и не показывал ему одни и те же куски.
    const val PROOF_FRAME_MIN_MS = 260L
    const val PROOF_FRAME_MAX_MS = 340L

    // Scan-response: 31 байт минус 4 байта заголовка Service Data; оставляем запас.
    const val NICKNAME_MAX_BYTES = 24

    const val DEBOUNCE_WINDOW_MS = 10_000L
    const val DEBOUNCE_STALE_MS = 10 * 60_000L
    const val DEBOUNCE_MAX_ENTRIES = 512
}
