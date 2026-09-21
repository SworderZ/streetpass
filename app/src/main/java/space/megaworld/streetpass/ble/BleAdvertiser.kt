package space.megaworld.streetpass.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertisingSet
import android.bluetooth.le.AdvertisingSetCallback
import android.bluetooth.le.AdvertisingSetParameters
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.getSystemService
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import space.megaworld.streetpass.R
import space.megaworld.streetpass.core.BleConstants
import space.megaworld.streetpass.core.IdentityProof

/**
 * Неподключаемая legacy-реклама: 8 байт ID в Service Data основного пакета, а в
 * scan-response по очереди — куски подписанного доказательства ID и ник. GATT-сервер
 * не поднимается, подключиться к устройству нельзя.
 *
 * Используется [BluetoothLeAdvertiser.startAdvertisingSet] в legacy-режиме, а не
 * `startAdvertising`: только у [AdvertisingSet] есть `setScanResponseData`, которым
 * кадры меняются на лету без перезапуска рекламы. Legacy-режим работает на любом
 * чипе с API 26, поддержка BLE 5 не нужна.
 */
class BleAdvertiser(
    private val context: Context,
    private val scope: CoroutineScope,
) {

    private val _advertising = MutableStateFlow(false)
    val advertising: StateFlow<Boolean> = _advertising

    /** null — ещё не проверяли (адаптер был выключен); false — чип не умеет рекламу. */
    private val _supported = MutableStateFlow<Boolean?>(null)
    val supported: StateFlow<Boolean?> = _supported

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private var advertiser: BluetoothLeAdvertiser? = null
    private var callback: AdvertisingSetCallback? = null
    private var rotationJob: Job? = null

    /**
     * [proofProvider] возвращает свежее доказательство или null, если подписать нечем;
     * вызывается при старте и затем каждые [BleConstants.PROOF_REFRESH_MS].
     */
    // Разрешения проверяет вызывающая сторона до запуска сервиса; отзыв в рантайме
    // ловится через SecurityException, поэтому статическая проверка lint здесь избыточна.
    @SuppressLint("MissingPermission")
    suspend fun start(
        peerId: ByteArray,
        nickname: ByteArray?,
        proofProvider: suspend () -> ByteArray?,
    ): Boolean {
        stop()
        val adapter = adapter()
        if (adapter == null || !adapter.isEnabled) {
            _error.value = context.getString(R.string.err_bt_off)
            return false
        }
        val leAdvertiser = try {
            adapter.bluetoothLeAdvertiser
        } catch (e: SecurityException) {
            _error.value = context.getString(R.string.err_adv_permission)
            return false
        }
        if (leAdvertiser == null) {
            // Адаптер включён, а advertiser нет — чип не поддерживает рекламу.
            _supported.value = false
            Log.w(TAG, "BLE advertising not supported on this device")
            return false
        }
        _supported.value = true

        val nicknameFrame = nickname?.takeIf { it.isNotEmpty() }?.let { serviceData(BleConstants.NICKNAME_UUID, it) }
        // Случайное стартовое поколение: после перезапуска сервиса сосед мог хранить куски
        // прошлого доказательства, и с тем же номером поколения он бы их смешал с новыми.
        val generation = Random.nextInt(IdentityProof.GENERATION_COUNT)
        val frames = buildFrames(nicknameFrame, proofProvider(), generation)

        val parameters = AdvertisingSetParameters.Builder()
            .setLegacyMode(true)
            .setConnectable(false)
            // С scan-response стек переключает неподключаемую рекламу на ADV_SCAN_IND:
            // сканеры дозапрашивают второй пакет, подключаться по-прежнему нельзя.
            .setScannable(frames.isNotEmpty())
            // MEDIUM (~250 мс) даёт десятки пакетов за одно окно сканирования соседа
            // при незаметном расходе батареи; LOW (1 с) уже рискует не попасть в окно.
            .setInterval(AdvertisingSetParameters.INTERVAL_MEDIUM)
            .setTxPowerLevel(AdvertisingSetParameters.TX_POWER_HIGH)
            .build()
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addServiceUuid(BleConstants.SERVICE_UUID)
            .addServiceData(BleConstants.SERVICE_UUID, peerId)
            .build()

        val cb = object : AdvertisingSetCallback() {
            override fun onAdvertisingSetStarted(advertisingSet: AdvertisingSet?, txPower: Int, status: Int) {
                // Пока стек отвечал, рекламу могли перезапустить — устаревший колбэк игнорируем.
                if (callback !== this) return
                if (status != AdvertisingSetCallback.ADVERTISE_SUCCESS || advertisingSet == null) {
                    _advertising.value = false
                    _error.value = describe(status)
                    Log.w(TAG, "advertising failed: $status")
                    return
                }
                _advertising.value = true
                _error.value = null
                Log.d(TAG, "advertising started, nickname=${nicknameFrame != null}, frames=${frames.size}")
                startRotation(advertisingSet, frames, nicknameFrame, generation, proofProvider)
            }

            override fun onAdvertisingSetStopped(advertisingSet: AdvertisingSet?) {
                if (callback === this) _advertising.value = false
            }

            override fun onScanResponseDataSet(advertisingSet: AdvertisingSet?, status: Int) {
                if (status != AdvertisingSetCallback.ADVERTISE_SUCCESS) Log.w(TAG, "scan response update failed: $status")
            }
        }

        return try {
            leAdvertiser.startAdvertisingSet(parameters, data, frames.firstOrNull(), null, null, cb)
            advertiser = leAdvertiser
            callback = cb
            true
        } catch (e: SecurityException) {
            _error.value = context.getString(R.string.err_adv_permission)
            false
        } catch (e: IllegalStateException) {
            _error.value = context.getString(R.string.err_bt_unavailable, e.message)
            false
        } catch (e: IllegalArgumentException) {
            // Стек отвергает данные длиннее 31 байта ещё до отправки в контроллер.
            _error.value = context.getString(R.string.err_adv_too_large)
            false
        }
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        rotationJob?.cancel()
        rotationJob = null
        val cb = callback ?: return
        try {
            advertiser?.stopAdvertisingSet(cb)
        } catch (e: SecurityException) {
            Log.w(TAG, "stopAdvertisingSet: permission revoked")
        } catch (e: IllegalStateException) {
            Log.w(TAG, "stopAdvertisingSet: adapter off")
        }
        callback = null
        advertiser = null
        _advertising.value = false
    }

    /**
     * Крутит кадры scan-response по кругу и раз в [BleConstants.PROOF_REFRESH_MS]
     * переподписывает доказательство. Первый кадр уже ушёл вместе со стартом рекламы.
     */
    private fun startRotation(
        set: AdvertisingSet,
        initialFrames: List<AdvertiseData>,
        nicknameFrame: AdvertiseData?,
        initialGeneration: Int,
        proofProvider: suspend () -> ByteArray?,
    ) {
        rotationJob?.cancel()
        rotationJob = scope.launch {
            var frames = initialFrames
            var generation = initialGeneration
            var signedAt = System.currentTimeMillis()
            var index = 1
            var changed = false
            while (isActive) {
                delay(Random.nextLong(BleConstants.PROOF_FRAME_MIN_MS, BleConstants.PROOF_FRAME_MAX_MS))
                val now = System.currentTimeMillis()
                if (now - signedAt >= BleConstants.PROOF_REFRESH_MS) {
                    generation = (generation + 1) % IdentityProof.GENERATION_COUNT
                    frames = buildFrames(nicknameFrame, proofProvider(), generation)
                    signedAt = now
                    index = 0
                    changed = true
                }
                // Единственный кадр (только ник) переустанавливать незачем — он и так в эфире.
                if (frames.isEmpty() || (frames.size == 1 && !changed)) continue
                changed = false
                val frame = frames[index % frames.size]
                index = (index + 1) % frames.size
                if (!setScanResponse(set, frame)) break
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun setScanResponse(set: AdvertisingSet, frame: AdvertiseData): Boolean = try {
        set.setScanResponseData(frame)
        true
    } catch (e: SecurityException) {
        Log.w(TAG, "setScanResponseData: permission revoked")
        false
    } catch (e: IllegalStateException) {
        Log.w(TAG, "setScanResponseData: advertising set is gone")
        false
    } catch (e: IllegalArgumentException) {
        Log.w(TAG, "setScanResponseData: frame rejected: ${e.message}")
        false
    }

    private fun buildFrames(nicknameFrame: AdvertiseData?, proof: ByteArray?, generation: Int): List<AdvertiseData> {
        val frames = ArrayList<AdvertiseData>()
        if (proof != null) {
            IdentityProof.chunks(proof, generation).mapTo(frames) { serviceData(BleConstants.PROOF_UUID, it) }
        }
        if (nicknameFrame != null) frames += nicknameFrame
        return frames
    }

    private fun serviceData(uuid: ParcelUuid, payload: ByteArray): AdvertiseData =
        AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addServiceData(uuid, payload)
            .build()

    private fun adapter(): BluetoothAdapter? = context.getSystemService<BluetoothManager>()?.adapter

    private fun describe(code: Int): String = when (code) {
        AdvertisingSetCallback.ADVERTISE_FAILED_DATA_TOO_LARGE -> context.getString(R.string.err_adv_too_large)
        AdvertisingSetCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> context.getString(R.string.err_adv_too_many)
        AdvertisingSetCallback.ADVERTISE_FAILED_ALREADY_STARTED -> context.getString(R.string.err_adv_already_started)
        AdvertisingSetCallback.ADVERTISE_FAILED_INTERNAL_ERROR -> context.getString(R.string.err_adv_internal)
        AdvertisingSetCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> context.getString(R.string.err_adv_unsupported)
        else -> context.getString(R.string.err_adv_generic, code)
    }

    companion object {
        private const val TAG = "BleAdvertiser"
    }
}
