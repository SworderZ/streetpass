package space.megaworld.streetpass.data

import android.content.Context
import space.megaworld.streetpass.BuildConfig
import space.megaworld.streetpass.StreetPassApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.UUID

/** Отправляет только агрегируемый минимум после явного согласия пользователя. */
class TelemetryRepository(private val context: Context) {
    private val prefs = context.getSharedPreferences("telemetry", Context.MODE_PRIVATE)
    private val installationId: String
        get() = prefs.getString("installation_id", null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString("installation_id", it).apply()
        }

    suspend fun sendIfAllowed(): Boolean = withContext(Dispatchers.IO) {
        val settings = (context.applicationContext as StreetPassApp).container.settingsRepository.current()
        if (!settings.shareAnonymousStats) return@withContext false
        val connection = URL("https://streetpass.coolify.megaworld.space/v1/telemetry").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            val country = Locale.getDefault().country.ifBlank { "XX" }
            val body = JSONObject().apply {
                put("installation_id", installationId)
                put("country", country)
                put("app_version", BuildConfig.VERSION_NAME)
            }.toString()
            connection.outputStream.use { it.write(body.toByteArray()) }
            connection.responseCode in 200..299
        } finally { connection.disconnect() }
    }
}
