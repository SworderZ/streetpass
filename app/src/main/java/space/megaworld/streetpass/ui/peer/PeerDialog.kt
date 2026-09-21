package space.megaworld.streetpass.ui.peer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import space.megaworld.streetpass.AppContainer
import space.megaworld.streetpass.R
import space.megaworld.streetpass.core.BleConstants
import space.megaworld.streetpass.core.Hex
import space.megaworld.streetpass.core.Nicknames
import space.megaworld.streetpass.data.db.PeerEntity
import space.megaworld.streetpass.ui.AppViewModelProvider
import space.megaworld.streetpass.ui.Format
import space.megaworld.streetpass.ui.components.displayName

/**
 * Карточка peer'а: отметить другом, дать локальное имя. Открывается по нажатию на строку
 * встречи с любого экрана, поэтому живёт отдельно от экранов вместе со своей ViewModel.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PeerViewModel(private val container: AppContainer) : ViewModel() {

    private val peerId = MutableStateFlow<String?>(null)

    val peer: StateFlow<PeerEntity?> = peerId
        .flatMapLatest { id -> if (id == null) flowOf(null) else container.encounterRepository.peer(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun open(id: String) {
        peerId.value = id
    }

    fun setFriend(friend: Boolean) {
        val id = peerId.value ?: return
        viewModelScope.launch {
            container.encounterRepository.setFriend(id, friend, System.currentTimeMillis())
            // Достижения за друзей зависят от списка, а не от встреч — пересчёт сразу.
            container.achievementRepository.check(System.currentTimeMillis())
        }
    }

    fun setAlias(alias: String) {
        val id = peerId.value ?: return
        viewModelScope.launch { container.encounterRepository.setAlias(id, alias) }
    }
}

@Composable
fun PeerDialog(
    peerId: String,
    onDismiss: () -> Unit,
    viewModel: PeerViewModel = viewModel(key = "peer-$peerId", factory = AppViewModelProvider.Factory),
) {
    LaunchedEffect(peerId) { viewModel.open(peerId) }
    val peer by viewModel.peer.collectAsStateWithLifecycle()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(peer?.let { displayName(it.alias, it.nickname, it.peerId) } ?: Hex.short(peerId)) },
        text = {
            val current = peer
            if (current == null) {
                Text(stringResource(R.string.none_dash))
            } else {
                PeerDetails(
                    peer = current,
                    onFriendChange = viewModel::setFriend,
                    onAliasSave = viewModel::setAlias,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
        },
    )
}

@Composable
private fun PeerDetails(
    peer: PeerEntity,
    onFriendChange: (Boolean) -> Unit,
    onAliasSave: (String) -> Unit,
) {
    Column {
        Text(
            text = stringResource(R.string.peer_id_label),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = Hex.grouped(peer.peerId),
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
        )
        if (peer.nickname != null && peer.alias != null) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.nickname_label) + ": " + peer.nickname,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = pluralStringResource(R.plurals.encounters_count, peer.encounterCount, peer.encounterCount),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = stringResource(R.string.peer_first_seen, Format.dateTime(peer.firstSeenAt)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // После очистки истории у друга lastEncounterAt = 0 — даты нет, строку не показываем.
        if (peer.lastEncounterAt > 0) {
            Text(
                text = stringResource(R.string.peer_last_seen, Format.dateTime(peer.lastEncounterAt)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        HorizontalDivider()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.peer_friend_title), style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = peer.friendSince?.let { stringResource(R.string.friend_since, Format.dateTime(it)) }
                        ?: stringResource(R.string.peer_friend_sub),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = peer.isFriend, onCheckedChange = onFriendChange)
        }
        HorizontalDivider()
        Spacer(modifier = Modifier.height(12.dp))
        AliasEditor(saved = peer.alias.orEmpty(), onSave = onAliasSave)
    }
}

@Composable
private fun AliasEditor(
    saved: String,
    onSave: (String) -> Unit,
) {
    var draft by remember(saved) { mutableStateOf(saved) }
    val clean = Nicknames.sanitize(draft)
    val dirty = clean != saved

    OutlinedTextField(
        value = draft,
        onValueChange = { draft = it },
        label = { Text(stringResource(R.string.peer_alias_label)) },
        supportingText = {
            Text(stringResource(R.string.nickname_bytes, Nicknames.byteLength(clean), BleConstants.NICKNAME_MAX_BYTES))
        },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Text(
        text = stringResource(R.string.peer_alias_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(modifier = Modifier.height(8.dp))
    Button(onClick = { onSave(clean) }, enabled = dirty) {
        Text(stringResource(R.string.peer_alias_save))
    }
}
