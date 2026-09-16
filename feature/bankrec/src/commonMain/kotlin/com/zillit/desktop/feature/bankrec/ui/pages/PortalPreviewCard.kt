package com.zillit.desktop.feature.bankrec.ui.pages

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitDivider
import com.zillit.desktop.core.designsystem.component.ZillitSkeletonBar
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.rememberAvatar
import com.zillit.desktop.core.designsystem.component.ZillitTooltip
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.bankrec.domain.BankRecFormat
import com.zillit.desktop.feature.bankrec.domain.BankRecPerson
import com.zillit.desktop.feature.bankrec.domain.ExceptionStatus
import com.zillit.desktop.feature.bankrec.domain.FraudStatus
import com.zillit.desktop.feature.bankrec.domain.PeriodStatus
import com.zillit.desktop.feature.bankrec.domain.PortalPermission
import com.zillit.desktop.feature.bankrec.domain.PortalPreview
import com.zillit.desktop.feature.bankrec.domain.WorkspaceRows
import com.zillit.desktop.feature.bankrec.ui.BankRecUiState
import com.zillit.desktop.feature.bankrec.ui.LocalBankRecPeople
import com.zillit.desktop.feature.bankrec.ui.components.BankRecIcons
import com.zillit.desktop.feature.bankrec.ui.components.BrAlign
import com.zillit.desktop.feature.bankrec.ui.components.BrBadge
import com.zillit.desktop.feature.bankrec.ui.components.BrColumn
import com.zillit.desktop.feature.bankrec.ui.components.BrSegmentBar
import com.zillit.desktop.feature.bankrec.ui.components.BrTable
import com.zillit.desktop.feature.bankrec.ui.components.BrTone
import com.zillit.desktop.feature.bankrec.ui.components.bg
import com.zillit.desktop.feature.bankrec.ui.components.eyebrow
import com.zillit.desktop.feature.bankrec.ui.components.fg
import com.zillit.desktop.feature.bankrec.ui.components.mono
import com.zillit.desktop.feature.bankrec.ui.components.titleStyle
import com.zillit.desktop.feature.bankrec.ui.signer

/**
 * The read-only summary a shared link shows — the web's `PortalPreview`.
 *
 * Drawn for the accountant twice: above the links on the Guarantor Portal tab,
 * so what is shared is seen before it is sent, and inside a period's detail,
 * where [beforeNotes] replaces the transaction section with the fuller tables.
 *
 * A section the link's permissions leave out is drawn blurred behind a lock
 * rather than removed, as the web draws it: the recipient should see that a
 * part exists and was not shared, not wonder whether the page is broken. The
 * accountant's own preview names no link and so shows every section.
 */
@Composable
internal fun PortalPreviewCard(
    preview: PortalPreview?,
    loading: Boolean,
    state: BankRecUiState,
    modifier: Modifier = Modifier,
    /** Square and borderless, for a dialog that is already the frame. */
    flush: Boolean = false,
    beforeNotes: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val colors = ZillitTheme.colors
    val shape = if (flush) RoundedCornerShape(0.dp) else ZillitTheme.shapes.large
    Column(
        modifier.fillMaxWidth().clip(shape).background(colors.surface)
            .then(if (flush) Modifier else Modifier.border(1.dp, colors.border, shape)),
    ) {
        when {
            loading -> PreviewSkeleton()
            preview == null -> ZillitText(
                "No period data available. Import a statement to see the preview.",
                style = ZillitTheme.typography.bodySmall,
                color = colors.textMuted,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(vertical = 56.dp),
            )

            else -> PreviewBody(preview, state, beforeNotes)
        }
    }
}

