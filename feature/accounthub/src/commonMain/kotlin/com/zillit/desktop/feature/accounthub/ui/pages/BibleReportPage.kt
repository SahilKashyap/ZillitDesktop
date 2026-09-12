package com.zillit.desktop.feature.accounthub.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.common.Money
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitSegmented
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitTab
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.accounthub.domain.BibleAccountTypes
import com.zillit.desktop.feature.accounthub.domain.BibleFilters
import com.zillit.desktop.feature.accounthub.domain.BibleFormat
import com.zillit.desktop.feature.accounthub.domain.BiblePeriod
import com.zillit.desktop.feature.accounthub.domain.Company
import com.zillit.desktop.feature.accounthub.domain.FilterOption
import com.zillit.desktop.feature.accounthub.domain.LedgerSource
import com.zillit.desktop.feature.accounthub.domain.PeriodMode
import com.zillit.desktop.feature.accounthub.domain.ProjectCurrency
import com.zillit.desktop.feature.accounthub.domain.Vendor
import com.zillit.desktop.feature.accounthub.domain.bibleTaxOptions
import com.zillit.desktop.feature.accounthub.ui.AccountHubEvent
import com.zillit.desktop.feature.accounthub.ui.AccountHubUiState
import com.zillit.desktop.feature.accounthub.ui.HubPage
import com.zillit.desktop.feature.accounthub.ui.components.DateField
import com.zillit.desktop.feature.accounthub.ui.components.ExportMenu
import com.zillit.desktop.feature.accounthub.ui.components.FieldHint
import com.zillit.desktop.feature.accounthub.ui.components.FilterCell
import com.zillit.desktop.feature.accounthub.ui.components.FilterMultiSelect
import com.zillit.desktop.feature.accounthub.ui.components.FilterSelect
import kotlinx.datetime.TimeZone

/**
 * Every posted transaction of a period, under the account it posted to — the
 * web's `BibleReportModule`.
 *
 * The production's closeout bible: what a set of books is checked against line
 * by line, and what an auditor is handed. The page reads top to bottom as the
 * web's does — a header with Run Report and Export, the filter bar, then the
 * report: its banner, one folding section per account, and the grand total
 * pinned under the rows.
 *
 * It runs on Run Report, never on a filter change, and it opens on a card
 * that says so rather than on a report nobody asked for.
 */
@Composable
fun BibleReportPage(state: AccountHubUiState, onEvent: (AccountHubEvent) -> Unit, canExport: Boolean = false) {
    HubPage {
        Header(state, onEvent, canExport)
        FilterBar(state, onEvent)
        Body(state, onEvent)
    }
}

// -- header ----------------------------------------------------------------------

@Composable
private fun Header(state: AccountHubUiState, onEvent: (AccountHubEvent) -> Unit, canExport: Boolean) {
    val bible = state.bible
    val report = bible.report
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        BackChip(onClick = { onEvent(AccountHubEvent.BackToHub) })
        // The one weighted child: in a narrow window the title's hint cuts off
        // and Run Report and Export keep their size, rather than the last
        // button being crushed into a column of letters.
        Title(onEvent, Modifier.weight(1f))
        if (report != null) {
            val accounts = report.accounts.size
            val generated = report.generatedAtMillis
                ?.let { " · Generated ${BibleFormat.shortDate(it, TimeZone.currentSystemDefault())}" }
                .orEmpty()
            ZillitText(
                text = "$accounts account${if (accounts == 1) "" else "s"}$generated",
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textMuted,
                maxLines = 1,
            )
        }
        ZillitButton(
            text = if (bible.loading) "Running…" else "Run Report",
            onClick = { onEvent(AccountHubEvent.RunBibleReport) },
            leadingIcon = ZillitIcons.Search,
            loading = bible.loading,
            enabled = canRun(state),
        )
        if (canExport) {
            ExportMenu(
                open = bible.exportOpen,
                onOpen = { onEvent(AccountHubEvent.ToggleBibleExport(it)) },
                exporting = bible.exporting,
                onExport = { onEvent(AccountHubEvent.ExportBible(it)) },
                enabled = !bible.loading && report?.accounts?.isNotEmpty() == true && bible.exporting == null,
            )
        }
    }
}

