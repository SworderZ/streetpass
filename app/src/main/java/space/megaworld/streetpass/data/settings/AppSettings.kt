package space.megaworld.streetpass.data.settings

data class AppSettings(
    val advertiseEnabled: Boolean = true,
    val scanEnabled: Boolean = true,
    val autoStart: Boolean = true,
    val powerMode: PowerMode = PowerMode.BALANCED,
    val cooldownMinutes: Int = DEFAULT_COOLDOWN_MINUTES,
    val minRssi: Int = DEFAULT_MIN_RSSI,
    val storeRssi: Boolean = true,
    /** Засчитывать ID без подписи — совместимость со сборками до появления подписи, ценой защиты от копирования. */
    val acceptUnsigned: Boolean = false,
    /** Отдельное уведомление, когда встречен друг. */
    val notifyFriends: Boolean = true,
    /** Было ли обнаружение включено пользователем — нужно для восстановления после перезагрузки. */
    val discoveryActive: Boolean = false,
) {
    companion object {
        const val DEFAULT_COOLDOWN_MINUTES = 60
        const val DEFAULT_MIN_RSSI = -95
        /** До 12 часов: «один человек — одна встреча в день» для коллег и соседей. */
        val COOLDOWN_RANGE = 5..720
        const val COOLDOWN_STEP_MINUTES = 5
        val RSSI_RANGE = -100..-40
    }
}
