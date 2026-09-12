package com.zillit.desktop.feature.accounthub.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitHorizontalScrollRail
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitLazyColumn
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTooltip
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.accounthub.domain.BibleAccount
import com.zillit.desktop.feature.accounthub.domain.BibleFormat
import com.zillit.desktop.feature.accounthub.domain.LedgerTransaction
import com.zillit.desktop.feature.accounthub.domain.SourceBadge
import com.zillit.desktop.feature.accounthub.ui.AccountHubEvent
import com.zillit.desktop.feature.accounthub.ui.AccountHubUiState
import kotlinx.datetime.TimeZone

/**
 * The report itself: the banner, the column headings, one folding section per
 * account, and the grand total pinned under the rows.
 *
 * ## Lazy, with pinned account headings
 *
 * A production's bible runs to thousands of lines. The rows are a lazy list so
 * scrolling stays smooth at that size, and each account's heading sticks to the
 * top while its lines scroll under it — in a long account the reader never has
 * to scroll back up to learn which code they are reading.
 *
 * ## Wide rather than squeezed
 *
 * Below [MIN_TABLE_WIDTH] the table keeps its width and scrolls sideways, as
 * the web's `min-width: 860px` table does. Squeezing it would crush the vendor
 * and description columns to nothing, which are the two a reader scans by.
 */
@Composable
internal fun ColumnScope.BibleReportTable(state: AccountHubUiState, onEvent: (AccountHubEvent) -> Unit) {
    val bible = state.bible
    val report = bible.report ?: return
    val colors = ZillitTheme.colors
    val zone = remember { TimeZone.currentSystemDefault() }
    val symbol = bibleSymbol(state, report.currencyCode)
    // A line that names no currency was raised in the production's own — the
    // web prints "GBP" there, which is only right for a British production.
    val homeCurrency = state.setup.currencies.saved.defaultCode.orEmpty()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .weight(1f)
            .clip(ZillitTheme.shapes.large)
            .background(colors.surface)
            .border(1.dp, colors.border, ZillitTheme.shapes.large),
    ) {
        Banner(state, onEvent, zone)
        Rule()
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            val tableWidth = max(maxWidth, MIN_TABLE_WIDTH)
            val sideways = rememberScrollState()
            val rows = rememberLazyListState()
            Column(
                modifier = Modifier
                    .fillMaxHeight()
                    .horizontalScroll(sideways, enabled = tableWidth > maxWidth)
                    .width(tableWidth),
            ) {
                ColumnHeadings()
                Rule()
                ZillitLazyColumn(modifier = Modifier.fillMaxWidth().weight(1f), state = rows) {
                    report.accounts.forEachIndexed { index, account ->
                        val folded = account.code in bible.collapsed
                        stickyHeader(key = "account:$index", contentType = "account") {
                            AccountHeading(account, symbol, folded) {
                                onEvent(AccountHubEvent.ToggleBibleAccount(account.code))
                            }
                        }
                        if (!folded) {
                            items(
                                count = account.transactions.size,
                                key = { row -> "line:$index:$row" },
                                contentType = { "line" },
                            ) { row -> TransactionRow(account.transactions[row], symbol, homeCurrency, zone) }
                            if (account.transactions.size > 1) {
                                item(key = "total:$index", contentType = "total") { AccountTotal(account, symbol) }
                            }
                        }
                    }
                }
            }
            if (tableWidth > maxWidth) {
                ZillitHorizontalScrollRail(sideways, Modifier.align(Alignment.BottomCenter).fillMaxWidth())
            }
        }
        GrandTotal(report.grandTotal, symbol)
    }
}

// -- banner ----------------------------------------------------------------------

