package space.megaworld.streetpass.data.settings

import android.bluetooth.le.ScanSettings

/**
 * Профили duty cycle. Все держатся ниже системного лимита в 5 запусков сканирования
 * за 30 секунд — при его превышении Android молча перестаёт отдавать результаты.
 */
enum class PowerMode(
    val title: String,
    val description: String,
    val scanWindowMs: Long,
    val pauseMs: Long,
    val scanMode: Int,
) {
    SAVER(
        title = "Экономия",
        description = "8 секунд сканирования каждые 2 минуты. Максимальное время от батареи, " +
            "но встречи мимоходом могут быть пропущены.",
        scanWindowMs = 8_000L,
        pauseMs = 112_000L,
        scanMode = ScanSettings.SCAN_MODE_BALANCED,
    ),
    BALANCED(
        title = "Баланс",
        description = "10 секунд сканирования каждую минуту. Разумный компромисс для " +
            "повседневного ношения.",
        scanWindowMs = 10_000L,
        pauseMs = 50_000L,
        scanMode = ScanSettings.SCAN_MODE_BALANCED,
    ),
    MAX(
        title = "Максимум",
        description = "Непрерывное сканирование. Ничего не пропускает, но заметно " +
            "расходует батарею.",
        scanWindowMs = 0L,
        pauseMs = 0L,
        scanMode = ScanSettings.SCAN_MODE_LOW_LATENCY,
    );

    val continuous: Boolean
        get() = pauseMs == 0L
}
