package com.zillit.desktop.feature.formsignature.ui.pages

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitPageHeader
import com.zillit.desktop.core.designsystem.component.ZillitSectionCard
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.formsignature.domain.SignatureBlock
import com.zillit.desktop.feature.formsignature.domain.StrokePoint
import com.zillit.desktop.feature.formsignature.ui.DrawState
import com.zillit.desktop.feature.formsignature.ui.FormSignatureEvent
import com.zillit.desktop.feature.formsignature.ui.FormSignatureUiState
import com.zillit.desktop.feature.formsignature.ui.decodeImageBitmap

/**
 * The signature block — one signature and one initials, drawn by hand.
 *
 * The web enforces exactly one of each by hiding the Add button once a block
 * of that kind exists; replacing goes through the same save with the block's
 * id. Mirrored here.
 */
@Composable
internal fun SignaturesPage(
    state: FormSignatureUiState,
    onEvent: (FormSignatureEvent) -> Unit,
) {
    val signatures = state.signatures
    val signature = signatures.blocks.firstOrNull { it.isSignature }
    val initials = signatures.blocks.firstOrNull { !it.isSignature }

    ZillitPageHeader(
        eyebrow = "Documents & Signature",
        title = "Signature block",
        description = "The marks that sign for you. Signing a document stamps these " +
            "into the PDF at the placed boxes.",
        actions = {
            ZillitButton(
                text = "Refresh",
                onClick = { onEvent(FormSignatureEvent.Refresh) },
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
                leadingIcon = ZillitIcons.Reload,
                loading = signatures.loading,
            )
        },
    )

    if (signatures.loading && signatures.blocks.isEmpty()) ZillitSpinner()

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg),
    ) {
        BlockCard(
            title = "Signature",
            block = signature,
            image = signature?.let { signatures.images[it.id] },
            isSignature = true,
            onEvent = onEvent,
        )
        BlockCard(
            title = "Initials",
            block = initials,
            image = initials?.let { signatures.images[it.id] },
            isSignature = false,
            onEvent = onEvent,
        )
    }

    DrawDialog(state.draw, onEvent)
}

@Composable
private fun BlockCard(
    title: String,
    block: SignatureBlock?,
    image: ByteArray?,
    isSignature: Boolean,
    onEvent: (FormSignatureEvent) -> Unit,
) {
    ZillitSectionCard(
        modifier = Modifier.width(BLOCK_CARD_WIDTH.dp),
        title = title,
        icon = ZillitIcons.Edit,
        meta = block?.name,
        action = {
            if (block == null) {
                ZillitButton(
                    text = "Draw",
                    onClick = { onEvent(FormSignatureEvent.StartDraw(isSignature, null)) },
                    size = ButtonSize.Small,
                    leadingIcon = ZillitIcons.Add,
                )
            } else {
                ZillitButton(
                    text = "Redraw",
                    onClick = { onEvent(FormSignatureEvent.StartDraw(isSignature, block.id)) },
                    variant = ButtonVariant.Secondary,
                    size = ButtonSize.Small,
                )
                ZillitButton(
                    text = "Delete",
                    onClick = { onEvent(FormSignatureEvent.DeleteSignature(block.id)) },
                    variant = ButtonVariant.Tertiary,
                    size = ButtonSize.Small,
                )
            }
        },
    ) {
        when {
            block == null -> ZillitText(
                text = "Not set up yet. You need this before you can sign anything.",
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textSecondary,
            )

            image == null -> ZillitSpinner()

            else -> {
                val bitmap = remember(block.id, image.size) { decodeImageBitmap(image) }
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap,
                        contentDescription = title,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(BLOCK_PREVIEW_HEIGHT.dp)
                            .background(Color.White),
                    )
                } else {
                    ZillitText(
                        text = "The stored image could not be shown.",
                        style = ZillitTheme.typography.bodySmall,
                        color = ZillitTheme.colors.textSecondary,
                    )
                }
            }
        }
    }
}

/**
 * The drawing pad.
 *
 * Strokes are captured in the pad's own pixel space and scaled to the
 * rasteriser's 800×300 canvas on save — the PNG must come out the same size
 * whatever the dialog's density did to the pad.
 */
