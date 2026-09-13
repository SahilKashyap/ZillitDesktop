package com.zillit.desktop.feature.dealmemo.ui.pages.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.dealmemo.domain.DealLabels
import com.zillit.desktop.feature.dealmemo.domain.DocRead
import com.zillit.desktop.feature.dealmemo.domain.preview.ChainNode
import com.zillit.desktop.feature.dealmemo.domain.preview.ChainNodeState
import com.zillit.desktop.feature.dealmemo.domain.preview.DealPreviewRules
import com.zillit.desktop.feature.dealmemo.domain.preview.EditAction
import com.zillit.desktop.feature.dealmemo.domain.preview.MemoFormat
import com.zillit.desktop.feature.dealmemo.ui.DealMemoEvent
import com.zillit.desktop.feature.dealmemo.ui.DealMemoUiState
import com.zillit.desktop.feature.dealmemo.ui.PreviewEvent
import com.zillit.desktop.feature.dealmemo.ui.components.DmType
import com.zillit.desktop.feature.dealmemo.ui.preview.DealPreviewState
import com.zillit.desktop.feature.dealmemo.ui.preview.PreviewAction

/**
 * The approval card (`DMDealPreviewPage.jsx:3054-3184`) — above the memo: the
 * chain while a deal awaits approval, and Approve & Sign for the approver
 * whose level is next.
 */
@Suppress("LongMethod")
@Composable
internal fun ApprovalBar(
    state: DealMemoUiState,
    preview: DealPreviewState,
    rules: DealPreviewRules,
    onEvent: (DealMemoEvent) -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(pv.card)
            .border(1.dp, pv.chipBorder, shape)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        if (rules.showApprovalChain) {
            val chain = rules.chain
            Column {
                ZillitText(
                    text = "APPROVAL CHAIN",
                    style = DmType.mono(10.sp, FontWeight.Bold, 0.16.em),
                    color = Color(0xFF8A8D95),
                    maxLines = 1,
                )
                Spacer(Modifier.height(4.dp))
                ZillitText(
                    text = buildAnnotatedString {
                        withStyle(SpanStyle(fontSize = 13.sp, fontWeight = FontWeight.Bold, color = pv.ink)) {
                            append(chain.approvedCount.toString())
                        }
                        withStyle(SpanStyle(fontSize = 13.sp, color = Color(0xFF8A8D95))) {
                            append("/${chain.nodes.size}")
                        }
                        withStyle(
                            SpanStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF8A8D95)),
                        ) {
                            append(" approved")
                        }
                    },
                    style = DmType.sans(13.sp),
                    maxLines = 1,
                )
            }
            VerticalRule(pv.chipBorder, 40.dp)
            Row(
                modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.Top,
            ) {
                chain.nodes.forEachIndexed { index, node ->
                    ChainNodeView(node, state)
                    if (index < chain.nodes.lastIndex) {
                        Box(
                            Modifier
                                .padding(top = 13.dp)
                                .width(28.dp)
                                .height(2.dp)
                                .clip(RoundedCornerShape(1.dp))
                                .background(
                                    if (node.state == ChainNodeState.Done) PreviewInk.GreenLine else pv.divider,
                                ),
                        )
                    }
                }
            }
        } else {
            Spacer(Modifier.weight(1f))
        }
        if (rules.showApproverActions) {
            val approving = preview.action == PreviewAction.Approve
            SolidButton(
                text = if (approving) "Approving…" else "Approve & Sign",
                onClick = { onEvent(PreviewEvent.ApproveAndSign) },
                color = PreviewInk.Green,
                hover = PreviewInk.GreenLine,
                height = 32.dp,
                horizontal = 14.dp,
                textSize = 12f,
                enabled = preview.action == null,
                loading = approving,
            )
        }
    }
}

@Suppress("LongMethod")
@Composable
private fun ChainNodeView(node: ChainNode, state: DealMemoUiState) {
    val approver = node.approverId?.let(state.people::get)
    Column(modifier = Modifier.widthIn(min = 92.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        when (node.state) {
            ChainNodeState.Done -> Box(
                Modifier.size(28.dp).clip(CircleShape).background(PreviewInk.Green),
                contentAlignment = Alignment.Center,
            ) { ZillitIcon(ZillitIcons.Check, size = 14.dp, tint = Color.White) }
            ChainNodeState.Current -> Box(
                Modifier
                    .size(28.dp)
                    // The 4 px ring sits outside the circle, as the web's box-shadow does, so levels stay aligned.
                    .drawBehind {
                        drawCircle(PreviewInk.Brand.copy(alpha = 0.15f), radius = size.minDimension / 2 + 4.dp.toPx())
                    }
                    .clip(CircleShape)
                    .background(PreviewInk.Brand),
                contentAlignment = Alignment.Center,
            ) { Box(Modifier.size(10.dp).clip(CircleShape).background(Color.White)) }
            ChainNodeState.Pending -> Box(
                Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFE5E7EB))
                    .border(2.dp, Color(0xFFD1D5DB), CircleShape),
            )
        }
        Spacer(Modifier.height(6.dp))
        ZillitText(
            text = "LEVEL ${node.tier}",
            style = DmType.sans(9.5.sp, FontWeight.Bold, 0.1.em),
            color = Color(0xFF9CA3AF),
            maxLines = 1,
        )
        Spacer(Modifier.height(2.dp))
        when {
            node.state == ChainNodeState.Done && approver != null -> {
                ZillitText(
                    text = approver.fullName,
                    style = DmType.sans(11.sp, FontWeight.SemiBold),
                    color = PreviewInk.GreenInk,
                    maxLines = 1,
                    textAlign = TextAlign.Center,
                )
                approver.designationName?.takeIf { it.isNotEmpty() }?.let { role ->
                    ZillitText(
                        text = DealLabels.formatLabel(role),
                        style = DmType.sans(10.sp),
                        color = PreviewInk.GreenRole,
                        maxLines = 1,
                    )
                }
                node.actedAt?.let {
                    Spacer(Modifier.height(2.dp))
                    ZillitText(
                        text = MemoFormat.dateTime(it),
                        style = DmType.sans(9.5.sp),
                        color = Color(0xFF9CA3AF),
                        maxLines = 1,
                    )
                }
            }
            node.state == ChainNodeState.Current -> ZillitText(
                text = "Awaiting approval",
                style = DmType.sans(11.sp, FontWeight.SemiBold),
                color = PreviewInk.Todo,
                maxLines = 1,
            )
            // A done level whose approver is not in the directory reads "Pending" under its tick, as the web does.
            else -> ZillitText(text = "Pending", style = DmType.sans(11.sp), color = Color(0xFF9CA3AF), maxLines = 1)
        }
    }
}

