package space.megaworld.streetpass.ble

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.database.SQLException
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import space.megaworld.streetpass.AppContainer
import space.megaworld.streetpass.MainActivity
import space.megaworld.streetpass.R
import space.megaworld.streetpass.StreetPassApp
import space.megaworld.streetpass.core.BleConstants
import space.megaworld.streetpass.core.Hex
import space.megaworld.streetpass.core.IdentityProof
import space.megaworld.streetpass.core.Nicknames
import space.megaworld.streetpass.core.PeerVerifier
import space.megaworld.streetpass.data.SightingResult
import space.megaworld.streetpass.data.settings.AppSettings
import space.megaworld.streetpass.ui.components.displayName
import space.megaworld.streetpass.ui.widget.TodayWidgetProvider

data class DiscoveryState(
    val running: Boolean = false,
    val advertising: Boolean = false,
    val scanning: Boolean = false,
    val advertisingSupported: Boolean = true,
    val bluetoothOn: Boolean = true,
    val lastSightingAt: Long? = null,
    val error: String? = null,
)

/**
 * Foreground-сервис типа connectedDevice. С Android 8 фоновые процессы усыпляются, а
 * BLE-сканирование без видимого уведомления система останавливает — постоянное
 * уведомление единственный легальный способ держать сканер живым долго.
 */
class DiscoveryService : Service() {

    private class Sighting(
        val peerId: String,
        val rssi: Int,
        val nickname: String?,
        val proofFrame: ByteArray?,
        val at: Long,
    )

    private lateinit var container: AppContainer
    private lateinit var advertiser: BleAdvertiser
    private lateinit var scanner: BleScanner

    // BLE API дёргаем из main-потока, тяжёлую работу уводим в IO явно.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var radioJob: Job? = null

