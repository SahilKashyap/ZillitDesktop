// The accountant's process editor, as a full page — the web's `ProcessReceiptModal fullPage`.
@file:Suppress("TooManyFunctions")

package com.zillit.desktop.feature.cardexpenses.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.common.EpochDate
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDateField
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitDivider
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitScrollColumn
import com.zillit.desktop.core.designsystem.component.ZillitSelect
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.cardexpenses.domain.CardPerson
import com.zillit.desktop.feature.cardexpenses.domain.CardWorkflowStatus
import com.zillit.desktop.feature.cardexpenses.domain.ProcessFigures
import com.zillit.desktop.feature.cardexpenses.domain.ProcessRules
import com.zillit.desktop.feature.cardexpenses.domain.ProcessingFlag
import com.zillit.desktop.feature.cardexpenses.domain.ReceiptProcessing
import com.zillit.desktop.feature.cardexpenses.domain.TopUpMethod
import com.zillit.desktop.feature.cardexpenses.ui.AssignDraft
import com.zillit.desktop.feature.cardexpenses.ui.CardEvent
import com.zillit.desktop.feature.cardexpenses.ui.CardUiState
import com.zillit.desktop.feature.cardexpenses.ui.ProcessDraft
import com.zillit.desktop.feature.cardexpenses.ui.ProcessMode
import com.zillit.desktop.feature.cardexpenses.ui.ProcessPageEvent
import com.zillit.desktop.feature.cardexpenses.ui.components.CardHistoryTrail
import com.zillit.desktop.feature.cardexpenses.ui.components.FieldGroupLabel
import com.zillit.desktop.feature.cardexpenses.ui.date
import com.zillit.desktop.feature.cardexpenses.ui.localDay
import com.zillit.desktop.feature.cardexpenses.ui.money
import kotlin.math.abs
import kotlin.time.Clock

/**
 * The accountant's process editor — the web's `ProcessReceiptModal`, as the
 * full page it is there: it takes over the content column, the sidebar stays,
 * and the back button or the breadcrumb returns to the queue.
 *
 * Top to bottom as the web reads: the breadcrumb and the header buttons; the
 * receipt's facts and the three corrections (vendor, cost code, ledger date)
 * beside the receipt itself; the banners; the match against the receipt; the
 * coded lines; the top-up decision when the holder asked for one. Which
 * buttons show is [ProcessRules]' decision, and the view model checks the same
 * rules again on the way in. A receipt dated in the closed period is frozen:
 * every field disabled, and nothing but History and Query offered.
 */
@Composable
fun ProcessEditorPage(state: CardUiState, onEvent: (CardEvent) -> Unit) {
    val draft = state.process ?: return
    val refs = state.processPages.refs
    val figures = draft.figures(refs)
    val locked = draft.periodLocked(refs.lock)
    var layers by remember { mutableStateOf<LayersTarget?>(null) }

    Box(Modifier.fillMaxSize().background(ZillitTheme.colors.canvas)) {
        Column(Modifier.fillMaxSize()) {
            TopBar(state, draft, figures, locked, onEvent)
            ZillitDivider()
            ZillitScrollColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(ZillitTheme.spacing.xl),
                verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg)) {
                    DetailsCard(state, draft, locked, onEvent, Modifier.weight(1f))
                    PreviewCard(draft, onEvent)
                }
                if (draft.loading) {
                    Card(Modifier.fillMaxWidth()) {
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = ZillitTheme.spacing.xl),
                            horizontalArrangement = Arrangement.spacedBy(
                                ZillitTheme.spacing.sm,
                                Alignment.CenterHorizontally,
                            ),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            ZillitSpinner()
                            ZillitText(
                                text = str(S.desktop_ce_process_loading_details),
                                style = ZillitTheme.typography.bodySmall,
                                color = ZillitTheme.colors.textMuted,
                            )
                        }
                    }
                } else {
                    Banners(state, draft, figures)
                    if (draft.mode == ProcessMode.Process) {
                        GrossMatchBar(draft.receipt.amount, figures.gross, draft.receipt.currency)
                    }
                    Card(Modifier.fillMaxWidth()) {
                        ProcessLineItems(draft, refs, figures, locked, draft.receipt.currency, onEvent) { layers = it }
                    }
                    if (draft.mode == ProcessMode.Process && draft.receipt.processing.requestTopUp) {
                        Card(Modifier.fillMaxWidth()) { TopUpDecision(draft, figures, onEvent) }
                    }
                }
            }
        }
        // Dialogs at the root: a dialog shell is not a popup, and inside the
        // scrolling column it would draw at the foot of the page.
        layers?.let { LayersPicker(draft, refs, it, onDismiss = { layers = null }, onEvent = onEvent) }
        AssignDialog(state, draft, onEvent)
        EscalateDialog(state, draft, onEvent)
        HistoryPanel(draft, onEvent)
    }
}

