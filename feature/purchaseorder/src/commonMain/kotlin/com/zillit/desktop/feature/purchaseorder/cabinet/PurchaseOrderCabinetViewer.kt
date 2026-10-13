package com.zillit.desktop.feature.purchaseorder.cabinet

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.cabinet.CabinetEntry
import com.zillit.desktop.core.cabinet.CabinetViewer
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitChoiceChip
import com.zillit.desktop.core.designsystem.component.ZillitDivider
import com.zillit.desktop.core.designsystem.component.ZillitErrorState
import com.zillit.desktop.core.designsystem.component.ZillitErrorToast
import com.zillit.desktop.core.designsystem.component.ZillitScrollColumn
import com.zillit.desktop.core.designsystem.component.ZillitSelect
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.purchaseorder.domain.PoAttachment
import com.zillit.desktop.feature.purchaseorder.domain.PoProjectSettings
import com.zillit.desktop.feature.purchaseorder.domain.PoSettingsPeople
import com.zillit.desktop.feature.purchaseorder.domain.PurchaseOrder
import com.zillit.desktop.feature.purchaseorder.domain.PurchaseOrderRepository
import com.zillit.desktop.feature.purchaseorder.ui.PoEvent
import com.zillit.desktop.feature.purchaseorder.ui.PoUiState
import com.zillit.desktop.feature.purchaseorder.ui.pages.DetailActions
import com.zillit.desktop.feature.purchaseorder.ui.pages.PoDetailBody
import com.zillit.desktop.feature.purchaseorder.ui.pages.PoOrderTable

/**
 * File Cabinet → Purchase Order → "All Po's In The Project".
 *
 * The web's `FileCabinetPOList`: a read-only table of the project's Account Hub
 * orders as the server archives them (`isFileCabinet=true`), with the All /
 * Approved / Rejected / Posted / Closed chips and a department filter, and a
 * row that opens the order in display mode — View PDF, attachments, History.
 * The rows, the status pill and the detail are the Purchase Orders tool's own;
 * this adds the cabinet's frame around them and takes every button that writes
 * off. See [PoCabinetModel] for why it is not the tool's view model.
 *
 * The detail replaces the list inside the window, with a Back bar, rather than
 * opening a second dialog over the first: the cabinet window is itself a dialog
 * shell and the shell is not a popup, so a nested one only paints over its host.
 *
 * @param people names the raiser, the assignee and the departments; null leaves ids blank.
 * @param projectSettings the departments, when [people] has none.
 * @param onOpenAttachment fetches one of the order's files (or its rendered PDF) to disk and
 *   hands it to the OS — the host's business, as in the tool itself.
 */
class PurchaseOrderCabinetViewer(
    private val repository: PurchaseOrderRepository,
    private val people: PoSettingsPeople? = null,
    private val projectSettings: PoProjectSettings? = null,
    private val onOpenAttachment: (PoAttachment) -> Unit = {},
) : CabinetViewer {

    override val identifier: String = IDENTIFIER

    override val expands: Boolean = true

    /** The web's one submenu item, `{ key: 'report', unit_name: 'reports' }`. */
    override suspend fun entries(): List<CabinetEntry> =
        listOf(CabinetEntry(key = REPORT_KEY, name = str(S.desktop_fc_all_pos_in_project)))

    @Composable
    override fun Content(entry: CabinetEntry?, modifier: Modifier) {
        val scope = rememberCoroutineScope()
        val model = remember { PoCabinetModel(repository, people, projectSettings, onOpenAttachment, scope) }
        LaunchedEffect(model) { model.start() }
        val state by model.state.collectAsState()
        val failure by model.failure.collectAsState()
        Box(modifier = modifier) {
            PoCabinetPage(state, model::onEvent)
            ZillitErrorToast(message = failure, onDismiss = model::clearFailure)
        }
    }

    companion object {
        /** The row's identifier on the cabinet page. */
        const val IDENTIFIER = "purchase_order"

        /** The submenu entry's key. */
        const val REPORT_KEY = "report"
    }
}

