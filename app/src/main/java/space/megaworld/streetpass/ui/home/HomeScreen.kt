package space.megaworld.streetpass.ui.home

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import space.megaworld.streetpass.AppContainer
import space.megaworld.streetpass.R
import space.megaworld.streetpass.ble.DiscoveryService
import space.megaworld.streetpass.ble.DiscoveryState
import space.megaworld.streetpass.core.Hex
import space.megaworld.streetpass.data.achievements.Achievement
import space.megaworld.streetpass.data.db.EncounterRow
import space.megaworld.streetpass.ui.AppViewModelProvider
import space.megaworld.streetpass.ui.Format
import space.megaworld.streetpass.ui.Permissions
import space.megaworld.streetpass.ui.components.EncounterItem
import space.megaworld.streetpass.ui.components.InfoCard
import space.megaworld.streetpass.ui.components.InfoTone
import space.megaworld.streetpass.ui.components.SectionTitle
import space.megaworld.streetpass.ui.components.StatTile
import space.megaworld.streetpass.ui.components.StatusDot
import space.megaworld.streetpass.ui.components.achievementTitle
import space.megaworld.streetpass.ui.peer.PeerDialog

data class HomeUiState(
    val discovery: DiscoveryState = DiscoveryState(),
    val permissionsGranted: Boolean = false,
    val bluetoothOn: Boolean = false,
    val notificationsAllowed: Boolean = true,
    val todayEncounters: Int = 0,
    val todayPeople: Int = 0,
    val totalPeers: Int = 0,
    val totalEncounters: Int = 0,
    val recent: List<EncounterRow> = emptyList(),
    val newAchievements: List<Achievement> = emptyList(),
    val peerId: String = "",
    val nickname: String = "",
)