/**
 * Back, the breadcrumb — "Production Expense Cards / Process / {receipt}" —
 * the status badge, the lock banner, and the header buttons.
 */
@OptIn(ExperimentalLayoutApi::class)
@Suppress("LongMethod") // The breadcrumb and its buttons are one bar.
@Composable
private fun TopBar(
    state: CardUiState,
    draft: ProcessDraft,
    figures: ProcessFigures,
    locked: Boolean,
    onEvent: (CardEvent) -> Unit,
) {
    val colors = ZillitTheme.colors
    val history = draft.mode == ProcessMode.History
    val receipt = draft.receipt
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surface)
            .padding(horizontal = ZillitTheme.spacing.xl, vertical = ZillitTheme.spacing.md),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitIconButton(
            icon = ZillitIcons.ChevronLeft,
            contentDescription = str(S.back),
            onClick = { onEvent(CardEvent.CloseProcess) },
        )
        FlowRow(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
        ) {
            ZillitText(
                text = str(S.ah_card_expenses).uppercase(),
                style = ZillitTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                color = colors.accentText,
                modifier = Modifier.clickable { onEvent(CardEvent.CloseProcess) }.align(Alignment.CenterVertically),
            )
            Crumb("/")
            Crumb(if (history) str(S.history) else str(S.ah_process))
            Crumb("/")
            ZillitText(
                text = receipt.description.ifBlank { str(S.desktop_receipt) },
                style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = CRUMB_MAX).align(Alignment.CenterVertically),
            )
            when {
                !history -> ZillitStatusPill(label = str(S.ah_ready_to_post), tone = StatusTone.Ready)
                receipt.status == CardWorkflowStatus.Posted ->
                    ZillitStatusPill(label = str(S.ah_status_posted), tone = StatusTone.Done)

                else -> ZillitStatusPill(label = receipt.status.label, tone = StatusTone.Neutral)
            }
            if (receipt.urgent) UrgentPill()
            if (locked) {
                ZillitStatusPill(
                    label = str(S.desktop_ce_process_locked_receipt, state.processPages.refs.lock.lockedThrough),
                    tone = StatusTone.Rejected,
                )
            }
        }
        HeaderButtons(state, draft, figures, locked, onEvent)
    }
}

@Composable
private fun RowScope.Crumb(text: String) {
    ZillitText(
        text = text,
        style = ZillitTheme.typography.bodyMedium,
        color = ZillitTheme.colors.textSecondary,
        modifier = Modifier.align(Alignment.CenterVertically),
    )
}

/**
 * The web's header row (`ProcessReceiptModal.jsx:503-570`): History always;
 * Save unless the period is locked; then, in the queue, Assign or Reassign,
 * Query (red while a query rule is on the receipt), Escalate and Submit for
 * Review for a non-senior, and Post wherever [ProcessRules.canPost] allows.
 */
