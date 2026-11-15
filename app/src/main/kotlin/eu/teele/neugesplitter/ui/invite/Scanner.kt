package eu.teele.neugesplitter.ui.invite

import androidx.activity.compose.BackHandler
import androidx.camera.compose.CameraXViewfinder
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BinaryBitmap
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import eu.teele.neugesplitter.R
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.awaitCancellation

/**
 * The back camera, full screen, reading QR codes until [onCode] takes one (BE-18). A code it turns
 * down is said to be no invitation, and not offered again.
 */
@Composable
fun Scanner(onCode: (String) -> Boolean, onClose: () -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val take by rememberUpdatedState(onCode)
    var unknown by remember { mutableStateOf(false) }
    var request by remember { mutableStateOf<SurfaceRequest?>(null) }

    BackHandler(onBack = onClose)
    LaunchedEffect(owner) {
        val provider = ProcessCameraProvider.awaitInstance(context)
        val analysis = Executors.newSingleThreadExecutor()
        val main = ContextCompat.getMainExecutor(context)
        val reader = QRCodeReader()
        val taken = AtomicBoolean()
        var rejected: String? = null
        val preview = Preview.Builder().build().apply { setSurfaceProvider { request = it } }
        val frames =
            ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
        frames.setAnalyzer(analysis) { image ->
            val text = image.use { decoded(reader, it) } ?: return@setAnalyzer
            main.execute {
                if (taken.get() || text == rejected) return@execute
                if (take(text)) {
                    taken.set(true)
                } else {
                    rejected = text
                    unknown = true
                }
            }
        }
        val camera =
            if (provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) {
                CameraSelector.DEFAULT_BACK_CAMERA
            } else {
                CameraSelector.DEFAULT_FRONT_CAMERA
            }
        try {
            provider.bindToLifecycle(owner, camera, preview, frames)
            awaitCancellation()
        } finally {
            provider.unbind(preview, frames)
            frames.clearAnalyzer()
            analysis.shutdown()
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        request?.let { CameraXViewfinder(it, Modifier.fillMaxSize()) }
        IconButton(onClick = onClose, modifier = Modifier.statusBarsPadding().padding(8.dp)) {
            Icon(
                painterResource(R.drawable.ic_close),
                stringResource(R.string.close),
                tint = Color.White,
            )
        }
        Text(
            stringResource(if (unknown) R.string.join_scan_unknown else R.string.join_scan_hint),
            color = Color.White,
            modifier =
                Modifier.align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(24.dp)
                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

/** The text of the QR code in [image]'s brightness plane, if it shows one. */
private fun decoded(reader: QRCodeReader, image: ImageProxy): String? {
    val plane = image.planes[0]
    val buffer = plane.buffer
    val data = ByteArray(buffer.remaining()).also(buffer::get)
    val source =
        PlanarYUVLuminanceSource(
            data,
            plane.rowStride,
            image.height,
            0,
            0,
            image.width,
            image.height,
            false,
        )
    return try {
        reader.decode(BinaryBitmap(HybridBinarizer(source))).text
    } catch (_: ReaderException) {
        null
    } finally {
        reader.reset()
    }
}
