@file:Suppress("MagicNumber", "LongMethod") // Card and pad geometry, the web's own sizes; the pad is one gesture.

package com.zillit.desktop.feature.formsignature.ui.pages

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.TagTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitEmptyState
import com.zillit.desktop.core.designsystem.component.ZillitLazyVerticalGrid
import com.zillit.desktop.core.designsystem.component.ZillitSectionCard
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitTag
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.formsignature.domain.SignatureBlock
import com.zillit.desktop.feature.formsignature.domain.StrokePoint
import com.zillit.desktop.feature.formsignature.ui.DrawState
import com.zillit.desktop.feature.formsignature.ui.FormSignatureEvent
import com.zillit.desktop.feature.formsignature.ui.FormSignatureUiState
import com.zillit.desktop.feature.formsignature.ui.components.formDate
import com.zillit.desktop.feature.formsignature.ui.decodeImageBitmap

/**
 * Set/Edit Signature Block — the web's `SectionF`: Add Signature / Add
 * Initials while each is missing, then a card per saved block with Edit
 * and Delete.
 */
@Composable
internal fun SignatureBlockPage(state: FormSignatureUiState, onEvent: (FormSignatureEvent) -> Unit) {
    val signatures = state.signatures
    val colors = ZillitTheme.colors
    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.surface)
                .padding(horizontal = ZillitTheme.spacing.lg, vertical = ZillitTheme.spacing.sm),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ZillitText(
                "The marks that sign for you. Signing a document stamps these into the PDF.",
                style = ZillitTheme.typography.bodySmall,
                color = colors.textSecondary,
                modifier = Modifier.weight(1f),
            )
            if (signatures.signature == null) {
                ZillitButton(
                    text = "Add Signature",
                    onClick = { onEvent(FormSignatureEvent.StartDraw(isSignature = true)) },
                    size = ButtonSize.Small,
                    leadingIcon = ZillitIcons.Add,
                )
            }
            if (signatures.initials == null) {
                ZillitButton(
                    text = "Add Initials",
                    onClick = { onEvent(FormSignatureEvent.StartDraw(isSignature = false)) },
                    size = ButtonSize.Small,
                    leadingIcon = ZillitIcons.Add,
                )
            }
        }
        when {
            signatures.loading && signatures.blocks.isEmpty() ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { ZillitSpinner() }
            signatures.blocks.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                ZillitEmptyState(
                    title = "No Data Found",
                    message = "Add a signature and initials to sign documents with.",
                    icon = ZillitIcons.Signature,
                )
            }
            else -> ZillitLazyVerticalGrid(
                columns = GridCells.Adaptive(CARD_MIN_WIDTH.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(ZillitTheme.spacing.lg),
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg),
                verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg),
            ) {
                items(signatures.blocks, key = { it.id }) { block ->
                    BlockCard(block, signatures.images[block.id], onEvent)
                }
            }
        }
    }
}