    private val sightings = Channel<Sighting>(capacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Дебаунс: последний принятый пакет по peerId. Читается и пишется только из consumer-корутины. */
    private val lastSeen = HashMap<String, Long>()

    /** Сборка и проверка подписей ID. Читается и пишется только из consumer-корутины. */
    private val verifier = PeerVerifier()

    private val serviceError = MutableStateFlow<String?>(null)

    @Volatile private var settings = AppSettings()
    @Volatile private var ownId = ""
    private var todayCount = 0
    private var receiverRegistered = false

    private val state get() = container.discoveryState

    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                BluetoothAdapter.STATE_OFF, BluetoothAdapter.STATE_TURNING_OFF -> {
                    Log.d(TAG, "bluetooth off, pausing radios")
                    radioJob?.cancel()
                    advertiser.stop()
                    scanner.stop()
                    state.update { it.copy(bluetoothOn = false) }
                    serviceError.value = getString(R.string.err_bt_off_service)
                }
                BluetoothAdapter.STATE_ON -> {
                    Log.d(TAG, "bluetooth on, resuming radios")
                    state.update { it.copy(bluetoothOn = true) }
                    serviceError.value = null
                    restartRadios()
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        container = (application as StreetPassApp).container
        advertiser = BleAdvertiser(this, scope)
        scanner = BleScanner(this) { peerId, rssi, nickname, proofFrame ->
            sightings.trySend(Sighting(peerId, rssi, nickname, proofFrame, System.currentTimeMillis()))
        }
        createNotificationChannel()

        scope.launch {
            advertiser.advertising.collect { on -> state.update { it.copy(advertising = on) } }
        }
        scope.launch {
            advertiser.supported.collect { supported ->
                if (supported != null) state.update { it.copy(advertisingSupported = supported) }
            }
        }
        scope.launch {
            scanner.scanning.collect { on -> state.update { it.copy(scanning = on) } }
        }
        scope.launch {
            combine(serviceError, advertiser.error, scanner.error) { s, a, sc -> s ?: a ?: sc }
                .collect { error -> state.update { it.copy(error = error) } }
        }
        scope.launch {
            container.settingsRepository.settings.collect { settings = it }
        }
        scope.launch {
            container.encounterRepository.todayEncounters.collect { count ->
                todayCount = count
                if (state.value.running) updateNotification()
                TodayWidgetProvider.refresh(this@DiscoveryService)
            }
        }
        scope.launch(Dispatchers.IO) {
            for (sighting in sightings) handleSighting(sighting)
        }

        ContextCompat.registerReceiver(
            this,
            bluetoothReceiver,
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        receiverRegistered = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForeground нужен при любом действии: сервис поднят через
        // startForegroundService и без него система через 5 секунд роняет процесс.
        if (!startForegroundSafely()) {
            stopSelf()
            return START_NOT_STICKY
        }

        when (intent?.action) {
            ACTION_STOP -> {
                container.applicationScope.launch { container.settingsRepository.setDiscoveryActive(false) }
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_REFRESH -> {
                if (!state.value.running) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                restartRadios()
            }
            // ACTION_START или null-intent после перезапуска процесса системой (START_STICKY).
            else -> {
                state.update { it.copy(running = true) }
                container.applicationScope.launch { container.settingsRepository.setDiscoveryActive(true) }
                restartRadios()
            }
        }
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Смахивание приложения из недавних не должно останавливать обнаружение.
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        radioJob?.cancel()
        advertiser.stop()
        scanner.stop()
        if (receiverRegistered) {
            unregisterReceiver(bluetoothReceiver)
            receiverRegistered = false
        }
        sightings.close()
        scope.cancel()
        state.update { it.copy(running = false, advertising = false, scanning = false) }
        super.onDestroy()
    }

    private fun restartRadios() {
        radioJob?.cancel()
        radioJob = scope.launch {
            advertiser.stop()
            scanner.stop()

            val adapter = getSystemService<BluetoothManager>()?.adapter
            if (adapter == null || !adapter.isEnabled) {
                state.update { it.copy(bluetoothOn = false) }
                serviceError.value = getString(R.string.err_bt_off_service)
                return@launch
            }
            state.update { it.copy(bluetoothOn = true) }
            serviceError.value = null

            val current = container.settingsRepository.current()
            settings = current
            ownId = container.identityRepository.getOrCreate()

            if (current.advertiseEnabled) {
                val nickname = container.identityRepository.currentNickname()
                advertiser.start(Hex.decode(ownId), Nicknames.encode(nickname)) {
                    container.identityRepository.proof(System.currentTimeMillis())
                }
            }
            if (!current.scanEnabled) return@launch

            val mode = current.powerMode
            try {
                if (mode.continuous) {
                    scanner.start(mode.scanMode)
                    awaitCancellation()
                } else {
                    while (isActive) {
                        scanner.start(mode.scanMode)
                        delay(mode.scanWindowMs)
                        scanner.stop()
                        delay(mode.pauseMs)
                    }
                }
            } finally {
                scanner.stop()
            }
        }
    }

    private suspend fun handleSighting(sighting: Sighting) {
        // Контроллер не принимает собственную рекламу, но дешёвая страховка не мешает.
        if (sighting.peerId == ownId) return
        state.update { it.copy(lastSightingAt = sighting.at) }

        // Куски подписи собираем из каждого пакета, ещё до дебаунса: иначе за одно окно
        // сканирования дошёл бы только один кусок из четырёх.
        sighting.proofFrame?.let { frame ->
            when (val result = verifier.onFrame(sighting.peerId, frame, sighting.at)) {
                is IdentityProof.Result.Verified -> Log.d(TAG, "proof verified for ${Hex.short(sighting.peerId)}")
                is IdentityProof.Result.Rejected -> Log.w(TAG, "proof rejected for ${Hex.short(sighting.peerId)}: ${result.reason}")
                null -> Unit
            }
        }
        // Без подтверждённой подписи ID мог быть скопирован с чужого эфира — в базу не пускаем.
        // Исключение — явно разрешённая совместимость со сборками без подписи.
        if (!verifier.isTrusted(sighting.peerId, sighting.at) && !settings.acceptUnsigned) return

        val previous = lastSeen[sighting.peerId]
        if (previous != null && sighting.at - previous < BleConstants.DEBOUNCE_WINDOW_MS) return
        lastSeen[sighting.peerId] = sighting.at
        if (lastSeen.size > BleConstants.DEBOUNCE_MAX_ENTRIES) {
            lastSeen.entries.removeAll { sighting.at - it.value > BleConstants.DEBOUNCE_STALE_MS }
        }

        val cfg = settings
        val result = try {
            container.encounterRepository.processSighting(
                peerId = sighting.peerId,
                rssi = sighting.rssi,
                now = sighting.at,
                cooldownMinutes = cfg.cooldownMinutes,
                minRssi = cfg.minRssi,
                storeRssi = cfg.storeRssi,
                nickname = sighting.nickname,
            )
        } catch (e: SQLException) {
            Log.e(TAG, "failed to persist sighting", e)
            return
        }
        if (result is SightingResult.Registered) {
            Log.d(TAG, "encounter ${Hex.short(result.peerId)} first=${result.firstMeeting}")
            // Достижения пересчитываются по базе, а не по событию: повторный вызов безвреден.
            try {
                container.achievementRepository.check(sighting.at)
                    .forEach { Log.d(TAG, "achievement unlocked: ${it.id}") }
                if (cfg.notifyFriends) notifyFriendIfNeeded(result.peerId)
            } catch (e: SQLException) {
                Log.e(TAG, "failed to check achievements", e)
            }
        }
    }

    /**
     * Зачтённая встреча с другом — отдельное уведомление. Registered приходит не чаще
     * окна антидубля, так что уведомление не спамит; id по peerId — у каждого друга своё.
     */
    private suspend fun notifyFriendIfNeeded(peerId: String) {
        val peer = container.encounterRepository.peerOnce(peerId) ?: return
        if (!peer.isFriend) return
        val manager = NotificationManagerCompat.from(this)
        if (!manager.areNotificationsEnabled()) return
        val name = displayName(peer.alias, peer.nickname, peer.peerId)
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(this, FRIENDS_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notif_friend_title, name))
            .setContentText(getString(R.string.notif_friend_text))
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_SOCIAL)
            .build()
        try {
            manager.notify(FRIEND_NOTIFICATION_BASE_ID + peerId.hashCode(), notification)
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS отозвали между проверкой и показом.
            Log.w(TAG, "friend notification blocked", e)
        }
    }

    private fun startForegroundSafely(): Boolean = try {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )
        true
    } catch (e: SecurityException) {
        // На API 34+ тип connectedDevice требует выданного Bluetooth-разрешения.
        Log.e(TAG, "startForeground rejected", e)
        serviceError.value = getString(R.string.err_fgs_permission)
        false
    } catch (e: IllegalStateException) {
        // ForegroundServiceStartNotAllowedException и родня: запуск из фона запрещён.
        Log.e(TAG, "startForeground not allowed", e)
        serviceError.value = getString(R.string.err_fgs_not_allowed, e.message)
        false
    }

