package com.zillit.desktop.feature.accounthub.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitCheckbox
import com.zillit.desktop.core.designsystem.component.ZillitDateField
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitLazyColumn
import com.zillit.desktop.core.designsystem.component.ZillitSkeletonBar
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.accounthub.domain.LedgerMoney
import com.zillit.desktop.feature.accounthub.domain.PeriodMode
import com.zillit.desktop.feature.accounthub.domain.ProjectCurrency
import com.zillit.desktop.feature.accounthub.domain.TrialBalance
import com.zillit.desktop.feature.accounthub.domain.TrialBalanceGroup
import com.zillit.desktop.feature.accounthub.domain.TrialBalancePeriod
import com.zillit.desktop.feature.accounthub.domain.TrialBalanceRow
import com.zillit.desktop.feature.accounthub.ui.AccountHubEvent
import com.zillit.desktop.feature.accounthub.ui.AccountHubUiState
import com.zillit.desktop.feature.accounthub.ui.components.ExportMenu
import com.zillit.desktop.feature.accounthub.ui.components.HubSelect
import com.zillit.desktop.feature.accounthub.ui.trialBalanceCurrencies
import com.zillit.desktop.feature.accounthub.ui.trialBalanceDirty
import com.zillit.desktop.feature.accounthub.ui.trialBalanceDraft
import kotlin.math.floor

/**
 * Account balances over a period — the web's `TrialBalanceModule`, drawn to
 * its `trial-balance.css`.
 *
 * Three bands, top to bottom: the header (back to the hub, "Reports / Trial
 * Balance", the account count, Refresh while a filter differs from what the
 * rows answer, and Export), the full-bleed filter bar, and the ledger —
 * grouped by cost type with a subtotal each, and the grand total pinned beneath
 * it saying whether the two sides balance.
 */
@Composable
fun TrialBalancePage(state: AccountHubUiState, onEvent: (AccountHubEvent) -> Unit, canExport: Boolean = false) {
    Column(modifier = Modifier.fillMaxSize().background(ZillitTheme.colors.canvas)) {
        Header(state, onEvent, canExport)
        FilterBar(state, onEvent)
        Ledger(state, onEvent, Modifier.weight(1f))
    }
}

// -- header ------------------------------------------------------------------------

@Composable
private fun Header(state: AccountHubUiState, onEvent: (AccountHubEvent) -> Unit, canExport: Boolean) {
    val trial = state.trialBalance
    val colors = ZillitTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.canvas)
            .lines(bottom = colors.border)
            .padding(horizontal = HEADER_PAD, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        BackButton(onClick = { onEvent(AccountHubEvent.BackToHub) })
        Crumbs(onRoot = { onEvent(AccountHubEvent.BackToHub) })
        Spacer(Modifier.weight(1f))
        if (trial.showsRows) {
            val count = trial.report.rows.size
            ZillitText(
                text = "$count account${if (count == 1) "" else "s"}",
                style = ZillitTheme.typography.numeric.copy(fontSize = 11.sp),
                color = colors.textMuted,
                maxLines = 1,
            )
        }
        if (state.trialBalanceDirty) {
            ZillitButton(
                text = "Refresh",
                onClick = { onEvent(AccountHubEvent.RefreshTrialBalance) },
                leadingIcon = ZillitIcons.Reload,
                enabled = state.trialBalanceDraft.hasValidPeriod,
            )
        }
        if (canExport) {
            ExportMenu(
                open = trial.exportOpen,
                onOpen = { onEvent(AccountHubEvent.ToggleTrialBalanceExport(it)) },
                exporting = trial.exporting,
                onExport = { onEvent(AccountHubEvent.ExportTrialBalance(it)) },
                enabled = trial.showsRows,
            )
        }
    }
}

