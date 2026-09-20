package space.megaworld.streetpass.ui

import androidx.annotation.StringRes
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import space.megaworld.streetpass.R

/** Форматирование дат и чисел под текущую локаль; тексты — в ресурсах. */
object Format {

    private val locale: Locale
        get() = Locale.getDefault()

    fun time(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        Instant.ofEpochMilli(epochMillis).atZone(zone).format(DateTimeFormatter.ofPattern("HH:mm", locale))

    fun timeWithSeconds(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        Instant.ofEpochMilli(epochMillis).atZone(zone).format(DateTimeFormatter.ofPattern("HH:mm:ss", locale))

    fun dateTime(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        Instant.ofEpochMilli(epochMillis).atZone(zone).format(DateTimeFormatter.ofPattern("d MMM, HH:mm", locale))

    fun date(date: LocalDate): String = date.format(DateTimeFormatter.ofPattern("d MMMM yyyy", locale))

    fun weekdayShort(date: LocalDate): String =
        date.dayOfWeek.getDisplayName(TextStyle.SHORT, locale).lowercase(locale)

    fun decimal(value: Float): String = String.format(locale, "%.1f", value)

    @StringRes
    fun rssiDistanceHintRes(rssi: Int): Int = when {
        rssi >= -50 -> R.string.rssi_hint_touch
        rssi >= -65 -> R.string.rssi_hint_near
        rssi >= -80 -> R.string.rssi_hint_room
        rssi >= -90 -> R.string.rssi_hint_far
        else -> R.string.rssi_hint_max
    }
}
