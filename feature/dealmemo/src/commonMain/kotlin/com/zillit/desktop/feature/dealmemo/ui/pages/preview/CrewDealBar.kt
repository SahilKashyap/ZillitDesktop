package com.zillit.desktop.feature.dealmemo.ui.pages.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.dealmemo.domain.preview.ChipTone
import com.zillit.desktop.feature.dealmemo.domain.preview.DealPreviewRules
import com.zillit.desktop.feature.dealmemo.domain.preview.DealSigning
import com.zillit.desktop.feature.dealmemo.domain.preview.DocChip
import com.zillit.desktop.feature.dealmemo.ui.DealMemoEvent
import com.zillit.desktop.feature.dealmemo.ui.PreviewEvent
import com.zillit.desktop.feature.dealmemo.ui.components.DmIcons
import com.zillit.desktop.feature.dealmemo.ui.components.DmType
import com.zillit.desktop.feature.dealmemo.ui.components.rememberHover
import com.zillit.desktop.feature.dealmemo.ui.preview.DealPreviewState
import com.zillit.desktop.feature.dealmemo.ui.preview.PreviewAction

/**
 * The crew deal bar (`CrewDealBar.jsx`) — after the memo, in the flow: the
 * documents to sign and to read, then where the signing stands and the
 * decisions — More, Reject, Send for Approval.
 */
@Composable
internal fun CrewDealBar(preview: DealPreviewState, rules: DealPreviewRules, onEvent: (DealMemoEvent) -> Unit) {
    val signChips = rules.signChips
    val viewChips = rules.viewChips
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(pv.bar)
            .border(1.dp, pv.barBorder, shape),
    ) {
        if (signChips.isNotEmpty() || viewChips.isNotEmpty()) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (signChips.isNotEmpty()) {
                    val owner = rules.ownerSigning
                    ChipGroup(
                        label = if (owner) str(S.desktop_dm_documents_require_your_signature) else str(
                            S.desktop_dm_documents_require_crew_signature,
                        ),
                        instruction = str(S.desktop_dm_click_a_document_below_to_sign_it)
                            .takeIf { owner && signChips.any { it.tone != ChipTone.Done } },
                        chips = signChips,
                        glow = owner,
                        onEvent = onEvent,
                    )
                }
                if (viewChips.isNotEmpty()) {
                    ChipGroup(str(S.dm_docs_title), null, viewChips, glow = false, onEvent = onEvent)
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(pv.innerDivider))
        }
        DecisionRow(preview, rules, signChips, onEvent)
    }
}

@Composable
private fun DecisionRow(
    preview: DealPreviewState,
    rules: DealPreviewRules,
    signChips: List<DocChip>,
    onEvent: (DealMemoEvent) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ZillitText(
            text = DealSigning.statusSentence(signChips, rules.ownerSigning),
            style = DmType.sans(12.5.sp),
            color = Color(0xFF8A8D95),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        MoreMenu(preview, rules, onEvent)
        if (rules.canCrewReject || rules.showSend) VerticalRule(pv.barBorder, 34.dp)
        if (rules.canCrewReject) {
            OutlineButton(
                text = str(S.dm_action_reject),
                onClick = { onEvent(PreviewEvent.OpenReject) },
                icon = DmIcons.Stop,
                ink = PreviewInk.RejectInk,
                border = PreviewInk.RejectBorder,
                hoverBg = PreviewInk.RejectHover,
                enabled = preview.action == null,
            )
        }
        if (rules.showSend) {
            val sending = preview.action == PreviewAction.Send
            SolidButton(
                text = str(S.dm_action_send_approval),
                onClick = { onEvent(PreviewEvent.SendForApproval) },
                icon = ZillitIcons.Send,
                enabled = preview.action == null,
                loading = sending,
            )
        }
    }
}

