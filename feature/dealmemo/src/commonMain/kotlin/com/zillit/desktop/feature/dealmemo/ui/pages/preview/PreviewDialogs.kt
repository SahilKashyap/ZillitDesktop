package com.zillit.desktop.feature.dealmemo.ui.pages.preview

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitScrollColumn
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.dealmemo.domain.preview.ChecklistRow
import com.zillit.desktop.feature.dealmemo.domain.preview.DealPreviewRules
import com.zillit.desktop.feature.dealmemo.domain.preview.DealSigning
import com.zillit.desktop.feature.dealmemo.domain.preview.StartForm
import com.zillit.desktop.feature.dealmemo.ui.DealMemoEvent
import com.zillit.desktop.feature.dealmemo.ui.DealMemoUiState
import com.zillit.desktop.feature.dealmemo.ui.PreviewEvent
import com.zillit.desktop.feature.dealmemo.ui.components.DmButton
import com.zillit.desktop.feature.dealmemo.ui.components.DmButtonStyle
import com.zillit.desktop.feature.dealmemo.ui.components.DmModal
import com.zillit.desktop.feature.dealmemo.ui.components.DmType
import com.zillit.desktop.feature.dealmemo.ui.components.dm
import com.zillit.desktop.feature.dealmemo.ui.preview.DealPreviewActions
import com.zillit.desktop.feature.dealmemo.ui.preview.FileViewer
import com.zillit.desktop.feature.dealmemo.ui.preview.FileViewerKind
import com.zillit.desktop.feature.dealmemo.ui.preview.GateMode
import com.zillit.desktop.feature.dealmemo.ui.preview.RejectDraft
import com.zillit.desktop.feature.dealmemo.ui.preview.ViewerContent

/** Every dialog the deal page opens, over the whole window. */
@Composable
fun PreviewDialogs(state: DealMemoUiState, onEvent: (DealMemoEvent) -> Unit) {
    val preview = state.preview
    val rules = remember(preview?.deal, preview?.crewDraft, state.viewer, state.metadata, preview?.embedded) {
        DealPreviewActions.rulesFor(state)
    }
    ChecklistModal(preview?.checklistOpen == true, rules, onEvent)
    GateModal(preview?.gate, rules, signerOpen = preview?.signer != null, onEvent)
    RejectModal(preview?.reject, onEvent)
    FileViewerModal(
        viewer = preview?.viewer,
        onClose = { onEvent(PreviewEvent.CloseViewer) },
        onDownload = { onEvent(PreviewEvent.DownloadViewer) },
    )
    StartFormModal(state, preview?.startFormOpen == true, rules, onEvent)
    SignDocumentModal(state, onEvent)
    UpdateNominalsModal(state, onEvent)
}

/** "Checklist for the Crew Member" — what still needs completing, with the marked fields under each section. */
@Composable
private fun ChecklistModal(open: Boolean, rules: DealPreviewRules?, onEvent: (DealMemoEvent) -> Unit) {
    val shown = remember { mutableStateOf<List<ChecklistRow>>(emptyList()) }
    if (open && rules != null) shown.value = rules.checklist
    DmModal(
        visible = open && rules != null,
        title = str(S.dm_step9_card_checks),
        onDismiss = { onEvent(PreviewEvent.CloseChecklist) },
        maxWidth = 520.dp,
    ) {
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
            ZillitText(
                text = buildAnnotatedString {
                    append(str(S.desktop_dm_what_still_needs_completing_on_this_deal) + " ")
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(str(S.dm_crew_complete_details)) }
                    append(" " + str(S.desktop_dm_to_fill_in_pending_suffix))
                },
                style = DmType.sans(12.sp).copy(lineHeight = 18.sp),
                color = pv.muted,
            )
            Spacer(Modifier.height(12.dp))
            val rows = shown.value
            if (rows.isEmpty()) {
                ZillitText(text = str(S.desktop_dm_no_onboarding_items), style = DmType.sans(12.sp), color = pv.muted)
            } else {
                ChecklistTable(rows)
            }
        }
    }
}