@Composable
private fun BlockCard(block: SignatureBlock, image: ByteArray?, onEvent: (FormSignatureEvent) -> Unit) {
    val colors = ZillitTheme.colors
    ZillitSectionCard(padded = false) {
        Box(
            modifier = Modifier.fillMaxWidth().height(CARD_IMAGE_HEIGHT.dp).background(Color.White).padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            val bitmap = remember(block.id, image?.size) { image?.let(::decodeImageBitmap) }
            when {
                image == null -> ZillitSpinner()
                bitmap == null -> ZillitText("The stored image could not be shown.", color = colors.textSecondary)
                else -> Image(
                    bitmap = bitmap,
                    contentDescription = block.name,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                )
            }
            Box(Modifier.align(Alignment.TopEnd)) {
                ZillitTag(
                    label = if (block.isSignature) "Signature" else "Initials",
                    tone = if (block.isSignature) TagTone.Info else TagTone.Success,
                )
            }
        }
        Column(Modifier.padding(ZillitTheme.spacing.lg), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            ZillitText(block.name.ifBlank { "—" }, style = ZillitTheme.typography.titleMedium, maxLines = 1)
            ZillitText(
                "Created: ${formDate(block.createdOn)}",
                style = ZillitTheme.typography.bodySmall,
                color = colors.textSecondary,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().border(1.dp, colors.divider).padding(ZillitTheme.spacing.sm),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            ZillitButton(
                text = "Edit",
                onClick = { onEvent(FormSignatureEvent.StartDraw(block.isSignature, block.id)) },
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
                leadingIcon = ZillitIcons.Edit,
            )
            ZillitButton(
                text = "Delete",
                onClick = { onEvent(FormSignatureEvent.AskDeleteSignature(block.id)) },
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
                leadingIcon = ZillitIcons.Trash,
            )
        }
    }
}

/**
 * Add/Edit — the web's `SignatureCanvas` page: a name card, the pad with
 * its "Draw Signature Here" ghost text, and Clear / Save.
 */
@Composable
internal fun DrawSignaturePage(draw: DrawState, onEvent: (FormSignatureEvent) -> Unit) {
    val colors = ZillitTheme.colors
    Box(Modifier.fillMaxSize().background(colors.canvas)) {
        Column(
            modifier = Modifier
                .width(PAGE_WIDTH.dp)
                .align(Alignment.TopCenter)
                .padding(vertical = ZillitTheme.spacing.xl),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg),
        ) {
            ZillitSectionCard {
                ZillitTextField(
                    value = draw.name,
                    onValueChange = { name ->
                        if (name.length <= NAME_MAX) onEvent(FormSignatureEvent.EditDraw(draw.copy(name = name)))
                    },
                    label = if (draw.isSignature) "Signature Name" else "Initials Name",
                    placeholder = if (draw.isSignature) "Signature Name" else "Initials Name",
                )
            }
            ZillitSectionCard {
                ZillitText(
                    if (draw.isSignature) "Draw Signature Here" else "Draw Initials here",
                    style = ZillitTheme.typography.titleSmall,
                    modifier = Modifier.padding(bottom = ZillitTheme.spacing.sm),
                )
                DrawingPad(draw, onEvent)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
                ZillitButton(
                    text = "Clear",
                    onClick = { onEvent(FormSignatureEvent.ClearDraw) },
                    variant = ButtonVariant.Secondary,
                    enabled = !draw.saving,
                    modifier = Modifier.weight(1f),
                )
                ZillitButton(
                    text = if (draw.isSignature) "Save Signature" else "Save Initials",
                    onClick = { onEvent(FormSignatureEvent.SubmitDraw) },
                    loading = draw.saving,
                    leadingIcon = ZillitIcons.Save,
                    modifier = Modifier.weight(1f),
                )
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
internal fun DrawingPad(draw: DrawState, onEvent: (FormSignatureEvent) -> Unit) {
    val colors = ZillitTheme.colors
    // The live stroke accumulates locally per drag and is committed as one
    // event on release, so the state isn't rewritten on every pixel.
    val current = remember { mutableListOf<StrokePoint>() }
    val live = remember { androidx.compose.runtime.mutableStateOf(0) }

    // One stable holder both the size callback and the (keyed, long-lived)
    // pointer lambda share — plain locals would split across recompositions.
    val padSize = remember { intArrayOf(0, 0) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(PAD_ASPECT)
            .clip(ZillitTheme.shapes.large)
            .background(Color.White)
            .border(3.dp, colors.accent, ZillitTheme.shapes.large),
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerHoverIcon(PointerIcon.Crosshair)
                .onSizeChanged {
                    padSize[0] = it.width
                    padSize[1] = it.height
                }
                .pointerInput(draw.isSignature, draw.existingId) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            current.clear()
                            current += offset.toStrokePoint(padSize[0], padSize[1])
                            live.value++
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            current += change.position.toStrokePoint(padSize[0], padSize[1])
                            live.value++
                        },
                        onDragEnd = {
                            if (current.size > 1) onEvent(FormSignatureEvent.AddDrawStroke(current.toList()))
                            current.clear()
                            live.value++
                        },
                    )
                },
        ) {
            val scaleX = size.width / RASTER_WIDTH
            val scaleY = size.height / RASTER_HEIGHT
            @Suppress("UNUSED_VARIABLE") val tick = live.value // the in-progress stroke redraws on each point
            (draw.strokes + listOf(current.toList())).forEach { stroke ->
                if (stroke.size < 2) return@forEach
                val path = Path()
                path.moveTo(stroke.first().x * scaleX, stroke.first().y * scaleY)
                stroke.drop(1).forEach { point -> path.lineTo(point.x * scaleX, point.y * scaleY) }
                drawPath(path, INK, style = Stroke(width = PEN_WIDTH))
            }
        }
        if (!draw.hasInk && current.isEmpty()) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                ZillitText(
                    if (draw.isSignature) "Draw Signature Here" else "Draw Initials here",
                    style = ZillitTheme.typography.titleMedium.copy(fontWeight = FontWeight.Medium),
                    color = Color(0xFF3B82F6),
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(4.dp))
                ZillitText(
                    if (draw.isSignature) {
                        "Click and drag to create your signature"
                    } else {
                        "Click and drag to create your initials"
                    },
                    style = ZillitTheme.typography.bodySmall,
                    color = colors.textMuted,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/** Pad pixels → the rasteriser's fixed 800×300 canvas. */
private fun Offset.toStrokePoint(padWidth: Int, padHeight: Int): StrokePoint {
    val safeWidth = if (padWidth == 0) 1 else padWidth
    val safeHeight = if (padHeight == 0) 1 else padHeight
    return StrokePoint(x = x / safeWidth * RASTER_WIDTH, y = y / safeHeight * RASTER_HEIGHT)
}

private const val CARD_MIN_WIDTH = 280
private const val CARD_IMAGE_HEIGHT = 190
private const val PAGE_WIDTH = 860
private const val NAME_MAX = 50
private const val PAD_ASPECT = 800f / 300f
private const val RASTER_WIDTH = 800f
private const val RASTER_HEIGHT = 300f
private const val PEN_WIDTH = 3f
private val INK = Color(0xFF162A60)
