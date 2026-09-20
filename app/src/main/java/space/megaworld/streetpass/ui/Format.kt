package space.megaworld.streetpass.ui

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

object Format {

    val locale: Locale = Locale.forLanguageTag("ru")

    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm", locale)
    private val timeWithSecondsFormatter = DateTimeFormatter.ofPattern("HH:mm:ss", locale)
    private val dateFormatter = DateTimeFormatter.ofPattern("d MMMM yyyy", locale)
    private val dateTimeFormatter = DateTimeFormatter.ofPattern("d MMM, HH:mm", locale)

    fun time(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        Instant.ofEpochMilli(epochMillis).atZone(zone).format(timeFormatter)

    fun timeWithSeconds(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        Instant.ofEpochMilli(epochMillis).atZone(zone).format(timeWithSecondsFormatter)

    fun dateTime(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        Instant.ofEpochMilli(epochMillis).atZone(zone).format(dateTimeFormatter)

    fun date(date: LocalDate): String = date.format(dateFormatter)

    /** «Сегодня», «Вчера» или полная дата. */
    fun dayTitle(date: LocalDate, today: LocalDate): String = when (date) {
        today -> "Сегодня"
        today.minusDays(1) -> "Вчера"
        else -> date(date)
    }

    fun weekdayShort(date: LocalDate): String =
        date.dayOfWeek.getDisplayName(TextStyle.SHORT, locale).lowercase(locale)

    /** Русское множественное число: plural(3, "встреча", "встречи", "встреч") → «3 встречи». */
    fun plural(count: Int, one: String, few: String, many: String): String {
        val n = count % 100
        val word = when {
            n in 11..19 -> many
            n % 10 == 1 -> one
            n % 10 in 2..4 -> few
            else -> many
        }
        return "$count $word"
    }

    fun encounters(count: Int): String = plural(count, "встреча", "встречи", "встреч")

    fun people(count: Int): String = plural(count, "человек", "человека", "человек")

    fun minutes(count: Int): String = plural(count, "минута", "минуты", "минут")

    fun rssiDistanceHint(rssi: Int): String = when {
        rssi >= -50 -> "только вплотную, до полуметра"
        rssi >= -65 -> "примерно 1–2 метра"
        rssi >= -80 -> "примерно 3–6 метров, одна комната"
        rssi >= -90 -> "около 10 метров, соседнее помещение"
        else -> "максимальная дальность, включая соседей за стеной"
    }
}
