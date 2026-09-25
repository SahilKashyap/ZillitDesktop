@file:Suppress("MatchingDeclarationName") // The view, its badge rule and its panes, together.

package com.zillit.desktop.feature.cardexpenses.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitDivider
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitScrollColumn
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitVerticalDivider
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.cardexpenses.domain.CardPerson
import com.zillit.desktop.feature.cardexpenses.domain.ReceiptDetail
import com.zillit.desktop.feature.cardexpenses.domain.ReceiptDetailLine
import com.zillit.desktop.feature.cardexpenses.domain.ReceiptMedia
import com.zillit.desktop.feature.cardexpenses.domain.ReconciliationBadge
import com.zillit.desktop.feature.cardexpenses.domain.inboxBadge
import com.zillit.desktop.feature.cardexpenses.ui.HistoryState
import com.zillit.desktop.feature.cardexpenses.ui.MediaState
import com.zillit.desktop.feature.cardexpenses.ui.WorkflowStatusPill
import com.zillit.desktop.feature.cardexpenses.ui.date
import com.zillit.desktop.feature.cardexpenses.ui.money

/**
 * Which badge the detail's title carries (`ReceiptDetailModal.jsx:135-143`,
 * the `reconciliationStatus` prop).
 */
enum class ReceiptBadgeRule {
    /** The workflow status, always — the approval, process and history queues. */
    Workflow,

    /** The Receipt Inbox rule: Unreconciled / Match Suggested / Reconciled, else workflow. */
    Inbox,

    /** My Transactions: Unreconciled, else workflow — a cardholder cannot act on a suggestion. */
    Crew,
}

/**
 * The shared receipt detail (`ui/ReceiptDetailModal.jsx` + `ui/ReceiptPreviewPane.jsx`):
 * the document on the left, the receipt's facts on the right, History and
 * Query in the footer.
 *
 * Stateless — every page that opens a receipt holds what this shows:
 *
 *  - [detail] the receipt, and whatever `/receipts/:id/detail` added (lines,
 *    approvals, the document model); a queue row alone is `ReceiptDetail.of(row)`.
 *  - [loading] while that read is in flight: a spinner body instead of a
 *    slim row that would flash an incomplete receipt.
 *  - [media] the document's bytes as the opener fetched them; [onOpenMedia]
 *    hands the document to the OS (null hides "Open").
 *  - [history] the trail once [onShowHistory] asked for it; [onHideHistory]
 *    closes it.
 *  - [allowQuery] false on an approval queue (ZL-20913): Query is an
 *    accountant's action, not an approver's.
 *  - [actions] the opener's own buttons, at the right of the footer.
 *
 * A dialog, drawn at the page root — `ZillitDialogShell` is not a popup.
 */
@Suppress("LongParameterList", "LongMethod") // The web modal's props, one for one.
@Composable
fun ReceiptDetailView(
    detail: ReceiptDetail,
    people: List<CardPerson>,
    onClose: () -> Unit,
    onShowHistory: () -> Unit,
    onHideHistory: () -> Unit,
    onQuery: () -> Unit,
    modifier: Modifier = Modifier,
    loading: Boolean = false,
    badgeRule: ReceiptBadgeRule = ReceiptBadgeRule.Workflow,
    isTelevision: Boolean = false,
    media: MediaState = MediaState(),
    onOpenMedia: ((ReceiptMedia) -> Unit)? = null,
    history: HistoryState? = null,
    allowQuery: Boolean = true,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val receipt = detail.receipt
    ZillitDialogShell(
        title = str(S.ah_receipt_details),
        subtitle = receipt.description.ifBlank { null },
        icon = ZillitIcons.Receipt,
        visible = true,
        width = DIALOG_WIDTH,
        maxHeight = DIALOG_HEIGHT,
        scrollable = false,
        onDismiss = onClose,
        modifier = modifier,
        actions = {
            ZillitButton(
                text = str(S.history),
                onClick = if (history == null) onShowHistory else onHideHistory,
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
                leadingIcon = ZillitIcons.Clock,
            )
            if (allowQuery) {
                ZillitButton(
                    text = str(S.ah_query_label),
                    onClick = onQuery,
                    variant = ButtonVariant.Secondary,
                    size = ButtonSize.Small,
                    leadingIcon = ZillitIcons.Chat,
                )
            }
            Spacer(Modifier.weight(1f))
            actions()
        },
    ) {
        TitleBadges(detail, badgeRule)
        Row(modifier = Modifier.fillMaxWidth().height(BODY_HEIGHT)) {
            if (loading) {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ZillitSpinner()
                    Spacer(Modifier.width(ZillitTheme.spacing.sm))
                    ZillitText(
                        text = str(S.desktop_ce_inbox_loading_details),
                        style = ZillitTheme.typography.bodyMedium,
                        color = ZillitTheme.colors.textSecondary,
                    )
                }
                return@Row
            }
            ReceiptMediaPanel(detail.media, media, onOpenMedia, Modifier.width(MEDIA_WIDTH).fillMaxHeight())
            ZillitVerticalDivider()
            Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                if (history != null) {
                    HistoryPane(history, onHideHistory)
                } else {
                    DetailFields(detail, people, isTelevision)
                }
            }
        }
    }
}

