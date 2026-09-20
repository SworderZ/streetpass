package space.megaworld.streetpass.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.util.Log
import androidx.core.content.getSystemService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import space.megaworld.streetpass.core.BleConstants
import space.megaworld.streetpass.core.Hex

/**
 * Сканер с обязательным фильтром по SERVICE_UUID. Колбэк [onSighting] приходит не в
 * main-потоке — вызывающая сторона обязана уводить работу в свой поток.
 */
class BleScanner(
    private val context: Context,
    private val onSighting: (peerId: String, rssi: Int) -> Unit,
) {

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private var scanner: BluetoothLeScanner? = null
    private var callback: ScanCallback? = null

    // Разрешения проверяет вызывающая сторона до запуска сервиса; отзыв в рантайме
    // ловится через SecurityException, поэтому статическая проверка lint здесь избыточна.
    @SuppressLint("MissingPermission")
    fun start(scanMode: Int): Boolean {
        stop()
        val adapter = adapter()
        if (adapter == null || !adapter.isEnabled) {
            _error.value = "Bluetooth выключен"
            return false
        }
        val leScanner = adapter.bluetoothLeScanner
        if (leScanner == null) {
            _error.value = "BLE-сканер недоступен"
            return false
        }

        // Фильтр по UUID — не оптимизация, а требование: с Android 8.1 система не отдаёт
        // результаты нефильтрованного сканирования при выключенном экране.
        val filters = listOf(
            ScanFilter.Builder().setServiceUuid(BleConstants.SERVICE_UUID).build(),
        )
        val settings = ScanSettings.Builder()
            .setScanMode(scanMode)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
            .setReportDelay(0)
            .build()

        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                handle(result)
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach(::handle)
            }

            override fun onScanFailed(errorCode: Int) {
                _scanning.value = false
                _error.value = describe(errorCode)
                Log.w(TAG, "scan failed: $errorCode")
            }
        }

        return try {
            leScanner.startScan(filters, settings, cb)
            scanner = leScanner
            callback = cb
            _scanning.value = true
            _error.value = null
            Log.d(TAG, "scan started, mode=$scanMode")
            true
        } catch (e: SecurityException) {
            _error.value = "Нет разрешения на BLE-сканирование"
            false
        } catch (e: IllegalStateException) {
            _error.value = "Bluetooth недоступен: ${e.message}"
            false
        }
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        val cb = callback ?: return
        try {
            scanner?.stopScan(cb)
        } catch (e: SecurityException) {
            Log.w(TAG, "stopScan: permission revoked")
        } catch (e: IllegalStateException) {
            Log.w(TAG, "stopScan: adapter off")
        }
        callback = null
        scanner = null
        _scanning.value = false
    }

    private fun handle(result: ScanResult) {
        // Из результата берём только Service Data и RSSI. MAC и имя не читаем: MAC система
        // ротирует и он никого не идентифицирует, имя устройства — персональные данные.
        val data = result.scanRecord?.getServiceData(BleConstants.SERVICE_UUID) ?: return
        if (data.size != BleConstants.PEER_ID_BYTES) return
        onSighting(Hex.encode(data), result.rssi)
    }

    private fun adapter(): BluetoothAdapter? = context.getSystemService<BluetoothManager>()?.adapter

    private fun describe(code: Int): String = when (code) {
        ScanCallback.SCAN_FAILED_ALREADY_STARTED -> "Сканирование уже запущено"
        ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED ->
            "Не удалось зарегистрировать сканер в Bluetooth-стеке. Обычно помогает выключить и включить Bluetooth"
        ScanCallback.SCAN_FAILED_INTERNAL_ERROR -> "Внутренняя ошибка Bluetooth-стека при сканировании"
        ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED -> "Устройство не поддерживает нужный режим сканирования"
        SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES -> "Bluetooth-контроллер исчерпал ресурсы для сканирования"
        SCAN_FAILED_SCANNING_TOO_FREQUENTLY -> "Слишком частые перезапуски сканирования, система временно блокирует их"
        else -> "Ошибка BLE-сканирования ($code)"
    }

    companion object {
        private const val TAG = "BleScanner"

        // Публичных констант для этих кодов в SDK нет, только скрытые в ScanCallback.
        private const val SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES = 5
        private const val SCAN_FAILED_SCANNING_TOO_FREQUENTLY = 6
    }
}
