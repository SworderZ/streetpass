package space.megaworld.streetpass.ui.settings

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import space.megaworld.streetpass.AppContainer
import space.megaworld.streetpass.BuildConfig
import space.megaworld.streetpass.ble.DiscoveryService
import space.megaworld.streetpass.core.Hex
import space.megaworld.streetpass.data.settings.AppSettings
import space.megaworld.streetpass.data.settings.PowerMode
import space.megaworld.streetpass.data.update.ReleaseInfo
import space.megaworld.streetpass.data.update.UpdateState
import space.megaworld.streetpass.ui.AppViewModelProvider
import space.megaworld.streetpass.ui.Format
import space.megaworld.streetpass.ui.components.SectionTitle

class SettingsViewModel(
    private val container: AppContainer,
    private val app: Application,
) : ViewModel() {

    val settings: StateFlow<AppSettings> = container.settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    val peerId: StateFlow<String> = container.identityRepository.idHex
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    fun setAdvertiseEnabled(value: Boolean) = updateRadio { container.settingsRepository.setAdvertiseEnabled(value) }

    fun setScanEnabled(value: Boolean) = updateRadio { container.settingsRepository.setScanEnabled(value) }

    fun setPowerMode(value: PowerMode) = updateRadio { container.settingsRepository.setPowerMode(value) }

    fun regenerateId() = updateRadio { container.identityRepository.regenerate() }

    fun setAutoStart(value: Boolean) {
        viewModelScope.launch { container.settingsRepository.setAutoStart(value) }
    }

    // Антидубль, порог и хранение RSSI сервис читает из живого Flow настроек —
    // перезапуск радио не нужен.
    fun setCooldownMinutes(value: Int) {
        viewModelScope.launch { container.settingsRepository.setCooldownMinutes(value) }
    }

    fun setMinRssi(value: Int) {
        viewModelScope.launch { container.settingsRepository.setMinRssi(value) }
    }

    fun setStoreRssi(value: Boolean) {
        viewModelScope.launch { container.settingsRepository.setStoreRssi(value) }
    }

    fun clearHistory() {
        viewModelScope.launch { container.encounterRepository.clearAll() }
    }

    val updateState: StateFlow<UpdateState> = container.updateRepository.state

    val currentVersion: String = BuildConfig.VERSION_NAME

    fun checkForUpdates() {
        viewModelScope.launch { container.updateRepository.check() }
    }

    fun canInstallPackages(): Boolean = container.updateRepository.canInstallPackages()

    fun downloadUpdate(release: ReleaseInfo) = container.updateRepository.download(release)

    fun resetUpdateState() = container.updateRepository.reset()

    /** Настройки, влияющие на эфир: после записи перезапускаем радио без остановки сервиса. */
    private fun updateRadio(write: suspend () -> Unit) {
        viewModelScope.launch {
            write()
            if (container.discoveryState.value.running) DiscoveryService.refresh(app)
        }
    }
}

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val peerId by viewModel.peerId.collectAsStateWithLifecycle()
    val updateState by viewModel.updateState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var confirmRegenerate by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SectionTitle("Обнаружение")
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    SwitchRow(
                        title = "Передавать свой ID",
                        subtitle = "Другие пользователи смогут засчитать встречу с вами",
                        checked = settings.advertiseEnabled,
                        onChange = viewModel::setAdvertiseEnabled,
                    )
                    HorizontalDivider()
                    SwitchRow(
                        title = "Искать других",
                        subtitle = "Сканировать эфир и записывать встречи",
                        checked = settings.scanEnabled,
                        onChange = viewModel::setScanEnabled,
                    )
                    HorizontalDivider()
                    SwitchRow(
                        title = "Автозапуск после перезагрузки",
                        subtitle = "Если обнаружение было включено, поднять его при старте системы",
                        checked = settings.autoStart,
                        onChange = viewModel::setAutoStart,
                    )
                }
            }
        }

        item {
            SectionTitle("Энергопотребление")
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(vertical = 4.dp)) {
                    PowerMode.entries.forEach { mode ->
                        PowerModeRow(
                            mode = mode,
                            selected = settings.powerMode == mode,
                            onSelect = { viewModel.setPowerMode(mode) },
                        )
                    }
                }
            }
        }

        item {
            SectionTitle("Антидубль")
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    CooldownSlider(value = settings.cooldownMinutes, onCommit = viewModel::setCooldownMinutes)
                }
            }
        }

        item {
            SectionTitle("Порог сигнала")
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    RssiSlider(value = settings.minRssi, onCommit = viewModel::setMinRssi)
                }
            }
        }

        item {
            SectionTitle("Приватность")
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Что собирается: случайный 8-байтовый ID каждого встреченного устройства, " +
                            "время встречи и, если включено ниже, уровень сигнала.\n\n" +
                            "Что не собирается: местоположение, MAC-адреса, имена устройств, " +
                            "контакты, любые данные о вас. Всё хранится только на этом телефоне: " +
                            "у приложения нет доступа в интернет и оно исключено из облачного бэкапа.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    SwitchRow(
                        title = "Хранить уровень сигнала",
                        subtitle = "RSSI в истории помогает понять, насколько близко был человек",
                        checked = settings.storeRssi,
                        onChange = viewModel::setStoreRssi,
                    )
                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("Ваш ID", style = MaterialTheme.typography.labelMedium)
                    Text(
                        text = if (peerId.isEmpty()) "…" else Hex.grouped(peerId),
                        style = MaterialTheme.typography.titleMedium,
                        fontFamily = FontFamily.Monospace,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClick = { confirmRegenerate = true }, modifier = Modifier.weight(1f)) {
                            Text("Сменить ID")
                        }
                        OutlinedButton(onClick = { confirmClear = true }, modifier = Modifier.weight(1f)) {
                            Text("Очистить историю")
                        }
                    }
                }
            }
        }

        item {
            SectionTitle("Обновления")
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    UpdateSection(
                        currentVersion = viewModel.currentVersion,
                        state = updateState,
                        onCheck = viewModel::checkForUpdates,
                        onDownload = { release ->
                            if (viewModel.canInstallPackages()) {
                                viewModel.downloadUpdate(release)
                            } else {
                                // Без этого права установщик откажет; система открывает экран
                                // разрешения именно для нашего пакета.
                                context.startActivity(
                                    Intent(
                                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                        Uri.parse("package:${context.packageName}"),
                                    ),
                                )
                            }
                        },
                        onOpenPage = { url ->
                            try {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                            } catch (e: ActivityNotFoundException) {
                                // Браузера нет — показать нечего, ссылка и так на экране.
                            }
                        },
                        onReset = viewModel::resetUpdateState,
                    )
                }
            }
        }
    }

    if (confirmRegenerate) {
        AlertDialog(
            onDismissRequest = { confirmRegenerate = false },
            title = { Text("Сменить ID?") },
            text = {
                Text(
                    "Другие пользователи начнут видеть вас как нового человека: при следующей " +
                        "встрече у них запишется «первая встреча». Ваша история не изменится.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmRegenerate = false
                    viewModel.regenerateId()
                }) { Text("Сменить") }
            },
            dismissButton = {
                TextButton(onClick = { confirmRegenerate = false }) { Text("Отмена") }
            },
        )
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Очистить историю?") },
            text = { Text("Все встречи и статистика будут удалены безвозвратно. Обнаружение продолжит работать.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    viewModel.clearHistory()
                }) { Text("Очистить") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("Отмена") }
            },
        )
    }
}

