package space.megaworld.streetpass.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
) {
    val meeting = if (row.firstMeeting) {
        stringResource(R.string.first_meeting)
    } else {
        stringResource(R.string.meeting_number, row.ordinal)
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            PeerName(nickname = row.nickname, peerId = row.peerId)
            Text(
                text = if (row.nickname != null) "${Hex.short(row.peerId)} · $meeting" else meeting,
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

/** Ник, если peer его передаёт, иначе короткий ID моноширинным. */
@Composable
fun PeerName(
    nickname: String?,
    peerId: String,
    modifier: Modifier = Modifier,
    prefix: String = "",
) {
    if (nickname != null) {
        Text(
            text = prefix + nickname,
            style = MaterialTheme.typography.bodyLarge,
            modifier = modifier,
        )
    } else {
        Text(
            text = prefix + Hex.short(peerId),
            style = MaterialTheme.typography.bodyLarge,
            fontFamily = FontFamily.Monospace,
            modifier = modifier,
        )
    }
}
