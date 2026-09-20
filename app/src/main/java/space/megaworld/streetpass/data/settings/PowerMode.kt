package space.megaworld.streetpass.data.settings

import android.bluetooth.le.ScanSettings
import androidx.annotation.StringRes
import space.megaworld.streetpass.R

/**
 * Профили duty cycle. Все держатся ниже системного лимита в 5 запусков сканирования
 * за 30 секунд — при его превышении Android молча перестаёт отдавать результаты.
 */
enum class PowerMode(
    @StringRes val titleRes: Int,
    @StringRes val descriptionRes: Int,
    val scanWindowMs: Long,
    val pauseMs: Long,
    val scanMode: Int,
) {
    SAVER(
        titleRes = R.string.power_saver_title,
        descriptionRes = R.string.power_saver_desc,
        scanWindowMs = 8_000L,
        pauseMs = 112_000L,
        scanMode = ScanSettings.SCAN_MODE_BALANCED,
    ),
    BALANCED(
        titleRes = R.string.power_balanced_title,
        descriptionRes = R.string.power_balanced_desc,
        scanWindowMs = 10_000L,
        pauseMs = 50_000L,
        scanMode = ScanSettings.SCAN_MODE_BALANCED,
    ),
    MAX(
        titleRes = R.string.power_max_title,
        descriptionRes = R.string.power_max_desc,
        scanWindowMs = 0L,
        pauseMs = 0L,
        scanMode = ScanSettings.SCAN_MODE_LOW_LATENCY,
    );

    val continuous: Boolean
        get() = pauseMs == 0L
}