/** A bordered tile rather than a bare glyph, as the web draws it, so it reads as the page's way out. */
@Composable
private fun BackButton(onClick: () -> Unit) {
    val colors = ZillitTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(shape)
            .background(if (hovered) colors.surfaceSunken else colors.surface)
            .border(1.dp, if (hovered) colors.borderStrong else colors.border, shape)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClickLabel = BACK_LABEL,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        ZillitIcon(
            icon = ZillitIcons.ArrowLeft,
            contentDescription = BACK_LABEL,
            tint = if (hovered) colors.textPrimary else colors.textSecondary,
            size = 14.dp,
        )
    }
}

/** "REPORTS / Trial Balance" — the first crumb is a way back to the hub, like the arrow beside it. */
@Composable
private fun Crumbs(onRoot: () -> Unit) {
    val colors = ZillitTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ZillitText(
            text = "REPORTS",
            style = ZillitTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = 0.08.em),
            color = colors.accentText,
            maxLines = 1,
            modifier = Modifier
                .clip(ZillitTheme.shapes.small)
                .clickable(onClickLabel = BACK_LABEL, onClick = onRoot)
                .padding(2.dp),
        )
        ZillitText(text = "/", style = ZillitTheme.typography.bodySmall, color = colors.textDisabled)
        ZillitText(
            text = "Trial Balance",
            style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
            maxLines = 1,
        )
    }
}

// -- filter bar --------------------------------------------------------------------

/** Period · Account · Company · Currency · Zero accounts — a draft: nothing runs until Refresh. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterBar(state: AccountHubUiState, onEvent: (AccountHubEvent) -> Unit) {
    val trial = state.trialBalance
    val colors = ZillitTheme.colors
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surface)
            .lines(bottom = colors.border)
            .padding(horizontal = PAD, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        FilterField("Period") { PeriodControls(state, onEvent) }
        FilterField("Account") {
            ZillitTextField(
                value = trial.accountFromText,
                onValueChange = { onEvent(AccountHubEvent.EditTrialBalanceAccounts(it, trial.accountToText)) },
                placeholder = "From",
                modifier = Modifier.width(CODE_INPUT_WIDTH),
            )
            RangeArrow()
            ZillitTextField(
                value = trial.accountToText,
                onValueChange = { onEvent(AccountHubEvent.EditTrialBalanceAccounts(trial.accountFromText, it)) },
                placeholder = "To",
                modifier = Modifier.width(CODE_INPUT_WIDTH),
            )
        }
        FilterField("Company") { CompanySelect(state, onEvent) }
        FilterField("Currency") { CurrencySelect(state, onEvent) }
        FilterField("Zero accounts") {
            ZillitCheckbox(
                checked = trial.includeZeroAccounts,
                onCheckedChange = { onEvent(AccountHubEvent.SetTrialBalanceZeroAccounts(it)) },
                label = "Include Zero Accounts",
            )
        }
    }
}

/** A small uppercase label over its controls — the web's `fgroup`. One row height for all, so they share a line. */
@Composable
private fun FilterField(label: String, content: @Composable RowScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ZillitText(
            text = label.uppercase(),
            style = ZillitTheme.typography.labelSmall.copy(
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.1.em,
            ),
            color = ZillitTheme.colors.textMuted,
            maxLines = 1,
        )
        Row(
            modifier = Modifier.height(CONTROL_ROW),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
            content = content,
        )
    }
}

@Composable
private fun PeriodControls(state: AccountHubUiState, onEvent: (AccountHubEvent) -> Unit) {
    val trial = state.trialBalance
    val colors = ZillitTheme.colors
    PeriodToggle(trial.periodMode) { onEvent(AccountHubEvent.SetTrialBalancePeriodMode(it)) }
    if (trial.periodMode == PeriodMode.Custom) {
        // Held to the other fields' height: the picker's own calendar button
        // would otherwise make these the two tallest controls on the bar.
        ZillitDateField(
            value = trial.fromText,
            onValueChange = { onEvent(AccountHubEvent.EditTrialBalanceDates(it, trial.toText)) },
            modifier = Modifier.width(DATE_WIDTH).height(CONTROL_ROW),
        )
        RangeArrow()
        ZillitDateField(
            value = trial.toText,
            onValueChange = { onEvent(AccountHubEvent.EditTrialBalanceDates(trial.fromText, it)) },
            modifier = Modifier.width(DATE_WIDTH).height(CONTROL_ROW),
        )
        rangeProblem(trial.fromText, trial.toText)?.let { problem ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                ZillitIcon(icon = ZillitIcons.Warning, tint = colors.danger, size = 12.dp)
                ZillitText(
                    text = problem,
                    style = ZillitTheme.typography.labelSmall,
                    color = colors.danger,
                    maxLines = 1,
                )
            }
        }
    } else {
        ZillitText(
            text = state.trialBalanceDraft.periodLabel,
            style = ZillitTheme.typography.numeric.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
            color = colors.textSecondary,
            maxLines = 1,
        )
    }
}