/** "REPORTS / Bible Report" over a one-line hint; the crumb goes back to the hub, as the web's does. */
@Composable
private fun Title(onEvent: (AccountHubEvent) -> Unit, modifier: Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            ZillitText(
                text = "REPORTS",
                style = ZillitTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = CRUMB_TRACKING,
                ),
                color = ZillitTheme.colors.accentText,
                modifier = Modifier
                    .clip(ZillitTheme.shapes.small)
                    .clickable { onEvent(AccountHubEvent.BackToHub) }
                    .padding(vertical = 1.dp),
            )
            ZillitText(text = "/", style = ZillitTheme.typography.bodySmall, color = ZillitTheme.colors.textMuted)
            ZillitText(text = "Bible Report", style = ZillitTheme.typography.titleSmall, maxLines = 1)
        }
        ZillitText(
            text = "Every posted transaction, grouped by the account it posted to.",
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textMuted,
            maxLines = 1,
        )
    }
}

/**
 * Whether Run Report does anything.
 *
 * Not while a run is out, not while a Date Range is half-typed or backwards,
 * and not in Current Period until the close boundary has been read — the
 * period starts on it.
 */
internal fun canRun(state: AccountHubUiState): Boolean {
    val bible = state.bible
    if (bible.loading) return false
    return when (bible.filters.periodMode) {
        PeriodMode.Current -> !bible.lockLoading
        PeriodMode.Custom -> BiblePeriod.custom(bible.filters.fromDate, bible.filters.toDate, TimeZone.UTC) != null
    }
}

/** The web's back chip: a bordered square with an arrow. */
@Composable
private fun BackChip(onClick: () -> Unit) {
    val colors = ZillitTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Box(
        modifier = Modifier
            .size(BACK_CHIP)
            .clip(RoundedCornerShape(BACK_RADIUS))
            .background(if (hovered) colors.surfaceHover else colors.surface)
            .border(1.dp, colors.border, RoundedCornerShape(BACK_RADIUS))
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        ZillitIcon(icon = ZillitIcons.ArrowLeft, contentDescription = "Back to Account Hub", size = 16.dp)
    }
}

// -- filters -----------------------------------------------------------------------

