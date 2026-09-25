package com.zillit.desktop.feature.cardexpenses.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitDimens
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitCheckbox
import com.zillit.desktop.core.designsystem.component.ZillitDateField
import com.zillit.desktop.core.designsystem.component.ZillitDivider
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitMeter
import com.zillit.desktop.core.designsystem.component.ZillitScrollColumn
import com.zillit.desktop.core.designsystem.component.ZillitSelect
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.cardexpenses.domain.CardDates
import com.zillit.desktop.feature.cardexpenses.domain.CrewRules
import com.zillit.desktop.feature.cardexpenses.domain.DraftCardReceipt
import com.zillit.desktop.feature.cardexpenses.domain.ReceiptCategory
import com.zillit.desktop.feature.cardexpenses.domain.UploadHeadroom
import com.zillit.desktop.feature.cardexpenses.ui.CardEvent
import com.zillit.desktop.feature.cardexpenses.ui.CardUiState
import com.zillit.desktop.feature.cardexpenses.ui.CrewEvent
import com.zillit.desktop.feature.cardexpenses.ui.money

/**
 * Upload Receipts — the full-screen batch form (`UserReceiptsPage.jsx:496-842`).
 *
 * Takes over the content area while open (`CrewState.fullScreen`): a pinned
 * breadcrumb bar with the running total and the submit, the card's headroom,
 * one card per receipt, and "Add Another Receipt".
 */
@Suppress("LongMethod") // The pinned bar and the body; the order is the page's.
@Composable
fun UploadReceiptsPage(state: CardUiState, onEvent: (CardEvent) -> Unit) {
    val card = CrewRules.activeCard(state.cards)
    val currency = card?.currency
    val headroom = UploadHeadroom.of(card)
    val total = state.draftTotal
    val remaining = headroom.available - total
    val over = headroom.batchExceeds(total)
    val withAmount = state.draft.count { it.amount.isNotBlank() }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(ZillitTheme.colors.surface)
                .padding(horizontal = ZillitTheme.spacing.xl, vertical = ZillitTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        ) {
            ZillitIconButton(
                icon = ZillitIcons.ChevronLeft,
                contentDescription = str(S.back),
                onClick = { onEvent(CrewEvent.CloseUpload) },
            )
            ZillitText(
                text = str(S.ah_card_expenses).uppercase(),
                style = ZillitTheme.typography.labelSmall,
                color = ZillitTheme.colors.accentText,
                modifier = Modifier.clickable { onEvent(CrewEvent.CloseUpload) },
            )
            ZillitText(text = "/", color = ZillitTheme.colors.textMuted)
            ZillitText(
                text = str(S.ah_upload_receipts),
                style = ZillitTheme.typography.label,
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
            ZillitText(
                text = listOf(
                    if (state.draft.size == 1) {
                        str(S.desktop_pc_receipt_one)
                    } else {
                        str(S.desktop_card_receipt_count_other, state.draft.size)
                    },
                    money(total, currency),
                ).joinToString(" · ") + " · ",
                style = ZillitTheme.typography.numeric,
                color = ZillitTheme.colors.textSecondary,
            )
            ZillitText(
                text = str(S.desktop_ce_crew_left, money(remaining, currency)),
                style = ZillitTheme.typography.numeric,
                color = if (remaining < 0) ZillitTheme.colors.danger else ZillitTheme.colors.textSecondary,
            )
            ZillitButton(
                text = when {
                    state.busy -> str(S.ah_submitting)
                    withAmount == 0 -> str(S.desktop_ce_submit_receipts)
                    withAmount == 1 -> str(S.desktop_ce_crew_submit_one)
                    else -> str(S.desktop_ce_crew_submit_many, withAmount)
                },
                onClick = { onEvent(CrewEvent.SubmitUpload) },
                leadingIcon = ZillitIcons.Check,
                loading = state.busy,
                enabled = !state.busy && withAmount > 0 && !over,
            )
        }
        ZillitDivider()

        ZillitScrollColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(ZillitTheme.spacing.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier = Modifier.widthIn(max = BODY_MAX).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
                    ZillitText(
                        text = str(S.desktop_ce_crew_add_your_receipts),
                        style = ZillitTheme.typography.titleLarge,
                    )
                    ZillitText(
                        text = str(S.desktop_ce_crew_upload_intro),
                        style = ZillitTheme.typography.bodyMedium,
                        color = ZillitTheme.colors.textSecondary,
                    )
                }
                HeadroomCard(headroom, currency, over)
                state.draft.forEachIndexed { index, row ->
                    DraftReceiptCard(state, index, row, currency, onEvent)
                }
                AddAnotherButton { onEvent(CardEvent.AddDraftReceipt) }
            }
        }
    }
}

