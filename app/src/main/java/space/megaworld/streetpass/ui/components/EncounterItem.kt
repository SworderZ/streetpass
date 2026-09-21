package space.megaworld.streetpass.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import space.megaworld.streetpass.R
import space.megaworld.streetpass.core.Hex
import space.megaworld.streetpass.data.EncounterRepository
import space.megaworld.streetpass.data.db.EncounterRow
import space.megaworld.streetpass.ui.Format

@Composable
fun EncounterItem(
    row: EncounterRow,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val meeting = if (row.firstMeeting) {
        stringResource(R.string.first_meeting)
    } else {
        stringResource(R.string.meeting_number, row.ordinal)
    }
    val named = row.alias != null || row.nickname != null
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            PeerName(nickname = row.nickname, peerId = row.peerId, alias = row.alias, friend = row.friendSince != null)
            Text(
                text = if (named) "${Hex.short(row.peerId)} · $meeting" else meeting,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(text = Format.time(row.timestamp), style = MaterialTheme.typography.bodyMedium)
            if (row.rssi != EncounterRepository.RSSI_NOT_STORED) {
                Text(
                    text = stringResource(R.string.rssi_dbm, row.rssi),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Имя для показа: локальное имя, иначе ник из эфира, иначе короткий ID. */
fun displayName(alias: String?, nickname: String?, peerId: String): String =
    alias ?: nickname ?: Hex.short(peerId)

/** Локальное имя или ник, если есть, иначе короткий ID моноширинным; у друзей — сердечко. */
@Composable
fun PeerName(
    nickname: String?,
    peerId: String,
    modifier: Modifier = Modifier,
    alias: String? = null,
    friend: Boolean = false,
    prefix: String = "",
) {
    val name = alias ?: nickname
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        if (friend) {
            Icon(
                imageVector = Icons.Filled.Favorite,
                contentDescription = stringResource(R.string.friend_badge),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .padding(end = 4.dp)
                    .size(16.dp),
            )
        }
        if (name != null) {
            Text(text = prefix + name, style = MaterialTheme.typography.bodyLarge)
        } else {
            Text(
                text = prefix + Hex.short(peerId),
                style = MaterialTheme.typography.bodyLarge,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}
