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
import space.megaworld.streetpass.core.Nicknames

/**
 * Анонимный идентификатор устройства: 8 случайных байт в hex. Не связан ни с аккаунтом,
 * ни с MAC, ни с номером телефона. Плюс необязательный ник, который пользователь
 * сам решил показывать другим.
 */
class IdentityRepository(private val dataStore: DataStore<Preferences>) {

    private val idKey = stringPreferencesKey("peer_id")
    private val nicknameKey = stringPreferencesKey("nickname")
    private val random = SecureRandom()

    val idHex: Flow<String> = dataStore.data
        .map { it[idKey] }
        .distinctUntilChanged()
        .map { it ?: getOrCreate() }
        .distinctUntilChanged()

    /** Пустая строка — ник не задан, в эфир уходит только ID. */
    val nickname: Flow<String> = dataStore.data
        .map { it[nicknameKey].orEmpty() }
        .distinctUntilChanged()

    suspend fun getOrCreate(): String {
        dataStore.data.first()[idKey]?.let { return it }
        val fresh = generate()
        dataStore.edit { prefs ->
            if (prefs[idKey] == null) prefs[idKey] = fresh
        }
        return dataStore.data.first()[idKey] ?: fresh
    }

    suspend fun idBytes(): ByteArray = Hex.decode(getOrCreate())

    suspend fun currentNickname(): String = nickname.first()

    suspend fun setNickname(value: String) {
        val clean = Nicknames.sanitize(value)
        dataStore.edit { prefs ->
            if (clean.isEmpty()) prefs.remove(nicknameKey) else prefs[nicknameKey] = clean
        }
    }

    suspend fun regenerate(): String {
        val fresh = generate()
        dataStore.edit { it[idKey] = fresh }
        return fresh
    }

    private fun generate(): String =
        ByteArray(BleConstants.PEER_ID_BYTES).also(random::nextBytes).let(Hex::encode)
}
