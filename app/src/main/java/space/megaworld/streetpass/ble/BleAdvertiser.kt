package space.megaworld.streetpass.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.util.Log
import androidx.core.content.getSystemService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import space.megaworld.streetpass.R
import space.megaworld.streetpass.core.BleConstants

/**
 * Неподключаемая legacy-реклама: 8 байт ID в Service Data основного пакета и,
 * если задан, ник в scan-response. GATT-сервер не поднимается, подключиться
 * к устройству нельзя.
 */
class BleAdvertiser(private val context: Context) {

    private val _advertising = MutableStateFlow(false)
    val advertising: StateFlow<Boolean> = _advertising

    /** null — ещё не проверяли (адаптер был выключен); false — чип не умеет рекламу. */
    private val _supported = MutableStateFlow<Boolean?>(null)
    val supported: StateFlow<Boolean?> = _supported

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private var advertiser: BluetoothLeAdvertiser? = null
    private var callback: AdvertiseCallback? = null

    // Разрешения проверяет вызывающая сторона до запуска сервиса; отзыв в рантайме
    // ловится через SecurityException, поэтому статическая проверка lint здесь избыточна.
    @SuppressLint("MissingPermission")
    fun start(peerId: ByteArray, nickname: ByteArray?): Boolean {
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

        val settings = AdvertiseSettings.Builder()
            // BALANCED (~250 мс) даёт десятки пакетов за одно окно сканирования соседа
            // при незаметном расходе батареи; LOW_POWER (1 с) уже рискует не попасть в окно.
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(false)
            .setTimeout(0)
            .build()
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addServiceUuid(BleConstants.SERVICE_UUID)
            .addServiceData(BleConstants.SERVICE_UUID, peerId)
            .build()
        // С scan-response стек переключает неподключаемую рекламу на ADV_SCAN_IND:
        // сканеры дозапрашивают второй пакет, подключаться по-прежнему нельзя.
        val scanResponse = nickname?.takeIf { it.isNotEmpty() }?.let {
            AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .setIncludeTxPowerLevel(false)
                .addServiceData(BleConstants.NICKNAME_UUID, it)
                .build()
        }

        val cb = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                _advertising.value = true
                _error.value = null
                Log.d(TAG, "advertising started, nickname=${scanResponse != null}")
            }

            override fun onStartFailure(errorCode: Int) {
                _advertising.value = false
                _error.value = describe(errorCode)
                Log.w(TAG, "advertising failed: $errorCode")
            }
        }

        return try {
            if (scanResponse != null) {
                leAdvertiser.startAdvertising(settings, data, scanResponse, cb)
            } else {
                leAdvertiser.startAdvertising(settings, data, cb)
            }
            advertiser = leAdvertiser
            callback = cb
            true
        } catch (e: SecurityException) {
            _error.value = context.getString(R.string.err_adv_permission)
            false
        } catch (e: IllegalStateException) {
            _error.value = context.getString(R.string.err_bt_unavailable, e.message)
            false
        }
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        val cb = callback ?: return
        try {
            advertiser?.stopAdvertising(cb)
        } catch (e: SecurityException) {
            Log.w(TAG, "stopAdvertising: permission revoked")
        } catch (e: IllegalStateException) {
            Log.w(TAG, "stopAdvertising: adapter off")
        }
        callback = null
        advertiser = null
        _advertising.value = false
    }

    private fun adapter(): BluetoothAdapter? = context.getSystemService<BluetoothManager>()?.adapter

    private fun describe(code: Int): String = when (code) {
        AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE -> context.getString(R.string.err_adv_too_large)
        AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> context.getString(R.string.err_adv_too_many)
        AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED -> context.getString(R.string.err_adv_already_started)
        AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR -> context.getString(R.string.err_adv_internal)
        AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> context.getString(R.string.err_adv_unsupported)
        else -> context.getString(R.string.err_adv_generic, code)
    }

    companion object {
        private const val TAG = "BleAdvertiser"
    }
}
