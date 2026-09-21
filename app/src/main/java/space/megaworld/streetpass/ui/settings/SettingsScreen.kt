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
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
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
import space.megaworld.streetpass.R
import space.megaworld.streetpass.ble.DiscoveryService
import space.megaworld.streetpass.core.BleConstants
import space.megaworld.streetpass.core.Hex
import space.megaworld.streetpass.core.Nicknames
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

    val nickname: StateFlow<String> = container.identityRepository.nickname
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    fun setNickname(value: String) = updateRadio { container.identityRepository.setNickname(value) }

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

    fun setAcceptUnsigned(value: Boolean) {
        viewModelScope.launch { container.settingsRepository.setAcceptUnsigned(value) }
    }

    fun clearHistory() {
        viewModelScope.launch { container.encounterRepository.clearAll() }
    }

    val updateState: StateFlow<UpdateState> = container.updateRepository.state

    val currentVersion: String = BuildConfig.VERSION_NAME

    /** Страница релизов — на случай, если проверка или скачивание из приложения не работают. */
    val releasesUrl: String = "${container.projectUrl}/releases"

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
    val nickname by viewModel.nickname.collectAsStateWithLifecycle()
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
            SectionTitle(stringResource(R.string.section_profile))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    NicknameEditor(saved = nickname, onSave = viewModel::setNickname)
                }
            }
        }

        item {
            SectionTitle(stringResource(R.string.section_discovery))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    SwitchRow(
                        title = stringResource(R.string.sw_advertise_title),
                        subtitle = stringResource(R.string.sw_advertise_sub),
                        checked = settings.advertiseEnabled,
                        onChange = viewModel::setAdvertiseEnabled,
                    )
                    HorizontalDivider()
                    SwitchRow(
                        title = stringResource(R.string.sw_scan_title),
                        subtitle = stringResource(R.string.sw_scan_sub),
                        checked = settings.scanEnabled,
                        onChange = viewModel::setScanEnabled,
                    )
                    HorizontalDivider()
                    SwitchRow(
                        title = stringResource(R.string.sw_accept_unsigned_title),
                        subtitle = stringResource(R.string.sw_accept_unsigned_sub),
                        checked = settings.acceptUnsigned,
                        onChange = viewModel::setAcceptUnsigned,
                    )
                    HorizontalDivider()
                    SwitchRow(
                        title = stringResource(R.string.sw_autostart_title),
                        subtitle = stringResource(R.string.sw_autostart_sub),
                        checked = settings.autoStart,
                        onChange = viewModel::setAutoStart,
                    )
                }
            }
        }

        item {
            SectionTitle(stringResource(R.string.section_power))
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
            SectionTitle(stringResource(R.string.section_cooldown))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    CooldownSlider(value = settings.cooldownMinutes, onCommit = viewModel::setCooldownMinutes)
                }
            }
        }

        item {
            SectionTitle(stringResource(R.string.section_rssi))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    RssiSlider(value = settings.minRssi, onCommit = viewModel::setMinRssi)
                }
            }
        }

        item {
            SectionTitle(stringResource(R.string.section_privacy))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    SwitchRow(
                        title = stringResource(R.string.sw_store_rssi_title),
                        subtitle = stringResource(R.string.sw_store_rssi_sub),
                        checked = settings.storeRssi,
                        onChange = viewModel::setStoreRssi,
                    )
                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.your_id),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = if (peerId.isEmpty()) "…" else Hex.grouped(peerId),
                        style = MaterialTheme.typography.titleMedium,
                        fontFamily = FontFamily.Monospace,
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    // Кнопки во всю ширину, одна под другой: в две колонки подписи переносились
                    // на разное число строк и кнопки выходили разной высоты.
                    OutlinedButton(onClick = { confirmRegenerate = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.btn_change_id))
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    OutlinedButton(onClick = { confirmClear = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.btn_clear_history))
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                }
            }
        }

        item {
            SectionTitle(stringResource(R.string.section_updates))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    UpdateSection(
                        currentVersion = viewModel.currentVersion,
                        releasesUrl = viewModel.releasesUrl,
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
            title = { Text(stringResource(R.string.dialog_regen_title)) },
            text = { Text(stringResource(R.string.dialog_regen_text)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmRegenerate = false
                    viewModel.regenerateId()
                }) { Text(stringResource(R.string.dialog_regen_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmRegenerate = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.dialog_clear_title)) },
            text = { Text(stringResource(R.string.dialog_clear_text)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    viewModel.clearHistory()
                }) { Text(stringResource(R.string.dialog_clear_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun NicknameEditor(
    saved: String,
    onSave: (String) -> Unit,
) {
    var draft by remember(saved) { mutableStateOf(saved) }
    val clean = Nicknames.sanitize(draft)
    val bytes = Nicknames.byteLength(clean)
    val dirty = clean != saved

    OutlinedTextField(
        value = draft,
        onValueChange = { draft = it },
        label = { Text(stringResource(R.string.nickname_label)) },
        supportingText = {
            Text(stringResource(R.string.nickname_bytes, bytes, BleConstants.NICKNAME_MAX_BYTES))
        },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(modifier = Modifier.height(4.dp))
    Text(
        text = stringResource(R.string.nickname_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(modifier = Modifier.height(8.dp))
    Text(
        text = stringResource(R.string.nickname_warning),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(modifier = Modifier.height(12.dp))
    Button(onClick = { onSave(clean) }, enabled = dirty) {
        Text(stringResource(R.string.nickname_save))
    }
}

@Composable
private fun UpdateSection(
    currentVersion: String,
    releasesUrl: String,
    state: UpdateState,
    onCheck: () -> Unit,
    onDownload: (ReleaseInfo) -> Unit,
    onOpenPage: (String) -> Unit,
    onReset: () -> Unit,
) {
    Text(stringResource(R.string.update_installed, currentVersion), style = MaterialTheme.typography.bodyMedium)
    Spacer(modifier = Modifier.height(8.dp))
    when (state) {
        UpdateState.Idle -> {
            Text(
                text = stringResource(R.string.update_idle_text),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedButton(onClick = onCheck, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.update_check))
            }
        }
        UpdateState.Checking -> {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Text(stringResource(R.string.update_checking), style = MaterialTheme.typography.bodyMedium)
            }
        }
        is UpdateState.UpToDate -> {
            Text(
                text = stringResource(R.string.update_up_to_date, state.latest),
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedButton(onClick = onCheck, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.update_check_again))
            }
        }
        is UpdateState.Available -> {
            ReleaseDetails(state.release)
            Spacer(modifier = Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (state.release.apkUrl != null) {
                    Button(onClick = { onDownload(state.release) }, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.update_download))
                    }
                }
                OutlinedButton(onClick = { onOpenPage(state.release.pageUrl) }, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.update_open_github))
                }
            }
        }
        is UpdateState.Downloading -> {
            Text(stringResource(R.string.update_downloading, state.release.version), style = MaterialTheme.typography.bodyMedium)
            Spacer(modifier = Modifier.height(8.dp))
            val percent = state.percent
            if (percent == null) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(progress = { percent / 100f }, modifier = Modifier.fillMaxWidth())
            }
        }
        is UpdateState.Downloaded -> {
            Text(text = stringResource(R.string.update_downloaded_text), style = MaterialTheme.typography.bodyMedium)
            Spacer(modifier = Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { onDownload(state.release) }) { Text(stringResource(R.string.update_redownload)) }
                TextButton(onClick = onReset) { Text(stringResource(R.string.close)) }
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
                    Button(onClick = { onDownload(release) }) { Text(stringResource(R.string.update_retry_download)) }
                } else {
                    OutlinedButton(onClick = onCheck) { Text(stringResource(R.string.retry)) }
                }
                TextButton(onClick = onReset) { Text(stringResource(R.string.close)) }
            }
        }
    }
    if (state !is UpdateState.Available) {
        // Прямой путь на страницу релизов: проверка и скачивание из приложения работают не везде.
        Spacer(modifier = Modifier.height(8.dp))
        TextButton(onClick = { onOpenPage(releasesUrl) }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.update_releases_github))
        }
    }
}

