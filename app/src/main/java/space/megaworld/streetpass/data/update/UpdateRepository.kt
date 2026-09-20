package space.megaworld.streetpass.data.update

import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.util.Log
import androidx.core.content.getSystemService
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import space.megaworld.streetpass.core.Versions

data class ReleaseInfo(
    val version: String,
    val tag: String,
    val notes: String,
    val apkUrl: String?,
    val pageUrl: String,
)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class UpToDate(val latest: String) : UpdateState
    data class Available(val release: ReleaseInfo) : UpdateState
    data class Downloading(val release: ReleaseInfo, val percent: Int?) : UpdateState
    data class Downloaded(val release: ReleaseInfo) : UpdateState
    data class Error(val message: String, val release: ReleaseInfo? = null) : UpdateState
}

/**
 * Единственное место в приложении, которое ходит в сеть: один GET к GitHub Releases
 * по нажатию пользователя и, по его желанию, скачивание APK через DownloadManager.
 */
class UpdateRepository(
    private val context: Context,
    private val repo: String,
    private val currentVersion: String,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state

    private var downloadJob: Job? = null

    suspend fun check() {
        if (_state.value is UpdateState.Checking || _state.value is UpdateState.Downloading) return
        _state.value = UpdateState.Checking
        _state.value = try {
            val release = withContext(Dispatchers.IO) { fetchLatest() }
            if (Versions.isNewer(release.version, currentVersion)) {
                UpdateState.Available(release)
            } else {
                UpdateState.UpToDate(release.version)
            }
        } catch (e: NoReleasesException) {
            UpdateState.Error("На GitHub пока нет ни одного релиза")
        } catch (e: IOException) {
            Log.w(TAG, "update check failed", e)
            UpdateState.Error("Не удалось связаться с GitHub: ${e.message ?: "нет сети"}")
        } catch (e: JSONException) {
            Log.w(TAG, "unexpected GitHub response", e)
            UpdateState.Error("GitHub вернул неожиданный ответ")
        }
    }

    fun canInstallPackages(): Boolean = context.packageManager.canRequestPackageInstalls()

    fun download(release: ReleaseInfo) {
        val url = release.apkUrl ?: return
        val manager = context.getSystemService<DownloadManager>()
        if (manager == null) {
            _state.value = UpdateState.Error("Менеджер загрузок недоступен", release)
            return
        }
        downloadJob?.cancel()
        _state.value = UpdateState.Downloading(release, null)

        val fileName = "streetpass-${release.tag}.apk"
        // Каталог приложения на внешнем хранилище: разрешений на storage не нужно.
        context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)?.let { dir ->
            java.io.File(dir, fileName).delete()
        }
        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle("StreetPass ${release.version}")
            .setMimeType(APK_MIME)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, fileName)
        val id = try {
            manager.enqueue(request)
        } catch (e: IllegalArgumentException) {
            _state.value = UpdateState.Error("Некорректная ссылка на APK", release)
            return
        }

        downloadJob = scope.launch(Dispatchers.IO) {
            while (true) {
                val progress = queryProgress(manager, id)
                when (progress.status) {
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        _state.value = UpdateState.Downloaded(release)
                        launchInstaller(manager, id, release)
                        return@launch
                    }
                    DownloadManager.STATUS_FAILED -> {
                        _state.value = UpdateState.Error("Загрузка не удалась (код ${progress.reason})", release)
                        return@launch
                    }
                    else -> _state.value = UpdateState.Downloading(release, progress.percent)
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    fun reset() {
        downloadJob?.cancel()
        _state.value = UpdateState.Idle
    }

    private class Progress(val status: Int, val reason: Int, val percent: Int?)

    private fun queryProgress(manager: DownloadManager, id: Long): Progress {
        manager.query(DownloadManager.Query().setFilterById(id)).use { cursor ->
            if (!cursor.moveToFirst()) return Progress(DownloadManager.STATUS_FAILED, -1, null)
            val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
            val done = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
            val total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
            val percent = if (total > 0) (done * 100 / total).toInt() else null
            return Progress(status, reason, percent)
        }
    }

    private fun launchInstaller(manager: DownloadManager, id: Long, release: ReleaseInfo) {
        val uri = manager.getUriForDownloadedFile(id)
        if (uri == null) {
            _state.value = UpdateState.Error("Скачанный файл не найден", release)
            return
        }
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, APK_MIME)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            _state.value = UpdateState.Error("Не найден системный установщик пакетов", release)
        }
    }

    private fun fetchLatest(): ReleaseInfo {
        val connection = URL("https://api.github.com/repos/$repo/releases/latest").openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            // GitHub отвергает запросы без User-Agent.
            connection.setRequestProperty("User-Agent", "StreetPass/$currentVersion")
            val code = connection.responseCode
            if (code == HttpURLConnection.HTTP_NOT_FOUND) throw NoReleasesException()
            if (code != HttpURLConnection.HTTP_OK) throw IOException("HTTP $code")
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            return parseRelease(JSONObject(body))
        } finally {
            connection.disconnect()
        }
    }

    private fun parseRelease(json: JSONObject): ReleaseInfo {
        val tag = json.getString("tag_name")
        val assets = json.optJSONArray("assets")
        var apkUrl: String? = null
        if (assets != null) {
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                if (asset.getString("name").endsWith(".apk", ignoreCase = true)) {
                    apkUrl = asset.getString("browser_download_url")
                    break
                }
            }
        }
        return ReleaseInfo(
            version = tag.removePrefix("v"),
            tag = tag,
            notes = json.optString("body").trim(),
            apkUrl = apkUrl,
            pageUrl = json.optString("html_url").ifEmpty { "https://github.com/$repo/releases/latest" },
        )
    }

    private class NoReleasesException : IOException("no releases")

    companion object {
        private const val TAG = "UpdateRepository"
        private const val APK_MIME = "application/vnd.android.package-archive"
        private const val TIMEOUT_MS = 10_000
        private const val POLL_INTERVAL_MS = 500L
    }
}
