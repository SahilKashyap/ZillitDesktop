package com.zillit.desktop.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.icon.ZillitIcons

/**
 * A file the OS dropped onto a surface.
 *
 * Bytes are read at drop time, exactly as the picker reads them at pick time —
 * the file may move or vanish the moment the drag ends. A file over the
 * caller's `maxBytes` arrives unread: empty [bytes], its real [sizeBytes], so
 * the surface can say why it was refused rather than pull it into memory.
 */
class DroppedFile(
    val name: String,
    val contentType: String,
    val bytes: ByteArray,
    val sizeBytes: Long = bytes.size.toLong(),
)

/**
 * Accepts files dragged in from the OS — Finder, an email, a browser download
 * strip. [onHover] drives the "drop to attach" overlay ([ZillitDropOverlay]);
 * [onFiles] fires once per drop with everything that was dragged.
 *
 * One seam for every surface that takes a drop — the Home board and a chat
 * thread share it, so a fix to the AWT bridge lands in both. Files larger
 * than [maxBytes] are not read (see [DroppedFile]).
 */
@Composable
expect fun Modifier.externalFileDrop(
    enabled: Boolean,
    onHover: (Boolean) -> Unit,
    onFiles: (List<DroppedFile>) -> Unit,
    maxBytes: Long = Long.MAX_VALUE,
): Modifier

/**
 * "Drop to attach" — the tinted veil and card a surface shows while an OS
 * drag is over it. Place it last in the surface's `Box` so it covers
 * everything; the caller decides the words.
 */
@Composable
fun ZillitDropOverlay(
    title: String,
    hint: String?,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(ZillitTheme.colors.accent.copy(alpha = DROP_SCRIM_ALPHA)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .clip(ZillitTheme.shapes.large)
                .background(ZillitTheme.colors.surface)
                .border(DROP_RING, ZillitTheme.colors.accent, ZillitTheme.shapes.large)
                .padding(ZillitTheme.spacing.lg),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        ) {
            ZillitIcon(
                icon = ZillitIcons.Add,
                tint = ZillitTheme.colors.accent,
                size = DROP_ICON,
            )
            ZillitText(
                text = title,
                style = ZillitTheme.typography.titleSmall,
                color = ZillitTheme.colors.textPrimary,
            )
            hint?.let {
                ZillitText(
                    text = it,
                    style = ZillitTheme.typography.labelSmall,
                    color = ZillitTheme.colors.textMuted,
                )
            }
        }
    }
}

private val DROP_RING = 2.dp
private val DROP_ICON = 32.dp
private const val DROP_SCRIM_ALPHA = 0.12f
