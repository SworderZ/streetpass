package space.megaworld.streetpass.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface PeerDao {

    @Query("SELECT * FROM peers WHERE peerId = :peerId")
    suspend fun getById(peerId: String): PeerEntity?

    @Query("SELECT * FROM peers WHERE peerId = :peerId")
    fun observe(peerId: String): Flow<PeerEntity?>

    @Upsert
    suspend fun upsert(peer: PeerEntity)

    @Query("SELECT COUNT(*) FROM peers")
    fun countAll(): Flow<Int>

    @Query("SELECT COUNT(*) FROM peers")
    suspend fun countAllOnce(): Int

    @Query("SELECT * FROM peers WHERE friendSince IS NOT NULL ORDER BY encounterCount DESC, lastEncounterAt DESC")
    fun friends(): Flow<List<PeerEntity>>

    @Query("SELECT COUNT(*) FROM peers WHERE friendSince IS NOT NULL")
    fun countFriends(): Flow<Int>

    @Query("SELECT COUNT(*) FROM peers WHERE friendSince IS NOT NULL")
    suspend fun countFriendsOnce(): Int

    @Query("SELECT MAX(encounterCount) FROM peers WHERE friendSince IS NOT NULL")
    fun maxFriendEncounters(): Flow<Int?>

    @Query("SELECT MAX(encounterCount) FROM peers WHERE friendSince IS NOT NULL")
    suspend fun maxFriendEncountersOnce(): Int?

    @Query("UPDATE peers SET friendSince = :since WHERE peerId = :peerId")
    suspend fun setFriendSince(peerId: String, since: Long?)

    @Query("UPDATE peers SET alias = :alias WHERE peerId = :peerId")
    suspend fun setAlias(peerId: String, alias: String?)

    /** Очистка истории: друзья остаются в списке, но их счётчики обнуляются вместе со встречами. */
    @Query(
        """
        UPDATE peers SET encounterCount = 0, lastEncounterAt = 0, lastRssi = 0, bestRssi = 0
        WHERE friendSince IS NOT NULL
        """,
    )
    suspend fun resetFriends()

    @Query("DELETE FROM peers WHERE friendSince IS NULL")
    suspend fun deleteNonFriends()

    @Query("SELECT * FROM peers ORDER BY encounterCount DESC, lastEncounterAt DESC LIMIT :limit")
    fun top(limit: Int): Flow<List<PeerEntity>>

    @Query("DELETE FROM peers")
    suspend fun deleteAll()
}

@Dao
interface EncounterDao {

    @Insert
    suspend fun insert(encounter: EncounterEntity): Long

    @Query("SELECT COUNT(*) FROM encounters")
    fun countAll(): Flow<Int>

    @Query("SELECT COUNT(*) FROM encounters")
    suspend fun countAllOnce(): Int

    @Query("SELECT COUNT(*) FROM encounters WHERE timestamp >= :from AND timestamp < :to")
    fun countBetween(from: Long, to: Long): Flow<Int>

    @Query("SELECT COUNT(DISTINCT peerId) FROM encounters WHERE timestamp >= :from AND timestamp < :to")
    fun countPeersBetween(from: Long, to: Long): Flow<Int>

    @Query("SELECT timestamp FROM encounters WHERE timestamp >= :from AND timestamp < :to")
    fun timestampsBetween(from: Long, to: Long): Flow<List<Long>>

    @Query("SELECT timestamp FROM encounters WHERE timestamp >= :from AND timestamp < :to")
    suspend fun timestampsBetweenOnce(from: Long, to: Long): List<Long>

    @Query(
        """
        SELECT e.id, e.peerId, p.nickname, p.alias, p.friendSince, e.timestamp, e.rssi, e.firstMeeting,
               (SELECT COUNT(*) FROM encounters e2 WHERE e2.peerId = e.peerId AND e2.id <= e.id) AS ordinal
        FROM encounters e
        JOIN peers p ON p.peerId = e.peerId
        ORDER BY e.timestamp DESC, e.id DESC
        LIMIT :limit
        """,
    )
    fun recent(limit: Int): Flow<List<EncounterRow>>

    @Query("SELECT * FROM encounters WHERE peerId = :peerId ORDER BY id")
    suspend fun forPeer(peerId: String): List<EncounterEntity>

    @Query("DELETE FROM encounters")
    suspend fun deleteAll()
}

@Dao
interface AchievementDao {

    @Query("SELECT * FROM achievements ORDER BY unlockedAt")
    fun all(): Flow<List<AchievementEntity>>

    @Query("SELECT id FROM achievements")
    suspend fun unlockedIds(): List<String>

    /** IGNORE: достижение получают один раз, повторная запись не должна сдвигать дату. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(achievement: AchievementEntity): Long

    @Query("UPDATE achievements SET seenAt = :seenAt WHERE seenAt IS NULL")
    suspend fun markAllSeen(seenAt: Long)
}
