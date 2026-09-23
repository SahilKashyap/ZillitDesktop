package com.zillit.desktop.feature.dealmemo.ui.pages.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitScrollColumn
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.dealmemo.domain.DealDates
import com.zillit.desktop.feature.dealmemo.domain.preview.DealPreviewRules
import com.zillit.desktop.feature.dealmemo.domain.preview.EditAction
import com.zillit.desktop.feature.dealmemo.domain.preview.MemoCard
import com.zillit.desktop.feature.dealmemo.ui.DealMemoEvent
import com.zillit.desktop.feature.dealmemo.ui.DealMemoUiState
import com.zillit.desktop.feature.dealmemo.ui.PreviewEvent
import com.zillit.desktop.feature.dealmemo.ui.components.DmBadge
import com.zillit.desktop.feature.dealmemo.ui.components.DmStatusBadge
import com.zillit.desktop.feature.dealmemo.ui.components.DmTone
import com.zillit.desktop.feature.dealmemo.ui.components.DmType
import com.zillit.desktop.feature.dealmemo.ui.components.rememberHover
import com.zillit.desktop.feature.dealmemo.ui.preview.DealPreviewActions
import com.zillit.desktop.feature.dealmemo.ui.preview.DealPreviewState
import com.zillit.desktop.feature.dealmemo.ui.preview.PreviewAction

/** The widest the memo and everything around it grows (`max-w-4xl`). */
internal val PREVIEW_MAX_WIDTH = 896.dp

/**
 * A deal on its own page — `/deals/:id` (`DMDealPreviewPage.jsx`): the header
 * outside the scroll, then the approval bar, banners, memo card and crew bar.
 */
@Composable
fun DealPreviewPage(state: DealMemoUiState, onEvent: (DealMemoEvent) -> Unit) {
    val preview = state.preview ?: return
    val rules = remember(preview.deal, preview.crewDraft, state.viewer, state.metadata, preview.embedded) {
        DealPreviewActions.rulesFor(state)
    }
    Column(modifier = Modifier.fillMaxSize().background(pv.page)) {
        PreviewHeader(preview, rules, onEvent)
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            when {
                rules != null -> ZillitScrollColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    DealPreviewBody(state, preview, rules, onEvent)
                }
                preview.notFound -> PreviewNote(str(S.desktop_dm_deal_memo_not_found))
                preview.failed -> PreviewNote(str(S.desktop_dm_couldnt_load_this_deal_memo))
                else -> PreviewSkeleton()
            }
        }
    }
}

/** The page under the header — shared with My Deal, which embeds it. */
@Composable
internal fun DealPreviewBody(
    state: DealMemoUiState,
    preview: DealPreviewState,
    rules: DealPreviewRules,
    onEvent: (DealMemoEvent) -> Unit,
) {
    val context = remember(state.people, state.catalogue, state.production) { DealPreviewActions.memoContext(state) }
    val card = remember(rules.shown, context) { MemoCard.build(rules.shown, context) }
    Column(modifier = Modifier.widthIn(max = PREVIEW_MAX_WIDTH).fillMaxWidth()) {
        if (preview.embedded && rules.crewCanEdit) CompleteDetailsStrip(onEvent)
        if (rules.showApprovalChain || rules.showApproverActions) {
            ApprovalBar(state, preview, rules, onEvent)
            Spacer(Modifier.height(18.dp))
        }
        DealPreviewActions.shareUrl(state)?.takeIf { rules.showShareStrip }?.let { url ->
            ShareStrip(url, preview.linkCopied, onEvent)
            Spacer(Modifier.height(18.dp))
        }
        PreviewBanners(preview, rules, onEvent)
        MemoCardView(card, onEvent)
        Spacer(Modifier.height(16.dp))
        CrewDealBar(preview, rules, onEvent)
        Spacer(Modifier.height(12.dp))
    }
}

