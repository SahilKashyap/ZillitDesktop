@file:Suppress("MagicNumber") // Overlay colours and handle sizes, the web's own values.

package com.zillit.desktop.feature.formsignature.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.formsignature.domain.PdfPageImage
import com.zillit.desktop.feature.formsignature.domain.SignSpot
import com.zillit.desktop.feature.formsignature.domain.SignSpotKind
import com.zillit.desktop.feature.formsignature.ui.decodeImageBitmap

/**
 * One rendered page, 800 wide like the web's `<Page width={800}>`, with a
 * slot for overlays measured in the image's own pixels (1 px = 1 dp here,
 * which is what lets every overlay rectangle come straight from
 * [PdfPageImage.pixelRect]).
 */
@Composable
internal fun PageCanvas(
    page: PdfPageImage,
    modifier: Modifier = Modifier,
    overlay: @Composable BoxScope.() -> Unit = {},
) {
    val bitmap = remember(page.page, page.imageBytes.size) { decodeImageBitmap(page.imageBytes) } ?: return
    val widthDp: Dp = page.widthPx.dp
    val heightDp: Dp = page.heightPx.dp
    Box(
        modifier = modifier
            .width(widthDp)
            .height(heightDp)
            .shadow(8.dp, ZillitTheme.shapes.small)
            .background(Color.White)
            .border(1.dp, ZillitTheme.colors.border),
    ) {
        Image(
            bitmap = bitmap,
            contentDescription = str(S.desktop_page_n, page.page),
            modifier = Modifier.size(widthDp, heightDp),
            contentScale = ContentScale.FillBounds,
        )
        overlay()
    }
}

/** The web's overlay hues: blue for signatures; initials green while being placed, amber when waiting to be filled. */
internal fun spotEdge(kind: SignSpotKind, placing: Boolean = false): Color = when {
    kind == SignSpotKind.Signature -> Color(0xFF3B82F6)
    placing -> Color(0xFF10B981)
    else -> Color(0xFFF59E0B)
}

internal fun spotFill(kind: SignSpotKind, placing: Boolean = false): Color = spotEdge(kind, placing).copy(alpha = 0.16f)

/**
 * A placeholder to fill — the web's clickable "Add Signature" / "Add
 * Initials" box on the received document. Hover brightens it.
 */
@Composable
internal fun BoxScope.PlaceholderBox(page: PdfPageImage, spot: SignSpot, onClick: () -> Unit) {
    val rect = page.pixelRect(spot)
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val edge = spotEdge(spot.kind)
    Box(
        modifier = Modifier
            .offset(x = rect[0].dp, y = rect[1].dp)
            .size(rect[2].dp, rect[3].dp)
            .background(edge.copy(alpha = if (hovered) 0.28f else 0.14f))
            .border(2.dp, edge)
            .hoverable(interaction)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        ZillitText(
            text = if (spot.kind == SignSpotKind.Signature) str(S.add_signature) else str(S.txt_add_initials),
            style = ZillitTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
            color = edge,
        )
    }
}

/**
 * A box that can be dragged around the page and resized from its corner —
 * the web's `react-rnd` `ResizableImage`, with its ✓ / ✗ pair when the
 * placement must be confirmed. Positions are in image pixels; drag deltas
 * come in screen pixels and are divided by density so 1 px = 1 dp holds.
 *
 * [aspect] locks the height to the width (a signature image); null lets
 * both edges move (a placeholder box).
 */
@Composable
internal fun BoxScope.DraggableBox(
    x: Float,
    y: Float,
    width: Float,
    height: Float,
    edge: Color,
    onMove: (x: Float, y: Float, width: Float, height: Float) -> Unit,
    modifier: Modifier = Modifier,
    aspect: Float? = null,
    onConfirm: (() -> Unit)? = null,
    onCancel: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit = {},
) {
    val density = LocalDensity.current.density
    // The geometry the long-lived gesture lambdas read; state is the source of truth,
    // this only avoids stale captures mid-drag.
    val current = remember { floatArrayOf(x, y, width, height) }
    current[0] = x; current[1] = y; current[2] = width; current[3] = height

    Box(
        modifier = modifier
            .offset(x = x.dp, y = y.dp)
            .size(width.dp, height.dp)
            .border(2.dp, edge)
            .pointerHoverIcon(PointerIcon.Hand)
            .pointerInput(Unit) {
                detectDragGestures { change, drag ->
                    change.consume()
                    onMove(current[0] + drag.x / density, current[1] + drag.y / density, current[2], current[3])
                }
            },
    ) {
        content()

        // The corner handle: width follows the drag; height follows the aspect when locked.
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .offset(x = 6.dp, y = 6.dp)
                .size(14.dp)
                .clip(CircleShape)
                .background(edge)
                .border(2.dp, Color.White, CircleShape)
                .pointerHoverIcon(PointerIcon.Crosshair)
                .pointerInput(aspect) {
                    detectDragGestures { change, drag ->
                        change.consume()
                        val w = (current[2] + drag.x / density).coerceAtLeast(MIN_BOX)
                        val h = if (aspect != null) {
                            w * aspect
                        } else {
                            (current[3] + drag.y / density).coerceAtLeast(MIN_BOX)
                        }
                        onMove(current[0], current[1], w, h)
                    }
                },
        )

        if (onConfirm != null || onCancel != null || onDelete != null) {
            Row(
                modifier = Modifier.align(Alignment.TopEnd).offset(x = 10.dp, y = (-12).dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                onConfirm?.let {
                    TinyAction(ZillitIcons.Tick, Color(0xFF10B981), str(S.desktop_fs_confirm_placement), it)
                }
                onCancel?.let { TinyAction(ZillitIcons.Close, Color(0xFFEF4444), str(S.cancel), it) }
                onDelete?.let { TinyAction(ZillitIcons.Close, Color(0xFFEF4444), str(S.remove), it) }
            }
        }
    }
}

@Composable
private fun TinyAction(icon: ImageVector, tint: Color, label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(22.dp)
            .shadow(2.dp, CircleShape)
            .clip(CircleShape)
            .background(tint)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        ZillitIcon(icon, contentDescription = label, tint = Color.White, size = 13.dp)
    }
}

/** The label inside a placeholder being placed or already placed — the web's "✍️ Signature Placeholder". */
@Composable
internal fun BoxScope.PlaceholderLabel(kind: SignSpotKind, fill: Color, edge: Color, owner: String? = null) {
    Box(Modifier.fillMaxSize().background(fill), contentAlignment = Alignment.Center) {
        ZillitText(
            text = buildString {
                append(
                    if (kind == SignSpotKind.Signature) {
                        str(S.desktop_fs_signature_placeholder)
                    } else {
                        str(S.desktop_fs_initials_placeholder)
                    },
                )
                if (!owner.isNullOrBlank()) append(" · ").append(owner)
            },
            style = ZillitTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
            color = edge,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}

/** A signature PNG stretched to its box — what will be stamped. */
@Composable
internal fun BoxScope.MarkImage(png: ByteArray) {
    val bitmap = remember(png.size) { decodeImageBitmap(png) } ?: return
    Image(
        bitmap = bitmap,
        contentDescription = str(S.signature_txt),
        modifier = Modifier.fillMaxSize(),
        contentScale = ContentScale.FillBounds,
    )
}

private const val MIN_BOX = 30f
