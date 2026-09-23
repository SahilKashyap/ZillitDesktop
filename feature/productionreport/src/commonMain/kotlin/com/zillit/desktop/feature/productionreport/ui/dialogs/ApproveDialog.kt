// Compose screens read top to bottom in layout order; splitting them by length hides that.
@file:Suppress("LongMethod")

package com.zillit.desktop.feature.productionreport.ui.dialogs

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.productionreport.domain.formatDateTime
import com.zillit.desktop.feature.productionreport.ui.DialogEvent
import com.zillit.desktop.feature.productionreport.ui.ReportDialog
import com.zillit.desktop.feature.productionreport.ui.ReportEvent
import com.zillit.desktop.feature.productionreport.ui.ReportUiState
import com.zillit.desktop.feature.productionreport.ui.WorkflowEvent
import com.zillit.desktop.feature.productionreport.ui.components.ButtonKind
import com.zillit.desktop.feature.productionreport.ui.components.ReportButton
import com.zillit.desktop.feature.productionreport.ui.components.ReportModal
import com.zillit.desktop.feature.productionreport.ui.components.plainClick
import com.zillit.desktop.feature.productionreport.ui.components.reportText
import com.zillit.desktop.feature.productionreport.ui.decodeImageBitmap
import com.zillit.desktop.feature.productionreport.ui.renderSignaturePng
import com.zillit.desktop.feature.productionreport.ui.theme.ReportIcons
import com.zillit.desktop.feature.productionreport.ui.theme.ReportTheme
import kotlin.time.Clock

/**
 * "Approve Production Report" (ZL-21512) — a chooser first: approve with a
 * signature, or without. Choosing the signature opens the signing screen,
 * locked to that choice: its one CTA is "Approve with Signature", disabled
 * until something is drawn; Cancel is the way back. A drawing that was not
 * confirmed still counts: the web silently dropped it.
 */
@Composable
internal fun ApproveDialog(state: ReportUiState, dialog: ReportDialog.Approve, onEvent: (ReportEvent) -> Unit) {
    if (dialog.sign) {
        SignAndApproveDialog(state, dialog, onEvent)
    } else {
        ApproveChoiceDialog(state, onEvent)
    }
}

/** `ApproveChoiceModal`: two choice cards plus Cancel; owns no request. */
@Composable
private fun ApproveChoiceDialog(state: ReportUiState, onEvent: (ReportEvent) -> Unit) {
    val colors = ReportTheme.colors
    ReportModal("Approve Production Report", { onEvent(DialogEvent.Dismiss) }, width = 520.dp) {
        Text(
            "Choose how to approve this production report:",
            style = reportText(14.sp),
            color = colors.textSecondary,
            modifier = Modifier.padding(bottom = 16.dp),
        )
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ApproveChoice(
                title = "Approve with Signature",
                hint = "Draw your signature; it is rendered into the report.",
                enabled = !state.busy,
            ) { onEvent(WorkflowEvent.ChooseSignedApproval) }
            ApproveChoice(
                title = "Approve Without Signature",
                hint = "Your name and the time are rendered into the report instead.",
                enabled = !state.busy,
            ) { onEvent(WorkflowEvent.ApproveWithoutSignature) }
        }
        ReportButton(
            "Cancel",
            { onEvent(DialogEvent.Dismiss) },
            Modifier.fillMaxWidth().padding(top = 12.dp),
            kind = ButtonKind.Ghost,
            enabled = !state.busy,
        )
    }
}

@Composable
private fun ApproveChoice(title: String, hint: String, enabled: Boolean, onClick: () -> Unit) {
    val colors = ReportTheme.colors
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(colors.surface)
            .border(2.dp, colors.border, RoundedCornerShape(12.dp))
            .plainClick(enabled = enabled, onClick = onClick)
            .padding(16.dp),
    ) {
        Text(title, style = reportText(14.sp, FontWeight.SemiBold), color = colors.textPrimary)
        Text(hint, style = reportText(12.sp), color = colors.textTertiary, modifier = Modifier.padding(top = 2.dp))
    }
}