@Suppress("LongMethod", "CyclomaticComplexMethod") // The web's buttons, in the web's order.
@Composable
private fun HeaderButtons(
    state: CardUiState,
    draft: ProcessDraft,
    figures: ProcessFigures,
    locked: Boolean,
    onEvent: (CardEvent) -> Unit,
) {
    val viewer = state.viewer
    val idle = !draft.loading && !state.busy
    val queue = draft.mode == ProcessMode.Process
    val processing = draft.receipt.processing
    Row(
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitButton(
            text = str(S.history),
            onClick = { onEvent(ProcessPageEvent.ShowHistory(true)) },
            variant = ButtonVariant.Secondary,
            size = ButtonSize.Small,
            leadingIcon = ZillitIcons.Clock,
        )
        if (!locked) {
            ZillitButton(
                text = str(S.save),
                onClick = { onEvent(CardEvent.SaveProcess) },
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
                leadingIcon = ZillitIcons.Save,
                enabled = idle,
            )
        }
        if (!queue) return@Row
        if (viewer.isAccountant && !locked) {
            ZillitButton(
                text = if (draft.receipt.assignedTo.isNullOrBlank()) str(S.assign) else str(S.desktop_po_reassign),
                onClick = { onEvent(CardEvent.EditProcess(draft.copy(assign = AssignDraft()))) },
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
                leadingIcon = ZillitIcons.Users,
                enabled = idle,
            )
        }
        ZillitButton(
            text = str(S.ah_query_label),
            onClick = { onEvent(CardEvent.OpenQuery(draft.receipt.id)) },
            variant = if (processing.needsQuery) ButtonVariant.Danger else ButtonVariant.Secondary,
            size = ButtonSize.Small,
            leadingIcon = ZillitIcons.Chat,
            enabled = idle,
        )
        if (ProcessRules.canHandUp(viewer) && !locked) {
            ZillitButton(
                text = str(S.desktop_ce_escalate),
                onClick = { onEvent(CardEvent.EditProcess(draft.copy(escalation = ""))) },
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
                enabled = idle,
            )
            ZillitButton(
                text = str(S.desktop_submit_for_review),
                onClick = { onEvent(CardEvent.SubmitProcessForReview) },
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
                enabled = idle,
            )
        }
        if (!locked && ProcessRules.canPost(viewer, processing, figures.effectiveAmount)) {
            val topUp = processing.requestTopUp && draft.topUp != TopUpMethod.None
            ZillitButton(
                text = if (topUp) str(S.desktop_card_post_and_top_up) else str(S.ah_post_to_ledger),
                onClick = { onEvent(CardEvent.PostProcess) },
                leadingIcon = ZillitIcons.Ledger,
                enabled = idle,
                loading = state.busy,
            )
        }
    }
}

/** A white card on the page's grey, as every block on the web's surface is. */
@Composable
private fun Card(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val colors = ZillitTheme.colors
    Box(
        modifier = modifier
            .clip(ZillitTheme.shapes.large)
            .background(colors.surface)
            .border(1.dp, colors.border, ZillitTheme.shapes.large)
            .padding(ZillitTheme.spacing.lg),
    ) { content() }
}

/**
 * The receipt's facts — amount, date, holder, card — and, under a rule, the
 * three fields an accountant may correct before posting: vendor, cost code,
 * and the ledger date posting needs, bounded by the lock and today.
 */
