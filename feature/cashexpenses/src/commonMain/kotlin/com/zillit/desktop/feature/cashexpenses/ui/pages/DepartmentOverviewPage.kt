package com.zillit.desktop.feature.cashexpenses.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.cashexpenses.domain.CashFloat
import com.zillit.desktop.feature.cashexpenses.domain.ClaimBatch
import com.zillit.desktop.feature.cashexpenses.domain.DepartmentStats
import com.zillit.desktop.feature.cashexpenses.domain.ExpenseCategory
import com.zillit.desktop.feature.cashexpenses.domain.FloatStatus
import com.zillit.desktop.feature.cashexpenses.ui.BatchStatusPill
import com.zillit.desktop.feature.cashexpenses.ui.CashUiState
import com.zillit.desktop.feature.cashexpenses.ui.FloatStatusPill
import com.zillit.desktop.feature.cashexpenses.ui.LocalCashPeople

/**
 * A coordinator's read-only view of their department — the web's `PCDeptViewPage`.
 *
 * A notice and a heading, four tiles, then the department's floats beside its
 * out-of-pocket claims and spend by category, and a closing note
 * (`PCDeptViewPage.jsx:48-201`).
 */
@Suppress("LongMethod") // Notice, heading, tiles, two columns: the web's page, read as one.
@Composable
fun DepartmentOverviewPage(state: CashUiState) {
    val overview = state.departmentOverview
    val stats = overview?.stats ?: DepartmentStats()
    val people = LocalCashPeople.current
    val departmentId = state.viewer.departmentId ?: overview?.departmentId
    val departmentName = state.departmentName(departmentId) ?: str(S.department)

    ScrollingPage {
        OverviewNotice(
            title = str(S.desktop_pc_dept_view_title, people.nameOf(state.viewer.userId)),
            body = str(S.desktop_pc_dept_view_body),
            tone = StatusTone.Escalated,
            icon = ZillitIcons.Users,
        )

        Column {
            ZillitText(
                text = str(S.desktop_pc_dept_financial_overview, departmentName),
                style = ZillitTheme.typography.titleLarge,
            )
            ZillitText(
                text = str(S.desktop_pc_dept_overview_sub),
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textSecondary,
            )
        }

        StatRow(
            listOf(
                StatTileSpec(
                    label = str(S.desktop_ce_active_floats),
                    value = stats.activeFloats.toString(),
                    sub = stats.activeFloatHolders.joinToString(" · ") { people.nameOf(it) }.ifBlank { "—" },
                    tone = StatusTone.Pending,
                    icon = ZillitIcons.Wallet,
                ),
                StatTileSpec(
                    label = str(S.desktop_pc_cash_issued),
                    value = state.formatAggregate(stats.cashIssued),
                    sub = str(S.desktop_this_period),
                    icon = ZillitIcons.Bank,
                ),
                StatTileSpec(
                    label = str(S.desktop_pc_receipts_approved),
                    value = state.formatAggregate(stats.receiptsApproved),
                    sub = str(S.desktop_pc_across_all_floats),
                    tone = StatusTone.Ready,
                    icon = ZillitIcons.Receipt,
                ),
                StatTileSpec(
                    label = str(S.desktop_pc_oop_claims),
                    value = stats.outOfPocketCount.toString(),
                    sub = str(S.desktop_pc_pending_approved, stats.outOfPocketPending, stats.outOfPocketApproved),
                    tone = StatusTone.Progress,
                    icon = ZillitIcons.Ledger,
                ),
            ),
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xl),
            verticalAlignment = Alignment.Top,
        ) {
            DepartmentFloats(state, departmentName, overview?.floats.orEmpty(), Modifier.weight(1f))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg),
            ) {
                DepartmentClaims(state, overview?.outOfPocketBatches.orEmpty())
                SpendByCategory(state)
            }
        }

        OverviewNotice(
            title = null,
            body = str(S.desktop_pc_dept_read_only_note),
            tone = StatusTone.Progress,
            icon = ZillitIcons.Info,
        )
    }
}

