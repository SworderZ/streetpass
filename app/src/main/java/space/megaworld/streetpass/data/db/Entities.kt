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

/** Строка истории: встреча плюс её порядковый номер у данного peer. */
data class EncounterRow(
    val id: Long,
    val peerId: String,
    val timestamp: Long,
    val rssi: Int,
    val firstMeeting: Boolean,
    val ordinal: Int,
)