@Suppress("LongMethod") // Two rows of one card.
@Composable
private fun DetailsCard(
    state: CardUiState,
    draft: ProcessDraft,
    locked: Boolean,
    onEvent: (CardEvent) -> Unit,
    modifier: Modifier,
) {
    val receipt = draft.receipt
    val refs = state.processPages.refs
    val earliest = refs.lock.firstOpenDay
    val today = remember { localDay(Clock.System.now().toEpochMilliseconds()) }
    val outOfRange = draft.effectiveDate.isNotBlank() &&
        ((earliest != null && draft.effectiveDate < earliest) || draft.effectiveDate > today)
    Card(modifier.heightIn(min = DETAILS_MIN)) {
        Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
            FieldGroupLabel(str(S.ah_receipt_details))
            Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg)) {
                Fact(str(S.amount), money(receipt.amount, receipt.currency), Modifier.weight(1f), accent = true)
                Fact(str(S.date), date(receipt.date), Modifier.weight(1f))
                Fact(
                    str(S.desktop_card_card_holder),
                    state.personName(receipt.holderId, receipt.holderName),
                    Modifier.weight(1f),
                )
                Fact(
                    str(S.ah_my_cards),
                    receipt.cardLastFour?.takeIf { it.isNotBlank() }?.let { "···· $it" } ?: EM_DASH,
                    Modifier.weight(1f),
                )
            }
            ZillitDivider()
            Row(
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
                verticalAlignment = Alignment.Top,
            ) {
                ZillitTextField(
                    value = draft.description,
                    onValueChange = { onEvent(CardEvent.EditProcess(draft.copy(description = it))) },
                    label = str(S.ah_lbl_vendor),
                    placeholder = str(S.cash_receipt_vendor_hint),
                    enabled = !locked,
                    modifier = Modifier.weight(2f),
                )
                CoaCodeInput(
                    value = draft.nominalCode,
                    onValueChange = { onEvent(CardEvent.EditProcess(draft.copy(nominalCode = it))) },
                    accounts = refs.accounts,
                    label = str(S.ah_cost_code_label),
                    enabled = !locked,
                    modifier = Modifier.weight(1f),
                )
                ZillitDateField(
                    value = draft.effectiveDate,
                    onValueChange = { onEvent(CardEvent.EditProcess(draft.copy(effectiveDate = it))) },
                    label = str(S.ah_lbl_eff_date) + " *",
                    enabled = !locked,
                    errorText = if (outOfRange) {
                        str(S.desktop_ce_process_date_bounds, earliest ?: EM_DASH, today)
                    } else {
                        null
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun Fact(label: String, value: String, modifier: Modifier = Modifier, accent: Boolean = false) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
        FieldGroupLabel(label)
        ZillitText(
            text = value,
            style = if (accent) {
                ZillitTheme.typography.numeric.copy(fontWeight = FontWeight.ExtraBold)
            } else {
                ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
            },
            color = if (accent) ZillitTheme.colors.accentText else ZillitTheme.colors.textPrimary,
            maxLines = 1,
        )
    }
}

/**
 * The receipt itself, in its own 220-wide card beside the details: the file
 * and a way to open it, or "No receipt uploaded". The desktop opens the file
 * through the host rather than drawing it inline.
 */
@Composable
private fun PreviewCard(draft: ProcessDraft, onEvent: (CardEvent) -> Unit) {
    val key = draft.receipt.attachmentKey?.takeIf { it.isNotBlank() }
    Card(Modifier.width(PREVIEW_WIDTH).heightIn(min = DETAILS_MIN)) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm, Alignment.CenterVertically),
        ) {
            ZillitIcon(ZillitIcons.File, tint = ZillitTheme.colors.textMuted, size = PREVIEW_ICON)
            if (key == null) {
                ZillitText(
                    text = str(S.desktop_ce_process_no_receipt),
                    style = ZillitTheme.typography.bodySmall,
                    color = ZillitTheme.colors.textMuted,
                )
            } else {
                ZillitText(
                    text = key.substringAfterLast('/'),
                    style = ZillitTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                ZillitButton(
                    text = str(S.drive_btn_open),
                    onClick = { onEvent(CardEvent.ViewReceipt(key)) },
                    variant = ButtonVariant.Secondary,
                    size = ButtonSize.Small,
                    leadingIcon = ZillitIcons.Eye,
                )
            }
        }
    }
}