/** Current Period | Date Range — the web's `seg`: joined buttons, the chosen one filled. */
@Composable
private fun PeriodToggle(mode: PeriodMode, onSelect: (PeriodMode) -> Unit) {
    val colors = ZillitTheme.colors
    val shape = RoundedCornerShape(8.dp)
    Row(
        modifier = Modifier
            .height(CONTROL_ROW)
            .clip(shape)
            .border(1.dp, colors.border, shape),
    ) {
        PeriodMode.entries.forEachIndexed { index, option ->
            if (index > 0) Box(Modifier.width(1.dp).fillMaxHeight().background(colors.border))
            val interaction = remember { MutableInteractionSource() }
            val hovered by interaction.collectIsHoveredAsState()
            val active = option == mode
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .background(
                        when {
                            active -> colors.accent
                            hovered -> colors.surfaceSunken
                            else -> colors.surface
                        },
                    )
                    .clickable(interactionSource = interaction, indication = null) { onSelect(option) }
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                ZillitText(
                    text = option.label,
                    style = ZillitTheme.typography.label.copy(fontWeight = FontWeight.SemiBold),
                    color = if (active) colors.textOnAccent else colors.textSecondary,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun RangeArrow() {
    ZillitIcon(icon = ZillitIcons.ArrowRight, tint = ZillitTheme.colors.textDisabled, size = 14.dp)
}

/** "All companies" first, then the production's legal entities — the web's `<select>`. */
@Composable
private fun CompanySelect(state: AccountHubUiState, onEvent: (AccountHubEvent) -> Unit) {
    val options = listOf(Choice("", ALL_COMPANIES)) + state.setup.companies.saved.map {
        Choice(it.id, it.name.ifBlank { it.legalName }.ifBlank { it.id })
    }
    HubSelect(
        value = options.firstOrNull { it.id == state.trialBalance.companyId } ?: options.first(),
        options = options,
        label = { it.label },
        onSelect = { picked -> onEvent(AccountHubEvent.PickTrialBalanceCompany(picked?.id.orEmpty())) },
        searchable = options.size > SEARCHABLE_FROM,
        modifier = Modifier.width(COMPANY_WIDTH),
    )
}

@Composable
private fun CurrencySelect(state: AccountHubUiState, onEvent: (AccountHubEvent) -> Unit) {
    val options = state.trialBalanceCurrencies
    val code = state.trialBalance.currency
    HubSelect(
        // A currency picked earlier stays named even if the production has
        // since dropped it, rather than reading as no choice at all.
        value = options.firstOrNull { it.code == code } ?: code.takeIf { it.isNotBlank() }?.let { ProjectCurrency(it) },
        options = options,
        label = ::currencyLabel,
        onSelect = { picked -> picked?.let { onEvent(AccountHubEvent.PickTrialBalanceCurrency(it.code)) } },
        placeholder = "Base currency",
        enabled = options.isNotEmpty(),
        searchable = options.size > SEARCHABLE_FROM,
        modifier = Modifier.width(CURRENCY_WIDTH),
    )
}

// -- the ledger --------------------------------------------------------------------

@Composable
private fun Ledger(state: AccountHubUiState, onEvent: (AccountHubEvent) -> Unit, modifier: Modifier) {
    val trial = state.trialBalance
    BoxWithConstraints(modifier = modifier.fillMaxWidth().background(ZillitTheme.colors.surface)) {
        val columns = if (maxWidth < NARROW) Columns.Narrow else Columns.Wide
        Column(Modifier.fillMaxSize()) {
            HeadRow(columns)
            when {
                trial.loading -> SkeletonRows(columns)
                trial.failed -> LedgerMessage(
                    title = "Couldn't load trial balance",
                    detail = "Adjust the filters and Refresh.",
                    note = trial.errorMessage,
                    onRetry = { onEvent(AccountHubEvent.RefreshTrialBalance) },
                )
                trial.report.rows.isEmpty() -> LedgerMessage(
                    title = "No account balances",
                    detail = "No data for these filters.",
                )
                else -> {
                    val symbol = currencySymbol(state)
                    LedgerRows(trial.report, columns, symbol, Modifier.weight(1f))
                    TotalRow(trial.report, columns, symbol)
                }
            }
        }
    }
}

@Composable
private fun HeadRow(columns: Columns) {
    val colors = ZillitTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surface)
            .lines(bottom = colors.divider)
            .padding(horizontal = PAD, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HeadText("Code", Modifier.width(columns.code))
        HeadText("Account name", Modifier.weight(1f))
        HeadText("Debit", Modifier.width(columns.money), TextAlign.End)
        HeadText("Credit", Modifier.width(columns.money), TextAlign.End)
        HeadText("Balance", Modifier.width(columns.money), TextAlign.End)
    }
}

@Composable
private fun HeadText(text: String, modifier: Modifier, align: TextAlign = TextAlign.Start) {
    ZillitText(
        text = text.uppercase(),
        style = ZillitTheme.typography.labelSmall.copy(
            fontSize = 10.5.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.09.em,
        ),
        color = ZillitTheme.colors.textMuted,
        textAlign = align,
        maxLines = 1,
        modifier = modifier,
    )
}

/** Lazily, group by group: a production's chart can run to hundreds of posting accounts. */
@Composable
private fun LedgerRows(report: TrialBalance, columns: Columns, symbol: String, modifier: Modifier) {
    val groups = remember(report) { report.groups }
    ZillitLazyColumn(modifier = modifier.fillMaxWidth()) {
        groups.forEach { group ->
            item(key = "head:${group.costType}") { GroupHeading(group) }
            itemsIndexed(
                items = group.rows,
                key = { index, row -> "row:${group.costType}:${row.accountCode}:$index" },
            ) { _, row ->
                AccountLine(row, columns, symbol)
            }
            item(key = "sub:${group.costType}") { SubtotalLine(group, columns, symbol) }
        }
    }
}

@Composable
private fun GroupHeading(group: TrialBalanceGroup) {
    val colors = ZillitTheme.colors
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surfaceHover)
            .lines(top = colors.divider, bottom = colors.divider)
            .padding(horizontal = PAD, vertical = 9.dp),
    ) {
        ZillitText(
            text = group.label.uppercase(),
            style = ZillitTheme.typography.labelSmall.copy(
                fontSize = 11.5.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 0.07.em,
            ),
            color = groupInk(group.costType),
            maxLines = 1,
        )
    }
}

@Composable
private fun AccountLine(row: TrialBalanceRow, columns: Columns, symbol: String) {
    val colors = ZillitTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (hovered) colors.surfaceSunken else colors.surface)
            .hoverable(interaction)
            .lines(bottom = colors.divider)
            .padding(horizontal = PAD, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitText(
            text = row.accountCode.ifBlank { "—" },
            style = ZillitTheme.typography.numeric.copy(fontSize = 12.sp),
            color = colors.textMuted,
            maxLines = 1,
            modifier = Modifier.width(columns.code).padding(end = 8.dp),
        )
        ZillitText(
            text = row.name.ifBlank { "—" },
            style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
            color = if (row.name.isBlank()) colors.textDisabled else colors.textPrimary,
            maxLines = 1,
            modifier = Modifier.weight(1f).padding(end = 12.dp),
        )
        Money(row.debit, symbol, columns.money, dashZero = true)
        Money(row.credit, symbol, columns.money, dashZero = true)
        Money(row.ending, symbol, columns.money, balance = true)
    }
}