@Composable
private fun ReleaseDetails(release: ReleaseInfo) {
    Text(
        text = stringResource(R.string.update_available, release.version),
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
            text = stringResource(R.string.update_no_apk),
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
            Text(stringResource(mode.titleRes), style = MaterialTheme.typography.bodyLarge)
            Text(
                text = stringResource(mode.descriptionRes),
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
    val step = AppSettings.COOLDOWN_STEP_MINUTES
    Text(
        text = stringResource(R.string.cooldown_text, Format.duration(local)),
        style = MaterialTheme.typography.bodyMedium,
    )
    Slider(
        value = local.toFloat(),
        // Диапазон до 12 часов: без шага по 5 минут ползунок дёргается по одной минуте.
        onValueChange = { local = ((it / step).roundToInt() * step).coerceIn(range) },
        onValueChangeFinished = { onCommit(local) },
        valueRange = range.first.toFloat()..range.last.toFloat(),
    )
    Text(
        text = stringResource(R.string.cooldown_help, Format.duration(range.first), Format.duration(range.last)),
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
        text = stringResource(R.string.rssi_text, local, stringResource(Format.rssiDistanceHintRes(local))),
        style = MaterialTheme.typography.bodyMedium,
    )
    Slider(
        value = local.toFloat(),
        onValueChange = { local = it.roundToInt().coerceIn(range) },
        onValueChangeFinished = { onCommit(local) },
        valueRange = range.first.toFloat()..range.last.toFloat(),
    )
    Text(
        text = stringResource(R.string.rssi_help),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