/** The department's floats; a row opens a one-line summary, a closed one does not. */
@Suppress("LongMethod") // A table whose rows expand; splitting it scatters one row's layout.
@Composable
private fun DepartmentFloats(
    state: CashUiState,
    departmentName: String,
    floats: List<CashFloat>,
    modifier: Modifier,
) {
    val people = LocalCashPeople.current
    var expanded by remember { mutableStateOf(emptySet<String>()) }
    OverviewSection(
        title = str(S.desktop_pc_dept_floats, departmentName),
        modifier = modifier,
        right = {
            ZillitText(
                text = str(S.desktop_read_only),
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textSecondary,
            )
        },
    ) {
        TableHeader {
            HeaderCell(str(S.crew_member), Modifier.weight(PERSON_WEIGHT))
            HeaderCell(str(S.desktop_ref), Modifier.weight(1f))
            HeaderCell(str(S.desktop_issued), Modifier.weight(1f))
            HeaderCell(str(S.ah_receipts_label), Modifier.weight(1f))
            HeaderCell(str(S.ah_balance_label), Modifier.weight(1f))
            HeaderCell(str(S.status), Modifier.width(STATUS_COL))
        }
        if (floats.isEmpty()) {
            OverviewEmpty(str(S.desktop_pc_no_dept_floats))
        }
        floats.forEachIndexed { index, float ->
            val closed = float.status == FloatStatus.Closed
            val balance = float.registerBalance
            val open = float.id in expanded
            OverviewRow(
                last = index == floats.lastIndex && !open,
                modifier = if (closed) Modifier.alpha(CLOSED_ALPHA) else Modifier,
                onClick = if (closed) {
                    null
                } else {
                    { expanded = if (open) expanded - float.id else expanded + float.id }
                },
            ) {
                Column(modifier = Modifier.weight(PERSON_WEIGHT)) {
                    ZillitText(
                        text = people.nameOf(float.userId, float.holderName),
                        style = ZillitTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                        maxLines = 1,
                    )
                    designationOf(state, float.userId)?.let {
                        ZillitText(
                            text = it,
                            style = ZillitTheme.typography.labelSmall,
                            color = ZillitTheme.colors.textSecondary,
                            maxLines = 1,
                        )
                    }
                }
                MonoCell(float.requestNumber.ifBlank { "—" }, Modifier.weight(1f), muted = true)
                MonoCell(state.formatMoney(float.registerIssued, float.currency), Modifier.weight(1f))
                MonoCell(
                    state.formatMoney(float.receiptsAmount, float.currency),
                    Modifier.weight(1f),
                    muted = float.receiptsAmount <= 0,
                )
                ZillitText(
                    text = state.formatMoney(balance, float.currency),
                    style = ZillitTheme.typography.numeric.copy(fontWeight = FontWeight.Medium),
                    color = if (balance >= 0) ZillitTheme.colors.success else ZillitTheme.colors.danger,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                Row(modifier = Modifier.width(STATUS_COL)) { FloatStatusPill(float.status) }
            }
            if (open) {
                ZillitText(
                    text = str(
                        S.desktop_pc_float_detail_line,
                        float.requestNumber,
                        people.nameOf(float.userId, float.holderName),
                        state.departmentName(float.departmentId) ?: float.departmentId.orEmpty(),
                        float.status.wire,
                    ),
                    style = ZillitTheme.typography.bodySmall,
                    color = ZillitTheme.colors.textSecondary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(ZillitTheme.colors.surfaceSunken)
                        .padding(horizontal = ROW_PAD, vertical = ZillitTheme.spacing.md),
                )
                if (index != floats.lastIndex) RowRule()
            }
        }
    }
}

/** The department's out-of-pocket claims, with their payment route. */
@Composable
private fun DepartmentClaims(state: CashUiState, batches: List<ClaimBatch>) {
    val people = LocalCashPeople.current
    OverviewSection(title = str(S.desktop_pc_oop_claims_your_dept)) {
        TableHeader {
            HeaderCell(str(S.crew_member), Modifier.weight(PERSON_WEIGHT))
            HeaderCell(str(S.desktop_ref), Modifier.weight(1f))
            HeaderCell(str(S.amount), Modifier.weight(1f))
            HeaderCell(str(S.desktop_pc_routing), Modifier.width(ROUTING_COL))
            HeaderCell(str(S.status), Modifier.width(STATUS_COL))
        }
        if (batches.isEmpty()) {
            OverviewEmpty(str(S.desktop_pc_no_dept_oop))
        }
        batches.forEachIndexed { index, batch ->
            OverviewRow(last = index == batches.lastIndex) {
                ZillitText(
                    text = people.nameOf(batch.userId, batch.holderName),
                    style = ZillitTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                    modifier = Modifier.weight(PERSON_WEIGHT),
                )
                MonoCell(batch.reference.ifBlank { "—" }, Modifier.weight(1f), muted = true)
                MonoCell(state.formatMoney(batch.totalGross, batch.currency), Modifier.weight(1f))
                Row(modifier = Modifier.width(ROUTING_COL)) { RoutingPill(batch.paymentMethod) }
                Row(modifier = Modifier.width(STATUS_COL)) { BatchStatusPill(batch.status, accountant = true) }
            }
        }
    }
}

/** BACS in blue, payroll in purple, anything else as sent (`PCDeptViewPage.jsx:159-166`). */
@Composable
private fun RoutingPill(method: String?) {
    val wire = method?.trim()?.uppercase().orEmpty()
    when (wire) {
        "BACS" -> ZillitStatusPill(label = str(S.desktop_pc_bacs), tone = StatusTone.Progress)
        "PAYROLL" -> ZillitStatusPill(label = str(S.dm_step9_title), tone = StatusTone.Escalated)
        else -> ZillitStatusPill(label = method?.takeIf { it.isNotBlank() } ?: "—", tone = StatusTone.Neutral)
    }
}

@Composable
private fun SpendByCategory(state: CashUiState) {
    val rows = state.departmentOverview?.spendByCategory.orEmpty()
    OverviewSection(title = str(S.desktop_pc_spend_by_category)) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(ROW_PAD),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        ) {
            if (rows.isEmpty()) {
                OverviewEmpty(str(S.desktop_pc_no_spend))
            }
            rows.forEachIndexed { index, row ->
                LabelledMeter(
                    label = ExpenseCategory.label(row.category),
                    trailing = state.formatAggregate(row.amount),
                    fraction = (row.percent / PERCENT).toFloat(),
                    tone = CATEGORY_TONES[index % CATEGORY_TONES.size],
                )
            }
        }
    }
}