@Composable
private fun ColumnScope.PreviewBody(
    preview: PortalPreview,
    state: BankRecUiState,
    beforeNotes: (@Composable ColumnScope.() -> Unit)?,
) {
    val period = preview.period
    val accountCurrency = preview.bankAccountCurrency.ifBlank { state.currencyOf(period) }
    val projectName = period.projectName.ifBlank { preview.projectName }
        .ifBlank { state.lookups.company.projectName }
    val code = state.projectCurrency

    HeaderBand(projectName, BankRecFormat.fullPeriodLabel(period), period.status == PeriodStatus.Complete)
    DetailsGrid(preview, state, projectName)
    ZillitDivider()
    Permitted(preview.allows(PortalPermission.Balances), locked = { LockedBalances(code) }) {
        Balances(preview, state, accountCurrency)
    }
    ZillitDivider()
    when {
        !preview.allows(PortalPermission.ReconciliationStatus) -> {
            LockedSection { LockedRecStatus() }
            ZillitDivider()
        }

        period.totalTxns > 0 -> {
            RecStatus(preview)
            ZillitDivider()
        }
    }
    SplitRow(
        left = {
            Permitted(preview.allows(PortalPermission.Exceptions), locked = { LockedExceptions(code) }) {
                ExceptionsNoted(preview, state, accountCurrency)
            }
        },
        right = {
            Permitted(preview.allows(PortalPermission.FraudAlerts), locked = { LockedFraud() }) {
                FraudAlerts(preview)
            }
        },
    )
    ZillitDivider()
    Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp)) {
        Permitted(preview.allows(PortalPermission.FxVariance), locked = { LockedFx(code) }) {
            ForeignPayments(preview, code)
        }
    }
    ZillitDivider()
    if (beforeNotes != null) {
        beforeNotes()
    } else {
        Permitted(preview.allows(PortalPermission.TransactionDetail), locked = { LockedTransactions(code) }) {
            TransactionDetail(preview, state, accountCurrency)
        }
        ZillitDivider()
    }
    if (period.signOffNotes.isNotBlank()) SignOffNote(preview)
}

// -- header and details --------------------------------------------------------

/**
 * The branded band: the web's navy in light and its orange in dark. Both carry
 * light text, which is why neither is simply the accent role.
 */
@Composable
private fun HeaderBand(projectName: String, periodLabel: String, signedOff: Boolean) {
    val colors = ZillitTheme.colors
    val ink = colors.titleBarText
    Row(
        Modifier.fillMaxWidth().background(if (colors.isDark) colors.accent else colors.secondary)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ZillitText("ZILLIT", style = titleStyle(14.sp).copy(letterSpacing = 0.08.em), color = ink)
                ZillitText("|", style = ZillitTheme.typography.labelSmall, color = ink.copy(alpha = 0.35f))
                ZillitText(
                    "Bank Reconciliation Summary",
                    style = ZillitTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                    color = ink.copy(alpha = 0.9f),
                )
            }
            ZillitText(
                listOf(projectName, periodLabel).filter { it.isNotBlank() }.joinToString(" · "),
                style = ZillitTheme.typography.bodySmall,
                color = ink.copy(alpha = 0.72f),
                maxLines = 1,
            )
        }
        BandChip("READ-ONLY", ink.copy(alpha = 0.75f), ink.copy(alpha = 0.1f), RoundedCornerShape(4.dp))
        if (signedOff) {
            BandChip("SIGNED OFF ✓", ink, ink.copy(alpha = 0.2f), CircleShape)
        } else {
            BandChip("AWAITING SIGN-OFF", colors.warning, colors.warning.copy(alpha = 0.18f), CircleShape)
        }
    }
}

@Composable
private fun BandChip(text: String, ink: Color, fill: Color, shape: Shape) {
    ZillitText(
        text,
        style = mono(10.5.sp, FontWeight.Bold).copy(letterSpacing = 0.08.em),
        color = ink,
        maxLines = 1,
        modifier = Modifier.clip(shape).background(fill).border(1.dp, ink.copy(alpha = 0.3f), shape)
            .padding(horizontal = 10.dp, vertical = 3.dp),
    )
}

