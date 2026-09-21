package space.megaworld.streetpass.core

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class StreaksTest {

    private val today = LocalDate.of(2026, 3, 10)

    private fun days(vararg offsets: Long) = offsets.map { today.minusDays(it) }.toSet()

    @Test
    fun emptyIsZero() {
        assertEquals(0, Streaks.current(emptySet(), today))
    }

    @Test
    fun countsConsecutiveDaysEndingToday() {
        assertEquals(3, Streaks.current(days(0, 1, 2), today))
    }

    @Test
    fun gapBreaksTheStreak() {
        assertEquals(2, Streaks.current(days(0, 1, 3, 4, 5), today))
    }

    @Test
    fun streakEndingYesterdayIsStillAlive() {
        // Сегодня встреч ещё не было — день не закончился, серия не прервана.
        assertEquals(4, Streaks.current(days(1, 2, 3, 4), today))
    }

    @Test
    fun streakEndingTwoDaysAgoIsBroken() {
        assertEquals(0, Streaks.current(days(2, 3, 4), today))
    }
}