/**
 * The receipt's standing: escalated (why, by whom, when), under review, the
 * rules it tripped as the web words them, and whether the lines add up to it.
 */
@Composable
private fun Banners(state: CardUiState, draft: ProcessDraft, figures: ProcessFigures) {
    if (draft.mode == ProcessMode.Process) StandingBanners(state, draft)
    RuleBanners(draft)
    MismatchBanner(draft, figures)
}

/** Escalated — why, by whom, when — or under review; the queue's banners only. */
@Composable
private fun StandingBanners(state: CardUiState, draft: ProcessDraft) {
    val receipt = draft.receipt
    val processing = receipt.processing
    val handedUp = processing.escalationReason != null || processing.escalatedBy != null
    if (receipt.status == CardWorkflowStatus.Escalated && handedUp) {
        val reason = processing.escalationReason ?: str(S.desktop_inv_no_reason_provided)
        ZillitNotice(
            text = listOfNotNull(
                str(S.ah_escalated_to_senior_toast),
                "“$reason”",
                escalationByline(state, processing),
            ).joinToString("\n"),
            tone = StatusTone.Pending,
            icon = ZillitIcons.Warning,
        )
    }
    if (receipt.status == CardWorkflowStatus.UnderReview) {
        ZillitNotice(text = str(S.ah_under_review), tone = StatusTone.Progress, icon = ZillitIcons.Info)
    }
}

/** One banner per rule the receipt tripped, worded from the rule itself. */
@Composable
private fun RuleBanners(draft: ProcessDraft) {
    val receipt = draft.receipt
    receipt.processing.rules.forEach { rule ->
        val tone = when (rule.flag) {
            ReceiptProcessing.QUERY -> StatusTone.Rejected
            ReceiptProcessing.REVIEW -> StatusTone.Progress
            ReceiptProcessing.DEDUCT -> StatusTone.Pending
            else -> return@forEach
        }
        val headline = ruleHeadline(rule) { money(it, receipt.currency) }
        ZillitNotice(
            text = listOfNotNull(headline, rule.description?.takeIf { it.isNotBlank() }).joinToString("\n"),
            tone = tone,
            icon = ZillitIcons.Info,
        )
    }
}

/** The lines against the receipt: lower (the post will reduce it) or not matching. */
@Composable
private fun MismatchBanner(draft: ProcessDraft, figures: ProcessFigures) {
    val receipt = draft.receipt
    val diff = figures.gross - receipt.amount
    if (abs(diff) > PENNY) {
        val total = money(figures.gross, receipt.currency)
        val amount = money(receipt.amount, receipt.currency)
        val signed = (if (diff > 0) "+" else "") + money(diff, receipt.currency)
        ZillitNotice(
            text = if (diff < 0) {
                str(S.desktop_card_lines_lower_note, total, amount) + "  " + signed
            } else {
                str(S.desktop_card_lines_mismatch_note, total, amount) + "  " + signed
            },
            tone = if (diff < 0) StatusTone.Pending else StatusTone.Rejected,
            icon = ZillitIcons.Warning,
        )
    }
}

/** "By Name (Designation) · 04 Aug 2026 | 4:35 PM" (`ProcessReceiptModal.jsx:686-704`). */
private fun escalationByline(state: CardUiState, processing: ReceiptProcessing): String? {
    val by = processing.escalatedBy
    val at = processing.escalatedAt
    if (by == null && at == null) return null
    val person = state.people.firstOrNull { it.id == by }
    val who = listOfNotNull(
        person?.name ?: by?.let { EM_DASH } ?: str(S.desktop_unknown),
        person?.designation?.takeIf { it.isNotBlank() }?.let { "($it)" },
    ).joinToString(" ")
    val stamp = at?.let { EpochDate.dateTime(it).replace(",", "") }?.takeIf { it.isNotBlank() }
    return str(S.desktop_ce_process_escalated_by, listOfNotNull(who, stamp).joinToString(" · "))
}

