package space.megaworld.streetpass.data.identity

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.security.SecureRandom
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import space.megaworld.streetpass.core.BleConstants
import space.megaworld.streetpass.core.Hex

/**
 * Анонимный идентификатор устройства: 8 случайных байт в hex. Не связан ни с аккаунтом,
 * ни с MAC, ни с номером телефона — единственное, что уходит в эфир.
 */
class IdentityRepository(private val dataStore: DataStore<Preferences>) {

    private val key = stringPreferencesKey("peer_id")
    private val random = SecureRandom()

    val idHex: Flow<String> = dataStore.data
        .map { it[key] }
        .distinctUntilChanged()
        .map { it ?: getOrCreate() }
        .distinctUntilChanged()

    suspend fun getOrCreate(): String {
        dataStore.data.first()[key]?.let { return it }
        val fresh = generate()
        dataStore.edit { prefs ->
            if (prefs[key] == null) prefs[key] = fresh
        }
        return dataStore.data.first()[key] ?: fresh
    }

    suspend fun idBytes(): ByteArray = Hex.decode(getOrCreate())

    suspend fun regenerate(): String {
        val fresh = generate()
        dataStore.edit { it[key] = fresh }
        return fresh
    }

    private fun generate(): String =
        ByteArray(BleConstants.PEER_ID_BYTES).also(random::nextBytes).let(Hex::encode)
}