/**
 * Production, account, period and preparer.
 *
 * No sort code and no account number: a recipient needs to know which account
 * was reconciled, not its identifiers — and no permission could switch them off.
 */
@Composable
private fun DetailsGrid(preview: PortalPreview, state: BankRecUiState, projectName: String) {
    val period = preview.period
    val people = LocalBankRecPeople.current
    val preparer = people.me() ?: people.signer(period)
    val range = if (period.openingDateMillis != null && period.closingDateMillis != null) {
        "${BankRecFormat.day(period.openingDateMillis)} – ${BankRecFormat.day(period.closingDateMillis)}"
    } else {
        "${period.totalTxns} transactions"
    }
    val accountName = preview.bankAccountName.ifBlank { state.account(period.bankAccountId)?.displayName.orEmpty() }
    CellRow(
        listOf(
            Triple("Production", projectName.ifBlank { BankRecFormat.DASH }, state.lookups.company.companyName),
            Triple(
                "Bank Account",
                accountName.ifBlank { BankRecFormat.DASH },
                preview.bankAccountHolder.ifBlank { preview.bankAccountCurrency }.ifBlank { BankRecFormat.DASH },
            ),
            Triple("Period", BankRecFormat.fullPeriodLabel(period), range),
            Triple(
                "Prepared by",
                preparer?.name?.ifBlank { null } ?: BankRecFormat.DASH,
                designationLabel(preparer?.designation.orEmpty()).ifBlank { "Production Accountant" },
            ),
        ),
    )
}

/** Labelled figures side by side, ruled between — text only, so the row may size to its tallest. */
@Composable
private fun CellRow(cells: List<Triple<String, String, String>>) {
    val colors = ZillitTheme.colors
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        cells.forEachIndexed { index, (label, value, sub) ->
            if (index > 0) VerticalRule()
            Column(
                Modifier.weight(1f).padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                ZillitText(label.uppercase(), style = eyebrow(10.sp), color = colors.textMuted, maxLines = 1)
                ZillitText(
                    value,
                    style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                )
                if (sub.isNotBlank()) {
                    ZillitText(sub, style = ZillitTheme.typography.bodySmall, color = colors.textMuted, maxLines = 1)
                }
            }
        }
    }
}

@Composable
internal fun VerticalRule() {
    Box(Modifier.width(1.dp).fillMaxHeight().background(ZillitTheme.colors.border))
}

/**
 * Two halves ruled down the middle.
 *
 * Not sized with intrinsics: a half can hold a table or a tooltip, and neither
 * answers an intrinsic measurement — so each half draws its own edge instead.
 */
@Composable
internal fun SplitRow(
    left: @Composable () -> Unit,
    right: @Composable () -> Unit,
    leftWeight: Float = 0.5f,
    padded: Boolean = true,
) {
    val border = ZillitTheme.colors.border
    val inset = if (padded) Modifier.padding(horizontal = 20.dp, vertical = 16.dp) else Modifier
    Row(Modifier.fillMaxWidth()) {
        Box(Modifier.weight(leftWeight).edge(border, end = true).then(inset)) { left() }
        Box(Modifier.weight(1f - leftWeight).edge(border, end = false).then(inset)) { right() }
    }
}

// -- balances and status ---------------------------------------------------------

@Composable
private fun Balances(preview: PortalPreview, state: BankRecUiState, accountCurrency: String) {
    val colors = ZillitTheme.colors
    val period = preview.period
    val kpi = state.kpiFor(period)
    val difference = kpi.difference
    val differenceSub = when {
        difference == null -> "No ${kpi.nativeCurrency ?: accountCurrency} rate in Project Currencies"
        kpi.isReconciled -> "Fully reconciled"
        else -> "${period.unmatchedCount} items pending resolution"
    }
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        BalanceCell(
            "Opening Bank Balance",
            BankRecFormat.money(period.openingBank, accountCurrency),
            BankRecFormat.day(period.openingDateMillis),
        )
        VerticalRule()
        BalanceCell(
            "Closing Bank Balance",
            BankRecFormat.money(period.closingBank, accountCurrency),
            BankRecFormat.day(period.closingDateMillis),
        )
        VerticalRule()
        BalanceCell(
            "Unreconciled Difference",
            difference?.let { BankRecFormat.money(it, kpi.currency) } ?: BankRecFormat.DASH,
            differenceSub,
            valueColor = when {
                difference == null -> colors.warning
                kpi.isReconciled -> colors.success
                else -> colors.danger
            },
        )
    }
}