/**
 * Production · Date Range · Accounts · Open POs — the web's metadata strip.
 *
 * Read from what the server says it applied, falling back to what this run
 * asked for; see [com.zillit.desktop.feature.accounthub.domain.BibleEcho].
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Banner(state: AccountHubUiState, onEvent: (AccountHubEvent) -> Unit, zone: TimeZone) {
    val bible = state.bible
    val report = bible.report ?: return
    val echo = report.echo
    val asked = bible.run?.query
    val start = echo?.periodStartMillis ?: asked?.periodStartMillis
    val end = echo?.periodEndMillis ?: asked?.periodEndMillis
    val accountStart = echo?.accountStart?.takeIf { it.isNotBlank() } ?: asked?.accountStart.orEmpty()
    val accountEnd = echo?.accountEnd?.takeIf { it.isNotBlank() } ?: asked?.accountEnd.orEmpty()
    val openPos = echo?.includeOpenPurchaseOrders ?: asked?.includeOpenPurchaseOrders ?: true
    val allFolded = report.accounts.isNotEmpty() && report.accounts.all { it.code in bible.collapsed }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(ZillitTheme.colors.surfaceSunken)
            .padding(horizontal = ZillitTheme.spacing.lg, vertical = ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        FlowRow(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xl),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
        ) {
            Meta("Production:", state.projectName.ifBlank { "—" })
            Meta("Date Range:", "${BibleFormat.shortDate(start, zone)} — ${BibleFormat.shortDate(end, zone)}")
            if (accountStart.isNotBlank()) Meta("Accts:", "$accountStart – ${accountEnd.ifBlank { "end" }}")
            Meta("Open POs:", if (openPos) "Included" else "Excluded")
            Meta("Lines:", report.transactionCount.toString())
        }
        ZillitText(
            text = if (allFolded) "Expand all" else "Collapse all",
            style = ZillitTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
            color = ZillitTheme.colors.accentText,
            modifier = Modifier
                .clip(ZillitTheme.shapes.small)
                .clickable { onEvent(AccountHubEvent.SetAllBibleAccounts(collapsed = !allFolded)) }
                .padding(horizontal = ZillitTheme.spacing.xs, vertical = ZillitTheme.spacing.xxs),
        )
    }
}

@Composable
private fun Meta(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
        ZillitText(
            text = label,
            style = ZillitTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold, fontSize = META_SIZE),
            color = ZillitTheme.colors.textSecondary,
        )
        ZillitText(
            text = value,
            style = ZillitTheme.typography.labelSmall.copy(fontSize = META_SIZE),
            color = ZillitTheme.colors.textMuted,
            maxLines = 1,
        )
    }
}

// -- the grid ----------------------------------------------------------------------

@Composable
private fun ColumnHeadings() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(ZillitTheme.colors.surfaceSunken)
            .padding(start = LINE_START, end = AMOUNT_END, top = HEADING_PAD, bottom = HEADING_PAD),
        horizontalArrangement = Arrangement.spacedBy(CELL_GAP),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Heading("Src", Modifier.width(SRC_WIDTH))
        Heading("Eff Date", Modifier.width(DATE_WIDTH))
        Heading("Invoice No.", Modifier.width(INVOICE_WIDTH))
        Heading("P/O No.", Modifier.width(PO_WIDTH))
        Heading("Vendor / Employee", Modifier.weight(VENDOR_WEIGHT))
        Heading("Description", Modifier.weight(DESCRIPTION_WEIGHT))
        // Two lines rather than a wide column: the web's label is long and the
        // values under it are three letters.
        Heading("Currency (Original)", Modifier.width(CURRENCY_WIDTH), TextAlign.Center, maxLines = 2)
        Heading("Amount", Modifier.width(AMOUNT_WIDTH), TextAlign.End)
    }
}

@Composable
private fun Heading(text: String, modifier: Modifier, align: TextAlign = TextAlign.Start, maxLines: Int = 1) {
    ZillitText(
        text = text.uppercase(),
        style = ZillitTheme.typography.labelSmall.copy(
            fontSize = HEADING_SIZE,
            fontWeight = FontWeight.Bold,
            letterSpacing = HEADING_TRACKING,
        ),
        color = ZillitTheme.colors.textMuted,
        textAlign = align,
        maxLines = maxLines,
        modifier = modifier,
    )
}

/** An account's heading row: chevron, code, name, entry count, and what the account comes to. */
@Composable
private fun AccountHeading(account: BibleAccount, symbol: String, folded: Boolean, onToggle: () -> Unit) {
    val colors = ZillitTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Column(Modifier.fillMaxWidth().background(if (hovered) colors.surfaceHover else colors.surfaceSunken)) {
        Box(Modifier.fillMaxWidth().height(ACCOUNT_RULE).background(colors.border))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .hoverable(interaction)
                .clickable(interactionSource = interaction, indication = null, onClick = onToggle)
                .padding(start = ZillitTheme.spacing.lg, end = AMOUNT_END, top = ROW_PAD_LARGE, bottom = ROW_PAD_LARGE),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            ZillitIcon(
                icon = if (folded) ZillitIcons.ChevronRight else ZillitIcons.ChevronDown,
                contentDescription = if (folded) "Show ${account.title}" else "Hide ${account.title}",
                tint = colors.textSecondary,
                size = CHEVRON,
            )
            // A service bucket has no chart code to print; its name says what it is.
            if (account.displayCode.isNotBlank()) {
                ZillitText(
                    text = account.displayCode,
                    style = ZillitTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = CODE_TRACKING,
                    ),
                    color = colors.accentText,
                    maxLines = 1,
                )
            }
            // The name and its count share what is left, so a long name cuts
            // off before the count does and the total keeps its column.
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            ) {
                ZillitText(
                    text = account.displayName,
                    style = ZillitTheme.typography.bodySmall.copy(
                        fontWeight = FontWeight.SemiBold,
                        fontSize = BODY_SIZE,
                    ),
                    maxLines = 1,
                    modifier = Modifier.weight(1f, fill = false),
                )
                ZillitText(
                    text = account.entriesLabel,
                    style = ZillitTheme.typography.labelSmall,
                    color = colors.textMuted,
                    maxLines = 1,
                )
            }
            Amount(account.total, symbol, bold = true, color = colors.textPrimary)
        }
    }
}

