package space.megaworld.streetpass.crossplatform

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {
    private val channelName = "space.megaworld.streetpass/permissions"
    private val requestCode = 4101
    private var permissionResult: MethodChannel.Result? = null

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, channelName)
            .setMethodCallHandler { call, result ->
                if (call.method != "requestBluetoothPermissions") {
                    result.notImplemented()
                    return@setMethodCallHandler
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val permissions = arrayOf(
                        Manifest.permission.BLUETOOTH_SCAN,
                        Manifest.permission.BLUETOOTH_CONNECT,
                        Manifest.permission.BLUETOOTH_ADVERTISE,
                    )
                    val missing = permissions.filter {
                        ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
                    }
                    if (missing.isEmpty()) {
                        result.success(true)
                    } else {
                        permissionResult?.error("PERMISSION_REQUEST_IN_PROGRESS", "Bluetooth permission request is already running", null)
                        permissionResult = result
                        ActivityCompat.requestPermissions(this, missing.toTypedArray(), requestCode)
                    }
                } else {
                    val location = Manifest.permission.ACCESS_FINE_LOCATION
                    if (ContextCompat.checkSelfPermission(this, location) == PackageManager.PERMISSION_GRANTED) {
                        result.success(true)
                    } else {
                        permissionResult?.error("PERMISSION_REQUEST_IN_PROGRESS", "Bluetooth permission request is already running", null)
                        permissionResult = result
                        ActivityCompat.requestPermissions(this, arrayOf(location), requestCode)
                    }
                }
            }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != this.requestCode) return
        val granted = grantResults.isNotEmpty() &&
            grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        permissionResult?.success(granted)
        permissionResult = null
    }
}
