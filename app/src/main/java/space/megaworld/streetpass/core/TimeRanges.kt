package space.megaworld.streetpass.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** Полуинтервал [startInclusive, endExclusive) в epoch millis. */
data class TimeRange(val startInclusive: Long, val endExclusive: Long)

object TimeRanges {

    fun startOfDay(date: LocalDate, zone: ZoneId = ZoneId.systemDefault()): Long =
        date.atStartOfDay(zone).toInstant().toEpochMilli()

    fun day(date: LocalDate, zone: ZoneId = ZoneId.systemDefault()): TimeRange =
        TimeRange(startOfDay(date, zone), startOfDay(date.plusDays(1), zone))

    /** Последние [days] суток, включая [today]. */
    fun lastDays(today: LocalDate, days: Int, zone: ZoneId = ZoneId.systemDefault()): TimeRange =
        TimeRange(
            startOfDay(today.minusDays(days - 1L), zone),
            startOfDay(today.plusDays(1), zone),
        )

    fun toLocalDate(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): LocalDate =
        Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate()

    fun millisUntilNextMidnight(zone: ZoneId = ZoneId.systemDefault()): Long {
        val nextMidnight = startOfDay(LocalDate.now(zone).plusDays(1), zone)
        return (nextMidnight - System.currentTimeMillis()).coerceAtLeast(1L)
    }

    /**
     * Эмитит текущую дату сразу и затем каждую полночь. Подписчики через flatMapLatest
     * переподписываются на запросы «за сегодня» — иначе в долго открытом приложении
     * дата залипает на вчерашней.
     */
    fun midnightTicker(zone: ZoneId = ZoneId.systemDefault()): Flow<LocalDate> = flow {
        while (true) {
            emit(LocalDate.now(zone))
            // Небольшой запас, чтобы после пробуждения LocalDate.now() точно был уже новым днём.
            delay(millisUntilNextMidnight(zone) + 100)
        }
    }
}