@Composable
private fun SubtotalLine(group: TrialBalanceGroup, columns: Columns, symbol: String) {
    val colors = ZillitTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surfaceSunken)
            .lines(top = colors.divider, bottom = colors.border)
            .padding(horizontal = PAD, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitText(
            text = "Subtotal — ${group.label}".uppercase(),
            style = ZillitTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = 0.06.em),
            color = colors.textSecondary,
            maxLines = 1,
            modifier = Modifier.weight(1f).padding(end = 12.dp),
        )
        Money(group.debit, symbol, columns.money, dashZero = true, strong = true)
        Money(group.credit, symbol, columns.money, dashZero = true, strong = true)
        Money(group.balance, symbol, columns.money, balance = true, strong = true)
    }
}

/** The grand total, pinned under the rows with the verdict beside it — green-tinted either way, as the web's is. */
@Composable
private fun TotalRow(report: TrialBalance, columns: Columns, symbol: String) {
    val colors = ZillitTheme.colors
    val ring = colors.success.copy(alpha = RING_ALPHA)
    val shade = colors.windowShadow
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                // A soft shade over the last rows, so the total reads as pinned
                // above them rather than as one more row.
                val fall = SHADE_HEIGHT.toPx()
                drawRect(
                    brush = Brush.verticalGradient(listOf(Color.Transparent, shade), startY = -fall, endY = 0f),
                    topLeft = Offset(0f, -fall),
                    size = Size(size.width, fall),
                )
            }
            .background(colors.surface)
            .background(colors.success.copy(alpha = FOOTER_TINT))
            .lines(top = ring, width = 2.dp)
            .padding(horizontal = PAD, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(1f).padding(end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ZillitText(
                text = "TRIAL BALANCE TOTAL",
                style = ZillitTheme.typography.bodyMedium.copy(
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 0.04.em,
                ),
                color = ink(colors.success),
                maxLines = 1,
            )
            BalancePill(report.isBalanced)
        }
        Money(report.debit, symbol, columns.money, strong = true, size = 13.5.sp)
        Money(report.credit, symbol, columns.money, strong = true, size = 13.5.sp)
        Money(report.balance, symbol, columns.money, balance = true, strong = true, size = 13.5.sp)
    }
}

