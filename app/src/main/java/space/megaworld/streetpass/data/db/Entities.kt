package space.megaworld.streetpass.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "peers")
data class PeerEntity(
    @PrimaryKey val peerId: String,
    val firstSeenAt: Long,
    val lastSeenAt: Long,
    val lastEncounterAt: Long,
    val encounterCount: Int,
    val lastRssi: Int,
    val bestRssi: Int,
    /** Последний ник, который peer передавал в эфир; null — ник не задан или не пойман. */
    val nickname: String? = null,
    /** Когда пользователь отметил peer'а другом; null — не друг. Метка локальная, в эфир не уходит. */
    val friendSince: Long? = null,
    /** Имя, которое пользователь дал peer'у сам; хранится только на этом телефоне. */
    val alias: String? = null,
) {
    val isFriend: Boolean
        get() = friendSince != null
}

/**
 * Полученное достижение. Определения живут в коде ([space.megaworld.streetpass.data.achievements.Achievement]),
 * в базе — только факт и время получения, чтобы дата не «плыла» и достижения переживали очистку истории.
 */
@Entity(tableName = "achievements")
data class AchievementEntity(
    @PrimaryKey val id: String,
    val unlockedAt: Long,
    /** null — пользователь ещё не видел карточку о новом достижении. */
    val seenAt: Long? = null,
)

@Entity(
    tableName = "encounters",
    foreignKeys = [
        ForeignKey(
            entity = PeerEntity::class,
            parentColumns = ["peerId"],
            childColumns = ["peerId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("peerId"), Index("timestamp")],
)
data class EncounterEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val peerId: String,
    val timestamp: Long,
    val rssi: Int,
    val firstMeeting: Boolean,
)

/** Строка истории: встреча плюс её порядковый номер у данного peer, его текущий ник и локальные пометки. */
data class EncounterRow(
    val id: Long,
    val peerId: String,
    val nickname: String?,
    val alias: String?,
    val friendSince: Long?,
    val timestamp: Long,
    val rssi: Int,
    val firstMeeting: Boolean,
    val ordinal: Int,
)