/** Card Limit, Committed, Available and % used, with the bar pink past 80% (`:588-621`). */
@Composable
private fun HeadroomCard(headroom: UploadHeadroom, currency: String?, over: Boolean) {
    val colors = ZillitTheme.colors
    val used = if (headroom.cardLimit > 0) (headroom.receiptsCommit / headroom.cardLimit) else 0.0
    CrewTile {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxl)) {
                CrewField(str(S.desktop_card_card_limit), money(headroom.cardLimit, currency))
                CrewField(str(S.desktop_cr_committed), money(headroom.receiptsCommit, currency))
                Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
                    ZillitText(
                        text = str(S.available).uppercase(),
                        style = ZillitTheme.typography.labelSmall,
                        color = colors.textMuted,
                    )
                    ZillitText(
                        text = money(headroom.available, currency),
                        style = ZillitTheme.typography.titleSmall,
                        color = if (headroom.available <= 0) colors.danger else colors.success,
                    )
                }
            }
            ZillitText(
                text = str(S.desktop_ce_crew_percent_used, (used * PERCENT).coerceAtMost(PERCENT).toInt()),
                style = ZillitTheme.typography.numeric,
                color = colors.textSecondary,
            )
        }
        ZillitMeter(
            fraction = used.toFloat(),
            tone = if (used > NEARLY_SPENT) StatusTone.Rejected else StatusTone.Ready,
            modifier = Modifier.fillMaxWidth(),
        )
        if (over) {
            ZillitText(
                text = str(S.desktop_ce_crew_batch_over, money(headroom.available, currency)),
                style = ZillitTheme.typography.label,
                color = colors.danger,
            )
        }
    }
}

