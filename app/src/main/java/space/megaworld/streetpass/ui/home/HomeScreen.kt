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
import space.megaworld.streetpass.AppContainer
import space.megaworld.streetpass.ble.DiscoveryService
import space.megaworld.streetpass.ble.DiscoveryState
import space.megaworld.streetpass.core.Hex
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
    val peerId: String = "",
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

    private val environment = MutableStateFlow(readEnvironment())

    private val counts = combine(
        container.encounterRepository.todayEncounters,
        container.encounterRepository.todayPeers,
        container.encounterRepository.totalPeers,
        container.encounterRepository.totalEncounters,
    ) { todayEncounters, todayPeople, totalPeers, totalEncounters ->
        Counts(todayEncounters, todayPeople, totalPeers, totalEncounters)
    }

    val uiState: StateFlow<HomeUiState> = combine(
        counts,
        container.encounterRepository.recent(RECENT_LIMIT),
        container.identityRepository.idHex,
        container.discoveryState,
        environment,
    ) { counts, recent, peerId, discovery, env ->
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
            recent = recent,
            peerId = peerId,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    /** Пользователь мог поменять разрешения или Bluetooth в системных настройках. */
    fun refreshEnvironment() {
        environment.value = readEnvironment()
    }

    fun setDiscoveryEnabled(enabled: Boolean) {
        if (enabled) DiscoveryService.start(app) else DiscoveryService.stop(app)
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

        if (!state.permissionsGranted) {
            item {
                InfoCard(
                    title = "Нужны разрешения",
                    text = if (Permissions.needsLocationExplanation) {
                        "Для поиска других устройств по Bluetooth Android до 12-й версии требует " +
                            "разрешение на геолокацию. Приложение не определяет и не сохраняет " +
                            "местоположение — это требование системы к BLE-сканированию."
                    } else {
                        "Для передачи и поиска по Bluetooth нужны разрешения «Устройства поблизости». " +
                            "Геолокация не запрашивается."
                    },
                    actionLabel = "Выдать разрешения",
                    onAction = { permissionLauncher.launch(Permissions.toRequest()) },
                )
            }
        }

        if (state.permissionsGranted && !state.bluetoothOn) {
            item {
                InfoCard(
                    title = "Bluetooth выключен",
                    text = "Без Bluetooth обнаружение не работает. После включения оно продолжится само.",
                    actionLabel = "Открыть настройки Bluetooth",
                    onAction = ::openBluetoothSettings,
                )
            }
        }

        val error = state.discovery.error
        if (error != null && state.bluetoothOn && state.discovery.running) {
            item {
                InfoCard(title = "Ошибка Bluetooth", text = error, tone = InfoTone.ERROR)
            }
        }

        if (!state.discovery.advertisingSupported) {
            item {
                InfoCard(
                    title = "Передача недоступна",
                    text = "Bluetooth-чип этого устройства не умеет BLE-рекламу. Вы будете видеть " +
                        "других пользователей, но они вас — нет.",
                    tone = InfoTone.NEUTRAL,
                )
            }
        }

        if (state.permissionsGranted && !state.notificationsAllowed) {
            item {
                InfoCard(
                    title = "Уведомления запрещены",
                    text = "Обнаружение работает и без них, но постоянное уведомление со счётчиком " +
                        "показываться не будет.",
                    tone = InfoTone.NEUTRAL,
                    actionLabel = "Разрешить",
                    onAction = { permissionLauncher.launch(Permissions.optional().toTypedArray()) },
                )
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatTile("встреч сегодня", state.todayEncounters.toString(), Modifier.weight(1f))
                    StatTile("людей сегодня", state.todayPeople.toString(), Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatTile("уникальных всего", state.totalPeers.toString(), Modifier.weight(1f))
                    StatTile("встреч всего", state.totalEncounters.toString(), Modifier.weight(1f))
                }
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SectionTitle("Последние встречи")
                TextButton(onClick = onOpenHistory) { Text("Вся история") }
            }
        }

        if (state.recent.isEmpty()) {
            item {
                Text(
                    text = "Пока ни одной встречи. Включите обнаружение и носите телефон с собой — " +
                        "другие пользователи StreetPass появятся здесь.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            items(state.recent, key = { it.id }) { row ->
                Column {
                    EncounterItem(row)
                    HorizontalDivider()
                }
            }
        }

        item { IdentityCard(peerId = state.peerId) }
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
                    Text("Обнаружение", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = if (discovery.running) "Работает в фоне" else "Выключено",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = discovery.running, onCheckedChange = onToggle)
            }
            Spacer(modifier = Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                StatusDot(active = discovery.advertising, label = "Передача")
                StatusDot(active = discovery.scanning, label = "Сканирование")
            }
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Последний сигнал: " +
                    (discovery.lastSightingAt?.let { Format.timeWithSeconds(it) } ?: "—"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun IdentityCard(peerId: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Ваш анонимный ID", style = MaterialTheme.typography.titleSmall)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = if (peerId.isEmpty()) "…" else Hex.grouped(peerId),
                style = MaterialTheme.typography.titleLarge,
                fontFamily = FontFamily.Monospace,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Это всё, что телефон передаёт в эфир: 8 случайных байт, никак не связанных " +
                    "с вами, аккаунтом или устройством. По ним другие пользователи считают встречу " +
                    "с вами. Сменить ID можно в настройках.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
