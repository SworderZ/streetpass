package space.megaworld.streetpass.data.achievements

import androidx.room.withTransaction
import java.time.ZoneId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import space.megaworld.streetpass.core.Streaks
import space.megaworld.streetpass.core.TimeRanges
import space.megaworld.streetpass.data.db.AchievementEntity
import space.megaworld.streetpass.data.db.AppDatabase

/**
 * Достижения считаются по уже накопленной базе, а не по событиям: [check] можно звать
 * сколько угодно раз, лишних записей не будет. Полученное достижение остаётся навсегда —
 * даже после очистки истории.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AchievementRepository(
    private val db: AppDatabase,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    private val peers = db.peerDao()
    private val encounters = db.encounterDao()
    private val achievements = db.achievementDao()

    private val streak: Flow<Int> = TimeRanges.midnightTicker(zone).flatMapLatest { today ->
        val range = TimeRanges.lastDays(today, STREAK_WINDOW_DAYS, zone)
        encounters.timestampsBetween(range.startInclusive, range.endExclusive).map { timestamps ->
            Streaks.current(timestamps.mapTo(HashSet()) { TimeRanges.toLocalDate(it, zone) }, today)
        }
    }

    val metrics: Flow<AchievementMetrics> = combine(
        peers.countAll(),
        encounters.countAll(),
        peers.countFriends(),
        peers.maxFriendEncounters(),
        streak,
    ) { people, encounterCount, friends, friendEncounters, streakDays ->
        AchievementMetrics(
            people = people,
            encounters = encounterCount,
            friends = friends,
            friendEncounters = friendEncounters ?: 0,
            streak = streakDays,
        )
    }

    /** Все достижения в фиксированном порядке с прогрессом — для сетки на экране статистики. */
    val progress: Flow<List<AchievementProgress>> = combine(metrics, achievements.all()) { metrics, unlocked ->
        val unlockedAt = unlocked.associate { it.id to it.unlockedAt }
        Achievement.ALL.map { achievement ->
            AchievementProgress(
                achievement = achievement,
                current = metrics[achievement.kind],
                unlockedAt = unlockedAt[achievement.id],
            )
        }
    }

    /**
     * Для экрана: у каждого вида показываем полученные ступени и одну ближайшую закрытую.
     * «Записать 500 встреч» появляется только после 100 — иначе сетка пугает и занимает экран.
     */
    val visibleProgress: Flow<List<AchievementProgress>> = progress.map { all ->
        val shownLocked = HashSet<AchievementKind>()
        all.filter { item ->
            item.unlocked || shownLocked.add(item.achievement.kind)
        }
    }

    /** Полученные, но ещё не показанные пользователю — карточка на главном экране. */
    val unseen: Flow<List<Achievement>> = achievements.all().map { entities ->
        entities.filter { it.seenAt == null }.mapNotNull { Achievement.byId(it.id) }
    }

    /**
     * Сверяет метрики с порогами и записывает новые достижения. [now] передаётся параметром
     * ради детерминированных тестов. Возвращает только что полученные.
     */
    suspend fun check(now: Long): List<Achievement> = db.withTransaction {
        val today = TimeRanges.toLocalDate(now, zone)
        val range = TimeRanges.lastDays(today, STREAK_WINDOW_DAYS, zone)
        val days = encounters.timestampsBetweenOnce(range.startInclusive, range.endExclusive)
            .mapTo(HashSet()) { TimeRanges.toLocalDate(it, zone) }
        val metrics = AchievementMetrics(
            people = peers.countAllOnce(),
            encounters = encounters.countAllOnce(),
            friends = peers.countFriendsOnce(),
            friendEncounters = peers.maxFriendEncountersOnce() ?: 0,
            streak = Streaks.current(days, today),
        )
        val already = achievements.unlockedIds().toHashSet()
        val fresh = Achievement.ALL.filter { it.id !in already && metrics[it.kind] >= it.threshold }
        fresh.forEach { achievements.insertIgnore(AchievementEntity(id = it.id, unlockedAt = now)) }
        fresh
    }

    suspend fun markSeen(now: Long) {
        achievements.markAllSeen(now)
    }

    companion object {
        /** Запас над самым длинным порогом серии, чтобы выборка не обрезала её. */
        const val STREAK_WINDOW_DAYS = 60
    }
}
