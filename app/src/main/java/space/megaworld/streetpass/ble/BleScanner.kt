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
import space.megaworld.streetpass.R
import space.megaworld.streetpass.core.BleConstants
import space.megaworld.streetpass.core.DesktopAdvertisement
import space.megaworld.streetpass.core.DesktopProofAssembler
import space.megaworld.streetpass.core.Hex
import space.megaworld.streetpass.core.Nicknames

/**
 * Сканер с фильтрами по legacy SERVICE_UUID и desktop manufacturer envelope.
 * Колбэк [onSighting] приходит не в main-потоке — вызывающая сторона обязана уводить
 * работу в свой поток.
 */
class BleScanner(
    private val context: Context,
    private val onSighting: (peerId: String, rssi: Int, nickname: String?, proofFrame: ByteArray?) -> Unit,
) {

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private var scanner: BluetoothLeScanner? = null
    private var callback: ScanCallback? = null
    private val desktopProofs = DesktopProofAssembler()

    // Разрешения проверяет вызывающая сторона до запуска сервиса; отзыв в рантайме
    // ловится через SecurityException, поэтому статическая проверка lint здесь избыточна.
    @SuppressLint("MissingPermission")
    fun start(scanMode: Int): Boolean {
        stop()
        val adapter = adapter()
        if (adapter == null || !adapter.isEnabled) {
            _error.value = context.getString(R.string.err_bt_off)
            return false
        }
        val leScanner = adapter.bluetoothLeScanner
        if (leScanner == null) {
            _error.value = context.getString(R.string.err_scanner_unavailable)
            return false
        }

        // Оба фильтра нужны: Android должен доставлять legacy и manufacturer-рекламу
        // при выключенном экране, а чужие BLE-пакеты не должны будить приложение.
        val filters = listOf(
            ScanFilter.Builder().setServiceUuid(BleConstants.SERVICE_UUID).build(),
            ScanFilter.Builder()
                .setManufacturerData(DesktopAdvertisement.COMPANY_ID, DesktopAdvertisement.PREFIX)
                .build(),
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
            _error.value = context.getString(R.string.err_scan_permission)
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
            scanner?.stopScan(cb)
        } catch (e: SecurityException) {
            Log.w(TAG, "stopScan: permission revoked")
        } catch (e: IllegalStateException) {
            Log.w(TAG, "stopScan: adapter off")
        }
        callback = null
        scanner = null
        desktopProofs.clear()
        _scanning.value = false
    }

    private fun handle(result: ScanResult) {
        // Из результата берём только Service Data/manufacturer data и RSSI. MAC и имя
        // не читаем: MAC система ротирует и он никого не идентифицирует, имя устройства
        // — персональные данные.
        val record = result.scanRecord ?: return
        val serviceData = record.getServiceData(BleConstants.SERVICE_UUID)
        if (serviceData != null) {
            handleServiceData(record, serviceData, result.rssi)
            return
        }

        val manufacturerData = record.getManufacturerSpecificData(DesktopAdvertisement.COMPANY_ID) ?: return
        when (val packet = DesktopAdvertisement.parse(manufacturerData)) {
            is DesktopAdvertisement.Packet.Nickname -> {
                onSighting(Hex.encode(packet.peerId), result.rssi, packet.value, null)
            }
            is DesktopAdvertisement.Packet.ProofChunk -> {
                val peerId = Hex.encode(packet.peerId)
                desktopProofs.accept(packet, System.currentTimeMillis())?.forEach { frame ->
                    onSighting(peerId, result.rssi, null, frame)
                }
            }
            null -> Unit
        }
    }

    private fun handleServiceData(
        record: android.bluetooth.le.ScanRecord,
        data: ByteArray,
        rssi: Int,
    ) {
        if (data.size != BleConstants.PEER_ID_BYTES) return
        // Ник и кусок доказательства есть только если стек успел получить scan-response,
        // и в одном пакете приходит что-то одно — кадры чередуются на стороне передатчика.
        val nickname = record.getServiceData(BleConstants.NICKNAME_UUID)?.let(Nicknames::decode)
        val proofFrame = record.getServiceData(BleConstants.PROOF_UUID)
        onSighting(Hex.encode(data), rssi, nickname, proofFrame)
    }

    private fun adapter(): BluetoothAdapter? = context.getSystemService<BluetoothManager>()?.adapter

    private fun describe(code: Int): String = when (code) {
        ScanCallback.SCAN_FAILED_ALREADY_STARTED -> context.getString(R.string.err_scan_already_started)
        ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> context.getString(R.string.err_scan_registration)
        ScanCallback.SCAN_FAILED_INTERNAL_ERROR -> context.getString(R.string.err_scan_internal)
        ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED -> context.getString(R.string.err_scan_unsupported)
        SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES -> context.getString(R.string.err_scan_hw_resources)
        SCAN_FAILED_SCANNING_TOO_FREQUENTLY -> context.getString(R.string.err_scan_too_frequent)
        else -> context.getString(R.string.err_scan_generic, code)
    }

    companion object {
        private const val TAG = "BleScanner"

        // Публичных констант для этих кодов в SDK нет, только скрытые в ScanCallback.
        private const val SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES = 5
        private const val SCAN_FAILED_SCANNING_TOO_FREQUENTLY = 6
    }
}