/** "Shareable crew link" — for external crew, who open their deal without an account. */
@Composable
internal fun ShareStrip(url: String, copied: Boolean, onEvent: (DealMemoEvent) -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(pv.shareBg)
            .border(1.dp, pv.amberBorder, shape)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ZillitIcon(ZillitIcons.Link, size = 14.dp, tint = PreviewInk.Action, modifier = Modifier.padding(top = 2.dp))
        Column(modifier = Modifier.weight(1f)) {
            ZillitText(text = "Shareable crew link", style = DmType.sans(12.sp, FontWeight.Bold), color = pv.ink)
            Spacer(Modifier.height(2.dp))
            ZillitText(
                text = "No crew member is linked to this deal. Send this link to the crew member — they can open it " +
                    "without an account to view their deal and complete their personal details. Anyone who opens " +
                    "this link has their IP address and browser recorded.",
                style = DmType.sans(11.sp).copy(lineHeight = 16.5.sp),
                color = pv.shareInk,
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                val fieldShape = RoundedCornerShape(8.dp)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(36.dp)
                        .clip(fieldShape)
                        .background(pv.card)
                        .border(1.dp, pv.chipBorder, fieldShape)
                        .padding(horizontal = 10.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    BasicTextField(
                        value = url,
                        onValueChange = {},
                        readOnly = true,
                        singleLine = true,
                        textStyle = DmType.mono(11.sp).copy(color = pv.muted),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                SolidButton(
                    text = if (copied) "✓ Copied" else "Copy link",
                    onClick = { onEvent(PreviewEvent.CopyShareLink) },
                    color = PreviewInk.Brand,
                    hover = PreviewInk.BrandHover,
                    height = 36.dp,
                    textSize = 12f,
                )
            }
        }
    }
}

/**
 * The banners over the memo, in the web's order: edited after signing,
 * nominal coding pending, the amendment to acknowledge, the withdrawn signature.
 */
@Suppress("LongMethod")
@Composable
internal fun PreviewBanners(preview: DealPreviewState, rules: DealPreviewRules, onEvent: (DealMemoEvent) -> Unit) {
    if (rules.editedAfterSigning) {
        AmberBanner(
            title = "You have edited your signed deal memo",
            body = "You'll have to sign it again and send it for approval again.",
        )
        Spacer(Modifier.height(12.dp))
    }
    if (rules.showNominalsPending) {
        val count = rules.missingNominals.size
        AmberBanner(
            title = "Nominal coding pending",
            body = "$count line${if (count == 1) "" else "s"} on this deal ${if (count == 1) "has" else "have"} no " +
                "nominal code, so this crew member's payroll will not appear in the Cost Report's Payroll committed " +
                "column until the codes are added.",
            large = true,
            trailing = {
                SolidButton(
                    text = "Update Nominals",
                    onClick = { onEvent(PreviewEvent.Edit(EditAction.Nominals)) },
                    hover = Color(0xFFD97A16),
                    horizontal = 16.dp,
                )
            },
        )
        Spacer(Modifier.height(12.dp))
    }
    if (rules.canAcknowledgeAmendment) {
        val message = DocRead.text(DocRead.obj(rules.deal.json, "amendment_ack"), "message")
        AmberBanner(
            title = "Your deal memo has been amended",
            body = message ?: "Please look at the amended working hours and conditions applied to your deal memo. If " +
                "you have any objections, please contact the administrator.",
            below = {
                Spacer(Modifier.height(10.dp))
                SolidButton(
                    text = if (preview.acknowledging) "Saving…" else "Acknowledge",
                    onClick = { onEvent(PreviewEvent.Acknowledge) },
                    icon = ZillitIcons.Check,
                    height = 32.dp,
                    radius = 9.dp,
                    textSize = 12f,
                    loading = preview.acknowledging,
                )
            },
        )
        Spacer(Modifier.height(12.dp))
    }
    if (rules.signatureWithdrawn) {
        val shape = RoundedCornerShape(12.dp)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(pv.amberBg)
                .border(1.dp, pv.amberBorder, shape)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ZillitIcon(ZillitIcons.Info, size = 14.dp, tint = pv.amberIcon)
            ZillitText(
                text = buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("Details changed after signing. ") }
                    append("The signed copy no longer matches this memo, so it's been withdrawn — please sign again.")
                },
                style = DmType.sans(12.5.sp),
                color = pv.amberInk,
            )
        }
        Spacer(Modifier.height(12.dp))
    }
}