/** The title's badges: the status by [rule], and Urgent. */
@Composable
private fun TitleBadges(detail: ReceiptDetail, rule: ReceiptBadgeRule) {
    val receipt = detail.receipt
    val badge = when (rule) {
        ReceiptBadgeRule.Inbox -> receipt.inboxBadge
        ReceiptBadgeRule.Crew -> ReconciliationBadge.crew(receipt.matchStatus)
        ReceiptBadgeRule.Workflow -> null
    }
    Row(
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(bottom = ZillitTheme.spacing.sm),
    ) {
        if (badge != null) ReconciliationBadgePill(badge) else WorkflowStatusPill(receipt.status)
        if (receipt.urgent) {
            ZillitStatusPill(str(S.ah_topup_filter_urgent), tone = StatusTone.Rejected, dot = true)
        }
    }
}

/** A reconciliation badge in its colour: amber for the two that wait on someone, green for done. */
@Composable
fun ReconciliationBadgePill(badge: ReconciliationBadge, modifier: Modifier = Modifier) {
    ZillitStatusPill(
        label = badge.label,
        modifier = modifier,
        tone = if (badge.needsAction) StatusTone.Pending else StatusTone.Ready,
        dot = true,
    )
}

/**
 * The document, previewed where the reader is (`ReceiptPreviewPane.jsx`):
 * an image or a PDF's pages, a spinner while it is fetched, the file name and
 * "Could not load preview" when it will not open, and "No receipt uploaded"
 * when there is nothing to fetch.
 */
@Suppress("LongMethod") // Four states of one pane and its file bar.
@Composable
fun ReceiptMediaPanel(
    stored: ReceiptMedia?,
    media: MediaState,
    onOpen: ((ReceiptMedia) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val colors = ZillitTheme.colors
    Column(modifier = modifier.background(colors.surfaceSunken)) {
        if (stored == null) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(EMPTY_TILE)
                        .clip(ZillitTheme.shapes.large)
                        .background(colors.surface)
                        .border(2.dp, colors.border, ZillitTheme.shapes.large),
                    contentAlignment = Alignment.Center,
                ) {
                    ZillitIcon(ZillitIcons.File, size = EMPTY_GLYPH, tint = colors.borderStrong)
                }
                Spacer(Modifier.height(ZillitTheme.spacing.sm))
                ZillitText(
                    text = str(S.desktop_ce_process_no_receipt),
                    style = ZillitTheme.typography.labelSmall,
                    color = colors.textMuted,
                )
            }
            return@Column
        }
        val pages = remember(media.bytes) { media.bytes?.let(::decodeReceiptPages).orEmpty() }
        Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            when {
                media.loading -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    ZillitSpinner()
                    ZillitText(str(S.ah_loading), style = ZillitTheme.typography.bodySmall, color = colors.textMuted)
                }

                pages.isNotEmpty() -> ZillitScrollColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(ZillitTheme.spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                ) {
                    pages.forEach { page ->
                        Image(
                            bitmap = page,
                            contentDescription = stored.fileName,
                            contentScale = ContentScale.FillWidth,
                            modifier = Modifier.fillMaxWidth().background(colors.surface),
                        )
                    }
                }

                // Nothing fetched yet (no document store) reads as the file,
                // not as a failure; a fetch that came back unreadable says so.
                else -> Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(ZillitTheme.spacing.lg),
                ) {
                    ZillitIcon(ZillitIcons.File, size = FAILED_GLYPH, tint = colors.textMuted)
                    ZillitText(stored.fileName, style = ZillitTheme.typography.bodySmall)
                    if (media.failed || media.bytes != null) {
                        ZillitText(
                            text = str(S.desktop_ce_inbox_preview_failed),
                            style = ZillitTheme.typography.labelSmall,
                            color = colors.danger,
                        )
                    }
                }
            }
        }
        ZillitDivider()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.surface)
                .padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            ZillitIcon(ZillitIcons.File, size = FILE_GLYPH)
            ZillitText(
                text = stored.fileName,
                style = ZillitTheme.typography.bodySmall,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            if (onOpen != null) {
                ZillitButton(
                    text = str(S.dd_action_open),
                    onClick = { onOpen(stored) },
                    variant = ButtonVariant.Tertiary,
                    size = ButtonSize.Small,
                )
            }
        }
    }
}