@Composable
private fun TransactionRow(txn: LedgerTransaction, symbol: String, homeCurrency: String, zone: TimeZone) {
    val colors = ZillitTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Column(Modifier.fillMaxWidth().background(if (hovered) colors.surfaceHover else Color.Transparent)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .hoverable(interaction)
                .padding(start = LINE_START, end = AMOUNT_END, top = ROW_PAD, bottom = ROW_PAD),
            horizontalArrangement = Arrangement.spacedBy(CELL_GAP),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.width(SRC_WIDTH)) { SourceTag(txn.source) }
            MonoCell(BibleFormat.shortDate(txn.effectiveDateMillis, zone), Modifier.width(DATE_WIDTH), colors.textMuted)
            MonoCell(txn.invoiceNumber.ifBlank { "—" }, Modifier.width(INVOICE_WIDTH), colors.textSecondary)
            MonoCell(txn.purchaseOrderNumber.ifBlank { "—" }, Modifier.width(PO_WIDTH), colors.textSecondary)
            TextCell(
                text = txn.party,
                weight = VENDOR_WEIGHT,
                color = colors.textPrimary,
                fontWeight = FontWeight.Medium,
            )
            TextCell(
                text = txn.description,
                weight = DESCRIPTION_WEIGHT,
                color = colors.textSecondary,
                fontWeight = FontWeight.Normal,
            )
            ZillitText(
                text = txn.originalCurrency.ifBlank { homeCurrency }.ifBlank { "—" }.uppercase(),
                style = ZillitTheme.typography.labelSmall,
                color = colors.textMuted,
                textAlign = TextAlign.Center,
                maxLines = 1,
                modifier = Modifier.width(CURRENCY_WIDTH),
            )
            Amount(txn.amount, symbol, bold = false, color = if (txn.amount < 0) colors.danger else colors.textPrimary)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.divider))
    }
}

/** "TOTAL 7100 · Camera hire", under an account with more than one line — one line is its own total. */
@Composable
private fun AccountTotal(account: BibleAccount, symbol: String) {
    val colors = ZillitTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surface)
            .padding(start = LINE_START, end = AMOUNT_END, top = ROW_PAD, bottom = ROW_PAD),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitText(
            text = "Total ${account.title}".uppercase(),
            style = ZillitTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.4.sp),
            color = colors.textMuted,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        Amount(account.total, symbol, bold = true, color = colors.textPrimary)
    }
}

/** Pinned under the rows — the web's heavy rule and "GRAND TOTAL". */
@Composable
private fun GrandTotal(total: Double, symbol: String) {
    val colors = ZillitTheme.colors
    Box(Modifier.fillMaxWidth().height(GRAND_RULE).background(colors.textPrimary))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surfaceSunken)
            .padding(start = ZillitTheme.spacing.lg, end = AMOUNT_END)
            .padding(vertical = ZillitTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitText(
            text = "GRAND TOTAL",
            style = ZillitTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = 0.7.sp),
            modifier = Modifier.weight(1f),
        )
        ZillitText(
            text = BibleFormat.money(total, symbol),
            style = ZillitTheme.typography.titleSmall.copy(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
            ),
            color = if (total < 0) colors.danger else colors.textPrimary,
            maxLines = 1,
        )
    }
}