/** `PreviewWrapper`'s header: back, the breadcrumb, the edit cluster, the status. */
@Composable
private fun PreviewHeader(preview: DealPreviewState, rules: DealPreviewRules?, onEvent: (DealMemoEvent) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().background(pv.page.copy(alpha = 0.85f))) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            HeaderBack(onClick = { onEvent(PreviewEvent.Back) })
            ZillitText(
                text = "CONTRACTS",
                style = DmType.sans(11.sp, FontWeight.Bold, 0.08.em),
                color = PreviewInk.Action,
                maxLines = 1,
            )
            ZillitText(text = "/", style = DmType.sans(12.5.sp), color = Color(0xFFB8B7B1), maxLines = 1)
            ZillitText(
                text = preview.deal?.reference ?: str(S.dm_title),
                style = DmType.sans(12.5.sp, FontWeight.SemiBold),
                color = pv.muted,
                maxLines = 1,
            )
            Spacer(Modifier.weight(1f))
            if (rules != null) HeaderCluster(preview, rules, onEvent)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(pv.chipBorder))
    }
}

@Composable
private fun HeaderCluster(preview: DealPreviewState, rules: DealPreviewRules, onEvent: (DealMemoEvent) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (rules.showEditCluster) {
            EditControlView(rules, preview.editMenuOpen, onEvent)
            if (rules.showActivate) {
                SolidButton(
                    text = if (preview.action == PreviewAction.Activate) str(S.desktop_dm_activating) else str(
                        S.desktop_dm_activate_deal,
                    ),
                    onClick = { onEvent(PreviewEvent.Activate) },
                    icon = ZillitIcons.Check,
                    color = PreviewInk.Brand,
                    hover = PreviewInk.BrandHover,
                    height = 30.dp,
                    horizontal = 12.dp,
                    textSize = 12f,
                    enabled = preview.action == null,
                    loading = preview.action == PreviewAction.Activate,
                )
            }
        }
        preview.deal?.let { deal ->
            DmStatusBadge(deal.status)
            if (rules.showDeactivatingChip) {
                DmBadge(str(S.desktop_dm_deactivating_on, DealDates.shortUtc(deal.lastPayDate)), DmTone.Amber)
            }
        }
    }
}

/** The header's 36 px back square. */
@Composable
private fun HeaderBack(onClick: () -> Unit) {
    val (source, hovered) = rememberHover()
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(shape)
            .background(if (hovered) pv.neutralHoverBg else pv.card)
            .border(1.dp, pv.chipBorder, shape)
            .hoverable(source)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand),
        contentAlignment = Alignment.Center,
    ) {
        ZillitIcon(ZillitIcons.ChevronLeft, size = 14.dp, tint = pv.muted)
    }
}

/**
 * `DealEditControl`: nothing, one plain button named for its action, or a
 * menu of the actions this viewer has on this deal.
 */
@Composable
private fun EditControlView(rules: DealPreviewRules, open: Boolean, onEvent: (DealMemoEvent) -> Unit) {
    val control = rules.editControl
    if (control.actions.isEmpty()) return
    val single = control.actions.singleOrNull()
    Box {
        EditTrigger(
            label = control.buttonLabel,
            primary = control.primary,
            caret = single == null,
            open = open,
            onClick = {
                if (single != null) onEvent(PreviewEvent.Edit(single)) else onEvent(PreviewEvent.ToggleEditMenu)
            },
        )
        if (open && single == null) {
            Popup(
                popupPositionProvider = remember { BelowEndPosition(gap = 6) },
                onDismissRequest = { onEvent(PreviewEvent.CloseEditMenu) },
                properties = PopupProperties(focusable = true),
            ) {
                EditMenu(control.actions, onEvent)
            }
        }
    }
}

@Composable
private fun EditTrigger(label: String, primary: Boolean, caret: Boolean, open: Boolean, onClick: () -> Unit) {
    val (source, hovered) = rememberHover()
    val shape = RoundedCornerShape(7.dp)
    val background = when {
        primary && hovered -> PreviewInk.ActionHover
        primary -> PreviewInk.Action
        hovered || open -> pv.divider
        else -> pv.card
    }
    Row(
        modifier = Modifier
            .height(28.dp)
            .clip(shape)
            .background(background)
            .border(1.dp, if (primary) PreviewInk.Action else pv.barBorder, shape)
            .hoverable(source)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ZillitText(
            text = label,
            style = DmType.sans(12.sp, FontWeight.Bold),
            color = if (primary) Color.White else pv.menuText,
            maxLines = 1,
        )
        if (caret) {
            ZillitText(
                text = "▾",
                style = DmType.sans(9.sp),
                color = if (primary) Color.White.copy(alpha = 0.8f) else pv.faint,
            )
        }
    }
}