class HomeViewModel(
    private val container: AppContainer,
    private val app: Application,
) : ViewModel() {

    private class Environment(
        val permissionsGranted: Boolean,
        val bluetoothOn: Boolean,
        val notificationsAllowed: Boolean,
    )

    private class Counts(val todayEncounters: Int, val todayPeople: Int, val totalPeers: Int, val totalEncounters: Int)

    private class Feed(val recent: List<EncounterRow>, val newAchievements: List<Achievement>)

    private class Identity(val peerId: String, val nickname: String)

    private val environment = MutableStateFlow(readEnvironment())

    private val counts = combine(
        container.encounterRepository.todayEncounters,
        container.encounterRepository.todayPeers,
        container.encounterRepository.totalPeers,
        container.encounterRepository.totalEncounters,
    ) { todayEncounters, todayPeople, totalPeers, totalEncounters ->
        Counts(todayEncounters, todayPeople, totalPeers, totalEncounters)
    }

    private val identity = combine(
        container.identityRepository.idHex,
        container.identityRepository.nickname,
    ) { id, nickname -> Identity(id, nickname) }

    private val feed = combine(
        container.encounterRepository.recent(RECENT_LIMIT),
        container.achievementRepository.unseen,
    ) { recent, unseen -> Feed(recent, unseen) }

    val uiState: StateFlow<HomeUiState> = combine(
        counts,
        feed,
        identity,
        container.discoveryState,
        environment,
    ) { counts, feed, identity, discovery, env ->
        HomeUiState(
            discovery = discovery,
            permissionsGranted = env.permissionsGranted,
            // Пока сервис работает, актуальнее его ресивер ACTION_STATE_CHANGED;
            // иначе полагаемся на проверку при ON_RESUME.
            bluetoothOn = if (discovery.running) discovery.bluetoothOn else env.bluetoothOn,
            notificationsAllowed = env.notificationsAllowed,
            todayEncounters = counts.todayEncounters,
            todayPeople = counts.todayPeople,
            totalPeers = counts.totalPeers,
            totalEncounters = counts.totalEncounters,
            recent = feed.recent,
            newAchievements = feed.newAchievements,
            peerId = identity.peerId,
            nickname = identity.nickname,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    /** Пользователь мог поменять разрешения или Bluetooth в системных настройках. */
    fun refreshEnvironment() {
        environment.value = readEnvironment()
    }

    fun setDiscoveryEnabled(enabled: Boolean) {
        if (enabled) DiscoveryService.start(app) else DiscoveryService.stop(app)
    }

    fun dismissNewAchievements() {
        viewModelScope.launch { container.achievementRepository.markSeen(System.currentTimeMillis()) }
    }

    private fun readEnvironment() = Environment(
        permissionsGranted = Permissions.hasBlePermissions(app),
        bluetoothOn = Permissions.isBluetoothEnabled(app),
        notificationsAllowed = Permissions.hasNotificationPermission(app),
    )

    companion object {
        const val RECENT_LIMIT = 5
    }
}

@Composable
fun HomeScreen(
    onOpenHistory: () -> Unit,
    viewModel: HomeViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.refreshEnvironment()
    }

    var startAfterPermissions by remember { mutableStateOf(false) }
    var selectedPeer by remember { mutableStateOf<String?>(null) }

    selectedPeer?.let { peerId ->
        PeerDialog(peerId = peerId, onDismiss = { selectedPeer = null })
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        viewModel.refreshEnvironment()
        if (startAfterPermissions && Permissions.hasBlePermissions(context)) {
            startAfterPermissions = false
            if (Permissions.isBluetoothEnabled(context)) viewModel.setDiscoveryEnabled(true)
        }
    }

    fun openBluetoothSettings() {
        // ACTION_REQUEST_ENABLE потребовал бы BLUETOOTH_CONNECT, которое больше ни для чего
        // не нужно, поэтому вместо системного диалога открываем настройки.
        try {
            context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
        } catch (e: ActivityNotFoundException) {
            context.startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    fun onToggle(enable: Boolean) {
        if (!enable) {
            viewModel.setDiscoveryEnabled(false)
            return
        }
        if (!state.permissionsGranted) {
            startAfterPermissions = true
            permissionLauncher.launch(Permissions.toRequest())
            return
        }
        if (!state.bluetoothOn) {
            openBluetoothSettings()
            return
        }
        viewModel.setDiscoveryEnabled(true)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { DiscoveryCard(state = state, onToggle = ::onToggle) }

        if (state.newAchievements.isNotEmpty()) {
            item {
                val first = achievementTitle(state.newAchievements.first())
                val rest = state.newAchievements.size - 1
                InfoCard(
                    title = stringResource(R.string.ach_new_title),
                    text = if (rest == 0) first else stringResource(R.string.ach_new_more, first, rest),
                    tone = InfoTone.NEUTRAL,
                    actionLabel = stringResource(R.string.ach_new_dismiss),
                    onAction = viewModel::dismissNewAchievements,
                )
            }
        }

        if (!state.permissionsGranted) {
            item {
                InfoCard(
                    title = stringResource(R.string.perm_title),
                    text = stringResource(
                        if (Permissions.needsLocationExplanation) R.string.perm_text_legacy else R.string.perm_text_modern,
                    ),
                    actionLabel = stringResource(R.string.perm_action),
                    onAction = { permissionLauncher.launch(Permissions.toRequest()) },
                )
            }
        }

        if (state.permissionsGranted && !state.bluetoothOn) {
            item {
                InfoCard(
                    title = stringResource(R.string.bt_off_title),
                    text = stringResource(R.string.bt_off_text),
                    actionLabel = stringResource(R.string.bt_off_action),
                    onAction = ::openBluetoothSettings,
                )
            }
        }

        val error = state.discovery.error
        if (error != null && state.bluetoothOn && state.discovery.running) {
            item {
                InfoCard(title = stringResource(R.string.ble_error_title), text = error, tone = InfoTone.ERROR)
            }
        }

        if (!state.discovery.advertisingSupported) {
            item {
                InfoCard(
                    title = stringResource(R.string.adv_unsupported_title),
                    text = stringResource(R.string.adv_unsupported_text),
                    tone = InfoTone.NEUTRAL,
                )
            }
        }

        if (state.permissionsGranted && !state.notificationsAllowed) {
            item {
                InfoCard(
                    title = stringResource(R.string.notif_denied_title),
                    text = stringResource(R.string.notif_denied_text),
                    tone = InfoTone.NEUTRAL,
                    actionLabel = stringResource(R.string.notif_allow),
                    onAction = { permissionLauncher.launch(Permissions.optional().toTypedArray()) },
                )
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatTile(stringResource(R.string.tile_today_encounters), state.todayEncounters.toString(), Modifier.weight(1f))
                    StatTile(stringResource(R.string.tile_today_people), state.todayPeople.toString(), Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatTile(stringResource(R.string.tile_total_people), state.totalPeers.toString(), Modifier.weight(1f))
                    StatTile(stringResource(R.string.tile_total_encounters), state.totalEncounters.toString(), Modifier.weight(1f))
                }
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SectionTitle(stringResource(R.string.recent_title))
                TextButton(onClick = onOpenHistory) { Text(stringResource(R.string.recent_all)) }
            }
        }

        if (state.recent.isEmpty()) {
            item {
                Text(
                    text = stringResource(R.string.recent_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            items(state.recent, key = { it.id }) { row ->
                Column {
                    EncounterItem(row, onClick = { selectedPeer = row.peerId })
                    HorizontalDivider()
                }
            }
        }

        item { IdentityCard(peerId = state.peerId, nickname = state.nickname) }
    }
}

@Composable
private fun DiscoveryCard(
    state: HomeUiState,
    onToggle: (Boolean) -> Unit,
) {
    val discovery = state.discovery
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(stringResource(R.string.discovery_title), style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = stringResource(if (discovery.running) R.string.discovery_running else R.string.discovery_off),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = discovery.running, onCheckedChange = onToggle)
            }
            Spacer(modifier = Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                StatusDot(active = discovery.advertising, label = stringResource(R.string.status_advertising))
                StatusDot(active = discovery.scanning, label = stringResource(R.string.status_scanning))
            }
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = stringResource(
                    R.string.last_signal,
                    discovery.lastSightingAt?.let { Format.timeWithSeconds(it) } ?: stringResource(R.string.none_dash),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun IdentityCard(peerId: String, nickname: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(stringResource(R.string.identity_title), style = MaterialTheme.typography.titleSmall)
            Spacer(modifier = Modifier.height(8.dp))
            if (nickname.isNotEmpty()) {
                Text(text = nickname, style = MaterialTheme.typography.titleLarge)
                Spacer(modifier = Modifier.height(4.dp))
            }
            Text(
                text = if (peerId.isEmpty()) "…" else Hex.grouped(peerId),
                style = if (nickname.isEmpty()) MaterialTheme.typography.titleLarge else MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
            )
            Spacer(modifier = Modifier.height(8.dp))
            if (nickname.isEmpty()) {
                Text(
                    text = stringResource(R.string.identity_no_nickname),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(4.dp))
            }
            Text(
                text = stringResource(R.string.identity_text),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