// -- cells ---------------------------------------------------------------------------

/**
 * INV · CRED · PO · CARD · CASH · PR, each in its own hue — the web's `SrcBadge`.
 *
 * The hues are the theme's finance roles rather than raw colours, so the tags
 * read in dark mode too; an unknown source is grey and says what it is.
 */
@Composable
private fun SourceTag(src: String) {
    val colors = ZillitTheme.colors
    val (background, content) = when (SourceBadge.from(src)) {
        SourceBadge.Invoice -> colors.infoSoft to colors.info
        SourceBadge.Credit -> colors.dangerSoft to colors.danger
        SourceBadge.PurchaseOrder -> colors.warningSoft to colors.warning
        SourceBadge.Card -> colors.violetSoft to colors.violet
        SourceBadge.Cash -> colors.tealSoft to colors.teal
        SourceBadge.Payroll -> colors.successSoft to colors.success
        null -> colors.surfaceSunken to colors.textSecondary
    }
    ZillitTooltip(SourceBadge.from(src)?.label.orEmpty()) {
        Box(
            modifier = Modifier
                .clip(ZillitTheme.shapes.small)
                .background(background)
                .border(1.dp, content.copy(alpha = TAG_RING_ALPHA), ZillitTheme.shapes.small)
                .padding(horizontal = 6.dp, vertical = 1.dp),
        ) {
            ZillitText(
                text = SourceBadge.textFor(src),
                style = ZillitTheme.typography.labelSmall.copy(
                    fontSize = TAG_SIZE,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.4.sp,
                ),
                color = content,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun MonoCell(text: String, modifier: Modifier, color: Color) {
    ZillitText(
        text = text,
        style = ZillitTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        color = color,
        maxLines = 1,
        modifier = modifier,
    )
}

/** A text column that truncates, with the whole value a hover away. */
@Composable
private fun RowScope.TextCell(text: String, weight: Float, color: Color, fontWeight: FontWeight) {
    Box(Modifier.weight(weight)) {
        ZillitTooltip(text.takeIf { it.length > TOOLTIP_FROM }.orEmpty()) {
            ZillitText(
                text = text.ifBlank { "—" },
                style = ZillitTheme.typography.bodySmall.copy(fontWeight = fontWeight),
                color = if (text.isBlank()) ZillitTheme.colors.textMuted else color,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun Amount(value: Double, symbol: String, bold: Boolean, color: Color) {
    ZillitText(
        text = BibleFormat.money(value, symbol),
        style = ZillitTheme.typography.numeric.copy(
            fontFamily = FontFamily.Monospace,
            fontSize = BODY_SIZE,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        ),
        color = color,
        textAlign = TextAlign.End,
        maxLines = 1,
        modifier = Modifier.width(AMOUNT_WIDTH),
    )
}

@Composable
private fun Rule() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(ZillitTheme.colors.border))
}

/**
 * Below this the table scrolls sideways instead of squeezing — the web's
 * `min-width: 860px`, widened until the vendor and description columns keep
 * about 290dp between them, the two a reader scans by.
 */
internal val MIN_TABLE_WIDTH: Dp = 980.dp

private val SRC_WIDTH = 56.dp
private val DATE_WIDTH = 72.dp
private val INVOICE_WIDTH = 110.dp
private val PO_WIDTH = 96.dp
private val CURRENCY_WIDTH = 84.dp
private val AMOUNT_WIDTH = 132.dp
private const val VENDOR_WEIGHT = 1.1f
private const val DESCRIPTION_WEIGHT = 1.4f
private val CELL_GAP = 12.dp
private val LINE_START = 32.dp
private val AMOUNT_END = 28.dp
private val ROW_PAD = 7.dp
private val ROW_PAD_LARGE = 9.dp
private val HEADING_PAD = 8.dp
private val ACCOUNT_RULE = 2.dp
private val GRAND_RULE = 2.dp
private val CHEVRON = 14.dp
private val HEADING_SIZE = 10.5.sp
private val HEADING_TRACKING = 0.7.sp
private val CODE_TRACKING = 0.5.sp
private val BODY_SIZE = 12.5.sp
private val META_SIZE = 11.5.sp
private val TAG_SIZE = 10.sp
private const val TAG_RING_ALPHA = 0.3f

/** A vendor or description this long may be cut off; shorter ones never get a tooltip that repeats them. */
private const val TOOLTIP_FROM = 24