@Composable
private fun MoreMenu(preview: DealPreviewState, rules: DealPreviewRules, onEvent: (DealMemoEvent) -> Unit) {
    Box {
        OutlineButton(
            text = str(S.more),
            onClick = { onEvent(PreviewEvent.ToggleMore) },
            trailingIcon = ZillitIcons.ChevronDown,
            horizontal = 14.dp,
        )
        if (preview.moreMenuOpen) {
            Popup(
                popupPositionProvider = remember { AboveEndPosition(gap = 8) },
                onDismissRequest = { onEvent(PreviewEvent.CloseMore) },
                properties = PopupProperties(focusable = true),
            ) {
                val shape = RoundedCornerShape(12.dp)
                Column(
                    modifier = Modifier
                        .width(300.dp)
                        .shadowed(shape)
                        .clip(shape)
                        .background(pv.card)
                        .border(1.dp, pv.barBorder, shape)
                        .padding(6.dp),
                ) {
                    val checklist = rules.checklist
                    MenuRow(
                        icon = ZillitIcons.Check,
                        label = str(S.desktop_dm_show_checklist),
                        sub = str(S.desktop_dm_n_of_m_outstanding, checklist.count { !it.done }, checklist.size),
                        onClick = { onEvent(PreviewEvent.ShowChecklist) },
                    )
                    MenuRow(
                        icon = ZillitIcons.Eye,
                        label = DealSigning.pdfLabel(rules.deal),
                        sub = str(S.desktop_dm_open_the_deal_memo_pdf),
                        onClick = { onEvent(PreviewEvent.ViewPdf) },
                    )
                    if (rules.showHistory) {
                        MenuRow(
                            icon = DmIcons.History,
                            label = str(S.dm_row_action_history),
                            sub = str(S.desktop_dm_every_action_taken_on_this_deal_memo),
                            onClick = { onEvent(PreviewEvent.History) },
                        )
                    }
                }
            }
        }
    }
}

/** A labelled group of document chips. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ChipGroup(
    label: String,
    instruction: String?,
    chips: List<DocChip>,
    glow: Boolean,
    onEvent: (DealMemoEvent) -> Unit,
) {
    Column {
        ZillitText(
            text = label.uppercase(),
            style = DmType.sans(10.5.sp, FontWeight.Bold, 0.08.em),
            color = Color(0xFF8A8D95),
            maxLines = 1,
        )
        instruction?.let {
            Spacer(Modifier.height(2.dp))
            ZillitText(text = it, style = DmType.sans(11.5.sp), color = pv.muted)
        }
        Spacer(Modifier.height(6.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            chips.forEach { chip ->
                DocChipView(chip, glow = glow, onClick = { onEvent(PreviewEvent.Chip(chip.action)) })
            }
        }
    }
}

/**
 * A document chip: solid amber while it waits for this signer (breathing a
 * ring), green with a tick once signed, neutral for reading, grey when it
 * can't be used yet.
 */
@Suppress("CyclomaticComplexMethod")
@Composable
internal fun DocChipView(chip: DocChip, glow: Boolean, onClick: () -> Unit) {
    val (source, hovered) = rememberHover()
    val shape = RoundedCornerShape(8.dp)
    val disabled = chip.tone == ChipTone.Disabled
    val (background, border, ink) = when (chip.tone) {
        ChipTone.Todo -> Triple(if (hovered) PreviewInk.TodoHover else PreviewInk.Todo, PreviewInk.Todo, Color.White)
        ChipTone.Done -> Triple(if (hovered) pv.doneHover else pv.doneBg, pv.doneBorder, pv.doneInk)
        ChipTone.Neutral -> Triple(
            if (hovered) pv.neutralHoverBg else pv.card,
            if (hovered) Color(0xFFD6D4CC) else pv.chipBorder,
            pv.muted,
        )
        ChipTone.Disabled -> Triple(Color(0xFFF3F4F6), Color(0xFFE5E7EB), Color(0xFF9CA3AF))
    }
    val spread = if (glow && chip.tone == ChipTone.Todo) rememberGlowSpread() else 0f
    MaybeTooltip("${chip.label} — ${chip.sub}") {
        Row(
            modifier = Modifier
                .signGlow(
                    enabled = glow && chip.tone == ChipTone.Todo,
                    color = PreviewInk.Todo.copy(alpha = GLOW_ALPHA * (1f - spread)),
                    radius = 8.dp,
                    spread = GLOW_RING * spread,
                )
                .height(32.dp)
                .clip(shape)
                .background(background)
                .border(1.dp, border, shape)
                .hoverable(source)
                .then(
                    if (disabled) {
                        Modifier
                    } else {
                        Modifier.clickable(interactionSource = source, indication = null, onClick = onClick)
                    },
                )
                .pointerHoverIcon(if (disabled) PointerIcon.Default else PointerIcon.Hand)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            when {
                chip.tone == ChipTone.Done -> Dot(PreviewInk.DoneDot)
                chip.dot -> Dot(PreviewInk.ObserverDot)
            }
            ZillitText(text = chip.label, style = DmType.sans(12.5.sp, FontWeight.Bold), color = ink, maxLines = 1)
            if (chip.tone == ChipTone.Done) ZillitIcon(ZillitIcons.Check, size = 11.dp, tint = ink)
        }
    }
}

private const val GLOW_ALPHA = 0.45f
private const val GLOW_RING = 9f