/**
 * A rule's banner sentence, built as the web builds it from the rule's
 * title and threshold (`ProcessReceiptModal.jsx:724-747`).
 */
internal fun ruleHeadline(rule: ProcessingFlag, format: (Double) -> String): String {
    val title = rule.title?.trim().orEmpty()
    val name = when {
        title.isEmpty() -> str(S.desktop_ce_process_rule)
        title.endsWith("rule", ignoreCase = true) -> title
        else -> str(S.desktop_ce_process_named_rule, title)
    }
    val threshold = rule.thresholdValue
    return when (rule.flag) {
        ReceiptProcessing.DEDUCT -> if (threshold == null) {
            str(S.desktop_ce_process_rule_deduct, name)
        } else {
            val figure = if (rule.thresholdType == PERCENTAGE) "${threshold.plainNumber()}%" else format(threshold)
            str(S.desktop_ce_process_rule_deduct_amount, figure, name)
        }

        ReceiptProcessing.REVIEW -> if (threshold == null) {
            str(S.desktop_ce_process_rule_review, name)
        } else {
            str(S.desktop_ce_process_rule_review_amount, format(threshold), name)
        }

        else -> if (threshold == null) {
            str(S.desktop_ce_process_rule_query, name)
        } else {
            str(S.desktop_ce_process_rule_query_amount, format(threshold), name)
        }
    }
}

private fun Double.plainNumber(): String = if (this == kotlin.math.floor(this)) toLong().toString() else toString()

/**
 * "RECEIPT £120.00 | CODED £120.00 | ✓ Matched" — or what is left to
 * allocate, or how far over (`GrossMatchBar.jsx`).
 */
@Composable
private fun GrossMatchBar(receipt: Double, coded: Double, currency: String?) {
    val colors = ZillitTheme.colors
    val diff = kotlin.math.round((coded - receipt) * CENTS) / CENTS
    val (tone, soft, text) = when {
        abs(diff) <= PENNY -> Triple(colors.success, colors.successSoft, "✓ " + str(S.desktop_matched))
        diff < 0 ->
            Triple(colors.warning, colors.warningSoft, str(S.desktop_inv_to_allocate, money(abs(diff), currency)))
        else -> Triple(colors.danger, colors.dangerSoft, str(S.desktop_inv_amount_over, money(diff, currency)))
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Row(
            modifier = Modifier
                .clip(ZillitTheme.shapes.medium)
                .background(soft)
                .border(1.dp, tone.copy(alpha = BAR_ALPHA), ZillitTheme.shapes.medium)
                .padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.xs),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FieldGroupLabel(str(S.desktop_receipt))
            ZillitText(text = money(receipt, currency), style = ZillitTheme.typography.numeric)
            ZillitText(text = "|", color = colors.textMuted)
            FieldGroupLabel(str(S.desktop_ce_coded))
            ZillitText(text = money(coded, currency), style = ZillitTheme.typography.numeric, color = tone)
            ZillitText(text = "|", color = colors.textMuted)
            ZillitText(
                text = text,
                style = ZillitTheme.typography.numeric.copy(fontWeight = FontWeight.Bold),
                color = tone,
            )
        }
    }
}

/** The receipt's trail, over the editor — the web's `HistoryPanel`. */
@Composable
private fun HistoryPanel(draft: ProcessDraft, onEvent: (CardEvent) -> Unit) {
    val trail = draft.history ?: return
    ZillitDialogShell(
        title = str(S.history),
        subtitle = draft.receipt.description.ifBlank { draft.receipt.id.take(ID_PREFIX) },
        icon = ZillitIcons.Clock,
        visible = true,
        width = SMALL_DIALOG,
        onDismiss = { onEvent(ProcessPageEvent.ShowHistory(false)) },
    ) {
        CardHistoryTrail(entries = trail)
    }
}

