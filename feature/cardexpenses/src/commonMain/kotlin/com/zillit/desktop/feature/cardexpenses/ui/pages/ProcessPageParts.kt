// The cells, pickers and bars the accountant's process pages share.
@file:Suppress("TooManyFunctions")

package com.zillit.desktop.feature.cardexpenses.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitBadge
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.cardexpenses.domain.CardCoaAccount
import com.zillit.desktop.feature.cardexpenses.domain.CardReceipt
import com.zillit.desktop.feature.cardexpenses.ui.CardUiState
import com.zillit.desktop.feature.cardexpenses.ui.date
import com.zillit.desktop.feature.cardexpenses.ui.money

/** The red "Urgent" pill a crew member's flag puts on a row. */
@Composable
internal fun UrgentPill() {
    ZillitStatusPill(label = str(S.ah_topup_filter_urgent), tone = StatusTone.Rejected, dot = true)
}

/** A row's date: its own, else when it was created (`r.date ? … : formatDate(r.created_at)`). */
@Composable
internal fun DateCell(receipt: CardReceipt) {
    ZillitText(
        text = date(receipt.date ?: receipt.createdAt),
        style = ZillitTheme.typography.numeric,
        color = ZillitTheme.colors.textMuted,
        maxLines = 1,
    )
}

/**
 * A person by id — name over role, the web's Card Holder cell. Someone the
 * crew list cannot name reads as a dash, never as their id.
 */
@Composable
internal fun HolderCell(state: CardUiState, userId: String?, fallback: String = "") {
    val person = state.people.firstOrNull { it.id == userId }
    val name = state.personName(userId, fallback)
    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
        ZillitText(
            text = name,
            style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
            color = if (name == EM_DASH) ZillitTheme.colors.textMuted else ZillitTheme.colors.textPrimary,
            maxLines = 1,
        )
        person?.designation?.takeIf { it.isNotBlank() }?.let {
            ZillitText(
                text = it,
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textMuted,
                maxLines = 1,
            )
        }
    }
}

/**
 * The Receipt Details cell of the process, bulk and history tables: the
 * merchant (and Urgent), the coding's description, and the statement line it
 * is matched to — merchant, then holder · amount · card · date
 * (`ProcessPage.jsx:590-657`).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ReceiptDetailsCell(
    state: CardUiState,
    receipt: CardReceipt,
    /** The card digits the linked line shows; the review queue reads the transaction's own. */
    lastFour: String? = receipt.cardLastFour,
    urgent: Boolean = true,
    /** The row's own unread chip, beside the merchant; 0 draws none. */
    unread: Int = 0,
) {
    val colors = ZillitTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
        ) {
            ZillitText(
                text = receipt.description.ifBlank { str(S.desktop_ce_cards_unknown_merchant) },
                style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
            )
            ZillitBadge(count = unread, modifier = Modifier.align(Alignment.CenterVertically))
            if (urgent && receipt.urgent) UrgentPill()
        }
        receipt.codeDescription?.takeIf { it.isNotBlank() }?.let {
            ZillitText(text = it, style = ZillitTheme.typography.bodySmall, color = colors.textSecondary, maxLines = 1)
        }
        if (!receipt.transactionId.isNullOrBlank()) {
            ZillitText(
                text = str(
                    S.desktop_ce_process_linked_txn,
                    receipt.transactionMerchant?.takeIf { it.isNotBlank() } ?: receipt.description,
                ),
                style = ZillitTheme.typography.bodySmall,
                color = colors.textSecondary,
            )
            val holder = state.people.firstOrNull { it.id == receipt.holderId }?.name
            val facts = listOfNotNull(
                holder,
                receipt.transactionAmount?.let { money(it, receipt.currency) },
                lastFour?.takeIf { it.isNotBlank() }?.let { "•••• $it" },
                receipt.transactionDate?.let { date(it) },
            )
            if (facts.isNotEmpty()) {
                ZillitText(
                    text = facts.joinToString(" · "),
                    style = ZillitTheme.typography.bodySmall,
                    color = colors.textSecondary,
                )
            }
        }
    }
}

/** The Code cell: the nominal in the accent, and "Ep N" under it on a television production. */
@Composable
internal fun CodeCell(code: String?, episode: String?, television: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
        ZillitText(
            text = code?.takeIf { it.isNotBlank() } ?: EM_DASH,
            style = ZillitTheme.typography.numeric.copy(fontWeight = FontWeight.SemiBold),
            color = if (code.isNullOrBlank()) ZillitTheme.colors.textMuted else ZillitTheme.colors.accentText,
            maxLines = 1,
        )
        if (television && !episode.isNullOrBlank()) {
            ZillitText(
                text = str(S.desktop_episode_abbrev_list, episode),
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textMuted,
            )
        }
    }
}