@Composable
internal fun RowScope.BalanceCell(label: String, value: String, sub: String, valueColor: Color? = null) {
    val colors = ZillitTheme.colors
    Column(
        Modifier.weight(1f).padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        ZillitText(label.uppercase(), style = eyebrow(10.sp), color = colors.textMuted, maxLines = 1)
        ZillitText(value, style = mono(18.sp, FontWeight.Bold), color = valueColor ?: colors.textPrimary, maxLines = 1)
        ZillitText(sub, style = ZillitTheme.typography.bodySmall, color = colors.textMuted, maxLines = 1)
    }
}

@Composable
private fun RecStatus(preview: PortalPreview) {
    val colors = ZillitTheme.colors
    val period = preview.period
    val total = period.totalTxns.coerceAtLeast(1).toFloat()
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SectionTitle(
            "Reconciliation Status — ${period.totalTxns} Bank Transactions",
            ZillitIcons.BarChart,
            colors.textSecondary,
            uppercase = true,
        )
        BrSegmentBar(
            segments = listOf(
                period.matchedCount / total to colors.success,
                period.suggestedCount / total to colors.warning,
                period.unmatchedCount / total to colors.danger,
                period.fraudCount / total to colors.danger.copy(alpha = FRAUD_ALPHA),
            ),
            gap = 4.dp,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatusTile(period.matchedCount, "Matched", BrTone.Green)
            StatusTile(period.suggestedCount, "Suggested match", BrTone.Amber)
            StatusTile(period.unmatchedCount, "No Zillit entry", BrTone.Red)
            StatusTile(period.fraudCount, "Fraud — under review", BrTone.Red)
        }
    }
}

@Composable
internal fun RowScope.StatusTile(count: Int?, label: String, tone: BrTone) {
    val colors = ZillitTheme.colors
    Column(
        Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(tone.bg())
            .padding(vertical = 10.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ZillitText(
            count?.toString() ?: "--",
            style = mono(18.sp, FontWeight.Bold),
            color = if (count == null) colors.textMuted else tone.fg(),
        )
        ZillitText(
            label,
            style = ZillitTheme.typography.labelSmall.copy(fontSize = 10.sp),
            color = colors.textMuted,
            maxLines = 1,
        )
    }
}

// -- exceptions, fraud, FX -----------------------------------------------------------

@Composable
private fun ExceptionsNoted(preview: PortalPreview, state: BankRecUiState, accountCurrency: String) {
    val colors = ZillitTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SectionTitle("Exceptions Noted", BankRecIcons.Exclaim, colors.textPrimary, iconTint = colors.warning)
        if (preview.exceptions.isEmpty()) {
            ZillitText("No exceptions noted", style = ZillitTheme.typography.bodySmall, color = colors.textMuted)
        }
        preview.exceptions.forEach { item ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    ZillitText(
                        item.title.ifBlank { BankRecFormat.DASH },
                        style = ZillitTheme.typography.bodySmall,
                        color = colors.textSecondary,
                        maxLines = 1,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (item.status != ExceptionStatus.Open) {
                        val (label, tone) = item.status.portalBadge
                        BrBadge(label, tone)
                    }
                }
                ZillitText(
                    BankRecFormat.money(item.amount, item.currency ?: accountCurrency),
                    style = mono(12.sp),
                    color = colors.danger,
                    maxLines = 1,
                )
            }
        }
        if (preview.exceptions.size > 1) {
            val subtotal = WorkspaceRows.panelTotal(
                preview.exceptions.map { it.amount to (it.currency ?: accountCurrency) },
                accountCurrency,
                state.rates,
            )
            ZillitDivider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                ZillitText(
                    "Subtotal",
                    style = ZillitTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                    modifier = Modifier.weight(1f),
                )
                ZillitTooltip(subtotal.exact) {
                    ZillitText(subtotal.value, style = mono(12.sp, FontWeight.Medium), color = colors.danger)
                }
            }
        }
    }
}