@Composable
private fun ChecklistTable(rows: List<ChecklistRow>) {
    val shape = RoundedCornerShape(6.dp)
    Column(modifier = Modifier.fillMaxWidth().clip(shape).border(1.dp, pv.divider, shape)) {
        Row(Modifier.fillMaxWidth().background(pv.tableHead).padding(horizontal = 12.dp, vertical = 8.dp)) {
            MemoLabel(str(S.desktop_dm_item))
            Spacer(Modifier.weight(1f))
            Box(Modifier.widthIn(min = 96.dp)) { MemoLabel(str(S.dm_label_status)) }
        }
        rows.forEach { row ->
            Box(Modifier.fillMaxWidth().height(1.dp).background(pv.divider))
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Column(Modifier.weight(1f)) {
                    ZillitText(text = row.label, style = DmType.sans(13.5.sp), color = pv.ink)
                    row.required.forEach { field ->
                        Row(
                            modifier = Modifier.padding(top = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            if (field.done) {
                                ZillitIcon(ZillitIcons.Check, size = 9.dp, tint = Color(0xFF16A34A))
                            } else {
                                Box(Modifier.size(5.dp).clip(CircleShape).background(Color(0xFFD97706)))
                            }
                            ZillitText(text = field.label, style = DmType.sans(11.sp), color = pv.muted)
                        }
                    }
                }
                Box(Modifier.widthIn(min = 96.dp)) {
                    if (row.done) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            ZillitIcon(ZillitIcons.Check, size = 10.dp, tint = Color(0xFF16A34A))
                            ZillitText(
                                text = str(S.dm_action_complete),
                                style = DmType.sans(13.sp),
                                color = Color(0xFF16A34A),
                            )
                        }
                    } else {
                        ZillitText(
                            text = str(S.dm_checklist_pending),
                            style = DmType.sans(13.sp),
                            color = Color(0xFF9CA3AF),
                        )
                    }
                }
            }
        }
    }
}

/**
 * "Not ready yet": what stands between the crew member and sending — listed
 * live, so it shrinks as they sign from inside it and closes on the last one.
 * Hidden, not closed, while a signer is open.
 */
@Suppress("LongMethod")
@Composable
private fun GateModal(
    gate: GateMode?,
    rules: DealPreviewRules?,
    signerOpen: Boolean,
    onEvent: (DealMemoEvent) -> Unit,
) {
    val blockers = when {
        rules == null || gate == null -> emptyList()
        gate == GateMode.Send -> rules.sendBlockers
        else -> rules.fieldBlockers
    }
    val visible = blockers.isNotEmpty() && !signerOpen
    val listed = blockers.filterNot { it.chipsOnly }
    DmModal(
        visible = visible,
        title = str(S.desktop_dm_not_ready_yet),
        onDismiss = { onEvent(PreviewEvent.CloseGate) },
        maxWidth = 460.dp,
        footer = {
            SolidButton(
                text = str(S.dd_action_got_it),
                onClick = { onEvent(PreviewEvent.CloseGate) },
                color = PreviewInk.Brand,
                hover = PreviewInk.BrandHover,
                textSize = 12f,
                height = 32.dp,
            )
        },
    ) {
        ZillitScrollColumn(
            modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp),
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 20.dp),
        ) {
            ZillitText(text = str(S.desktop_dm_finish_these_first), style = DmType.sans(13.sp), color = pv.muted)
            listed.forEach { blocker ->
                Spacer(Modifier.height(14.dp))
                ZillitText(text = blocker.section, style = DmType.sans(13.sp, FontWeight.Bold), color = pv.ink)
                Spacer(Modifier.height(4.dp))
                blocker.missing.forEach { item ->
                    Row(
                        modifier = Modifier.padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Box(Modifier.size(4.dp).clip(CircleShape).background(PreviewInk.Action))
                        ZillitText(text = item, style = DmType.sans(12.5.sp), color = pv.muted)
                    }
                }
            }
            if (listed.isNotEmpty() && rules?.crewCanEdit == true) {
                Spacer(Modifier.height(14.dp))
                SolidButton(
                    text = str(S.dm_crew_complete_details),
                    onClick = { onEvent(PreviewEvent.CompleteDetails) },
                    icon = ZillitIcons.Edit,
                    color = PreviewInk.Brand,
                    hover = PreviewInk.BrandHover,
                    height = 32.dp,
                    textSize = 12.5f,
                )
            }
            if (gate == GateMode.Send && rules != null && rules.ownerSigning) {
                val chips = DealSigning.gateChips(rules.signChips, rules.fieldBlockers.isNotEmpty())
                if (chips.isNotEmpty()) {
                    Spacer(Modifier.height(16.dp))
                    ChipGroup(
                        label = str(S.docusign_receiver_title),
                        instruction = str(S.desktop_dm_these_need_your_signature_before_this_deal),
                        chips = chips,
                        glow = false,
                        onEvent = onEvent,
                    )
                }
            }
        }
    }
}

