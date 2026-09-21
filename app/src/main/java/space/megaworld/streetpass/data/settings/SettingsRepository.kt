package space.megaworld.streetpass.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

class SettingsRepository(private val dataStore: DataStore<Preferences>) {

    private object Keys {
        val ADVERTISE_ENABLED = booleanPreferencesKey("advertise_enabled")
        val SCAN_ENABLED = booleanPreferencesKey("scan_enabled")
        val AUTO_START = booleanPreferencesKey("auto_start")
        val POWER_MODE = stringPreferencesKey("power_mode")
        val COOLDOWN_MINUTES = intPreferencesKey("cooldown_minutes")
        val MIN_RSSI = intPreferencesKey("min_rssi")
        val STORE_RSSI = booleanPreferencesKey("store_rssi")
        val ACCEPT_UNSIGNED = booleanPreferencesKey("accept_unsigned")
        val NOTIFY_FRIENDS = booleanPreferencesKey("notify_friends")
        val DISCOVERY_ACTIVE = booleanPreferencesKey("discovery_active")
    }

    val settings: Flow<AppSettings> = dataStore.data
        .map { it.toSettings() }
        .distinctUntilChanged()

    suspend fun current(): AppSettings = settings.first()

    suspend fun setAdvertiseEnabled(value: Boolean) = edit { it[Keys.ADVERTISE_ENABLED] = value }

    suspend fun setScanEnabled(value: Boolean) = edit { it[Keys.SCAN_ENABLED] = value }

    suspend fun setAutoStart(value: Boolean) = edit { it[Keys.AUTO_START] = value }

    suspend fun setPowerMode(value: PowerMode) = edit { it[Keys.POWER_MODE] = value.name }

    suspend fun setCooldownMinutes(value: Int) = edit {
        it[Keys.COOLDOWN_MINUTES] = value.coerceIn(AppSettings.COOLDOWN_RANGE)
    }

    suspend fun setMinRssi(value: Int) = edit {
        it[Keys.MIN_RSSI] = value.coerceIn(AppSettings.RSSI_RANGE)
    }

    suspend fun setStoreRssi(value: Boolean) = edit { it[Keys.STORE_RSSI] = value }

    suspend fun setAcceptUnsigned(value: Boolean) = edit { it[Keys.ACCEPT_UNSIGNED] = value }

    suspend fun setNotifyFriends(value: Boolean) = edit { it[Keys.NOTIFY_FRIENDS] = value }

    suspend fun setDiscoveryActive(value: Boolean) = edit { it[Keys.DISCOVERY_ACTIVE] = value }

    private suspend fun edit(block: (MutablePreferences) -> Unit) {
        dataStore.edit { block(it) }
    }

    private fun Preferences.toSettings(): AppSettings {
        val defaults = AppSettings()
        return AppSettings(
            advertiseEnabled = this[Keys.ADVERTISE_ENABLED] ?: defaults.advertiseEnabled,
            scanEnabled = this[Keys.SCAN_ENABLED] ?: defaults.scanEnabled,
            autoStart = this[Keys.AUTO_START] ?: defaults.autoStart,
            powerMode = this[Keys.POWER_MODE]
                ?.let { name -> PowerMode.entries.firstOrNull { it.name == name } }
                ?: defaults.powerMode,
            cooldownMinutes = (this[Keys.COOLDOWN_MINUTES] ?: defaults.cooldownMinutes)
                .coerceIn(AppSettings.COOLDOWN_RANGE),
            minRssi = (this[Keys.MIN_RSSI] ?: defaults.minRssi).coerceIn(AppSettings.RSSI_RANGE),
            storeRssi = this[Keys.STORE_RSSI] ?: defaults.storeRssi,
            acceptUnsigned = this[Keys.ACCEPT_UNSIGNED] ?: defaults.acceptUnsigned,
            notifyFriends = this[Keys.NOTIFY_FRIENDS] ?: defaults.notifyFriends,
            discoveryActive = this[Keys.DISCOVERY_ACTIVE] ?: defaults.discoveryActive,
        )
    }
}