@Suppress("LongMethod", "CyclomaticComplexMethod") // The modal's right column, top to bottom.
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DetailFields(detail: ReceiptDetail, people: List<CardPerson>, isTelevision: Boolean) {
    val receipt = detail.receipt
    val holder = people.firstOrNull { it.id == receipt.holderId }
    val currency = receipt.currency
    ZillitScrollColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = DetailPanePadding,
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        FieldGrid(
            columns = 2,
            cells = listOf(
                field(str(S.ah_merchant), receipt.description.ifBlank { EM_DASH }),
                FieldCell(str(S.amount)) {
                    ZillitText(
                        text = money(receipt.amount, currency),
                        style = ZillitTheme.typography.titleSmall,
                        color = ZillitTheme.colors.accent,
                    )
                },
                field(str(S.date), date(receipt.date)),
                FieldCell(str(S.desktop_card_card_holder)) {
                    ZillitText(holder?.name ?: EM_DASH, style = ZillitTheme.typography.bodySmall)
                    holder?.designation?.takeIf { it.isNotBlank() }?.let {
                        ZillitText(it, style = ZillitTheme.typography.labelSmall, color = ZillitTheme.colors.textMuted)
                    }
                },
            ),
        )
        ZillitDivider()
        FieldGrid(
            columns = 3,
            cells = listOfNotNull(
                field(str(S.description), receipt.codeDescription?.takeIf { it.isNotBlank() } ?: EM_DASH),
                FieldCell(str(S.desktop_card_cost_code)) {
                    ZillitText(
                        text = receipt.nominalCode?.takeIf { it.isNotBlank() } ?: EM_DASH,
                        style = ZillitTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                        color = ZillitTheme.colors.gold,
                    )
                },
                // Episode is a television field, hidden everywhere else.
                field(str(S.episode), receipt.episode?.takeIf { it.isNotBlank() } ?: EM_DASH).takeIf { isTelevision },
            ),
        )

        if (!receipt.transactionId.isNullOrBlank()) {
            ZillitDivider()
            FieldGroupLabel(str(S.desktop_ce_inbox_linked_transaction))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
                ZillitText(
                    text = receipt.transactionMerchant?.takeIf { it.isNotBlank() } ?: receipt.description,
                    style = ZillitTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                )
                receipt.transactionAmount?.let {
                    MutedText(money(it, detail.transactionCurrency ?: currency))
                }
                receipt.transactionCardLastFour?.takeIf { it.isNotBlank() }?.let { MutedText("•••• $it") }
                receipt.transactionDate?.let { MutedText(date(it)) }
            }
        }

        if (detail.approvals.isNotEmpty()) {
            ZillitDivider()
            FieldGroupLabel(str(S.desktop_po_approval_progress))
            detail.approvals.forEach { approval ->
                val approver = people.firstOrNull { it.id == approval.userId }
                val dot = if (approval.override) ZillitTheme.colors.violet else ZillitTheme.colors.success
                Row(
                    horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier.size(CHECK_DOT).clip(CircleShape).background(dot),
                        contentAlignment = Alignment.Center,
                    ) {
                        ZillitIcon(ZillitIcons.Check, size = CHECK_GLYPH, tint = ZillitTheme.colors.textOnAccent)
                    }
                    // Never an id where a person belongs: unresolved reads as a dash.
                    ZillitText(approver?.name ?: EM_DASH, style = ZillitTheme.typography.bodySmall)
                    approver?.designation?.takeIf { it.isNotBlank() }?.let { MutedText("($it)") }
                    if (approval.override) {
                        ZillitStatusPill(str(S.dm_nom_table_override), tone = StatusTone.Escalated)
                    } else {
                        MutedText(str(S.desktop_level_n, approval.tierNumber))
                    }
                }
            }
        }

        Column {
            FieldGroupLabel(str(S.txt_submitted))
            MutedText(date(receipt.createdAt))
        }

        if (detail.lines.isNotEmpty()) {
            ZillitDivider()
            FieldGroupLabel(str(S.ah_line_items))
            LineItemsTable(detail.lines, currency)
        }
    }
}