@Composable
private fun BalancePill(balanced: Boolean) {
    val colors = ZillitTheme.colors
    val tone = if (balanced) colors.success else colors.danger
    val content = ink(tone)
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(if (balanced) colors.successSoft else colors.dangerSoft)
            .border(1.dp, tone.copy(alpha = RING_ALPHA), CircleShape)
            .padding(start = 7.dp, end = 9.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Box(Modifier.size(5.dp).clip(CircleShape).background(content))
        ZillitText(
            text = if (balanced) "BALANCED" else "UNBALANCED",
            style = ZillitTheme.typography.labelSmall.copy(
                fontSize = 10.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 0.07.em,
            ),
            color = content,
            maxLines = 1,
        )
    }
}

/**
 * A figure as the web's `moneyEl` draws it: the currency's symbol first,
 * a negative in brackets and red, a balance of zero or more in green, and —
 * where asked — a quiet dash for a debit or credit of nothing.
 *
 * Never cut short. The columns are fixed, as the web's are, and a figure too
 * wide for one — a total in the billions of yen, a bracketed balance in a
 * narrow window — steps its size down rather than losing digits behind an
 * ellipsis, which in a ledger would print a different number.
 */
@Composable
private fun Money(
    value: Double,
    symbol: String,
    width: Dp,
    dashZero: Boolean = false,
    balance: Boolean = false,
    strong: Boolean = false,
    size: TextUnit = 13.sp,
) {
    val colors = ZillitTheme.colors
    // Judged on the figure as printed, to the penny: a sum of converted
    // decimals lands on -0.0000001 as easily as on zero, and a balanced total
    // must not read "(£0.00)" in red.
    val printedZero = LedgerMoney.pence(value) == 0L
    val dash = dashZero && printedZero
    val text = if (dash) "—" else LedgerMoney.format(value, symbol)
    val style = ZillitTheme.typography.numeric.copy(
        fontSize = size,
        fontWeight = if (strong) FontWeight.Bold else FontWeight.Medium,
    )
    // Measured, not auto-sized: a figure has no break in it, so a line wider
    // than its column never reports overflow — the text just runs into the
    // next column. Its natural width says exactly how far to step down.
    val measurer = rememberTextMeasurer()
    // Less a gutter: the columns sit edge to edge, so a figure that filled its
    // whole cell would read as one number with its neighbour.
    val room = with(LocalDensity.current) { (width - MONEY_GUTTER).roundToPx() }
    val fitted = remember(text, style, room) {
        val natural = measurer.measure(text, style, softWrap = false, maxLines = 1).size.width
        if (natural <= room) size else fittedSize(size, room.toFloat() / natural)
    }
    ZillitText(
        text = text,
        style = style.copy(fontSize = fitted),
        color = when {
            dash -> colors.textDisabled
            value < 0 && !printedZero -> ink(colors.danger)
            balance -> ink(colors.success)
            else -> colors.textPrimary
        },
        textAlign = TextAlign.End,
        maxLines = 1,
        modifier = Modifier.width(width).padding(start = MONEY_GUTTER),
    )
}

