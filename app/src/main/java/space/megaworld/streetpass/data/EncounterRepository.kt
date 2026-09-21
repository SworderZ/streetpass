package space.megaworld.streetpass.data

import androidx.room.withTransaction
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import space.megaworld.streetpass.core.Nicknames
import space.megaworld.streetpass.core.TimeRanges
import space.megaworld.streetpass.data.db.AppDatabase
import space.megaworld.streetpass.data.db.EncounterEntity
import space.megaworld.streetpass.data.db.EncounterRow
import space.megaworld.streetpass.data.db.PeerEntity

sealed interface SightingResult {
    /** Встреча записана в базу. */
    data class Registered(val peerId: String, val firstMeeting: Boolean) : SightingResult

    /** Peer рядом, но уже засчитан в текущем окне антидубля. */
    data object Cooldown : SightingResult

    /** Сигнал слабее порога — до базы не дошли. */
    data object TooWeak : SightingResult
}

data class DailyCount(val date: LocalDate, val count: Int)

@OptIn(ExperimentalCoroutinesApi::class)
class EncounterRepository(
    private val db: AppDatabase,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    private val peers = db.peerDao()
    private val encounters = db.encounterDao()

    /**
     * Обработка одного принятого пакета. [now] передаётся параметром, а не берётся
     * из системных часов — так логика антидубля детерминированно тестируется.
     */
    suspend fun processSighting(
        peerId: String,
        rssi: Int,
        now: Long,
        cooldownMinutes: Int,
        minRssi: Int,
        storeRssi: Boolean,
        nickname: String? = null,
    ): SightingResult {
        if (rssi < minRssi) return SightingResult.TooWeak
        val storedRssi = if (storeRssi) rssi else RSSI_NOT_STORED

        return db.withTransaction {
            val peer = peers.getById(peerId)
            if (peer == null) {
                peers.upsert(
                    PeerEntity(
                        peerId = peerId,
                        firstSeenAt = now,
                        lastSeenAt = now,
                        lastEncounterAt = now,
                        encounterCount = 1,
                        lastRssi = storedRssi,
                        bestRssi = storedRssi,
                        nickname = nickname,
                    ),
                )
                encounters.insert(
                    EncounterEntity(peerId = peerId, timestamp = now, rssi = storedRssi, firstMeeting = true),
                )
                return@withTransaction SightingResult.Registered(peerId, firstMeeting = true)
            }

            val cooldownMs = cooldownMinutes * 60_000L
            val counts = now - peer.lastEncounterAt >= cooldownMs
            val bestRssi = when {
                !storeRssi -> peer.bestRssi
                peer.bestRssi == RSSI_NOT_STORED -> rssi
                else -> maxOf(peer.bestRssi, rssi)
            }
            peers.upsert(
                peer.copy(
                    lastSeenAt = now,
                    lastRssi = storedRssi,
                    bestRssi = bestRssi,
                    lastEncounterAt = if (counts) now else peer.lastEncounterAt,
                    encounterCount = if (counts) peer.encounterCount + 1 else peer.encounterCount,
                    // Пакет без scan-response ника не несёт — старое значение не затираем.
                    nickname = nickname ?: peer.nickname,
                ),
            )
            if (counts) {
                encounters.insert(
                    EncounterEntity(peerId = peerId, timestamp = now, rssi = storedRssi, firstMeeting = false),
                )
                SightingResult.Registered(peerId, firstMeeting = false)
            } else {
                SightingResult.Cooldown
            }
        }
    }

    val totalEncounters: Flow<Int> = encounters.countAll()

    val totalPeers: Flow<Int> = peers.countAll()

    val todayEncounters: Flow<Int> = TimeRanges.midnightTicker(zone).flatMapLatest { today ->
        val range = TimeRanges.day(today, zone)
        encounters.countBetween(range.startInclusive, range.endExclusive)
    }

    val todayPeers: Flow<Int> = TimeRanges.midnightTicker(zone).flatMapLatest { today ->
        val range = TimeRanges.day(today, zone)
        encounters.countPeersBetween(range.startInclusive, range.endExclusive)
    }

    val weekEncounters: Flow<Int> = TimeRanges.midnightTicker(zone).flatMapLatest { today ->
        val range = TimeRanges.lastDays(today, WEEK_DAYS, zone)
        encounters.countBetween(range.startInclusive, range.endExclusive)
    }

    val weekPeers: Flow<Int> = TimeRanges.midnightTicker(zone).flatMapLatest { today ->
        val range = TimeRanges.lastDays(today, WEEK_DAYS, zone)
        encounters.countPeersBetween(range.startInclusive, range.endExclusive)
    }

    /** Встречи по дням за последнюю неделю, от старых к новым; дни без встреч — с нулём. */
    val weekDaily: Flow<List<DailyCount>> = TimeRanges.midnightTicker(zone).flatMapLatest { today ->
        val range = TimeRanges.lastDays(today, WEEK_DAYS, zone)
        encounters.timestampsBetween(range.startInclusive, range.endExclusive).map { timestamps ->
            val perDay = timestamps.groupingBy { TimeRanges.toLocalDate(it, zone) }.eachCount()
            (WEEK_DAYS - 1 downTo 0).map { offset ->
                val date = today.minusDays(offset.toLong())
                DailyCount(date, perDay[date] ?: 0)
            }
        }
    }

    fun recent(limit: Int): Flow<List<EncounterRow>> = encounters.recent(limit)

    fun topPeers(limit: Int): Flow<List<PeerEntity>> = peers.top(limit)

    fun peer(peerId: String): Flow<PeerEntity?> = peers.observe(peerId)

    val friends: Flow<List<PeerEntity>> = peers.friends()

    /** Метка «друг» — локальная: другой человек о ней не узнаёт, в эфир ничего не уходит. */
    suspend fun setFriend(peerId: String, friend: Boolean, now: Long) {
        peers.setFriendSince(peerId, if (friend) now else null)
    }

    /** Локальное имя peer'а; чистится теми же правилами, что и ник, пустое — снимает имя. */
    suspend fun setAlias(peerId: String, alias: String) {
        peers.setAlias(peerId, Nicknames.sanitize(alias).ifEmpty { null })
    }

    /**
     * Очистка истории. Друзья — список, который пользователь собирал руками, поэтому они
     * остаются, но с обнулёнными счётчиками; достижения хранятся отдельно и не трогаются.
     */
    suspend fun clearAll() {
        db.withTransaction {
            encounters.deleteAll()
            peers.deleteNonFriends()
            peers.resetFriends()
        }
    }

    companion object {
        /** RSSI всегда отрицателен, поэтому 0 однозначно означает «не сохранён». */
        const val RSSI_NOT_STORED = 0
        const val WEEK_DAYS = 7
    }
}
