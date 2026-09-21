package space.megaworld.streetpass.data.identity

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import space.megaworld.streetpass.core.BleConstants
import space.megaworld.streetpass.core.Hex
import space.megaworld.streetpass.core.IdentityProof

/**
 * В Robolectric нет AndroidKeyStore, поэтому здесь проверяется программный ключ —
 * тот же путь, что на прошивках со сломанным Keystore.
 */
@RunWith(RobolectricTestRunner::class)
class IdentityRepositoryTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var scope: CoroutineScope
    private lateinit var dataStore: DataStore<Preferences>

    private val now = 1_700_000_000_000L

    @Before
    fun setUp() {
        scope = CoroutineScope(Dispatchers.IO + Job())
        dataStore = PreferenceDataStoreFactory.create(scope = scope) { File(folder.root, "identity.preferences_pb") }
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun idIsDerivedFromKeyAndStable() = runBlocking {
        val repository = IdentityRepository(dataStore)

        val id = repository.getOrCreate()
        val again = IdentityRepository(dataStore).getOrCreate()

        assertEquals(BleConstants.PEER_ID_BYTES * 2, id.length)
        assertEquals(id, again)
        assertEquals(id, repository.idHex.first())
    }

    @Test
    fun proofVerifiesAgainstOwnId() = runBlocking {
        val repository = IdentityRepository(dataStore)
        val id = repository.getOrCreate()

        val proof = repository.proof(now)

        assertNotNull(proof)
        assertEquals(
            IdentityProof.Result.Verified(now / 1000),
            IdentityProof.verify(proof!!, Hex.decode(id), nowSeconds = now / 1000),
        )
    }

    @Test
    fun legacyRandomIdIsReplacedByKeyDerivedOne() = runBlocking {
        dataStore.edit { it[stringPreferencesKey("peer_id")] = "0011223344556677" }
        val repository = IdentityRepository(dataStore)

        val id = repository.getOrCreate()

        assertNotEquals("0011223344556677", id)
        assertEquals(id, dataStore.data.first()[stringPreferencesKey("peer_id")])
    }

    @Test
    fun regenerateChangesIdAndKey() = runBlocking {
        val repository = IdentityRepository(dataStore)
        val before = repository.getOrCreate()
        val proofBefore = repository.proof(now)!!

        val after = repository.regenerate()

        assertNotEquals(before, after)
        assertEquals(after, repository.getOrCreate())
        assertEquals(after, IdentityRepository(dataStore).getOrCreate())
        // Старое доказательство новому ID не принадлежит.
        assertEquals(
            IdentityProof.Result.Rejected(IdentityProof.Reason.ID_MISMATCH),
            IdentityProof.verify(proofBefore, Hex.decode(after), nowSeconds = now / 1000),
        )
        assertArrayEquals(Hex.decode(after), repository.idBytes())
    }
}