/**
 * Restore the card to its limit, top up by what was spent, or leave it: the
 * three the web offers, each with the balance it leaves and the task it raises.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TopUpDecision(draft: ProcessDraft, figures: ProcessFigures, onEvent: (CardEvent) -> Unit) {
    val receipt = draft.receipt
    val currency = receipt.currency
    val processing = receipt.processing
    val limit = processing.cardLimit ?: 0.0
    val balance = processing.cardBalance ?: limit
    FieldGroupLabel(str(S.desktop_card_top_up_decision))
    Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg)) {
        Fact(str(S.desktop_card_card_limit), money(limit, currency), Modifier.weight(1f))
        Fact(str(S.desktop_card_card_balance), money(balance, currency), Modifier.weight(1f))
        Fact(str(S.desktop_card_this_expense), "−${money(figures.effectiveAmount, currency)}", Modifier.weight(1f))
        Fact(str(S.desktop_card_balance_after), money(figures.balanceAfter, currency), Modifier.weight(1f))
    }
    FieldGroupLabel(str(S.desktop_card_top_up_method))
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        TopUpMethod.entries.forEach { method ->
            val after = when (method) {
                TopUpMethod.Restore -> limit
                TopUpMethod.Expense -> balance
                TopUpMethod.None -> figures.balanceAfter
            }
            val up = figures.topUpAmount(method)
            TopUpOption(
                label = method.label,
                balance = money(after, currency),
                note = if (method == TopUpMethod.None) {
                    str(S.desktop_card_no_balance_change)
                } else {
                    str(S.desktop_card_top_up_task, money(up, currency))
                },
                selected = draft.topUp == method,
                onClick = { onEvent(CardEvent.EditProcess(draft.copy(topUp = method))) },
            )
        }
    }
}

@Composable
private fun TopUpOption(label: String, balance: String, note: String, selected: Boolean, onClick: () -> Unit) {
    val colors = ZillitTheme.colors
    Column(
        modifier = Modifier
            .width(OPTION_WIDTH)
            .clip(ZillitTheme.shapes.large)
            .background(if (selected) colors.accentSoft else colors.surface)
            .border(OPTION_BORDER, if (selected) colors.accent else colors.border, ZillitTheme.shapes.large)
            .clickable(onClick = onClick)
            .padding(ZillitTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
    ) {
        ZillitText(
            text = label,
            style = ZillitTheme.typography.bodyMedium,
            color = if (selected) colors.accentText else colors.textSecondary,
        )
        ZillitText(text = balance, style = ZillitTheme.typography.titleMedium)
        ZillitText(text = note, style = ZillitTheme.typography.bodySmall, color = colors.textMuted)
    }
}


/** Assigning or reassigning, over the editor. The accounts team, never the current assignee. */
@Suppress("LongMethod") // One small form; splitting it hides the order.
@Composable
private fun AssignDialog(state: CardUiState, draft: ProcessDraft, onEvent: (CardEvent) -> Unit) {
    val choice = draft.assign ?: return
    val receipt = draft.receipt
    val reassign = !receipt.assignedTo.isNullOrBlank()
    val team = state.people.filter { it.isAccountsTeam && it.id != receipt.assignedTo }
    ZillitDialogShell(
        title = if (reassign) str(S.desktop_card_reassign_receipt) else str(S.desktop_card_assign_receipt),
        icon = ZillitIcons.Users,
        visible = true,
        width = SMALL_DIALOG,
        onDismiss = { onEvent(CardEvent.EditProcess(draft.copy(assign = null))) },
        actions = {
            Spacer(Modifier.weight(1f))
            ZillitButton(
                text = if (reassign) str(S.desktop_po_reassign) else str(S.assign),
                onClick = { onEvent(CardEvent.ConfirmAssign) },
                enabled = choice.complete && !state.busy,
                loading = state.busy,
            )
        },
    ) {
        receipt.assignedTo?.takeIf { it.isNotBlank() }?.let { current ->
            val name = if (current == state.viewer.userId) str(S.txt_me) else state.personName(current)
            ZillitNotice(text = str(S.desktop_card_currently_assigned_to, name), tone = StatusTone.Neutral)
        }
        FieldGroupLabel(str(S.desktop_assign_to))
        ZillitSelect(
            value = team.firstOrNull { it.id == choice.userId },
            options = team,
            onSelect = { person ->
                onEvent(CardEvent.EditProcess(draft.copy(assign = choice.copy(userId = person?.id.orEmpty()))))
            },
            label = { person -> person.optionLabel(state.viewer.userId) },
            modifier = Modifier.fillMaxWidth(),
        )
        FieldGroupLabel(str(S.reason))
        val reasons = AssignDraft.reasons + AssignDraft.CUSTOM
        ZillitSelect(
            value = choice.reason.takeIf { it.isNotBlank() },
            options = reasons,
            onSelect = { reason ->
                onEvent(CardEvent.EditProcess(draft.copy(assign = choice.copy(reason = reason.orEmpty()))))
            },
            label = { reason ->
                when (reason) {
                    null -> str(S.desktop_select_a_reason)
                    AssignDraft.CUSTOM -> str(S.desktop_other_custom_reason)
                    else -> reason
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        if (choice.reason == AssignDraft.CUSTOM) {
            ZillitTextField(
                value = choice.custom,
                onValueChange = { onEvent(CardEvent.EditProcess(draft.copy(assign = choice.copy(custom = it)))) },
                placeholder = str(S.desktop_card_enter_reason),
                singleLine = false,
                maxLength = MAX_REASON,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** A team member as the assign list names them — "(Me)" for the viewer. */
private fun CardPerson?.optionLabel(me: String): String = when {
    this == null -> str(S.desktop_select_team_member)
    id == me -> str(S.desktop_card_person_me, name)
    else -> name
}

/** Handing a receipt up to a senior, with the reason on the record. */
@Composable
private fun EscalateDialog(state: CardUiState, draft: ProcessDraft, onEvent: (CardEvent) -> Unit) {
    val reason = draft.escalation ?: return
    ZillitDialogShell(
        title = str(S.ah_escalate_to_senior),
        icon = ZillitIcons.Warning,
        visible = true,
        width = SMALL_DIALOG,
        onDismiss = { onEvent(CardEvent.EditProcess(draft.copy(escalation = null))) },
        actions = {
            Spacer(Modifier.weight(1f))
            ZillitButton(
                text = str(S.desktop_ce_escalate),
                onClick = { onEvent(CardEvent.ConfirmEscalation) },
                enabled = reason.isNotBlank() && !state.busy,
                loading = state.busy,
            )
        },
    ) {
        ZillitText(
            text = str(
                S.desktop_card_escalating_note,
                draft.receipt.description.ifBlank { str(S.desktop_receipt) },
            ),
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textSecondary,
        )
        ZillitTextField(
            value = reason,
            onValueChange = { onEvent(CardEvent.EditProcess(draft.copy(escalation = it))) },
            label = str(S.desktop_card_reason_for_escalation),
            placeholder = str(S.desktop_card_escalation_hint),
            singleLine = false,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private const val PERCENTAGE = "percentage"
private const val PENNY = 0.01
private const val CENTS = 100.0
private const val BAR_ALPHA = 0.4f
private const val ID_PREFIX = 8
private const val MAX_REASON = 500
private val CRUMB_MAX = 220.dp
private val DETAILS_MIN = 240.dp
private val PREVIEW_WIDTH = 220.dp
private val PREVIEW_ICON = 36.dp
private val SMALL_DIALOG = 480.dp
private val OPTION_WIDTH = 220.dp
private val OPTION_BORDER = 1.5.dp
