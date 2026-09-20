package space.megaworld.streetpass.ui

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService

object Permissions {

    /** Без этих разрешений обнаружение невозможно. */
    fun required(): List<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE)
        } else {
            // До Android 12 система не отдаёт результаты BLE-сканирования без геолокации,
            // хотя само приложение местоположение не использует.
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    /** Без уведомления сервис работает, просто не показывает его. */
    fun optional(): List<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            listOf(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            emptyList()
        }

    fun toRequest(): Array<String> = (required() + optional()).toTypedArray()

    fun hasBlePermissions(context: Context): Boolean = required().all { granted(context, it) }

    fun hasNotificationPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            granted(context, Manifest.permission.POST_NOTIFICATIONS)

    val needsLocationExplanation: Boolean
        get() = Build.VERSION.SDK_INT < Build.VERSION_CODES.S

    fun isBluetoothEnabled(context: Context): Boolean =
        context.getSystemService<BluetoothManager>()?.adapter?.isEnabled == true

    private fun granted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