private fun designationOf(state: CashUiState, userId: String?): String? =
    state.assignees.firstOrNull { it.userId == userId }?.designation?.takeIf { it.isNotBlank() }

@Composable
private fun TableHeader(content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(ZillitTheme.colors.surfaceSunken)
            .padding(horizontal = ROW_PAD, vertical = ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        content = content,
    )
    RowRule()
}

@Composable
private fun HeaderCell(text: String, modifier: Modifier) {
    ZillitText(
        text = text,
        style = ZillitTheme.typography.columnHeader,
        color = ZillitTheme.colors.textSecondary,
        maxLines = 1,
        modifier = modifier,
    )
}

@Composable
private fun MonoCell(text: String, modifier: Modifier, muted: Boolean = false) {
    ZillitText(
        text = text,
        style = ZillitTheme.typography.numeric,
        color = if (muted) ZillitTheme.colors.textSecondary else ZillitTheme.colors.textPrimary,
        maxLines = 1,
        modifier = modifier,
    )
}

private val CATEGORY_TONES = listOf(
    StatusTone.Pending,
    StatusTone.Progress,
    StatusTone.Escalated,
    StatusTone.Ready,
    StatusTone.Rejected,
    StatusTone.Done,
)

private const val PERSON_WEIGHT = 1.6f
private const val CLOSED_ALPHA = 0.5f
private const val PERCENT = 100.0
private val ROW_PAD = 18.dp
private val STATUS_COL = 130.dp
private val ROUTING_COL = 90.dp
