package space.megaworld.streetpass.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface PeerDao {

    @Query("SELECT * FROM peers WHERE peerId = :peerId")
    suspend fun getById(peerId: String): PeerEntity?

    @Upsert
    suspend fun upsert(peer: PeerEntity)

    @Query("SELECT COUNT(*) FROM peers")
    fun countAll(): Flow<Int>

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

    @Query("SELECT COUNT(*) FROM encounters WHERE timestamp >= :from AND timestamp < :to")
    fun countBetween(from: Long, to: Long): Flow<Int>

    @Query("SELECT COUNT(DISTINCT peerId) FROM encounters WHERE timestamp >= :from AND timestamp < :to")
    fun countPeersBetween(from: Long, to: Long): Flow<Int>

    @Query("SELECT timestamp FROM encounters WHERE timestamp >= :from AND timestamp < :to")
    fun timestampsBetween(from: Long, to: Long): Flow<List<Long>>

    @Query(
        """
        SELECT e.id, e.peerId, p.nickname, e.timestamp, e.rssi, e.firstMeeting,
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