@Composable
private fun EditMenu(actions: List<EditAction>, onEvent: (DealMemoEvent) -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Column(
        modifier = Modifier
            .width(284.dp)
            .shadowed(shape)
            .clip(shape)
            .background(pv.card)
            .border(1.dp, pv.barBorder, shape)
            .padding(6.dp),
    ) {
        actions.forEachIndexed { index, action ->
            if (action == EditAction.Edit && index > 0) {
                Box(
                    Modifier
                        .padding(horizontal = 9.dp, vertical = 5.dp)
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(pv.innerDivider),
                )
            }
            EditMenuItem(action, onClick = { onEvent(PreviewEvent.Edit(action)) })
        }
    }
}

@Composable
private fun EditMenuItem(action: EditAction, onClick: () -> Unit) {
    val (source, hovered) = rememberHover()
    val warn = action == EditAction.Edit
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (hovered) pv.divider else Color.Transparent)
            .hoverable(source)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (warn) Color(0xFFFDF2E2) else pv.divider),
            contentAlignment = Alignment.Center,
        ) {
            ZillitText(
                text = action.glyph,
                style = DmType.sans(11.5.sp, FontWeight.Bold),
                color = if (warn) Color(0xFFB8730C) else pv.muted,
            )
        }
        Column {
            ZillitText(text = action.title, style = DmType.sans(12.5.sp, FontWeight.SemiBold), color = pv.menuText)
            ZillitText(text = action.subtitle, style = DmType.sans(11.sp), color = pv.faint)
        }
    }
}

/** My Deal's action strip: "Complete your details", right-aligned over the memo. */
@Composable
private fun CompleteDetailsStrip(onEvent: (DealMemoEvent) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().padding(bottom = 18.dp), horizontalArrangement = Arrangement.End) {
        OutlineButton(
            text = str(S.dm_crew_complete_details),
            onClick = { onEvent(PreviewEvent.CompleteDetails) },
            icon = ZillitIcons.Edit,
            ink = pv.muted,
            border = pv.chipBorder,
            height = 36.dp,
            radius = 10.dp,
        )
    }
}

/** Loading: the card's outline, pulsing. */
@Composable
internal fun PreviewSkeleton() {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val shape = RoundedCornerShape(12.dp)
        Column(
            modifier = Modifier
                .widthIn(max = PREVIEW_MAX_WIDTH)
                .fillMaxWidth()
                .clip(shape)
                .background(pv.card)
                .border(1.dp, pv.cardBorder, shape),
        ) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 20.dp)) {
                Column(Modifier.weight(1f)) {
                    SkeletonBar(220, 16)
                    Spacer(Modifier.height(8.dp))
                    SkeletonBar(140, 10)
                }
                SkeletonBar(96, 28)
            }
            repeat(3) {
                Box(Modifier.fillMaxWidth().height(1.dp).background(pv.divider))
                Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 20.dp)) {
                    SkeletonBar(120, 9)
                    Spacer(Modifier.height(16.dp))
                    repeat(2) {
                        Row(
                            Modifier.fillMaxWidth().padding(bottom = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(20.dp),
                        ) {
                            repeat(4) {
                                Column(Modifier.weight(1f)) {
                                    Box(
                                        Modifier
                                            .fillMaxWidth(0.45f)
                                            .height(8.dp)
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(pv.divider),
                                    )
                                    Spacer(Modifier.height(6.dp))
                                    Box(
                                        Modifier
                                            .fillMaxWidth(0.7f)
                                            .height(11.dp)
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(pv.divider),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SkeletonBar(width: Int, height: Int) {
    Box(Modifier.width(width.dp).height(height.dp).clip(RoundedCornerShape(4.dp)).background(pv.divider))
}

@Composable
internal fun PreviewNote(text: String) {
    Box(
        modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 32.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        ZillitText(text = text, style = DmType.sans(14.sp), color = pv.muted, textAlign = TextAlign.Center)
    }
}

/** A soft drop shadow for popups. */
internal fun Modifier.shadowed(shape: Shape): Modifier =
    shadow(elevation = 14.dp, shape = shape, clip = false, ambientColor = SHADOW, spotColor = SHADOW)

private val SHADOW = Color(0x290A0C10)