@Composable
private fun FraudAlerts(preview: PortalPreview) {
    val colors = ZillitTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle("Fraud Alerts", ZillitIcons.Shield, colors.danger)
        if (preview.fraudAlerts.isEmpty()) {
            ZillitText("No fraud alerts", style = ZillitTheme.typography.bodySmall, color = colors.textMuted)
        }
        preview.fraudAlerts.forEach { alert ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    ZillitText(
                        alert.title.substringBefore(" — ").ifBlank { alert.title },
                        style = ZillitTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                        maxLines = 1,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (alert.status != FraudStatus.Active) {
                        val (label, tone) = alert.status.badge
                        BrBadge(label, tone)
                    }
                }
                ZillitText(
                    alert.description.take(DESCRIPTION_LIMIT).ifBlank { "Under review." },
                    style = ZillitTheme.typography.bodySmall,
                    color = colors.textMuted,
                    maxLines = 3,
                )
            }
        }
    }
}

@Composable
private fun ForeignPayments(preview: PortalPreview, defaultCode: String) {
    val colors = ZillitTheme.colors
    val groups = preview.fxByCurrency
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle("Foreign Currency Payments", BankRecIcons.Swap, colors.textPrimary, iconTint = colors.teal)
        if (groups.isEmpty()) {
            ZillitText(
                "No foreign currency payments in this period",
                style = ZillitTheme.typography.bodySmall,
                color = colors.textMuted,
            )
            return@Column
        }
        val figure = mono(13.sp)
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                .border(1.dp, colors.border, RoundedCornerShape(8.dp)),
        ) {
            BrTable(
                rows = groups,
                key = { it.currency },
                rowPadding = 9.dp,
                columns = listOf(
                    BrColumn("Currency Paid", weight = 1.2f) { g ->
                        ZillitText(
                            BankRecFormat.plainMoney(g.foreignTotal, g.currency),
                            style = mono(13.sp, FontWeight.SemiBold),
                            color = colors.teal,
                        )
                    },
                    BrColumn("Bank Rate") { g ->
                        ZillitText(BankRecFormat.rate(g.bankRate), style = figure, color = colors.textSecondary)
                    },
                    BrColumn("Budget Rate") { g ->
                        ZillitText(BankRecFormat.rate(g.budgetRate), style = figure, color = colors.textSecondary)
                    },
                    BrColumn("$defaultCode Paid", weight = 1.2f) { g ->
                        ZillitText(
                            BankRecFormat.money(g.paidTotal, defaultCode),
                            style = mono(13.sp, FontWeight.SemiBold),
                        )
                    },
                    BrColumn("FX Variance", weight = 1.2f, align = BrAlign.End) { g ->
                        ZillitText(
                            BankRecFormat.signedMoney(g.varianceTotal, defaultCode),
                            style = mono(13.sp, FontWeight.Bold),
                            color = if (g.varianceTotal > 0) colors.success else colors.danger,
                        )
                    },
                ),
            )
        }
    }
}

