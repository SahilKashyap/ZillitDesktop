package com.zillit.desktop.feature.cardexpenses.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import com.zillit.desktop.core.designsystem.ZillitDimens
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitScrollColumn
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.cardexpenses.domain.CardNominal
import com.zillit.desktop.feature.cardexpenses.domain.CardReceipt
import com.zillit.desktop.feature.cardexpenses.domain.CardWorkflowStatus
import com.zillit.desktop.feature.cardexpenses.domain.MatchStatus
import com.zillit.desktop.feature.cardexpenses.ui.CardEvent

/**
 * Pieces the crew pages share: the page frame, the web's status vocabulary,
 * the adaptive card grid, the chart-code picker and the receipt file pane.
 */

/** A crew page that scrolls as a whole. */
@Composable
internal fun CrewScrollPage(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    ZillitScrollColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(ZillitTheme.spacing.xl),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg),
        content = content,
    )
}

/** The web's receipt status words (`UserReceiptsPage.jsx:64-76`), not the desktop's older ones. */
internal fun crewStatusLabel(status: CardWorkflowStatus): String = when (status) {
    CardWorkflowStatus.PendingReceipt -> str(S.ah_txn_filter_pending_receipt)
    CardWorkflowStatus.PendingCode -> str(S.ah_txn_filter_pending_code)
    CardWorkflowStatus.AwaitingApproval -> str(S.dm_filter_status_pending)
    CardWorkflowStatus.Approved -> str(S.approved)
    CardWorkflowStatus.Rejected -> str(S.rejected)
    CardWorkflowStatus.Queried -> str(S.ah_queried)
    CardWorkflowStatus.UnderReview -> str(S.ah_under_review)
    CardWorkflowStatus.Escalated -> str(S.ah_escalated)
    CardWorkflowStatus.Posted -> str(S.ah_status_posted)
    CardWorkflowStatus.Personal -> str(S.personal)
    else -> status.label
}

/** The web's colours for the same (`statusColors`), as tones. */
internal fun crewStatusTone(status: CardWorkflowStatus): StatusTone = when (status) {
    CardWorkflowStatus.PendingReceipt, CardWorkflowStatus.AwaitingApproval, CardWorkflowStatus.Queried ->
        StatusTone.Pending

    CardWorkflowStatus.PendingCode, CardWorkflowStatus.UnderReview -> StatusTone.Progress
    CardWorkflowStatus.Approved -> StatusTone.Ready
    CardWorkflowStatus.Posted -> StatusTone.Done
    CardWorkflowStatus.Rejected, CardWorkflowStatus.Escalated, CardWorkflowStatus.Personal -> StatusTone.Rejected
    else -> StatusTone.Neutral
}

/**
 * A receipt's status pill.
 *
 * With [unreconciled] on — My Transactions and the detail opened from it —
 * an unmatched receipt reads "Unreconciled" whatever its workflow state: the
 * crew variant of the rule, with no "Reconciled" and no "Match Suggested",
 * both of which are the accounts team's to act on (`unreconciledBadge`).
 */
@Composable
internal fun CrewStatusPill(receipt: CardReceipt, modifier: Modifier = Modifier, unreconciled: Boolean = false) {
    if (unreconciled && receipt.matchStatus == MatchStatus.Unmatched) {
        ZillitStatusPill(str(S.ah_unreconciled), modifier, StatusTone.Pending, dot = true)
    } else {
        ZillitStatusPill(crewStatusLabel(receipt.status), modifier, crewStatusTone(receipt.status), dot = true)
    }
}

/**
 * The web's `grid-cols-1 sm:grid-cols-2 lg:grid-cols-3`: three across on a
 * wide window, two on a narrow one, one on a sliver.
 */
@Composable
internal fun <T> CrewCardGrid(items: List<T>, key: (T) -> String, cell: @Composable (T) -> Unit) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val columns = when {
            maxWidth >= THREE_ACROSS -> 3
            maxWidth >= TWO_ACROSS -> 2
            else -> 1
        }
        Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg)) {
            items.chunked(columns).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg)) {
                    row.forEach { item ->
                        androidx.compose.runtime.key(key(item)) {
                            Box(modifier = Modifier.weight(1f)) { cell(item) }
                        }
                    }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

/** A card-like surface: white, hairline, rounded, and clickable when [onClick] is set. */
@Composable
internal fun CrewTile(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    padding: Dp = ZillitTheme.spacing.lg,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = ZillitTheme.colors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.large)
            .background(colors.surface)
            .border(CREW_HAIRLINE, colors.border, ZillitTheme.shapes.large)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(padding),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        content = content,
    )
}

/** A small caption over a read-only value — the web's `text-[9px] uppercase` pairs. */
@Composable
internal fun CrewField(label: String, value: String, modifier: Modifier = Modifier, emphasised: Boolean = false) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
        ZillitText(
            text = label.uppercase(),
            style = ZillitTheme.typography.labelSmall,
            color = ZillitTheme.colors.textMuted,
        )
        ZillitText(
            text = value,
            style = if (emphasised) ZillitTheme.typography.titleSmall else ZillitTheme.typography.bodyMedium,
            color = if (emphasised) ZillitTheme.colors.accentText else ZillitTheme.colors.textPrimary,
        )
    }
}

