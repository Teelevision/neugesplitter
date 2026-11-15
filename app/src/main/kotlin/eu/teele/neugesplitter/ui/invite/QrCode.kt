package eu.teele.neugesplitter.ui.invite

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlin.math.floor

/** [text] as a QR code, dark on light whatever the theme, so that any camera reads it. */
@Composable
fun QrCode(text: String, description: String, modifier: Modifier = Modifier) {
    val matrix =
        remember(text) {
            QRCodeWriter()
                .encode(
                    text,
                    BarcodeFormat.QR_CODE,
                    0,
                    0,
                    mapOf(
                        EncodeHintType.MARGIN to 0,
                        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                    ),
                )
        }
    Canvas(
        modifier
            .aspectRatio(1f)
            .background(Color.White)
            // The quiet zone readers need around the code.
            .padding(24.dp)
            .semantics { contentDescription = description }
    ) {
        // Whole pixels per module, so that no seams show between them.
        val cell = floor(size.minDimension / matrix.width)
        val origin =
            Offset((size.width - cell * matrix.width) / 2, (size.height - cell * matrix.height) / 2)
        for (y in 0 until matrix.height) {
            for (x in 0 until matrix.width) {
                if (matrix[x, y]) {
                    drawRect(Color.Black, origin + Offset(x * cell, y * cell), Size(cell, cell))
                }
            }
        }
    }
}
