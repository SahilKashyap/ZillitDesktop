package com.zillit.desktop.feature.dealmemo.ui.pages.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitScrollColumn
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.dealmemo.domain.preview.NominalCoding
import com.zillit.desktop.feature.dealmemo.domain.preview.NominalRow
import com.zillit.desktop.feature.dealmemo.ui.DealMemoEvent
import com.zillit.desktop.feature.dealmemo.ui.DealMemoUiState
import com.zillit.desktop.feature.dealmemo.ui.NominalsEvent
import com.zillit.desktop.feature.dealmemo.ui.components.DmButton
import com.zillit.desktop.feature.dealmemo.ui.components.DmButtonStyle
import com.zillit.desktop.feature.dealmemo.ui.components.DmModal
import com.zillit.desktop.feature.dealmemo.ui.components.DmType
import com.zillit.desktop.feature.dealmemo.ui.preview.CoaState
import com.zillit.desktop.feature.dealmemo.ui.preview.NominalsEditorState

/**
 * "Update Nominals — {reference}" (`UpdateNominalsModal.jsx`): every pay line
 * of the saved deal with its code. No close button, an inert backdrop, and a
 * question before unsaved codes are thrown away.
 */
@Composable
internal fun UpdateNominalsModal(state: DealMemoUiState, onEvent: (DealMemoEvent) -> Unit) {
    val preview = state.preview
    val editor = preview?.nominals
    val deal = preview?.deal
    val shown = remember { mutableStateOf<Pair<NominalsEditorState, List<NominalRow>>?>(null) }
    if (editor != null && deal != null) shown.value = editor to NominalCoding.rowsFromDeal(deal, editor.form)
    val current = shown.value
    val saving = editor?.saving == true
    val reference = deal?.reference ?: deal?.crewName ?: "Deal Memo"
    DmModal(
        visible = editor != null,
        title = "Update Nominals — $reference",
        // Escape runs the guarded Cancel; the backdrop does nothing.
        onDismiss = { if (editor?.confirmLeave != true) onEvent(NominalsEvent.Cancel) },
        maxWidth = 1100.dp,
        fullHeight = true,
        showClose = false,
        closeOnBackdrop = false,
        footer = {
            DmButton(
                "Cancel",
                onClick = { onEvent(NominalsEvent.Cancel) },
                style = DmButtonStyle.ModalNeutral,
                enabled = !saving,
            )
            SolidButton(
                text = if (saving) "Saving…" else "Save",
                onClick = { onEvent(NominalsEvent.Save) },
                color = PreviewInk.Brand,
                hover = PreviewInk.BrandHover,
                enabled = !saving,
                loading = saving,
                textSize = 12f,
            )
        },
    ) {
        val (form, rows) = current ?: return@DmModal
        ZillitScrollColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        ) {
            LabourNominals(rows, form, state.coa, onEvent)
        }
    }
    LeaveConfirm(editor?.confirmLeave == true, saving, onEvent)
}

/** `Step7Nominal` in table-only mode: the gold "Labour Nominals" card, ELEMENT beside OVERRIDE. */
@Suppress("LongMethod")
@Composable
private fun LabourNominals(
    rows: List<NominalRow>,
    editor: NominalsEditorState,
    coa: CoaState,
    onEvent: (DealMemoEvent) -> Unit,
) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(pv.card)
            .border(1.dp, Color(0xFFF6D8A8), shape),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFFFFFAF1))
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ZillitText(
                text = "Labour Nominals",
                style = DmType.sans(14.sp, FontWeight.Bold),
                color = pv.ink,
                modifier = Modifier.weight(1f),
            )
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(pv.chipBorder))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFFFAFAF6))
                .padding(horizontal = 20.dp, vertical = 10.dp),
        ) {
            HeadText("ELEMENT", Modifier.weight(1f))
            HeadText("OVERRIDE", Modifier.width(OVERRIDE_WIDTH.dp))
        }
        if (rows.isEmpty()) {
            ZillitText(
                text = "Pick an agreement in Step 1 — the labour-nominal lines populate from its OT and premium rows.",
                style = DmType.sans(12.5.sp),
                color = pv.muted,
                modifier = Modifier.padding(20.dp),
            )
        }
        rows.forEach { row ->
            Box(Modifier.fillMaxWidth().height(1.dp).background(pv.chipBorder))
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    ZillitText(
                        text = row.element,
                        style = DmType.sans(13.sp, FontWeight.Medium),
                        color = pv.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    row.category?.let {
                        ZillitText(text = it, style = DmType.sans(11.sp), color = pv.faint, maxLines = 1)
                    }
                }
                CoaCodeField(
                    value = editor.form.valueFor(row),
                    onValueChange = { onEvent(NominalsEvent.Code(row, it)) },
                    coa = coa,
                    modifier = Modifier.width(OVERRIDE_WIDTH.dp),
                )
            }
        }
    }
}

@Composable
private fun HeadText(text: String, modifier: Modifier) {
    ZillitText(
        text = text,
        style = DmType.mono(10.sp, FontWeight.Bold, 0.1.em),
        color = Color(0xFF8A8D95),
        modifier = modifier,
        maxLines = 1,
    )
}

/** "Unsaved changes": save and leave, or discard; the close button keeps editing. */
@Composable
private fun LeaveConfirm(visible: Boolean, saving: Boolean, onEvent: (DealMemoEvent) -> Unit) {
    DmModal(
        visible = visible,
        title = "Unsaved changes",
        onDismiss = { onEvent(NominalsEvent.StayEditing) },
        maxWidth = 420.dp,
        footer = {
            DmButton(
                "Discard changes",
                onClick = { onEvent(NominalsEvent.Discard) },
                style = DmButtonStyle.ModalDangerText,
            )
            DmButton(
                text = if (saving) "Saving…" else "Save and leave",
                onClick = { onEvent(NominalsEvent.SaveAndLeave) },
                style = DmButtonStyle.ModalPrimary,
                loading = saving,
            )
        },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(24.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                Modifier.size(28.dp).clip(CircleShape).background(Color(0xFFFFEDD5)),
                contentAlignment = Alignment.Center,
            ) {
                ZillitIcon(ZillitIcons.Warning, size = 14.dp, tint = Color(0xFFEA580C))
            }
            ZillitText(
                text = "Your nominal code changes haven't been saved yet. Save them before leaving, or discard them " +
                    "and close.",
                style = DmType.sans(14.sp).copy(lineHeight = 21.sp),
                color = pv.muted,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

private const val OVERRIDE_WIDTH = 160
