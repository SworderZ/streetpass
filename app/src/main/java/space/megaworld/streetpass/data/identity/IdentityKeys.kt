package space.megaworld.streetpass.data.identity

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import space.megaworld.streetpass.core.Hex
import space.megaworld.streetpass.core.IdentityProof

/**
 * Ключ P-256, которым подписывается ID.
 *
 * Основное хранилище — AndroidKeyStore: приватный ключ не покидает системное хранилище
 * (на большинстве устройств — TEE), его нельзя вытащить вместе с данными приложения.
 * Если Keystore недоступен или сломан — такое бывает на старых и кастомных прошивках,
 * а также в Robolectric, — ключ создаётся программно и лежит в DataStore: без ключа
 * приложение осталось бы без ID вовсе. Однажды созданный программный ключ используется
 * и дальше, даже если Keystore потом «починился»: иначе ID менялся бы сам по себе.
 */
class IdentityKeys(private val dataStore: DataStore<Preferences>) {

    private val softwarePrivateKey = stringPreferencesKey("identity_private_key")
    private val softwarePublicKey = stringPreferencesKey("identity_public_key")

    suspend fun loadOrCreate(): KeyPair = withContext(Dispatchers.IO) {
        loadSoftware() ?: loadKeystore() ?: createKeystore() ?: createSoftware()
    }

    suspend fun recreate(): KeyPair = withContext(Dispatchers.IO) {
        deleteKeystore()
        dataStore.edit { prefs ->
            prefs.remove(softwarePrivateKey)
            prefs.remove(softwarePublicKey)
        }
        createKeystore() ?: createSoftware()
    }

    private fun loadKeystore(): KeyPair? = try {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        val privateKey = store.getKey(ALIAS, null)
        val certificate = store.getCertificate(ALIAS)
        if (privateKey is java.security.PrivateKey && certificate != null) {
            KeyPair(certificate.publicKey, privateKey)
        } else {
            null
        }
    } catch (e: GeneralSecurityException) {
        Log.w(TAG, "keystore unavailable: ${e.message}")
        null
    } catch (e: RuntimeException) {
        // ProviderException и родня: Keystore-демон вернул ошибку — считаем, что хранилища нет.
        Log.w(TAG, "keystore failed: ${e.message}")
        null
    }

    private fun createKeystore(): KeyPair? = try {
        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, KEYSTORE)
        generator.initialize(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(ECGenParameterSpec(IdentityProof.CURVE_NAME))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .build(),
        )
        generator.generateKeyPair().also { Log.d(TAG, "identity key created in keystore") }
    } catch (e: GeneralSecurityException) {
        Log.w(TAG, "keystore key generation unavailable: ${e.message}")
        null
    } catch (e: RuntimeException) {
        Log.w(TAG, "keystore key generation failed: ${e.message}")
        null
    }

    private fun deleteKeystore() {
        try {
            KeyStore.getInstance(KEYSTORE).apply { load(null) }.deleteEntry(ALIAS)
        } catch (e: GeneralSecurityException) {
            Log.w(TAG, "keystore delete skipped: ${e.message}")
        } catch (e: RuntimeException) {
            Log.w(TAG, "keystore delete failed: ${e.message}")
        }
    }

    private suspend fun loadSoftware(): KeyPair? {
        val prefs = dataStore.data.first()
        val privateHex = prefs[softwarePrivateKey] ?: return null
        val publicHex = prefs[softwarePublicKey] ?: return null
        return try {
            val factory = KeyFactory.getInstance(KeyProperties.KEY_ALGORITHM_EC)
            KeyPair(
                factory.generatePublic(X509EncodedKeySpec(Hex.decode(publicHex))),
                factory.generatePrivate(PKCS8EncodedKeySpec(Hex.decode(privateHex))),
            )
        } catch (e: GeneralSecurityException) {
            Log.w(TAG, "stored software key is unreadable, recreating")
            null
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "stored software key is corrupted, recreating")
            null
        }
    }

    private suspend fun createSoftware(): KeyPair {
        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC)
        generator.initialize(ECGenParameterSpec(IdentityProof.CURVE_NAME))
        val pair = generator.generateKeyPair()
        dataStore.edit { prefs ->
            prefs[softwarePrivateKey] = Hex.encode(pair.private.encoded)
            prefs[softwarePublicKey] = Hex.encode(pair.public.encoded)
        }
        Log.d(TAG, "identity key created in software")
        return pair
    }

    private companion object {
        const val TAG = "IdentityKeys"
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "streetpass_identity"
    }
}
