package space.megaworld.streetpass.core

import java.time.LocalDate

object Streaks {

    /**
     * Длина серии дней подряд со встречами, заканчивающейся сегодня. Если сегодня встреч ещё
     * не было, серия считается от вчера — она ещё не прервана, день не закончился.
     */
    fun current(daysWithEncounters: Set<LocalDate>, today: LocalDate): Int {
        var day = if (today in daysWithEncounters) today else today.minusDays(1)
        var length = 0
        while (day in daysWithEncounters) {
            length++
            day = day.minusDays(1)
        }
        return length
    }
}
