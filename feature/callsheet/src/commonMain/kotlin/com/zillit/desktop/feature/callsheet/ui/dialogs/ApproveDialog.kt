// Compose screens read top to bottom in layout order; splitting them by length hides that.
@file:Suppress("LongMethod")

package com.zillit.desktop.feature.callsheet.ui.dialogs

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.callsheet.domain.formatDateTime
import com.zillit.desktop.feature.callsheet.ui.DialogEvent
import com.zillit.desktop.feature.callsheet.ui.SheetDialog
import com.zillit.desktop.feature.callsheet.ui.SheetEvent
import com.zillit.desktop.feature.callsheet.ui.SheetUiState
import com.zillit.desktop.feature.callsheet.ui.WorkflowEvent
import com.zillit.desktop.feature.callsheet.ui.components.ButtonKind
import com.zillit.desktop.feature.callsheet.ui.components.SheetButton
import com.zillit.desktop.feature.callsheet.ui.components.SheetModal
import com.zillit.desktop.feature.callsheet.ui.components.plainClick
import com.zillit.desktop.feature.callsheet.ui.components.rememberHover
import com.zillit.desktop.feature.callsheet.ui.components.sheetText
import com.zillit.desktop.feature.callsheet.ui.decodeImageBitmap
import com.zillit.desktop.feature.callsheet.ui.renderSignaturePng
import com.zillit.desktop.feature.callsheet.ui.theme.SheetIcons
import com.zillit.desktop.feature.callsheet.ui.theme.SheetTheme

/**
 * "Approve Call Sheet" — ZL-21512: the chooser first (`ApproveChoiceModal`:
 * with a signature, or without, or Cancel), then, for a signature, the pad
 * (`ApproveSignatureModal`): who is approving, the signature drawn and
 * confirmed with "Use This Signature", and one button locked to that choice,
 * disabled until a signature exists. No saved-signature picker here.
 */
@Composable
internal fun ApproveDialog(
    state: SheetUiState,
    dialog: SheetDialog.Approve,
    onEvent: (SheetEvent) -> Unit,
    nowMillis: () -> Long,
) {
    if (dialog.sign) {
        SignatureStep(state, dialog, onEvent, nowMillis)
    } else {
        ChoiceStep(state, onEvent)
    }
}

/** The two choices as cards, plus Cancel — the production report's step-1 chooser shape. */
@Composable
private fun ChoiceStep(state: SheetUiState, onEvent: (SheetEvent) -> Unit) {
    val colors = SheetTheme.colors
    SheetModal(
        str(S.desktop_cs_approve_call_sheet),
        { onEvent(DialogEvent.Dismiss) },
        width = 520.dp,
        closeOnScrim = !state.busy,
    ) {
        Text(
            str(S.desktop_cs_choose_how_to_approve),
            style = sheetText(14.sp),
            color = colors.textSecondary,
            modifier = Modifier.padding(bottom = 12.dp),
        )
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ChoiceCard(
                title = str(S.desktop_approve_with_signature),
                hint = str(S.desktop_cs_approve_with_signature_hint),
                enabled = !state.busy,
            ) { onEvent(WorkflowEvent.ChooseSignature) }
            ChoiceCard(
                title = if (state.busy) {
                    str(S.ah_run_detail_btn_approving)
                } else {
                    str(S.desktop_approve_without_signature)
                },
                hint = str(S.desktop_cs_approve_without_signature_hint),
                enabled = !state.busy,
            ) { onEvent(WorkflowEvent.ApproveWithoutSignature) }
        }
        Box(Modifier.fillMaxWidth().padding(top = 16.dp).height(1.dp).background(colors.border))
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
            SheetButton(
                str(S.cancel),
                { onEvent(DialogEvent.Dismiss) },
                kind = ButtonKind.Outline,
                enabled = !state.busy,
                height = 36.dp,
                fontSize = 13.sp,
            )
        }
    }
}

@Composable
private fun ChoiceCard(title: String, hint: String, enabled: Boolean, onClick: () -> Unit) {
    val colors = SheetTheme.colors
    val (source, hovered) = rememberHover()
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (hovered && enabled) colors.hover else colors.surface)
            .border(1.dp, if (hovered && enabled) colors.accent else colors.border, RoundedCornerShape(10.dp))
            .hoverable(source)
            .plainClick(enabled = enabled, source = source, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(14.dp).clip(CircleShape)
                .border(1.dp, if (hovered && enabled) colors.accent else colors.borderStrong, CircleShape),
        )
        Column(Modifier.weight(1f)) {
            Text(title, style = sheetText(14.sp, FontWeight.SemiBold), color = colors.textPrimary)
            Text(hint, style = sheetText(12.sp, lineHeight = 17.sp), color = colors.textTertiary)
        }
    }
}