/** Code, description, net, tax and tags; tax lines are already out, split children marked `↳`. */
@Suppress("LongMethod") // Header and rows of one table.
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LineItemsTable(lines: List<ReceiptDetailLine>, currency: String?) {
    val colors = ZillitTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.medium)
            .border(1.dp, colors.border, ZillitTheme.shapes.medium),
    ) {
        Row(Modifier.fillMaxWidth().background(colors.surfaceSunken).padding(LINE_CELL)) {
            LineHeader(str(S.code), Modifier.width(CODE_COLUMN))
            LineHeader(str(S.description), Modifier.weight(1f))
            LineHeader(str(S.desktop_net), Modifier.width(NET_COLUMN), end = true)
            LineHeader(str(S.ah_lbl_vat), Modifier.width(TAX_COLUMN), end = true)
            LineHeader(str(S.drive_tags), Modifier.width(TAGS_COLUMN).padding(start = ZillitTheme.spacing.sm))
        }
        lines.forEach { line ->
            ZillitDivider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(if (line.splitChild) colors.surfaceSunken else colors.surface)
                    .padding(LINE_CELL),
                verticalAlignment = Alignment.Top,
            ) {
                ZillitText(
                    text = line.code ?: EM_DASH,
                    style = ZillitTheme.typography.labelSmall,
                    color = colors.gold,
                    maxLines = 1,
                    modifier = Modifier.width(CODE_COLUMN),
                )
                ZillitText(
                    text = (if (line.splitChild) "↳ " else "") + (line.description ?: EM_DASH),
                    style = ZillitTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                ZillitText(
                    text = money(line.net, currency),
                    style = ZillitTheme.typography.labelSmall,
                    color = colors.textSecondary,
                    modifier = Modifier.width(NET_COLUMN),
                    textAlign = TextAlign.End,
                )
                ZillitText(
                    text = if (line.splitChild) EM_DASH else money(line.tax, currency),
                    style = ZillitTheme.typography.labelSmall,
                    color = if (line.splitChild) colors.textMuted else colors.gold,
                    modifier = Modifier.width(TAX_COLUMN),
                    textAlign = TextAlign.End,
                )
                FlowRow(
                    modifier = Modifier.width(TAGS_COLUMN).padding(start = ZillitTheme.spacing.sm),
                    horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
                ) {
                    line.tags.forEach { tag ->
                        ZillitText(
                            text = tag,
                            style = ZillitTheme.typography.labelSmall,
                            color = colors.textSecondary,
                            modifier = Modifier
                                .clip(ZillitTheme.shapes.small)
                                .background(colors.surfaceHover)
                                .padding(horizontal = ZillitTheme.spacing.xs),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LineHeader(text: String, modifier: Modifier, end: Boolean = false) {
    ZillitText(
        text = text.uppercase(),
        style = ZillitTheme.typography.labelSmall,
        color = ZillitTheme.colors.textMuted,
        modifier = modifier,
        textAlign = if (end) TextAlign.End else null,
    )
}

/** The trail, in place of the fields — the web's side panel, inside the dialog. */
@Composable
private fun HistoryPane(history: HistoryState, onClose: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(ZillitTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ZillitText(str(S.history), style = ZillitTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            ZillitButton(
                text = str(S.close),
                onClick = onClose,
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
            )
        }
        ZillitDivider()
        if (history.loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { ZillitSpinner() }
        } else {
            ZillitScrollColumn(modifier = Modifier.fillMaxSize(), contentPadding = DetailPanePadding) {
                CardHistoryTrail(entries = history.entries)
            }
        }
    }
}

/** One label-over-value cell of a [FieldGrid]. */
private class FieldCell(val label: String, val value: @Composable () -> Unit)

private fun field(label: String, value: String) = FieldCell(label) {
    ZillitText(value, style = ZillitTheme.typography.bodySmall)
}

/** Label-over-value fields, [columns] across — the modal's `grid-cols-N`. */
@Composable
private fun FieldGrid(columns: Int, cells: List<FieldCell>) {
    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
        cells.chunked(columns).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
                row.forEach { cell ->
                    Column(Modifier.weight(1f)) {
                        FieldGroupLabel(cell.label)
                        cell.value()
                    }
                }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun MutedText(text: String) {
    ZillitText(text, style = ZillitTheme.typography.bodySmall, color = ZillitTheme.colors.textSecondary)
}

private const val EM_DASH = "—"
private val DIALOG_WIDTH = 1080.dp
private val DIALOG_HEIGHT = 760.dp
private val BODY_HEIGHT = 560.dp
private val MEDIA_WIDTH = 360.dp
private val EMPTY_TILE = 80.dp
private val EMPTY_GLYPH = 34.dp
private val FAILED_GLYPH = 30.dp
private val FILE_GLYPH = 14.dp
private val CHECK_DOT = 16.dp
private val CHECK_GLYPH = 10.dp
private val CODE_COLUMN = 80.dp
private val NET_COLUMN = 72.dp
private val TAX_COLUMN = 64.dp
private val TAGS_COLUMN = 90.dp
private val LINE_CELL = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