/** "Reject deal memo" — the crew member's reason goes to the production administrator. */
@Suppress("LongMethod")
@Composable
private fun RejectModal(draft: RejectDraft?, onEvent: (DealMemoEvent) -> Unit) {
    val shown = remember { mutableStateOf(RejectDraft()) }
    if (draft != null) shown.value = draft
    val current = shown.value
    val busy = draft?.busy == true
    val hasReason = current.reason.isNotBlank()
    DmModal(
        visible = draft != null,
        title = str(S.dm_reject_title),
        onDismiss = { onEvent(PreviewEvent.CancelReject) },
        maxWidth = 480.dp,
        dismissible = !busy,
        footer = {
            DmButton(
                str(S.dm_cancel),
                onClick = { onEvent(PreviewEvent.CancelReject) },
                style = DmButtonStyle.ModalNeutral,
                enabled = !busy,
            )
            SolidButton(
                text = if (busy) str(S.ah_run_detail_btn_rejecting) else str(S.desktop_dm_confirm_rejection),
                onClick = { onEvent(PreviewEvent.ConfirmReject) },
                color = if (hasReason) PreviewInk.Red else Color(0xFFE5E7EB),
                hover = if (hasReason) PreviewInk.RedHover else Color(0xFFE5E7EB),
                ink = if (hasReason) Color.White else Color(0xFF9CA3AF),
                textSize = 12f,
                enabled = hasReason && !busy,
                loading = busy,
            )
        },
    ) {
        Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp)) {
            ZillitText(
                text = str(S.desktop_dm_tell_your_production_administrator_what_you_dont),
                style = DmType.sans(12.sp).copy(lineHeight = 18.sp),
                color = pv.muted,
            )
            Spacer(Modifier.height(14.dp))
            ZillitText(
                text = buildAnnotatedString {
                    append(str(S.reason_for_rejection))
                    withStyle(SpanStyle(color = PreviewInk.Todo)) { append(" *") }
                },
                style = DmType.sans(12.sp, FontWeight.SemiBold, 0.05.em),
                color = pv.muted,
            )
            Spacer(Modifier.height(6.dp))
            val shape = RoundedCornerShape(8.dp)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(80.dp)
                    .clip(shape)
                    .background(pv.card)
                    .border(1.dp, dm.controlBorder, shape)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                if (current.reason.isEmpty()) {
                    ZillitText(
                        text = str(S.desktop_dm_e_g_the_daily_rate_doesnt_match),
                        style = DmType.sans(12.sp),
                        color = pv.faint,
                    )
                }
                BasicTextField(
                    value = current.reason,
                    onValueChange = { onEvent(PreviewEvent.RejectReason(it)) },
                    enabled = !busy,
                    textStyle = DmType.sans(12.sp).copy(color = pv.ink),
                    cursorBrush = SolidColor(PreviewInk.Action),
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

/**
 * The deal memo PDF, an additional document or a passport scan — on screen at
 * once, filled when the bytes arrive; Download only once there are bytes.
 */
@Suppress("LongMethod")
@Composable
internal fun FileViewerModal(viewer: FileViewer?, onClose: () -> Unit, onDownload: () -> Unit) {
    val shown = remember { mutableStateOf<FileViewer?>(null) }
    if (viewer != null) shown.value = viewer
    val current = shown.value
    DmModal(
        visible = viewer != null,
        title = current?.title.orEmpty(),
        onDismiss = onClose,
        maxWidth = if (current?.kind == FileViewerKind.Passport) 900.dp else 1100.dp,
        fullHeight = true,
        titleContent = {
            Column(modifier = Modifier.weight(1f)) {
                ZillitText(
                    text = current?.title.orEmpty(),
                    style = DmType.display(14.sp, FontWeight.SemiBold),
                    color = pv.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                current?.subtitle?.takeIf { it.isNotEmpty() }?.let {
                    ZillitText(
                        text = it,
                        style = DmType.sans(11.5.sp),
                        color = pv.muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
        headerActions = {
            if (current?.bytes != null) DownloadButton(onClick = onDownload)
        },
    ) {
        if (current == null) return@DmModal
        Box(
            modifier = Modifier.fillMaxSize()
                .background(if (current.kind == FileViewerKind.DealPdf) pv.card else Color(0xFFFAF9F6)),
            contentAlignment = Alignment.Center,
        ) {
            when {
                current.loading -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ZillitSpinner(size = 16.dp, color = Color(0xFF9CA3AF))
                    ZillitText(
                        text = if (current.kind == FileViewerKind.DealPdf) str(S.desktop_dm_loading_pdf) else str(
                            S.desktop_dm_loading_attachment,
                        ),
                        style = DmType.sans(13.sp),
                        color = Color(0xFF9CA3AF),
                    )
                }
                current.failed -> ViewerMessage(
                    when (current.kind) {
                        FileViewerKind.DealPdf -> str(S.desktop_dm_failed_to_load_the_pdf_please_try)
                        FileViewerKind.Document -> str(S.desktop_dm_preview_not_available_for_this_document)
                        FileViewerKind.Passport -> str(S.desktop_dm_failed_to_load_attachment)
                    },
                )
                else -> ViewerBody(current, onDownload)
            }
        }
    }
}

@Composable
private fun ViewerBody(viewer: FileViewer, onDownload: () -> Unit) {
    when (val content = viewer.content) {
        is ViewerContent.Pages -> PdfPages(content)
        is ViewerContent.Picture -> Image(
            bitmap = content.image,
            contentDescription = viewer.title,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().padding(16.dp).clip(RoundedCornerShape(8.dp)),
        )
        ViewerContent.Unsupported, null -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
            ZillitIcon(ZillitIcons.File, size = 36.dp, tint = Color(0xFFD1D5DB))
            Spacer(Modifier.height(10.dp))
            ZillitText(
                text = str(S.desktop_dm_preview_not_available_for_this_file_type),
                style = DmType.sans(14.sp),
                color = pv.muted,
            )
            Spacer(Modifier.height(6.dp))
            ZillitText(
                text = str(S.desktop_dm_download_to_view),
                style = DmType.sans(12.sp, FontWeight.SemiBold).copy(textDecoration = TextDecoration.Underline),
                color = PreviewInk.Download,
                modifier = Modifier
                    .clickable(onClick = onDownload)
                    .pointerHoverIcon(PointerIcon.Hand),
            )
        }
    }
}

/** Every page stacked, 12 px apart, each its own sheet. */
@Composable
internal fun PdfPages(content: ViewerContent.Pages) {
    ZillitScrollColumn(
        modifier = Modifier.fillMaxSize().background(Color(0xFFECEAE4)),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        content.pages.forEach { page ->
            Image(
                bitmap = page.image,
                contentDescription = null,
                contentScale = ContentScale.FillWidth,
                modifier = Modifier
                    .widthIn(max = 1000.dp)
                    .fillMaxWidth()
                    .aspectRatio(1f / page.aspect)
                    .shadow(6.dp, RoundedCornerShape(2.dp))
                    .background(Color.White),
            )
        }
    }
}

@Composable
private fun ViewerMessage(text: String) {
    ZillitText(text = text, style = DmType.sans(14.sp), color = pv.muted)
}

/** The viewers' Download: amber, white text. */
@Composable
internal fun DownloadButton(onClick: () -> Unit) {
    SolidButton(
        text = str(S.dm_nda_download),
        onClick = onClick,
        icon = ZillitIcons.Download,
        color = PreviewInk.Download,
        hover = PreviewInk.DownloadHover,
        height = 28.dp,
        horizontal = 12.dp,
        textSize = 12f,
    )
}

/** The Crew Start Form, read-only HTML — never a PDF from this page. */
@Composable
private fun StartFormModal(
    state: DealMemoUiState,
    open: Boolean,
    rules: DealPreviewRules?,
    onEvent: (DealMemoEvent) -> Unit,
) {
    val view = remember(rules?.shown, state.production, state.people, state.catalogue) {
        rules?.let {
            StartForm.build(it.shown, DealPreviewActions.memoContext(state), state.production.project.companyName)
        }
    }
    val shown = remember { mutableStateOf(view) }
    if (open && view != null) shown.value = view
    DmModal(
        visible = open && view != null,
        title = str(S.dm_prev_tab_start_form),
        onDismiss = { onEvent(PreviewEvent.CloseStartForm) },
        maxWidth = 1100.dp,
        fullHeight = true,
    ) {
        val current = shown.value ?: return@DmModal
        ZillitScrollColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        ) {
            StartFormDocument(current)
        }
    }
}
