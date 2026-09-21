package space.megaworld.streetpass.data.identity

import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.security.GeneralSecurityException
import java.security.KeyPair
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import space.megaworld.streetpass.core.Hex
import space.megaworld.streetpass.core.IdentityProof
import space.megaworld.streetpass.core.Nicknames

/**
 * Анонимный идентификатор устройства: 8 байт хеша публичного ключа подписи, в hex.
 * Не связан ни с аккаунтом, ни с MAC, ни с номером телефона. Ключ первичен, ID —
 * производная: сменить ID значит создать новый ключ. Плюс необязательный ник,
 * который пользователь сам решил показывать другим.
 */
class IdentityRepository(
    private val dataStore: DataStore<Preferences>,
    private val keys: IdentityKeys = IdentityKeys(dataStore),
) {

    private val idKey = stringPreferencesKey("peer_id")
    private val nicknameKey = stringPreferencesKey("nickname")

    // Keystore не любит параллельного создания одного alias: два вызова получили бы разные
    // ключи, и один из них подписывал бы уже чужой ID.
    private val mutex = Mutex()
    private var keyPair: KeyPair? = null

    val idHex: Flow<String> = dataStore.data
        .map { it[idKey] }
        .distinctUntilChanged()
        .map { it ?: getOrCreate() }
        .distinctUntilChanged()

    /** Пустая строка — ник не задан, в эфир уходит только ID. */
    val nickname: Flow<String> = dataStore.data
        .map { it[nicknameKey].orEmpty() }
        .distinctUntilChanged()

    suspend fun getOrCreate(): String = mutex.withLock {
        Hex.encode(IdentityProof.peerId(ensureKeyLocked().public))
    }

    suspend fun idBytes(): ByteArray = Hex.decode(getOrCreate())

    /**
     * Свежее доказательство владения ID для эфира; null — подписать не удалось даже
     * новым ключом. [nowMillis] передаётся параметром ради тестов.
     */
    suspend fun proof(nowMillis: Long): ByteArray? = mutex.withLock {
        withContext(Dispatchers.IO) {
            signLocked(ensureKeyLocked(), nowMillis) ?: run {
                // Keystore иногда теряет ключ после обновления прошивки: такой ключ загружается,
                // но подписывать отказывается. Единственный выход — новый ключ, то есть новый ID.
                Log.w(TAG, "identity key unusable, recreating")
                signLocked(recreateLocked(), nowMillis)
            }
        }
    }

    suspend fun currentNickname(): String = nickname.first()

    suspend fun setNickname(value: String) {
        val clean = Nicknames.sanitize(value)
        dataStore.edit { prefs ->
            if (clean.isEmpty()) prefs.remove(nicknameKey) else prefs[nicknameKey] = clean
        }
    }

    suspend fun regenerate(): String = mutex.withLock {
        Hex.encode(IdentityProof.peerId(recreateLocked().public))
    }

    private suspend fun ensureKeyLocked(): KeyPair {
        keyPair?.let { return it }
        return keys.loadOrCreate().also { storeIdLocked(it) }
    }

    private suspend fun recreateLocked(): KeyPair =
        keys.recreate().also { storeIdLocked(it) }

    private suspend fun storeIdLocked(pair: KeyPair) {
        keyPair = pair
        val id = Hex.encode(IdentityProof.peerId(pair.public))
        // В DataStore может лежать ID старого формата (8 случайных байт до появления подписи)
        // или от прежнего ключа — источник истины ключ, запись подтягиваем к нему.
        if (dataStore.data.first()[idKey] != id) {
            dataStore.edit { it[idKey] = id }
        }
    }

    private fun signLocked(pair: KeyPair, nowMillis: Long): ByteArray? = try {
        IdentityProof.sign(pair.private, pair.public, nowMillis / 1000)
    } catch (e: GeneralSecurityException) {
        Log.w(TAG, "signing failed: ${e.message}")
        null
    } catch (e: RuntimeException) {
        // AndroidKeyStore заворачивает свои ошибки в ProviderException.
        Log.w(TAG, "signing failed: ${e.message}")
        null
    }

    private companion object {
        const val TAG = "IdentityRepository"
    }
}