/** The window's body: the list, the order that was opened over it, or why neither could be read. */
@Composable
internal fun PoCabinetPage(state: PoUiState, onEvent: (PoEvent) -> Unit) {
    val open = state.detail
    val error = state.error
    when {
        open != null -> PoCabinetDetail(state, open, onEvent)
        error != null -> ZillitErrorState(
            message = error.localised(),
            onRetry = { onEvent(PoEvent.Refresh) },
            modifier = Modifier.fillMaxSize(),
        )

        else -> PoCabinetList(state, onEvent)
    }
}

@Composable
private fun PoCabinetList(state: PoUiState, onEvent: (PoEvent) -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        PoCabinetFilters(state, onEvent)
        // One scroll for the list, as the tool's own pages have it: the table
        // lays out every row instead of virtualising inside a box of its own.
        ZillitScrollColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(
                start = ZillitTheme.spacing.lg,
                end = ZillitTheme.spacing.lg,
                bottom = ZillitTheme.spacing.lg,
            ),
        ) {
            PoOrderTable(
                state = state,
                rows = state.rows,
                onEvent = onEvent,
                selectable = false,
                emptyMessage = str(S.desktop_fc_no_purchase_orders),
            )
        }
    }
}

/** The chips and the department picker — the web's `QuickFilters` and `DepartmentFilterSelect`. */
@Composable
private fun PoCabinetFilters(state: PoUiState, onEvent: (PoEvent) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(ZillitTheme.spacing.lg),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        state.quickFilters.forEach { filter ->
            ZillitChoiceChip(
                label = filter.label,
                selected = state.quickFilter == filter,
                onClick = { onEvent(PoEvent.Filter(filter)) },
            )
        }
        Spacer(modifier = Modifier.weight(1f))
        ZillitText(
            text = str(S.ah_dept_label_upper),
            style = ZillitTheme.typography.labelSmall,
            color = ZillitTheme.colors.textMuted,
        )
        ZillitSelect(
            value = state.departmentFilter,
            options = listOf(null) + state.departments.map { it.id },
            onSelect = { onEvent(PoEvent.FilterDepartment(it)) },
            label = { id -> id?.let { state.departmentName(it) } ?: str(S.all) },
            modifier = Modifier.width(DEPARTMENT_WIDTH),
        )
    }
}

/** One order in display mode, under a bar that goes back to the list. */
@Composable
private fun PoCabinetDetail(state: PoUiState, order: PurchaseOrder, onEvent: (PoEvent) -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(ZillitTheme.spacing.lg),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        ) {
            ZillitButton(
                text = str(S.back),
                onClick = { onEvent(PoEvent.CloseOrder) },
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
                leadingIcon = ZillitIcons.ArrowLeft,
            )
            Column(modifier = Modifier.weight(1f)) {
                ZillitText(
                    text = order.number.ifBlank { str(S.purchase_order) },
                    style = ZillitTheme.typography.titleMedium,
                    maxLines = 1,
                )
                val subtitle = listOfNotNull(
                    state.vendorName(order).takeIf { it.isNotBlank() },
                    state.departmentName(order.departmentId).takeIf { it.isNotBlank() },
                ).joinToString(" · ")
                if (subtitle.isNotBlank()) {
                    ZillitText(
                        text = subtitle,
                        style = ZillitTheme.typography.bodySmall,
                        color = ZillitTheme.colors.textMuted,
                        maxLines = 1,
                    )
                }
            }
            DetailActions(state, order, onEvent)
        }
        ZillitDivider()
        ZillitScrollColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(ZillitTheme.spacing.lg),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        ) {
            PoDetailBody(state, order, onEvent)
        }
    }
}

private val DEPARTMENT_WIDTH = 200.dp
