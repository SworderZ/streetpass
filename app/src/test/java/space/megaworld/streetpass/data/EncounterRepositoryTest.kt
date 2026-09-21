package space.megaworld.streetpass.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import space.megaworld.streetpass.data.db.AppDatabase

@RunWith(RobolectricTestRunner::class)
class EncounterRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: EncounterRepository

    private val peer = "0123456789abcdef"
    private val t0 = 1_700_000_000_000L
    private val minute = 60_000L

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = EncounterRepository(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun sight(
        rssi: Int = -60,
        now: Long = t0,
        cooldownMinutes: Int = 60,
        minRssi: Int = -95,
        storeRssi: Boolean = true,
        peerId: String = peer,
    ) = repository.processSighting(peerId, rssi, now, cooldownMinutes, minRssi, storeRssi)

    @Test
    fun firstSightingRegistersFirstMeeting() = runBlocking {
        val result = sight()

        assertEquals(SightingResult.Registered(peer, firstMeeting = true), result)
        val stored = db.peerDao().getById(peer)
        assertNotNull(stored)
        assertEquals(1, stored!!.encounterCount)
        assertEquals(t0, stored.firstSeenAt)
        assertEquals(t0, stored.lastEncounterAt)
        assertEquals(1, repository.totalEncounters.first())
        assertTrue(db.encounterDao().forPeer(peer).single().firstMeeting)
    }

    @Test
    fun repeatWithinCooldownIsNotCountedButUpdatesLastSeen() = runBlocking {
        sight(now = t0)

        val result = sight(now = t0 + 30 * minute, rssi = -50)

        assertEquals(SightingResult.Cooldown, result)
        val stored = db.peerDao().getById(peer)!!
        assertEquals(1, stored.encounterCount)
        assertEquals(t0 + 30 * minute, stored.lastSeenAt)
        assertEquals(t0, stored.lastEncounterAt)
        assertEquals(-50, stored.lastRssi)
        assertEquals(-50, stored.bestRssi)
        assertEquals(1, repository.totalEncounters.first())
    }

    @Test
    fun repeatAfterCooldownIsCountedAsSecondMeeting() = runBlocking {
        sight(now = t0)
        sight(now = t0 + 59 * minute)

        val result = sight(now = t0 + 60 * minute)

        assertEquals(SightingResult.Registered(peer, firstMeeting = false), result)
        val stored = db.peerDao().getById(peer)!!
        assertEquals(2, stored.encounterCount)
        assertEquals(t0 + 60 * minute, stored.lastEncounterAt)
        val encounters = db.encounterDao().forPeer(peer)
        assertEquals(2, encounters.size)
        assertFalse(encounters.last().firstMeeting)
    }

    @Test
    fun cooldownIsMeasuredFromLastCountedEncounterNotLastSeen() = runBlocking {
        sight(now = t0, cooldownMinutes = 5)
        sight(now = t0 + 4 * minute, cooldownMinutes = 5)

        val result = sight(now = t0 + 5 * minute, cooldownMinutes = 5)

        assertEquals(SightingResult.Registered(peer, firstMeeting = false), result)
        assertEquals(2, db.peerDao().getById(peer)!!.encounterCount)
    }

    @Test
    fun weakSignalIsDroppedBeforeDatabase() = runBlocking {
        val result = sight(rssi = -96, minRssi = -95)

        assertEquals(SightingResult.TooWeak, result)
        assertNull(db.peerDao().getById(peer))
        assertEquals(0, repository.totalEncounters.first())
    }

    @Test
    fun signalExactlyAtThresholdIsAccepted() = runBlocking {
        val result = sight(rssi = -95, minRssi = -95)

        assertEquals(SightingResult.Registered(peer, firstMeeting = true), result)
    }

    @Test
    fun rssiIsNotStoredWhenDisabled() = runBlocking {
        sight(rssi = -60, storeRssi = false)

        val stored = db.peerDao().getById(peer)!!
        assertEquals(EncounterRepository.RSSI_NOT_STORED, stored.lastRssi)
        assertEquals(EncounterRepository.RSSI_NOT_STORED, stored.bestRssi)
        assertEquals(EncounterRepository.RSSI_NOT_STORED, db.encounterDao().forPeer(peer).single().rssi)
    }

    @Test
    fun nearbyListsOnlyRecentlySeenPeers() = runBlocking {
        val now = System.currentTimeMillis()
        sight(now = now - EncounterRepository.NEARBY_WINDOW_MS - minute, peerId = "fedcba9876543210")
        sight(now = now - minute)

        val nearby = repository.nearby.first().map { it.peerId }

        assertEquals(listOf(peer), nearby)
    }

    @Test
    fun ordinalInHistoryCountsPerPeer() = runBlocking {
        val other = "fedcba9876543210"
        sight(now = t0, cooldownMinutes = 5)
        sight(now = t0 + minute, peerId = other, cooldownMinutes = 5)
        sight(now = t0 + 10 * minute, cooldownMinutes = 5)

        val rows = repository.recent(500).first()

        assertEquals(3, rows.size)
        assertEquals(2, rows[0].ordinal)
        assertEquals(peer, rows[0].peerId)
        assertEquals(1, rows[1].ordinal)
        assertEquals(other, rows[1].peerId)
        assertEquals(1, rows[2].ordinal)
    }

    @Test
    fun nicknameIsStoredAndKeptWhenPacketHasNone() = runBlocking {
        repository.processSighting(peer, -60, t0, 60, -95, true, nickname = "Alice")
        repository.processSighting(peer, -60, t0 + minute, 60, -95, true, nickname = null)

        assertEquals("Alice", db.peerDao().getById(peer)!!.nickname)
        assertEquals("Alice", repository.recent(10).first().single().nickname)

        repository.processSighting(peer, -60, t0 + 2 * minute, 60, -95, true, nickname = "Bob")

        assertEquals("Bob", db.peerDao().getById(peer)!!.nickname)
    }

    @Test
    fun clearAllRemovesPeersAndEncounters() = runBlocking {
        sight()

        repository.clearAll()

        assertEquals(0, repository.totalPeers.first())
        assertEquals(0, repository.totalEncounters.first())
    }
}
