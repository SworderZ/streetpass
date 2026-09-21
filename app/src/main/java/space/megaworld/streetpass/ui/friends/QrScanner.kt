package space.megaworld.streetpass.ui.friends

import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import java.util.concurrent.Executors

/**
 * Видоискатель с распознаванием QR. Каждый распознанный текст отдаётся в [onText] в
 * main-потоке; вызывающая сторона сама решает, закрыть сканер или ждать другой код.
 */
@Composable
fun QrScannerView(
    onText: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnText = rememberUpdatedState(onText)
    // Свой поток под анализ кадров: декодирование ZXing занимает десятки миллисекунд.
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    val providerFuture = remember { ProcessCameraProvider.getInstance(context) }

    DisposableEffect(Unit) {
        onDispose {
            // Без явного unbind камера остаётся у lifecycle активити и после закрытия диалога.
            if (providerFuture.isDone) providerFuture.get().unbindAll()
            analysisExecutor.shutdown()
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            val view = PreviewView(ctx).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
            val mainExecutor = ContextCompat.getMainExecutor(ctx)
            providerFuture.addListener({
                val provider = providerFuture.get()
                val preview = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(analysisExecutor, QrAnalyzer { text -> mainExecutor.execute { currentOnText.value(text) } })
                try {
                    provider.unbindAll()
                    provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                } catch (e: IllegalStateException) {
                    Log.w(TAG, "camera bind failed", e)
                } catch (e: IllegalArgumentException) {
                    // Нет задней камеры — на планшетах и эмуляторах бывает.
                    Log.w(TAG, "no suitable camera", e)
                }
            }, mainExecutor)
            view
        },
    )
}

private class QrAnalyzer(private val onText: (String) -> Unit) : ImageAnalysis.Analyzer {

    private val reader = QRCodeReader()
    private val hints = mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE))

    override fun analyze(image: ImageProxy) {
        image.use { frame ->
            // ZXing хватает одной яркостной плоскости YUV; QR распознаётся в любом повороте,
            // поэтому rotationDegrees не учитываем.
            val plane = frame.planes[0]
            val width = frame.width
            val height = frame.height
            val buffer = plane.buffer
            val data = ByteArray(width * height)
            if (plane.rowStride == width) {
                buffer.get(data)
            } else {
                // Строки выровнены с запасом — копируем без padding'а.
                for (row in 0 until height) {
                    buffer.position(row * plane.rowStride)
                    buffer.get(data, row * width, width)
                }
            }
            val source = PlanarYUVLuminanceSource(data, width, height, 0, 0, width, height, false)
            val result = try {
                reader.decode(BinaryBitmap(HybridBinarizer(source)), hints)
            } catch (e: ReaderException) {
                // В кадре нет кода — обычное состояние, не ошибка.
                null
            } finally {
                reader.reset()
            }
            result?.text?.let(onText)
        }
    }
}

private const val TAG = "QrScanner"
