package space.megaworld.streetpass.data.achievements

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.time.ZoneId
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
import space.megaworld.streetpass.core.FriendInvite
import space.megaworld.streetpass.data.EncounterRepository
import space.megaworld.streetpass.data.SightingResult
import space.megaworld.streetpass.data.db.AppDatabase

@RunWith(RobolectricTestRunner::class)
class AchievementRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var encounters: EncounterRepository
    private lateinit var achievements: AchievementRepository

    private val zone = ZoneId.of("UTC")
    private val t0 = 1_700_000_000_000L
    private val day = 24 * 60 * 60_000L
    private val hour = 60 * 60_000L

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        encounters = EncounterRepository(db, zone)
        achievements = AchievementRepository(db, zone)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun peer(i: Int) = "peer%014x".format(i)

    private suspend fun meet(peerId: String, at: Long) =
        encounters.processSighting(peerId, rssi = -60, now = at, cooldownMinutes = 5, minRssi = -95, storeRssi = true)

    @Test
    fun nothingUnlockedOnEmptyDatabase() = runBlocking {
        assertTrue(achievements.check(t0).isEmpty())
        assertTrue(achievements.progress.first().none { it.unlocked })
    }

    @Test
    fun peopleThresholdUnlocksExactlyOnce() = runBlocking {
        repeat(4) { meet(peer(it), t0) }
        assertTrue(achievements.check(t0).isEmpty())

        meet(peer(4), t0)
        val fresh = achievements.check(t0 + 1)

        assertEquals(listOf("people_5"), fresh.map { it.id })
        assertTrue(achievements.check(t0 + 2).isEmpty())
        val progress = achievements.progress.first().first { it.achievement.id == "people_5" }
        assertEquals(t0 + 1, progress.unlockedAt)
        assertEquals(5, progress.current)
    }

    @Test
    fun encounterThresholdCountsRepeatVisits() = runBlocking {
        repeat(10) { meet(peer(0), t0 + it * hour) }

        val ids = achievements.check(t0 + 10 * hour).map { it.id }

        assertTrue("encounters_10" in ids)
        assertFalse("people_5" in ids)
    }

    @Test
    fun friendAchievementsFollowTheFriendList() = runBlocking {
        repeat(10) { meet(peer(0), t0 + it * hour) }
        assertTrue(achievements.check(t0 + 10 * hour).none { it.kind == AchievementKind.FRIENDS })

        encounters.setFriend(peer(0), friend = true, now = t0 + 11 * hour)
        val ids = achievements.check(t0 + 11 * hour).map { it.id }

        assertTrue("friends_1" in ids)
        assertTrue("friend_encounters_10" in ids)
        assertEquals(10, achievements.metrics.first().friendEncounters)
    }

    @Test
    fun streakUnlocksAfterSevenConsecutiveDays() = runBlocking {
        repeat(6) { meet(peer(it), t0 + it * day) }
        assertTrue(achievements.check(t0 + 5 * day).none { it.kind == AchievementKind.STREAK })

        meet(peer(6), t0 + 6 * day)
        val ids = achievements.check(t0 + 6 * day).map { it.id }

        assertTrue("streak_7" in ids)
    }

    @Test
    fun unseenIsClearedByMarkSeen() = runBlocking {
        repeat(5) { meet(peer(it), t0) }
        achievements.check(t0)
        assertEquals(listOf("people_5"), achievements.unseen.first().map { it.id })

        achievements.markSeen(t0 + 1)

        assertTrue(achievements.unseen.first().isEmpty())
        assertNotNull(achievements.progress.first().first { it.achievement.id == "people_5" }.unlockedAt)
    }

    @Test
    fun clearHistoryKeepsAchievementsAndFriendsButResetsCounters() = runBlocking {
        repeat(5) { meet(peer(it), t0) }
        encounters.setFriend(peer(0), friend = true, now = t0)
        encounters.setAlias(peer(0), "Alice")
        achievements.check(t0)

        encounters.clearAll()

        assertEquals(0, encounters.totalEncounters.first())
        val friend = db.peerDao().getById(peer(0))
        assertNotNull(friend)
        assertTrue(friend!!.isFriend)
        assertEquals("Alice", friend.alias)
        assertEquals(0, friend.encounterCount)
        assertNull(db.peerDao().getById(peer(1)))
        assertTrue(achievements.progress.first().first { it.achievement.id == "people_5" }.unlocked)
        // Друг остался, но люди из статистики ушли: прогресс считается заново по базе.
        assertEquals(1, achievements.metrics.first().people)
    }

    @Test
    fun friendFromInviteAppearsWithoutEncountersAndFirstMeetingIsRecordedLater() = runBlocking {
        val invite = FriendInvite.Invite(peerId = peer(7), nickname = "Alice", publicKey = ByteArray(33))

        encounters.addFriend(invite, t0)

        val friend = db.peerDao().getById(peer(7))!!
        assertTrue(friend.isFriend)
        assertEquals("Alice", friend.nickname)
        assertEquals(0, friend.encounterCount)
        assertEquals(1, achievements.check(t0).count { it.id == "friends_1" })

        val result = meet(peer(7), t0 + hour)
        assertEquals(SightingResult.Registered(peer(7), firstMeeting = true), result)
        assertEquals(1, db.peerDao().getById(peer(7))!!.encounterCount)
    }

    @Test
    fun inviteForKnownPeerKeepsBroadcastNicknameAndCounters() = runBlocking {
        meet(peer(0), t0)
        db.peerDao().upsert(db.peerDao().getById(peer(0))!!.copy(nickname = "FromAir"))

        encounters.addFriend(FriendInvite.Invite(peer(0), "FromInvite", ByteArray(33)), t0 + 1)

        val friend = db.peerDao().getById(peer(0))!!
        assertTrue(friend.isFriend)
        assertEquals("FromAir", friend.nickname)
        assertEquals(1, friend.encounterCount)
    }

    @Test
    fun firstMeetingAfterClearHistoryIsFirstAgain() = runBlocking {
        meet(peer(0), t0)
        encounters.setFriend(peer(0), friend = true, now = t0)
        encounters.clearAll()

        val result = meet(peer(0), t0 + hour)

        assertEquals(SightingResult.Registered(peer(0), firstMeeting = true), result)
    }

    @Test
    fun visibleProgressShowsOnlyNextLockedTierPerKind() = runBlocking {
        repeat(5) { meet(peer(it), t0) }
        achievements.check(t0)

        val visible = achievements.visibleProgress.first()

        val people = visible.filter { it.achievement.kind == AchievementKind.PEOPLE }.map { it.achievement.id }
        assertEquals(listOf("people_5", "people_15"), people)
        assertEquals(listOf("encounters_10"), visible.filter { it.achievement.kind == AchievementKind.ENCOUNTERS }.map { it.achievement.id })
        assertEquals(AchievementKind.entries.size + 1, visible.size)
    }

    @Test
    fun aliasIsSanitisedAndEmptyClearsIt() = runBlocking {
        meet(peer(0), t0)

        encounters.setAlias(peer(0), "  Bob\n<x>  ")
        assertEquals("Bobx", db.peerDao().getById(peer(0))!!.alias)

        encounters.setAlias(peer(0), "   ")
        assertNull(db.peerDao().getById(peer(0))!!.alias)
    }
}