@Composable
private fun UpdateSection(
    currentVersion: String,
    state: UpdateState,
    onCheck: () -> Unit,
    onDownload: (ReleaseInfo) -> Unit,
    onOpenPage: (String) -> Unit,
    onReset: () -> Unit,
) {
    Text("Установлена версия $currentVersion", style = MaterialTheme.typography.bodyMedium)
    Spacer(modifier = Modifier.height(8.dp))
    when (state) {
        UpdateState.Idle -> {
            Text(
                text = "Проверка — единственное, ради чего приложению нужен интернет: один запрос " +
                    "к GitHub по нажатию кнопки. О встречах и вашем ID в нём ничего нет.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedButton(onClick = onCheck) { Text("Проверить обновления") }
        }
        UpdateState.Checking -> {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Text("Спрашиваем GitHub…", style = MaterialTheme.typography.bodyMedium)
            }
        }
        is UpdateState.UpToDate -> {
            Text(
                text = "У вас последняя версия (на GitHub — ${state.latest}).",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedButton(onClick = onCheck) { Text("Проверить ещё раз") }
        }
        is UpdateState.Available -> {
            ReleaseDetails(state.release)
            Spacer(modifier = Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (state.release.apkUrl != null) {
                    Button(onClick = { onDownload(state.release) }, modifier = Modifier.weight(1f)) {
                        Text("Скачать и установить")
                    }
                }
                OutlinedButton(onClick = { onOpenPage(state.release.pageUrl) }, modifier = Modifier.weight(1f)) {
                    Text("Открыть на GitHub")
                }
            }
        }
        is UpdateState.Downloading -> {
            Text("Загрузка ${state.release.version}…", style = MaterialTheme.typography.bodyMedium)
            Spacer(modifier = Modifier.height(8.dp))
            val percent = state.percent
            if (percent == null) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(progress = { percent / 100f }, modifier = Modifier.fillMaxWidth())
            }
        }
        is UpdateState.Downloaded -> {
            Text(
                text = "Файл скачан, должен открыться установщик. Если он не появился — " +
                    "откройте уведомление о загрузке.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { onDownload(state.release) }) { Text("Скачать заново") }
                TextButton(onClick = onReset) { Text("Закрыть") }
            }
        }
        is UpdateState.Error -> {
            Text(
                text = state.message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val release = state.release
                if (release?.apkUrl != null) {
                    Button(onClick = { onDownload(release) }) { Text("Повторить загрузку") }
                } else {
                    OutlinedButton(onClick = onCheck) { Text("Повторить") }
                }
                TextButton(onClick = onReset) { Text("Закрыть") }
            }
        }
    }
}