    private fun updateNotification() {
        // Повторный startForeground обновляет уведомление и не требует POST_NOTIFICATIONS,
        // в отличие от NotificationManager.notify.
        startForegroundSafely()
    }

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, DiscoveryService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notif_text, todayCount))
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp)
            .addAction(0, getString(R.string.notif_stop), stop)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notif_channel_desc)
            enableVibration(false)
            setSound(null, null)
            setShowBadge(false)
        }
        // Встречи с друзьями — обычной важности, со звуком: ради них уведомление и нужно.
        val friends = NotificationChannel(
            FRIENDS_CHANNEL_ID,
            getString(R.string.notif_friend_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = getString(R.string.notif_friend_channel_desc)
        }
        getSystemService<NotificationManager>()?.let {
            it.createNotificationChannel(channel)
            it.createNotificationChannel(friends)
        }
    }

    companion object {
        private const val TAG = "DiscoveryService"
        private const val CHANNEL_ID = "discovery"
        private const val FRIENDS_CHANNEL_ID = "friends"
        private const val NOTIFICATION_ID = 1
        private const val FRIEND_NOTIFICATION_BASE_ID = 1000

        private const val ACTION_START = "space.megaworld.streetpass.action.START"
        private const val ACTION_STOP = "space.megaworld.streetpass.action.STOP"
        private const val ACTION_REFRESH = "space.megaworld.streetpass.action.REFRESH"

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, intent(context, ACTION_START))
        }

        fun stop(context: Context) {
            ContextCompat.startForegroundService(context, intent(context, ACTION_STOP))
        }

        /** Перезапуск радио без остановки сервиса — после смены настроек или ID. */
        fun refresh(context: Context) {
            ContextCompat.startForegroundService(context, intent(context, ACTION_REFRESH))
        }

        private fun intent(context: Context, action: String): Intent =
            Intent(context, DiscoveryService::class.java).setAction(action)
    }
}