/** An amount, right-aligned and in the accent, as the process tables print it. */
@Composable
internal fun AmountCell(receipt: CardReceipt) {
    ZillitText(
        text = if (receipt.amount != 0.0) money(receipt.amount, receipt.currency) else EM_DASH,
        style = ZillitTheme.typography.numeric.copy(fontWeight = FontWeight.Bold),
        color = ZillitTheme.colors.accentText,
        maxLines = 1,
    )
}

/**
 * The floating selection bar the web pins to the foot of a queue — here the
 * last thing on the page, bordered in the accent so it reads as the page's
 * one pending decision.
 */
@Composable
internal fun SelectionBar(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val colors = ZillitTheme.colors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .shadow(BAR_ELEVATION, ZillitTheme.shapes.large)
            .clip(ZillitTheme.shapes.large)
            .background(colors.surfaceRaised)
            .border(1.dp, colors.accent.copy(alpha = BAR_BORDER_ALPHA), ZillitTheme.shapes.large)
            .padding(horizontal = ZillitTheme.spacing.lg, vertical = ZillitTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        content = content,
    )
}

/**
 * A chart-code typeahead — the web's `CoaCodeInput`.
 *
 * Focus opens the chart's postable codes; typing narrows them by code and by
 * name, codes that start with what was typed first. A code the chart has not
 * got can still be typed and kept, as the web allows.
 */
@Composable
internal fun CoaCodeInput(
    value: String,
    onValueChange: (String) -> Unit,
    accounts: List<CardCoaAccount>,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String = str(S.desktop_po_search_or_enter_code),
    enabled: Boolean = true,
    error: Boolean = false,
) {
    var focused by remember { mutableStateOf(false) }
    val suggestions = remember(value, accounts) { suggest(accounts, value) }
    val colors = ZillitTheme.colors
    val known = accounts.firstOrNull { it.code == value.trim() }

    Box(modifier = modifier) {
        ZillitTextField(
            value = value,
            onValueChange = onValueChange,
            label = label,
            placeholder = placeholder,
            enabled = enabled,
            containerColor = if (error) colors.dangerSoft else null,
            modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
        )
        val offering = focused && enabled && known == null
        if (offering && suggestions.isNotEmpty()) {
            Popup(offset = IntOffset(0, CODE_DROP), onDismissRequest = { focused = false }) {
                Column(
                    modifier = Modifier
                        .width(POPUP_WIDTH)
                        .shadow(BAR_ELEVATION, ZillitTheme.shapes.large)
                        .clip(ZillitTheme.shapes.large)
                        .background(colors.surfaceRaised)
                        .border(1.dp, colors.border, ZillitTheme.shapes.large)
                        .heightIn(max = POPUP_MAX)
                        .verticalScroll(rememberScrollState())
                        .padding(ZillitTheme.spacing.xs),
                ) {
                    suggestions.forEach { account ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(ZillitTheme.shapes.medium)
                                .clickable {
                                    onValueChange(account.code)
                                    focused = false
                                }
                                .padding(horizontal = ZillitTheme.spacing.sm, vertical = ZillitTheme.spacing.xs),
                            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            ZillitText(
                                text = account.code,
                                style = ZillitTheme.typography.numeric.copy(fontFamily = FontFamily.Monospace),
                            )
                            ZillitText(
                                text = account.name,
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

/** Codes starting with [term] first, then any whose code or name contains it. */
internal fun suggest(accounts: List<CardCoaAccount>, term: String): List<CardCoaAccount> {
    val needle = term.trim()
    if (needle.isEmpty()) return accounts.take(SUGGEST_LIMIT)
    val starts = accounts.filter { it.code.startsWith(needle, ignoreCase = true) }
    val contains = accounts.filter {
        it !in starts && (it.code.contains(needle, true) || it.name.contains(needle, true))
    }
    return (starts + contains).take(SUGGEST_LIMIT)
}

internal const val EM_DASH = "—"
private const val SUGGEST_LIMIT = 40
private const val CODE_DROP = 44
private const val BAR_BORDER_ALPHA = 0.5f
private val BAR_ELEVATION = 8.dp
private val POPUP_WIDTH = 320.dp
private val POPUP_MAX = 280.dp