@Composable
private fun ReleaseDetails(release: ReleaseInfo) {
    Text(
        text = "Доступна версия ${release.version}",
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
    if (release.notes.isNotEmpty()) {
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = release.notes,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 12,
            overflow = TextOverflow.Ellipsis,
        )
    }
    if (release.apkUrl == null) {
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "К релизу не приложен APK — скачать можно только вручную со страницы релиза.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.padding(horizontal = 8.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun PowerModeRow(
    mode: PowerMode,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Column(modifier = Modifier.padding(start = 4.dp)) {
            Text(mode.title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = mode.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CooldownSlider(
    value: Int,
    onCommit: (Int) -> Unit,
) {
    // Локальное состояние на время перетаскивания: в DataStore пишем только по отпусканию.
    var local by remember(value) { mutableIntStateOf(value) }
    val range = AppSettings.COOLDOWN_RANGE
    Text(
        text = "Повторная встреча с тем же человеком засчитывается не раньше, чем через " +
            Format.minutes(local),
        style = MaterialTheme.typography.bodyMedium,
    )
    Slider(
        value = local.toFloat(),
        onValueChange = { local = it.roundToInt().coerceIn(range) },
        onValueChangeFinished = { onCommit(local) },
        valueRange = range.first.toFloat()..range.last.toFloat(),
    )
    Text(
        text = "Всё это время человек может быть рядом, сигнал будет приниматься, но новая " +
            "встреча в историю не попадёт. Диапазон: ${range.first}–${range.last} минут.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun RssiSlider(
    value: Int,
    onCommit: (Int) -> Unit,
) {
    var local by remember(value) { mutableIntStateOf(value) }
    val range = AppSettings.RSSI_RANGE
    Text(
        text = "Не ниже $local dBm — ${Format.rssiDistanceHint(local)}",
        style = MaterialTheme.typography.bodyMedium,
    )
    Slider(
        value = local.toFloat(),
        onValueChange = { local = it.roundToInt().coerceIn(range) },
        onValueChangeFinished = { onCommit(local) },
        valueRange = range.first.toFloat()..range.last.toFloat(),
    )
    Text(
        text = "Пакеты слабее порога отбрасываются: так не считаются люди за стеной или этажом " +
            "ниже. Чем ближе к −40, тем ближе должен быть человек. Оценка дистанции " +
            "приблизительная и зависит от телефона.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
