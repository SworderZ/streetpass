package space.megaworld.streetpass.data.achievements

/**
 * Что измеряет достижение. Все метрики — целые счётчики, порог сравнивается через `>=`.
 */
enum class AchievementKind {
    /** Уникальных людей встречено. */
    PEOPLE,

    /** Встреч всего. */
    ENCOUNTERS,

    /** Друзей в списке. */
    FRIENDS,

    /** Встреч с одним и тем же другом (максимум по друзьям). */
    FRIEND_ENCOUNTERS,

    /** Дней подряд со встречами. */
    STREAK,
}

/**
 * Определение достижения. Список фиксирован в коде: id попадает в базу, поэтому
 * существующие id не переименовывать — иначе полученные достижения «потеряются».
 */
data class Achievement(
    val id: String,
    val kind: AchievementKind,
    val threshold: Int,
) {
    companion object {
        val ALL: List<Achievement> = buildList {
            tiers(AchievementKind.PEOPLE, "people", 5, 15, 30, 50, 100)
            tiers(AchievementKind.ENCOUNTERS, "encounters", 10, 50, 100, 500)
            tiers(AchievementKind.FRIENDS, "friends", 1, 5, 10)
            tiers(AchievementKind.FRIEND_ENCOUNTERS, "friend_encounters", 10, 25, 50, 100)
            tiers(AchievementKind.STREAK, "streak", 7, 30)
        }

        fun byId(id: String): Achievement? = ALL.firstOrNull { it.id == id }

        private fun MutableList<Achievement>.tiers(kind: AchievementKind, prefix: String, vararg thresholds: Int) {
            thresholds.mapTo(this) { Achievement("${prefix}_$it", kind, it) }
        }
    }
}

/** Текущие значения всех метрик — одна выборка, по которой оцениваются все достижения. */
data class AchievementMetrics(
    val people: Int = 0,
    val encounters: Int = 0,
    val friends: Int = 0,
    val friendEncounters: Int = 0,
    val streak: Int = 0,
) {
    operator fun get(kind: AchievementKind): Int = when (kind) {
        AchievementKind.PEOPLE -> people
        AchievementKind.ENCOUNTERS -> encounters
        AchievementKind.FRIENDS -> friends
        AchievementKind.FRIEND_ENCOUNTERS -> friendEncounters
        AchievementKind.STREAK -> streak
    }
}

/** Достижение вместе с прогрессом для UI. */
data class AchievementProgress(
    val achievement: Achievement,
    val current: Int,
    val unlockedAt: Long?,
) {
    val unlocked: Boolean
        get() = unlockedAt != null

    /** Для полоски прогресса: полученное достижение — всегда полная. */
    val fraction: Float
        get() = if (unlocked) 1f else (current.toFloat() / achievement.threshold).coerceIn(0f, 1f)
}