@Composable
private fun DrawDialog(draw: DrawState?, onEvent: (FormSignatureEvent) -> Unit) {
    ZillitDialogShell(
        title = if (draw?.isSignature != false) "Draw your signature" else "Draw your initials",
        visible = draw != null,
        onDismiss = { onEvent(FormSignatureEvent.CancelDraw) },
        icon = ZillitIcons.Edit,
        actions = {
            ZillitButton(
                text = "Clear",
                onClick = {
                    draw?.let { onEvent(FormSignatureEvent.EditDraw(it.copy(strokes = emptyList()))) }
                },
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
            )
            ZillitButton(
                text = "Save",
                onClick = { onEvent(FormSignatureEvent.SubmitDraw) },
                size = ButtonSize.Small,
                loading = draw?.saving == true,
            )
        },
    ) {
        if (draw == null) return@ZillitDialogShell
        Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
            ZillitTextField(
                value = draw.name,
                onValueChange = { onEvent(FormSignatureEvent.EditDraw(draw.copy(name = it))) },
                label = "Name",
                placeholder = if (draw.isSignature) "e.g. Full signature" else "e.g. Initials",
            )
            DrawingPad(draw, onEvent)
            ZillitNotice(
                text = "Draw with the mouse or trackpad. What you save here is stamped " +
                    "into every document you sign.",
                tone = StatusTone.Neutral,
                icon = ZillitIcons.Info,
            )
        }
    }
}

@Composable
private fun DrawingPad(draw: DrawState, onEvent: (FormSignatureEvent) -> Unit) {
    // The live stroke accumulates locally per drag and is committed as one
    // event on release, so the state isn't rewritten on every pixel.
    val current = remember { mutableListOf<StrokePoint>() }

    // One stable holder both the size callback and the (keyed, long-lived)
    // pointer lambda share — plain locals would split across recompositions.
    val padSize = remember { intArrayOf(0, 0) }

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(PAD_ASPECT)
            .background(Color.White)
            .border(1.dp, ZillitTheme.colors.border)
            .onSizeChanged {
                padSize[0] = it.width
                padSize[1] = it.height
            }
            .pointerInput(draw.isSignature, draw.existingId) {
                detectDragGestures(
                    onDragStart = { offset ->
                        current.clear()
                        current += offset.toStrokePoint(padSize[0], padSize[1])
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        current += change.position.toStrokePoint(padSize[0], padSize[1])
                    },
                    onDragEnd = {
                        if (current.size > 1) {
                            onEvent(FormSignatureEvent.AddDrawStroke(current.toList()))
                        }
                        current.clear()
                    },
                )
            },
    ) {
        val scaleX = size.width / RASTER_WIDTH
        val scaleY = size.height / RASTER_HEIGHT
        draw.strokes.forEach { stroke ->
            if (stroke.size < 2) return@forEach
            val path = Path()
            path.moveTo(stroke.first().x * scaleX, stroke.first().y * scaleY)
            stroke.drop(1).forEach { point -> path.lineTo(point.x * scaleX, point.y * scaleY) }
            drawPath(path, INK, style = Stroke(width = PEN_WIDTH))
        }
    }
}

/** Pad pixels → the rasteriser's fixed 800×300 canvas. */
private fun Offset.toStrokePoint(padWidth: Int, padHeight: Int): StrokePoint {
    val safeWidth = if (padWidth == 0) 1 else padWidth
    val safeHeight = if (padHeight == 0) 1 else padHeight
    return StrokePoint(
        x = x / safeWidth * RASTER_WIDTH,
        y = y / safeHeight * RASTER_HEIGHT,
    )
}

private const val BLOCK_CARD_WIDTH = 380
private const val BLOCK_PREVIEW_HEIGHT = 120
private const val PAD_ASPECT = 800f / 300f
private const val RASTER_WIDTH = 800f
private const val RASTER_HEIGHT = 300f
private const val PEN_WIDTH = 3f
private val INK = Color(0xFF162A60)