/** Period · Account · Account Type · Source · Company · Vendor · Tax · Tags · Currency · Open POs. */
@Suppress("LongMethod") // One filter bar, read left to right as the web lays it out.
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterBar(state: AccountHubUiState, onEvent: (AccountHubEvent) -> Unit) {
    val bible = state.bible
    val filters = bible.filters
    val colors = ZillitTheme.colors
    fun edit(next: BibleFilters) = onEvent(AccountHubEvent.EditBibleFilters(next))

    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.large)
            .background(colors.surface)
            .border(1.dp, colors.border, ZillitTheme.shapes.large)
            .padding(horizontal = ZillitTheme.spacing.lg, vertical = ZillitTheme.spacing.md),
        horizontalArrangement = Arrangement.spacedBy(FILTER_GAP),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        FilterCell("Period") {
            ZillitSegmented(
                options = PeriodMode.entries.map { ZillitTab(it.name, it.label) },
                activeId = filters.periodMode.name,
                onSelect = { name ->
                    PeriodMode.entries.firstOrNull { it.name == name }?.let { edit(filters.copy(periodMode = it)) }
                },
            )
            if (filters.periodMode == PeriodMode.Custom) {
                DateField(
                    value = filters.fromDate,
                    onValueChange = { edit(filters.copy(fromDate = it)) },
                    max = filters.toDate.takeIf { BiblePeriod.parse(it) != null },
                    modifier = Modifier.width(DATE_WIDTH),
                )
                RangeArrow()
                DateField(
                    value = filters.toDate,
                    onValueChange = { edit(filters.copy(toDate = it)) },
                    min = filters.fromDate.takeIf { BiblePeriod.parse(it) != null },
                    modifier = Modifier.width(DATE_WIDTH),
                )
            } else {
                CurrentPeriodLabel(state)
            }
        }
        FilterCell("Account") {
            ZillitTextField(
                value = filters.accountStart,
                onValueChange = { edit(filters.copy(accountStart = it)) },
                placeholder = "From",
                modifier = Modifier.width(CODE_WIDTH),
            )
            RangeArrow()
            ZillitTextField(
                value = filters.accountEnd,
                onValueChange = { edit(filters.copy(accountEnd = it)) },
                placeholder = "To",
                modifier = Modifier.width(CODE_WIDTH),
            )
        }
        FilterCell("Account Type") {
            FilterMultiSelect(
                selected = filters.accountTypes,
                options = BibleAccountTypes,
                key = FilterOption::value,
                label = FilterOption::label,
                onChange = { edit(filters.copy(accountTypes = it)) },
                placeholder = "All types",
                modifier = Modifier.width(SELECT_WIDTH),
            )
        }
        FilterCell("Source") {
            FilterMultiSelect(
                selected = filters.sources,
                options = LedgerSource.entries,
                key = LedgerSource::wire,
                label = LedgerSource::label,
                onChange = { edit(filters.copy(sources = it)) },
                placeholder = "All sources",
                modifier = Modifier.width(SELECT_WIDTH),
            )
        }
        FilterCell("Company") {
            FilterMultiSelect<Company>(
                selected = filters.companyIds,
                options = state.setup.companies.saved,
                key = { it.id },
                label = { it.name.ifBlank { it.legalName } },
                onChange = { edit(filters.copy(companyIds = it)) },
                placeholder = "All companies",
                modifier = Modifier.width(SELECT_WIDTH),
            )
        }
        FilterCell("Vendor") {
            val vendors = bible.vendors
            FilterSelect<Vendor>(
                value = vendors.firstOrNull { it.id == filters.vendorId },
                options = vendors,
                key = { it.id },
                label = { it.display },
                onSelect = { edit(filters.copy(vendorId = it?.id.orEmpty())) },
                placeholder = "All vendors",
                searchText = { "${it.display} ${vendorSubline(it)}" },
                popupWidth = VENDOR_POPUP_WIDTH,
                rowHeight = VENDOR_ROW_HEIGHT,
                row = { VendorOption(it) },
                modifier = Modifier.width(SELECT_WIDTH),
            )
        }
        FilterCell("Tax") {
            FilterMultiSelect(
                selected = filters.taxes,
                options = bibleTaxOptions(state.setup.taxTypes.saved),
                key = FilterOption::value,
                label = FilterOption::label,
                onChange = { edit(filters.copy(taxes = it)) },
                placeholder = "All tax types",
                modifier = Modifier.width(SELECT_WIDTH),
            )
        }
        FilterCell("Tags") {
            FilterMultiSelect<String>(
                selected = filters.tags,
                options = state.setup.assetTags.saved,
                key = { it },
                label = { it },
                onChange = { edit(filters.copy(tags = it)) },
                placeholder = "All tags",
                modifier = Modifier.width(SELECT_WIDTH),
            )
        }
        FilterCell("Currency") {
            val currencies = bibleCurrencies(state)
            FilterSelect<ProjectCurrency>(
                value = currencies.firstOrNull { it.code == filters.currency },
                options = currencies,
                key = { it.code },
                label = { if (it.symbol.isNotBlank()) "${it.code} (${it.symbol})" else it.code },
                onSelect = { picked -> picked?.let { edit(filters.copy(currency = it.code)) } },
                placeholder = "Currency",
                clearable = false,
                searchText = { "${it.code} ${it.name}" },
                modifier = Modifier.width(CURRENCY_WIDTH),
            )
        }
        FilterCell("Open POs") {
            OpenPosToggle(
                included = filters.includeOpenPurchaseOrders,
                onToggle = { edit(filters.copy(includeOpenPurchaseOrders = !filters.includeOpenPurchaseOrders)) },
            )
        }
    }
}

/** "6 Sep 2026 – 12 Sep 2026", or "Till 12 Sep 2026", with a quiet spinner while the boundary is read. */
@Composable
private fun CurrentPeriodLabel(state: AccountHubUiState) {
    val bible = state.bible
    val today = BiblePeriod.parse(bible.today)
    Row(
        modifier = Modifier.height(com.zillit.desktop.core.designsystem.ZillitDimens.controlHeight),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        ZillitText(
            text = today?.let { BiblePeriod.label(bible.filters, state.periodClose.lock.lockedThrough, it) } ?: "—",
            style = ZillitTheme.typography.bodySmall.copy(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
            ),
            color = ZillitTheme.colors.textSecondary,
            maxLines = 1,
        )
        if (bible.lockLoading) ZillitSpinner(size = 12.dp)
    }
}