@Composable
private fun SignatureStep(
    state: SheetUiState,
    dialog: SheetDialog.Approve,
    onEvent: (SheetEvent) -> Unit,
    nowMillis: () -> Long,
) {
    val colors = SheetTheme.colors
    val strokes = remember(dialog.request.id) { mutableStateListOf<List<Offset>>() }
    var padSize by remember { mutableStateOf(IntSize.Zero) }
    val openedAt = remember(dialog.request.id) { nowMillis() }
    SheetModal(
        str(S.desktop_cs_approve_call_sheet),
        { onEvent(DialogEvent.Dismiss) },
        width = 640.dp,
        closeOnScrim = !dialog.uploading,
    ) {
        val member = state.currentMember
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(colors.elevated)
                .border(1.dp, colors.border, RoundedCornerShape(12.dp)).padding(12.dp),
        ) {
            Text(
                str(S.desktop_approving_as_upper),
                style = sheetText(10.sp, FontWeight.SemiBold).copy(letterSpacing = 0.5.sp),
                color = colors.textMuted,
            )
            Row(Modifier.padding(top = 4.dp)) {
                Text(
                    member?.fullName?.ifBlank { null } ?: state.viewer.displayName,
                    style = sheetText(14.sp, FontWeight.SemiBold),
                    color = colors.textPrimary,
                )
                val designation = member?.designation.orEmpty().ifBlank { state.viewer.designation }.localised()
                if (designation.isNotBlank()) {
                    Text(" ($designation)", style = sheetText(14.sp), color = colors.textTertiary)
                }
            }
            Text(formatDateTime(openedAt), style = sheetText(12.sp), color = colors.textMuted)
        }
        Text(
            str(S.desktop_signature_upper),
            style = sheetText(11.sp, FontWeight.SemiBold).copy(letterSpacing = 0.5.sp),
            color = colors.textPrimary,
            modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
        )
        val confirmed = dialog.signature
        if (confirmed != null) {
            ConfirmedSignature(confirmed.png, enabled = !dialog.uploading) {
                strokes.clear()
                onEvent(WorkflowEvent.ChangeSignature)
            }
        } else {
            PadHeader(drawn = strokes.isNotEmpty()) { strokes.clear() }
            SignaturePad(strokes, onSize = { padSize = it })
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f))
                if (strokes.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SheetButton(
                            str(S.desktop_redraw),
                            { strokes.clear() },
                            kind = ButtonKind.Outline,
                            icon = SheetIcons.Undo,
                            height = 26.dp,
                            fontSize = 11.sp,
                            radius = 6.dp,
                            horizontalPadding = 10.dp,
                        )
                        SheetButton(
                            str(S.desktop_use_this_signature),
                            {
                                renderSignaturePng(strokes.toList(), padSize.width, padSize.height, SIGNATURE_STROKE)
                                    ?.let { onEvent(WorkflowEvent.UseSignature(it)) }
                            },
                            kind = ButtonKind.Approve,
                            icon = ZillitIcons.Check,
                            height = 26.dp,
                            fontSize = 11.sp,
                            radius = 6.dp,
                            horizontalPadding = 12.dp,
                        )
                    }
                }
            }
        }
        Box(Modifier.fillMaxWidth().padding(top = 16.dp).height(1.dp).background(colors.border))
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SheetButton(
                str(S.cancel),
                { onEvent(DialogEvent.Dismiss) },
                Modifier.weight(1f),
                kind = ButtonKind.Outline,
                enabled = !dialog.uploading,
                height = 42.dp,
                fontSize = 14.sp,
            )
            // One button, locked to the choice already made; disabled until a signature exists.
            SheetButton(
                if (dialog.uploading || state.busy) str(S.ah_uploading) else str(S.desktop_approve_with_signature),
                { onEvent(WorkflowEvent.ApproveWithSignature) },
                Modifier.weight(1f),
                kind = ButtonKind.Approve,
                enabled = confirmed != null && !dialog.uploading && !state.busy,
                height = 42.dp,
                fontSize = 14.sp,
            )
        }
    }
}

private const val SIGNATURE_STROKE = 3.5f