/**
 * A chart-code field — the web's `CoaCodeInput`.
 *
 * Suggests the production's postable codes as the person types, code
 * matches first; anything typed is kept as typed, so a chart the host could
 * not supply degrades to the plain text field the desktop had.
 */
@Composable
internal fun CrewCodeField(
    value: String,
    onValueChange: (String) -> Unit,
    nominals: List<CardNominal>,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    enabled: Boolean = true,
) {
    var focused by remember { mutableStateOf(false) }
    val needle = value.trim()
    val suggestions = remember(needle, nominals) { suggest(nominals, needle) }
    val known = nominals.firstOrNull { it.code.equals(needle, ignoreCase = true) }
    val colors = ZillitTheme.colors

    Box(modifier = modifier) {
        ZillitTextField(
            value = value,
            onValueChange = onValueChange,
            label = label,
            placeholder = placeholder,
            enabled = enabled,
            helperText = known?.name?.takeIf { it.isNotBlank() },
            modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
        )
        val offering = focused && enabled && known == null
        if (offering && suggestions.isNotEmpty()) {
            Popup(offset = IntOffset(0, CODE_DROP), onDismissRequest = { focused = false }) {
                Column(
                    modifier = Modifier
                        .width(POPUP_WIDTH)
                        .shadow(POPUP_ELEVATION, ZillitTheme.shapes.large)
                        .clip(ZillitTheme.shapes.large)
                        .background(colors.surfaceRaised)
                        .border(CREW_HAIRLINE, colors.border, ZillitTheme.shapes.large)
                        .padding(ZillitTheme.spacing.xs),
                ) {
                    suggestions.forEach { nominal ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(ZillitTheme.shapes.medium)
                                .clickable {
                                    onValueChange(nominal.code)
                                    focused = false
                                }
                                .padding(horizontal = ZillitTheme.spacing.sm, vertical = ZillitTheme.spacing.xs),
                            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                        ) {
                            ZillitText(
                                text = nominal.code,
                                style = ZillitTheme.typography.numeric.copy(fontFamily = FontFamily.Monospace),
                            )
                            ZillitText(
                                text = nominal.name,
                                style = ZillitTheme.typography.bodySmall,
                                color = colors.textSecondary,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Codes starting with the text first, then codes or names containing it. */
private fun suggest(nominals: List<CardNominal>, needle: String): List<CardNominal> {
    if (needle.isEmpty()) return nominals.take(SUGGESTIONS)
    val starts = nominals.filter { it.code.startsWith(needle, ignoreCase = true) }
    val contains = nominals.filter {
        it !in starts && (it.code.contains(needle, ignoreCase = true) || it.name.contains(needle, ignoreCase = true))
    }
    return (starts + contains).take(SUGGESTIONS)
}

/**
 * The receipt's document, left of a dialog's fields — the web's
 * `ReceiptPreviewPane` / `ReceiptMediaPanel`.
 *
 * The desktop opens the file through the host rather than drawing it inline,
 * so the pane names it and offers Open; without one it says so.
 */
@Composable
internal fun ReceiptFilePane(receipt: CardReceipt, onEvent: (CardEvent) -> Unit, modifier: Modifier = Modifier) {
    val colors = ZillitTheme.colors
    val key = receipt.attachmentKey?.takeIf { it.isNotBlank() }
    Column(
        modifier = modifier
            .clip(ZillitTheme.shapes.large)
            .background(colors.surfaceSunken)
            .padding(ZillitTheme.spacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm, Alignment.CenterVertically),
    ) {
        ZillitIcon(ZillitIcons.File, tint = if (key == null) colors.textMuted else colors.accent, size = FILE_ICON)
        if (key == null) {
            ZillitText(
                text = str(S.desktop_ce_process_no_receipt),
                style = ZillitTheme.typography.bodySmall,
                color = colors.textMuted,
            )
        } else {
            ZillitText(
                text = receipt.attachmentName ?: key.substringAfterLast('/'),
                style = ZillitTheme.typography.bodySmall,
                maxLines = 2,
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

/** A rounded amber well behind an icon — the web's `bg-[#fdf2e2]` chips. */
@Composable
internal fun IconWell(icon: androidx.compose.ui.graphics.vector.ImageVector, size: Dp = WELL) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(WELL_CORNER))
            .background(ZillitTheme.colors.accentSoft)
            .padding(ZillitTheme.spacing.sm),
        contentAlignment = Alignment.Center,
    ) {
        ZillitIcon(icon, tint = ZillitTheme.colors.accent, size = size - ZillitTheme.spacing.lg)
    }
}

internal val CREW_HAIRLINE = 1.dp
private val THREE_ACROSS = 900.dp
private val TWO_ACROSS = 560.dp
private val POPUP_WIDTH = 320.dp
private val POPUP_ELEVATION = 8.dp
private const val CODE_DROP = 68
private const val SUGGESTIONS = 8
private val FILE_ICON = ZillitDimens.icon * 2
private val WELL = 36.dp
private val WELL_CORNER = 10.dp