/** One receipt: the file on the left, the fields on the right (`:623-833`). */
@Suppress("LongMethod") // One receipt's whole form; the order is the order it is filled in.
@Composable
private fun DraftReceiptCard(
    state: CardUiState,
    index: Int,
    row: DraftCardReceipt,
    currency: String?,
    onEvent: (CardEvent) -> Unit,
) {
    val colors = ZillitTheme.colors
    var codingOpen by remember(index) { mutableStateOf(false) }
    val change: (DraftCardReceipt) -> Unit = { onEvent(CardEvent.EditDraftReceipt(index, it)) }

    CrewTile(padding = ZillitTheme.spacing.none) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.surfaceSunken)
                .padding(horizontal = ZillitTheme.spacing.lg, vertical = ZillitTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            ZillitIcon(ZillitIcons.File, tint = colors.accent, size = ZillitDimens.iconSmall)
            ZillitText(
                text = str(S.desktop_ce_crew_receipt_number, (index + 1).toString().padStart(2, '0')),
                style = ZillitTheme.typography.labelSmall,
                color = colors.accentText,
                modifier = Modifier.weight(1f),
            )
            if (state.draft.size > 1) {
                ZillitButton(
                    text = str(S.remove),
                    onClick = { onEvent(CardEvent.RemoveDraftReceipt(index)) },
                    variant = ButtonVariant.Secondary,
                    size = ButtonSize.Small,
                )
            }
        }
        Row(modifier = Modifier.fillMaxWidth().padding(ZillitTheme.spacing.lg)) {
            FileDropZone(
                fileName = row.attachmentName ?: row.attachmentKey?.substringAfterLast('/'),
                enabled = state.canAttachFiles && !state.uploading,
                onPick = { onEvent(CardEvent.AttachDraftReceipt(index)) },
                onClear = { onEvent(CardEvent.ClearDraftAttachment(index)) },
                modifier = Modifier.width(MEDIA_WIDTH),
            )
            Column(
                modifier = Modifier.weight(1f).padding(start = ZillitTheme.spacing.lg),
                verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
                    ZillitDateField(
                        value = CardDates.toIso(row.date),
                        onValueChange = { change(row.copy(date = CardDates.toMillis(it))) },
                        label = str(S.date) + " *",
                        modifier = Modifier.weight(1f),
                    )
                    ZillitTextField(
                        value = row.amount,
                        onValueChange = { change(row.copy(amount = it.crewAmount())) },
                        label = str(S.amount) + " *",
                        placeholder = "0.00",
                        keyboardType = KeyboardType.Decimal,
                        modifier = Modifier.weight(1f),
                    )
                    ZillitTextField(
                        value = currency ?: "—",
                        onValueChange = {},
                        label = str(S.ah_lbl_currency),
                        readOnly = true,
                        enabled = false,
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
                    ZillitTextField(
                        value = row.description,
                        onValueChange = { change(row.copy(description = it)) },
                        label = str(S.desktop_ce_crew_merchant_description) + " *",
                        placeholder = str(S.desktop_ce_crew_what_did_you_purchase),
                        modifier = Modifier.weight(2f),
                    )
                    CategoryPicker(row.category, { change(row.copy(category = it)) }, Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xl)) {
                    ZillitCheckbox(
                        checked = row.urgent,
                        onCheckedChange = { change(row.copy(urgent = it)) },
                        label = str(S.desktop_ce_crew_mark_urgent),
                    )
                    ZillitCheckbox(
                        checked = row.requestTopUp,
                        onCheckedChange = { change(row.copy(requestTopUp = it)) },
                        label = str(S.desktop_card_request_top_up),
                    )
                }
                ZillitDivider()
                ZillitButton(
                    text = str(S.desktop_ce_crew_budget_coding_optional) +
                        (row.costCode.takeIf { !codingOpen && it.isNotBlank() }?.let { "  $it" } ?: ""),
                    onClick = { codingOpen = !codingOpen },
                    variant = ButtonVariant.Tertiary,
                    size = ButtonSize.Small,
                    leadingIcon = if (codingOpen) ZillitIcons.ChevronDown else ZillitIcons.ChevronRight,
                )
                if (codingOpen) {
                    Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
                        CrewCodeField(
                            value = row.costCode,
                            onValueChange = { change(row.copy(costCode = it)) },
                            nominals = state.crew.nominals,
                            label = str(S.desktop_card_cost_code),
                            placeholder = str(S.desktop_ce_crew_search_code),
                            modifier = Modifier.weight(CODE_WEIGHT),
                        )
                        if (state.crew.isTelevision) {
                            ZillitTextField(
                                value = row.episode,
                                onValueChange = { change(row.copy(episode = it)) },
                                label = str(S.episode),
                                placeholder = str(S.ah_episode_hint),
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                    ZillitTextField(
                        value = row.codedDescription,
                        onValueChange = { change(row.copy(codedDescription = it)) },
                        label = str(S.desktop_pc_coding_description),
                        placeholder = str(S.desktop_pc_coding_description_placeholder),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

/**
 * The receipt's file: a dashed well to click when empty, the file's name and
 * Remove when chosen. Required — the batch is refused without one.
 */
@Composable
internal fun FileDropZone(
    fileName: String?,
    enabled: Boolean,
    onPick: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = ZillitTheme.colors
    Column(
        modifier = modifier
            .heightIn(min = DROP_HEIGHT)
            .clip(ZillitTheme.shapes.large)
            .background(colors.surfaceSunken)
            .border(
                CREW_HAIRLINE,
                if (fileName == null) colors.borderStrong else colors.border,
                ZillitTheme.shapes.large,
            )
            .then(if (fileName == null && enabled) Modifier.clickable(onClick = onPick) else Modifier)
            .padding(ZillitTheme.spacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm, Alignment.CenterVertically),
    ) {
        if (fileName != null) {
            ZillitIcon(ZillitIcons.Paperclip, tint = colors.success)
            ZillitText(
                text = fileName,
                style = ZillitTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                maxLines = 3,
            )
            ZillitButton(
                text = str(S.remove),
                onClick = onClear,
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
            )
        } else {
            IconWell(ZillitIcons.Upload)
            ZillitText(
                text = str(S.desktop_click_to_upload) + " *",
                style = ZillitTheme.typography.label,
                textAlign = TextAlign.Center,
            )
            ZillitText(
                text = str(S.desktop_ce_crew_file_rules),
                style = ZillitTheme.typography.labelSmall,
                color = colors.textMuted,
                textAlign = TextAlign.Center,
            )
            if (!enabled) {
                ZillitText(
                    text = str(S.desktop_card_no_picker_receipts),
                    style = ZillitTheme.typography.labelSmall,
                    color = colors.textMuted,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/** The nine categories, labelled as the web labels them. */
@Composable
internal fun CategoryPicker(
    value: ReceiptCategory,
    onSelect: (ReceiptCategory) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
        ZillitText(
            text = str(S.av_category),
            style = ZillitTheme.typography.label,
            color = ZillitTheme.colors.textSecondary,
        )
        ZillitSelect(
            value = value,
            options = ReceiptCategory.entries,
            onSelect = onSelect,
            label = { it.label },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun AddAnotherButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.large)
            .border(CREW_HAIRLINE, ZillitTheme.colors.accent, ZillitTheme.shapes.large)
            .clickable(onClick = onClick)
            .padding(ZillitTheme.spacing.md),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            ZillitIcon(ZillitIcons.Add, tint = ZillitTheme.colors.accent, size = ZillitDimens.iconSmall)
            ZillitText(
                text = str(S.desktop_ce_crew_add_another_receipt),
                style = ZillitTheme.typography.titleSmall,
                color = ZillitTheme.colors.accentText,
            )
        }
    }
}

/** Digits and one point, two places — the web's `sanitizeAmountInput` (ZL-20769). */
internal fun String.crewAmount(): String {
    val kept = filter { it.isDigit() || it == '.' }
    val point = kept.indexOf('.')
    if (point < 0) return kept
    val whole = kept.substring(0, point)
    val fraction = kept.substring(point + 1).replace(".", "").take(2)
    return "$whole.$fraction"
}

private val BODY_MAX = 1080.dp
private val MEDIA_WIDTH = 300.dp
private val DROP_HEIGHT = 220.dp
private const val CODE_WEIGHT = 1.4f
private const val PERCENT = 100.0
private const val NEARLY_SPENT = 0.8
