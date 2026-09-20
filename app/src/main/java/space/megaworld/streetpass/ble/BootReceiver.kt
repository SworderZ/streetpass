package space.megaworld.streetpass.ble

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.launch
import space.megaworld.streetpass.StreetPassApp
import space.megaworld.streetpass.ui.Permissions

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return

        val container = (context.applicationContext as StreetPassApp).container
        val pending = goAsync()
        container.applicationScope.launch {
            try {
                val settings = container.settingsRepository.current()
                if (!settings.autoStart || !settings.discoveryActive) return@launch
                if (!Permissions.hasBlePermissions(context)) {
                    Log.w(TAG, "skip autostart: permissions missing")
                    return@launch
                }
                try {
                    DiscoveryService.start(context)
                } catch (e: IllegalStateException) {
                    // На Android 15 и ряде прошивок старт foreground-сервиса из
                    // BOOT_COMPLETED может быть запрещён — это не повод падать.
                    Log.w(TAG, "autostart rejected by system", e)
                } catch (e: SecurityException) {
                    Log.w(TAG, "autostart rejected: permission", e)
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}