@Composable
private fun PadHeader(drawn: Boolean, onClear: () -> Unit) {
    val colors = SheetTheme.colors
    Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(ZillitIcons.Edit, contentDescription = null, tint = colors.accent, modifier = Modifier.size(12.dp))
        Text(
            str(S.txt_signature),
            style = sheetText(11.sp),
            color = colors.textTertiary,
            modifier = Modifier.padding(start = 6.dp),
        )
        Spacer(Modifier.weight(1f))
        if (drawn) {
            val (source, hovered) = rememberHover()
            Row(
                Modifier.hoverable(source).plainClick(source = source, onClick = onClear),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val tint = if (hovered) colors.red else colors.textMuted
                Icon(SheetIcons.Eraser, contentDescription = null, tint = tint, modifier = Modifier.size(11.dp))
                Text(str(S.ah_clear), style = sheetText(11.sp), color = tint)
            }
        }
    }
}

/**
 * The pad — a dashed box that tints once drawn on; strokes smoothed through
 * their midpoints, a single tap leaves a dot. The ink stays dark on the light
 * pad in either theme, as it will on the page.
 */
@Composable
private fun SignaturePad(strokes: MutableList<List<Offset>>, onSize: (IntSize) -> Unit) {
    var current by remember { mutableStateOf<List<Offset>>(emptyList()) }
    val drawn = strokes.isNotEmpty() || current.isNotEmpty()
    val rim = if (drawn) Color(0xFFA5B4FC) else Color(0xFFE2E8F0)
    Box(
        Modifier
            .fillMaxWidth()
            .height(250.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (drawn) Color(0xFFFAFBFF) else Color(0xFFF8FAFC))
            .drawBehind {
                drawRoundRect(
                    color = rim,
                    cornerRadius = CornerRadius(12.dp.toPx()),
                    style = Stroke(2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(DASH, GAP))),
                )
            }
            .onSizeChanged(onSize)
            .pointerHoverIcon(PointerIcon.Crosshair)
            .pointerInput(Unit) {
                detectTapGestures { point -> strokes.add(listOf(point)) }
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { start -> current = listOf(start) },
                    onDrag = { change, _ -> current = current + change.position },
                    onDragEnd = {
                        if (current.isNotEmpty()) strokes.add(current)
                        current = emptyList()
                    },
                    onDragCancel = { current = emptyList() },
                )
            },
    ) {
        if (!drawn) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    SheetIcons.Pen,
                    contentDescription = null,
                    tint = Color(0xFFD0D5DD),
                    modifier = Modifier.size(28.dp),
                )
                Text(
                    str(S.desktop_draw_your_signature_here),
                    style = sheetText(14.sp),
                    color = Color(0xFF98A2B3),
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
        Canvas(Modifier.fillMaxSize()) {
            (strokes + listOf(current)).filter { it.isNotEmpty() }.forEach { points ->
                if (points.size == 1) {
                    drawCircle(INK, radius = SIGNATURE_STROKE / 2 + 0.5f, center = points.first())
                    return@forEach
                }
                val path = Path().apply {
                    moveTo(points.first().x, points.first().y)
                    points.zipWithNext { a, b ->
                        val mid = Offset((a.x + b.x) / 2, (a.y + b.y) / 2)
                        quadraticTo(a.x, a.y, mid.x, mid.y)
                    }
                    lineTo(points.last().x, points.last().y)
                }
                drawPath(
                    path,
                    INK,
                    style = Stroke(width = SIGNATURE_STROKE, cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
            }
        }
    }
}

private val INK = Color(0xFF1D2939)
private const val DASH = 8f
private const val GAP = 6f

@Composable
private fun ConfirmedSignature(png: ByteArray, enabled: Boolean, onChange: () -> Unit) {
    val bitmap = remember(png) { decodeImageBitmap(png) }
    Box(
        Modifier.fillMaxWidth().height(250.dp).clip(RoundedCornerShape(12.dp))
            .background(Color(0xFFF0FDF4))
            .border(1.dp, Color(0xFF86EFAC), RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center,
    ) {
        bitmap?.let {
            Image(
                it,
                contentDescription = str(S.txt_signature),
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth(0.8f).heightIn(max = 230.dp),
            )
        }
        Box(Modifier.align(Alignment.TopEnd).padding(8.dp)) {
            SheetButton(
                str(S.change),
                onChange,
                kind = ButtonKind.Outline,
                icon = SheetIcons.Eraser,
                enabled = enabled,
                height = 24.dp,
                fontSize = 11.sp,
                horizontalPadding = 8.dp,
                radius = 6.dp,
            )
        }
    }
}