@Composable
private fun SignAndApproveDialog(state: ReportUiState, dialog: ReportDialog.Approve, onEvent: (ReportEvent) -> Unit) {
    val colors = ReportTheme.colors
    val strokes = remember { mutableStateListOf<List<Offset>>() }
    var padSize by remember { mutableStateOf(IntSize.Zero) }
    var confirmed by remember { mutableStateOf<ByteArray?>(null) }
    fun signaturePng(): ByteArray? = confirmed ?: strokes.takeIf { it.isNotEmpty() }?.let {
        renderSignaturePng(it.toList(), padSize.width, padSize.height, SIGNATURE_STROKE)
    }
    ReportModal("Approve Production Report", { onEvent(DialogEvent.Dismiss) }, width = 640.dp) {
        val member = state.currentMember
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(colors.elevated)
                .border(1.dp, colors.border, RoundedCornerShape(12.dp)).padding(12.dp),
        ) {
            Text(
                "APPROVING AS",
                style = reportText(10.sp, FontWeight.SemiBold).copy(letterSpacing = 0.5.sp),
                color = colors.textMuted,
            )
            Row(Modifier.padding(top = 4.dp)) {
                Text(
                    state.viewer.displayName.ifBlank { member?.fullName.orEmpty() },
                    style = reportText(14.sp, FontWeight.SemiBold),
                    color = colors.textPrimary,
                )
                val designation = member?.designation.orEmpty().ifBlank { state.viewer.designation }
                if (designation.isNotBlank()) Text(
                    " ($designation)",
                    style = reportText(14.sp),
                    color = colors.textTertiary,
                )
            }
            Text(
                formatDateTime(Clock.System.now().toEpochMilliseconds()),
                style = reportText(12.sp),
                color = colors.textMuted,
            )
        }
        Text(
            "SIGNATURE",
            style = reportText(11.sp, FontWeight.SemiBold).copy(letterSpacing = 0.5.sp),
            color = colors.textPrimary,
            modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
        )
        val preview = confirmed
        if (preview != null) {
            ConfirmedSignature(preview) {
                confirmed = null
                strokes.clear()
            }
        } else {
            SignaturePad(strokes, onSize = { padSize = it })
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                if (strokes.isNotEmpty()) {
                    ReportButton(
                        "Redraw",
                        { strokes.clear() },
                        kind = ButtonKind.Outline,
                        icon = ReportIcons.Undo,
                        height = 28.dp,
                        fontSize = 11.sp,
                    )
                    ReportButton(
                        "Use This Signature",
                        {
                            confirmed = renderSignaturePng(
                                strokes.toList(),
                                padSize.width,
                                padSize.height,
                                SIGNATURE_STROKE,
                            )
                        },
                        kind = ButtonKind.Approve,
                        icon = ZillitIcons.Check,
                        height = 28.dp,
                        fontSize = 11.sp,
                    )
                }
            }
        }
        Box(Modifier.fillMaxWidth().padding(top = 16.dp).height(1.dp).background(colors.border))
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ReportButton(
                "Cancel",
                { onEvent(DialogEvent.Dismiss) },
                Modifier.weight(1f),
                kind = ButtonKind.Outline,
                height = 42.dp,
                fontSize = 14.sp,
            )
            // Locked to the choice made on the chooser: no flip to "without" once here.
            val hasSignature = confirmed != null || strokes.isNotEmpty()
            ReportButton(
                if (dialog.uploading) "Uploading..." else "Approve with Signature",
                { signaturePng()?.let { onEvent(WorkflowEvent.ApproveWithSignature(it)) } },
                Modifier.weight(1f),
                kind = ButtonKind.Approve,
                enabled = hasSignature && !dialog.uploading && !state.busy,
                height = 42.dp,
                fontSize = 14.sp,
            )
        }
    }
}

private const val SIGNATURE_STROKE = 3f

@Composable
private fun SignaturePad(strokes: MutableList<List<Offset>>, onSize: (IntSize) -> Unit) {
    val colors = ReportTheme.colors
    var current by remember { mutableStateOf<List<Offset>>(emptyList()) }
    Box(
        Modifier.fillMaxWidth().height(250.dp).clip(RoundedCornerShape(12.dp))
            .background(Color.White)
            .border(1.dp, colors.borderStrong, RoundedCornerShape(12.dp))
            .onSizeChanged(onSize)
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
        if (strokes.isEmpty() && current.isEmpty()) {
            Text(
                "Sign here",
                style = reportText(13.sp),
                color = Color(0xFF98A2B3),
                modifier = Modifier.align(Alignment.Center),
            )
        }
        Canvas(Modifier.fillMaxSize()) {
            (strokes + listOf(current)).filter { it.isNotEmpty() }.forEach { points ->
                val path = Path().apply {
                    moveTo(points.first().x, points.first().y)
                    points.drop(1).forEach { lineTo(it.x, it.y) }
                }
                drawPath(
                    path,
                    Color(0xFF1D2939),
                    style = Stroke(width = SIGNATURE_STROKE, cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
            }
        }
    }
}

@Composable
private fun ConfirmedSignature(png: ByteArray, onChange: () -> Unit) {
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
                contentDescription = "Signature",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth(0.8f).heightIn(max = 230.dp),
            )
        }
        Box(Modifier.align(Alignment.TopEnd).padding(8.dp)) {
            ReportButton(
                "Change",
                onChange,
                kind = ButtonKind.Outline,
                icon = ZillitIcons.Close,
                height = 24.dp,
                fontSize = 11.sp,
                horizontalPadding = 8.dp,
            )
        }
    }
}