/** [size] scaled by [ratio], rounded down to a half point so it stays crisp, and never below the floor. */
private fun fittedSize(size: TextUnit, ratio: Float): TextUnit =
    (floor(size.value * ratio * HALF_POINTS) / HALF_POINTS).coerceAtLeast(SMALLEST_FIGURE.value).sp

@Composable
private fun SkeletonRows(columns: Columns) {
    val colors = ZillitTheme.colors
    Column(Modifier.fillMaxWidth()) {
        repeat(SKELETON_ROWS) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .lines(bottom = colors.divider)
                    .padding(horizontal = PAD, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.width(columns.code)) { ZillitSkeletonBar(Modifier.width(46.dp), height = 11.dp) }
                Box(Modifier.weight(1f)) { ZillitSkeletonBar(Modifier.fillMaxWidth(0.6f), height = 11.dp) }
                repeat(MONEY_COLUMNS) {
                    Box(Modifier.width(columns.money), contentAlignment = Alignment.CenterEnd) {
                        ZillitSkeletonBar(Modifier.width(64.dp), height = 11.dp)
                    }
                }
            }
        }
    }
}

/** The web's `tbmsg`: a quiet tile, what happened, and what to do about it. */
@Composable
private fun LedgerMessage(title: String, detail: String, note: String? = null, onRetry: (() -> Unit)? = null) {
    val colors = ZillitTheme.colors
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 64.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier.size(48.dp).clip(RoundedCornerShape(16.dp)).background(colors.surfaceHover),
            contentAlignment = Alignment.Center,
        ) {
            ZillitIcon(icon = ZillitIcons.File, tint = colors.textDisabled, size = 20.dp)
        }
        ZillitText(text = title, style = ZillitTheme.typography.titleSmall, color = colors.textSecondary)
        ZillitText(
            text = detail,
            style = ZillitTheme.typography.bodySmall,
            color = colors.textMuted,
            textAlign = TextAlign.Center,
        )
        if (!note.isNullOrBlank()) {
            ZillitText(
                text = note,
                style = ZillitTheme.typography.labelSmall,
                color = colors.textDisabled,
                textAlign = TextAlign.Center,
            )
        }
        if (onRetry != null) {
            ZillitButton(
                text = "Try again",
                onClick = onRetry,
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
                leadingIcon = ZillitIcons.Reload,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

// -- small pieces ------------------------------------------------------------------

/**
 * A status hue deepened toward the text colour in the light theme.
 *
 * The theme's green and red are signal colours — bright enough for a dot, too
 * light to read as a figure on white. The web's ledger inks (`--green-ink`,
 * `--rose`) are the darker cut; the dark theme's hues are already lifted.
 */
@Composable
private fun ink(tone: Color): Color {
    val colors = ZillitTheme.colors
    return if (colors.isDark) tone else lerp(tone, colors.textPrimary, INK_DEPTH)
}

/** The web's `glabel` colours: assets and income green, liabilities amber, capital violet, expense blue. */
@Composable
private fun groupInk(costType: String): Color {
    val colors = ZillitTheme.colors
    return when (costType) {
        "asset", "income" -> ink(colors.success)
        "liability" -> colors.accentText
        "capital" -> ink(colors.violet)
        "expense" -> ink(colors.info)
        else -> colors.textSecondary
    }
}

/** Hairlines drawn inside a band, so rows keep their height whatever the borders are. */
private fun Modifier.lines(top: Color? = null, bottom: Color? = null, width: Dp = 1.dp): Modifier = drawBehind {
    val stroke = width.toPx()
    top?.let { drawRect(it, topLeft = Offset.Zero, size = Size(size.width, stroke)) }
    bottom?.let { drawRect(it, topLeft = Offset(0f, size.height - stroke), size = Size(size.width, stroke)) }
}

/** The symbol of the currency the rows are in — the web's `curSym`; blank when it has none. */
private fun currencySymbol(state: AccountHubUiState): String {
    val trial = state.trialBalance
    val code = trial.applied?.currency?.takeIf { it.isNotBlank() }
        ?: trial.currency.ifBlank { state.setup.currencies.saved.defaultCode.orEmpty() }
    return state.trialBalanceCurrencies.firstOrNull { it.code == code }?.symbol.orEmpty()
}

private fun currencyLabel(currency: ProjectCurrency): String =
    if (currency.symbol.isNotBlank()) "${currency.code} (${currency.symbol})" else currency.code

/** Why the Date Range cannot be run as typed, or null when it can. */
private fun rangeProblem(from: String, to: String): String? {
    val start = TrialBalancePeriod.parse(from)
    val end = TrialBalancePeriod.parse(to)
    return when {
        start == null -> "Pick a start date"
        end == null -> "Pick an end date"
        start > end -> "Start is after end"
        else -> null
    }
}

private data class Choice(val id: String, val label: String)

/** The web's `--cols`: 104px · the name · three 168px figures, narrowed when the console is. */
private enum class Columns(val code: Dp, val money: Dp) {
    Wide(104.dp, 168.dp),
    Narrow(84.dp, 128.dp),
}

private const val BACK_LABEL = "Back to Account Hub"
private const val ALL_COMPANIES = "All companies"

/**
 * One inset for every band, so the back arrow, the first filter and the code
 * column share an edge. The web runs its bars at 32px beside no sidebar; here
 * the hub's sidebar takes that room, and 24 keeps the filters on one line at a
 * laptop's width.
 */
private val PAD = 24.dp
private val HEADER_PAD = PAD

/** The height of every control on the filter bar — the web's 33px fields. */
private val CONTROL_ROW = 32.dp
private val DATE_WIDTH = 160.dp
private val CODE_INPUT_WIDTH = 88.dp
private val COMPANY_WIDTH = 176.dp
private val CURRENCY_WIDTH = 132.dp
private val NARROW = 860.dp
private val SHADE_HEIGHT = 10.dp

/** How far a figure may shrink to fit its column; sizes land on half points. */
private val SMALLEST_FIGURE = 9.sp
private const val HALF_POINTS = 2f

/** The clear space a figure keeps from the column before it. */
private val MONEY_GUTTER = 12.dp
private const val SKELETON_ROWS = 7
private const val MONEY_COLUMNS = 3
private const val SEARCHABLE_FROM = 8
private const val INK_DEPTH = 0.22f
private const val FOOTER_TINT = 0.07f
private const val RING_ALPHA = 0.35f