@Composable
private fun TransactionDetail(preview: PortalPreview, state: BankRecUiState, accountCurrency: String) {
    if (preview.transactions.isEmpty()) {
        ZillitText(
            "No transaction data available",
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textMuted,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        )
        return
    }
    SplitRow(
        leftWeight = BANK_SHARE,
        padded = false,
        left = {
            Column {
                PanelHeading("Bank Transactions", preview.transactions.size, null)
                BankLinesTable(preview.transactions, accountCurrency, detail = false)
            }
        },
        right = {
            Column {
                PanelHeading("Ledger Entries", preview.ledgerEntries.size, null)
                LedgerLinesTable(preview.ledgerEntries, state.projectCurrency, detail = false)
            }
        },
    )
}

// -- shared pieces ------------------------------------------------------------------------

@Composable
internal fun SectionTitle(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    color: Color,
    iconTint: Color = color,
    uppercase: Boolean = false,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        com.zillit.desktop.core.designsystem.component.ZillitIcon(icon, tint = iconTint, size = 13.dp)
        ZillitText(
            if (uppercase) text.uppercase() else text,
            style = titleStyle(12.sp).copy(letterSpacing = if (uppercase) 0.06.em else 0.em),
            color = color,
            maxLines = 1,
        )
    }
}

@Composable
private fun SignOffNote(preview: PortalPreview) {
    val colors = ZillitTheme.colors
    val period = preview.period
    val signer = LocalBankRecPeople.current.signer(period)
    val name = signer?.name.orEmpty()
    val byline = listOf(
        name.ifBlank { BankRecFormat.DASH },
        designationLabel(signer?.designation.orEmpty()),
        BankRecFormat.dateTime(period.signedAtMillis),
    ).filter { it.isNotBlank() }.joinToString(" · ")
    Row(
        Modifier.fillMaxWidth().edge(colors.accent.copy(alpha = 0.4f), end = false, width = 2.dp)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // The signer's picture when they have one; the web's initials otherwise.
        val face = rememberAvatar(period.signedBy)
        Box(Modifier.size(36.dp).clip(CircleShape).background(colors.accentSoft), contentAlignment = Alignment.Center) {
            if (face != null) {
                Image(
                    bitmap = face,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(36.dp),
                )
            } else {
                ZillitText(
                    BankRecPerson(name).initials.ifBlank { BankRecFormat.DASH },
                    style = ZillitTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = colors.accentText,
                )
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            ZillitText("Sign-off Note", style = ZillitTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold))
            ZillitText(period.signOffNotes, style = ZillitTheme.typography.bodySmall, color = colors.textSecondary)
            ZillitText(byline, style = mono(10.5.sp, FontWeight.Medium), color = colors.textSecondary)
        }
    }
}

@Composable
private fun PreviewSkeleton() {
    val border = ZillitTheme.colors.border
    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ZillitSkeletonBar(Modifier.size(40.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ZillitSkeletonBar(Modifier.width(200.dp))
                ZillitSkeletonBar(Modifier.width(130.dp))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            repeat(4) {
                Column(
                    Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).border(1.dp, border, RoundedCornerShape(8.dp))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ZillitSkeletonBar(Modifier.fillMaxWidth(0.66f))
                    ZillitSkeletonBar(Modifier.fillMaxWidth(0.5f))
                }
            }
        }
        ZillitSkeletonBar(Modifier.fillMaxWidth())
        ZillitSkeletonBar(Modifier.fillMaxWidth(0.83f))
        ZillitSkeletonBar(Modifier.fillMaxWidth().height(96.dp))
    }
}

/** `production_accountant` → `Production Accountant`; an already-readable title as it came. */
internal fun designationLabel(raw: String): String {
    val text = raw.trim()
    if (text.isEmpty() || (!text.contains('_') && text != text.lowercase())) return text
    return text.split('_', ' ').filter { it.isNotBlank() }
        .joinToString(" ") { word -> word.lowercase().replaceFirstChar { it.uppercase() } }
}

private const val BANK_SHARE = 0.55f
private const val FRAUD_ALPHA = 0.72f
private const val DESCRIPTION_LIMIT = 150
