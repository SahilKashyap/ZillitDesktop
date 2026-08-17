package com.zillit.desktop.core.designsystem.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme

/**
 * Renders a QR code.
 *
 * ## Encoded locally, on purpose
 *
 * The Android client renders login QR codes by calling a third-party image
 * service:
 *
 * ```
 * https://qrcode.tec-it.com/API/QRCode?data=${code}&...
 * ```
 *
 * That puts the device-linking secret in a URL query string and sends it to an
 * unrelated third party on every sign-in — where it lands in their access logs.
 * Encoding here means the code never leaves the machine, and the screen works
 * with no network at all.
 *
 * Always drawn on white with a quiet zone regardless of theme: scanners need
 * dark-on-light contrast, and a "helpfully" theme-tinted QR is a QR that phones
 * fail to read in dim light.
 */
@Composable
fun ZillitQrCode(
    content: String,
    modifier: Modifier = Modifier,
    size: Dp = DEFAULT_SIZE,
    onEncodingFailed: @Composable () -> Unit = { QrEncodingFailed() },
) {
    val matrix = remember(content) { encodeQrCode(content) }

    if (matrix == null) {
        onEncodingFailed()
        return
    }

    Box(
        modifier = modifier
            .size(size)
            .clip(ZillitTheme.shapes.medium)
            // White, never `colors.surface` — see kdoc.
            .background(Color.White)
            .padding(QUIET_ZONE),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(size - QUIET_ZONE * 2)) {
            val module = this.size.width / matrix.size
            for (y in 0 until matrix.size) {
                for (x in 0 until matrix.size) {
                    if (!matrix[x, y]) continue
                    drawRect(
                        color = Color.Black,
                        topLeft = Offset(x * module, y * module),
                        // A hair over one module: adjacent modules must meet, or
                        // sub-pixel gaps appear as white seams that hurt scanning.
                        size = Size(module + MODULE_OVERLAP, module + MODULE_OVERLAP),
                    )
                }
            }
        }
    }
}

@Composable
private fun QrEncodingFailed() {
    ZillitText(
        text = "Could not display the sign-in code.",
        style = ZillitTheme.typography.bodySmall,
        color = ZillitTheme.colors.danger,
    )
}

private val DEFAULT_SIZE = 220.dp
private val QUIET_ZONE = 12.dp
private const val MODULE_OVERLAP = 0.5f