/** The web's Included / Excluded chip: warm when commitments are in the report, quiet when not. */
@Composable
private fun OpenPosToggle(included: Boolean, onToggle: () -> Unit) {
    val colors = ZillitTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        modifier = Modifier
            .height(com.zillit.desktop.core.designsystem.ZillitDimens.controlHeight)
            .clip(ZillitTheme.shapes.medium)
            .background(
                when {
                    included -> colors.warningSoft
                    hovered -> colors.surfaceHover
                    else -> colors.surface
                },
            )
            .border(
                1.dp,
                if (included) colors.warning.copy(alpha = TOGGLE_BORDER_ALPHA) else colors.border,
                ZillitTheme.shapes.medium,
            )
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onToggle)
            .padding(horizontal = ZillitTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        Box(
            Modifier
                .size(TOGGLE_DOT)
                .clip(CircleShape)
                .background(if (included) colors.warning else colors.textMuted),
        )
        ZillitText(
            text = if (included) "Included" else "Excluded",
            style = ZillitTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold, fontSize = 12.sp),
            color = if (included) colors.warning else colors.textSecondary,
        )
    }
}

/** A vendor as the web's pickers draw one: a monogram, the name with its verified state, then who to call. */
@Composable
private fun VendorOption(vendor: Vendor) {
    val colors = ZillitTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        Box(
            modifier = Modifier.size(MONOGRAM).clip(RoundedCornerShape(MONOGRAM_RADIUS)).background(colors.accent),
            contentAlignment = Alignment.Center,
        ) {
            ZillitText(
                text = vendor.display.take(2).uppercase(),
                style = ZillitTheme.typography.labelSmall.copy(fontWeight = FontWeight.ExtraBold),
                color = colors.textOnAccent,
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
            ) {
                ZillitText(
                    text = vendor.display,
                    style = ZillitTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                    maxLines = 1,
                    modifier = Modifier.weight(1f, fill = false),
                )
                VerifiedMark(vendor.verified)
            }
            vendorSubline(vendor).takeIf { it.isNotBlank() }?.let {
                ZillitText(
                    text = it,
                    style = ZillitTheme.typography.labelSmall,
                    color = colors.textMuted,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun VerifiedMark(verified: Boolean) {
    val colors = ZillitTheme.colors
    val tint = if (verified) colors.info else colors.danger
    Box(
        modifier = Modifier
            .clip(ZillitTheme.shapes.pill)
            .background(if (verified) colors.infoSoft else colors.dangerSoft)
            .padding(horizontal = 6.dp, vertical = 1.dp),
    ) {
        ZillitText(
            text = if (verified) "VERIFIED" else "NON-VERIFIED",
            style = ZillitTheme.typography.labelSmall.copy(
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.8.sp,
            ),
            color = tint,
            maxLines = 1,
        )
    }
}

/** Contact · email · phone · address — the web's `vendorSubline`, blank parts dropped. */
internal fun vendorSubline(vendor: Vendor): String =
    listOf(vendor.contactPerson, vendor.email, vendor.phone?.display.orEmpty(), vendor.address.oneLine)
        .filter { it.isNotBlank() }
        .joinToString(" · ")

/**
 * The Currency filter's choices — the web's `buildCurrencyOptions`.
 *
 * The production's currencies with its default first, or the whole catalogue
 * when it has picked none, so the picker is never empty. A code picked here
 * that has since left the list is kept at the top, so the bar never shows a
 * blank for a currency the report is still being asked in.
 */
internal fun bibleCurrencies(state: AccountHubUiState): List<ProjectCurrency> {
    val settings = state.setup.currencies.saved
    val catalogue = state.setup.currencyCatalogue
    fun enrich(code: String): ProjectCurrency =
        settings.currencies.firstOrNull { it.code == code }?.takeIf { it.symbol.isNotBlank() }
            ?: catalogue.firstOrNull { it.code == code }
            ?: ProjectCurrency(code, symbol = Money.symbol(code).trim().takeIf { it != code }.orEmpty())
    val defaultCode = settings.defaultCode.orEmpty()
    val codes = settings.currencies.map { it.code }.filter { it.isNotBlank() }
    val base = when {
        codes.isEmpty() && defaultCode.isBlank() -> catalogue
        else -> (listOf(defaultCode).filter { it.isNotBlank() } + codes).distinct().map(::enrich)
    }
    val picked = state.bible.filters.currency
    return if (picked.isBlank() || base.any { it.code == picked }) base else listOf(enrich(picked)) + base
}

/**
 * The display currency's symbol, prefixed onto every amount — nothing when
 * neither the production nor the symbol table knows one, as the web prints it.
 */
internal fun bibleSymbol(state: AccountHubUiState, code: String): String {
    val resolved = code
        .ifBlank { state.bible.filters.currency }
        .ifBlank { state.setup.currencies.saved.defaultCode.orEmpty() }
    if (resolved.isBlank()) return ""
    return bibleCurrencies(state).firstOrNull { it.code == resolved }?.symbol?.takeIf { it.isNotBlank() }
        ?: Money.symbol(resolved).takeIf { it.trim() != resolved }.orEmpty()
}

@Composable
private fun RangeArrow() {
    ZillitText(text = "→", style = ZillitTheme.typography.bodySmall, color = ZillitTheme.colors.textMuted)
}

// -- body ----------------------------------------------------------------------------

@Composable
private fun ColumnScope.Body(state: AccountHubUiState, onEvent: (AccountHubEvent) -> Unit) {
    val bible = state.bible
    val report = bible.report
    Column(
        modifier = Modifier.fillMaxWidth().weight(1f),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        if (bible.loading) {
            Loading()
            return@Column
        }
        bible.error?.let { message ->
            ZillitNotice(text = message, tone = StatusTone.Rejected, icon = ZillitIcons.Warning)
        }
        report?.errors?.takeIf { it.isNotEmpty() }?.let { BucketErrors(it) }
        when {
            report == null -> if (bible.error == null) PreRunCard()
            report.accounts.isEmpty() -> if (bible.error == null) NoResults()
            else -> BibleReportTable(state, onEvent)
        }
    }
}

@Composable
private fun ColumnScope.Loading() {
    Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        ) {
            ZillitSpinner(size = 28.dp)
            FieldHint("Aggregating transactions…")
        }
    }
}

/**
 * A bucket that failed to read is said out loud. This report is what a
 * production closes its books against, and a total that quietly omits payroll
 * is worse than one that admits payroll is missing.
 */
@Composable
private fun BucketErrors(errors: Map<String, String>) {
    ZillitNotice(
        text = "Some sources failed: " +
            errors.entries.joinToString(" · ") { (bucket, message) -> "${LedgerSource.labelFor(bucket)}: $message" },
        tone = StatusTone.Pending,
        icon = ZillitIcons.Warning,
    )
}

@Composable
private fun ColumnScope.PreRunCard() {
    val colors = ZillitTheme.colors
    Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier
                .width(PRE_RUN_WIDTH)
                .clip(RoundedCornerShape(PRE_RUN_RADIUS))
                .background(colors.surface)
                .border(1.dp, colors.border, RoundedCornerShape(PRE_RUN_RADIUS))
                .padding(horizontal = ZillitTheme.spacing.xxl, vertical = PRE_RUN_VERTICAL),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            Box(
                modifier = Modifier
                    .size(PRE_RUN_ICON_WELL)
                    .clip(CircleShape)
                    .background(colors.accentSoft)
                    .border(1.dp, colors.accent.copy(alpha = WELL_BORDER_ALPHA), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                ZillitIcon(icon = ZillitIcons.File, tint = colors.accentText, size = 22.dp)
            }
            ZillitText(
                text = "Set filters and run the report",
                style = ZillitTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold, fontSize = 15.sp),
                modifier = Modifier.padding(top = ZillitTheme.spacing.sm),
            )
            ZillitText(
                text = "Every PO, invoice, card receipt, cash claim and payroll line grouped by account code — " +
                    "the production closeout Bible.",
                style = ZillitTheme.typography.bodySmall,
                color = colors.textSecondary,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun ColumnScope.NoResults() {
    Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            ZillitIcon(icon = ZillitIcons.Search, tint = ZillitTheme.colors.textMuted, size = 22.dp)
            FieldHint("No transactions found for the selected filters.")
        }
    }
}

private val BACK_CHIP = 36.dp
private val BACK_RADIUS = 10.dp
private val CRUMB_TRACKING = 0.9.sp
private val FILTER_GAP = 20.dp
private val CODE_WIDTH = 96.dp
private val DATE_WIDTH = 150.dp
private val SELECT_WIDTH = 176.dp
private val CURRENCY_WIDTH = 132.dp
private val VENDOR_POPUP_WIDTH = 400.dp
private val VENDOR_ROW_HEIGHT = 52.dp
private val MONOGRAM = 32.dp
private val MONOGRAM_RADIUS = 9.dp
private val TOGGLE_DOT = 6.dp
private const val TOGGLE_BORDER_ALPHA = 0.45f
private val PRE_RUN_WIDTH = 380.dp
private val PRE_RUN_RADIUS = 16.dp
private val PRE_RUN_VERTICAL = 40.dp
private val PRE_RUN_ICON_WELL = 48.dp
private const val WELL_BORDER_ALPHA = 0.35f
