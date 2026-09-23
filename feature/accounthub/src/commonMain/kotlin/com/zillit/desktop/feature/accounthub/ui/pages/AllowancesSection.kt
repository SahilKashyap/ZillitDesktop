package com.zillit.desktop.feature.accounthub.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.accounthub.domain.AllowanceApplies
import com.zillit.desktop.feature.accounthub.domain.AllowancesRentals
import com.zillit.desktop.feature.accounthub.domain.EntitlementRow
import com.zillit.desktop.feature.accounthub.domain.PayBasis
import com.zillit.desktop.feature.accounthub.domain.RentalApplies
import com.zillit.desktop.feature.accounthub.ui.AccountHubEvent
import com.zillit.desktop.feature.accounthub.ui.AccountHubUiState
import com.zillit.desktop.feature.accounthub.ui.SetupSection
import com.zillit.desktop.feature.accounthub.ui.components.CalcField
import com.zillit.desktop.feature.accounthub.ui.components.CoaCodeField
import com.zillit.desktop.feature.accounthub.ui.components.FieldHint
import com.zillit.desktop.feature.accounthub.ui.components.GhostAddButton
import com.zillit.desktop.feature.accounthub.ui.components.HubSelect
import com.zillit.desktop.feature.accounthub.ui.components.MonoLabel
import com.zillit.desktop.feature.accounthub.ui.components.SectionShell
import com.zillit.desktop.feature.accounthub.ui.components.SubCard
import com.zillit.desktop.feature.accounthub.ui.components.quickCreateHandler

/**
 * The production's default allowances and equipment rentals — the web's
 * `AllowancesRentalsSection`.
 *
 * These seed a new deal memo's entitlements step, which is why the basis
 * catalogue is shared with it: an option offered here and not there saves a
 * row the wizard renders as a blank required field.
 *
 * Rentals come first, as on the web — they are the larger, more-edited list.
 * Each list is a header band over rows of label-less cells, the web's grid,
 * with the columns weighted the way it weights them.
 *
 * No per-row enable toggle, deliberately: this is the *defaults* surface,
 * where an unwanted default is deleted rather than switched off. A stored
 * `enable: false` from the deal wizard round-trips untouched all the same.
 */
@Suppress("LongMethod") // A screen, read top to bottom; the order is the reading order.
@Composable
internal fun AllowancesSection(
    state: AccountHubUiState,
    onEvent: (AccountHubEvent) -> Unit,
) {
    val section = state.setup.allowances
    val value = section.edited
    val editable = state.viewer.canEdit

    SectionShell(
        title = str(S.dm_allow_title),
        description = str(S.desktop_hub_production_default_allowances_equipment_rentals_pre_populates_the_deal_memo),
        dirty = section.dirty,
        saving = section.saving,
        onSave = { onEvent(AccountHubEvent.SaveSection(SetupSection.Allowances)) },
        onCancel = { onEvent(AccountHubEvent.RevertSection(SetupSection.Allowances)) },
        editable = editable,
        leftPanel = { AtAGlance(value) },
    ) {
        SubCard(title = str(S.desktop_hub_equipment_rentals_box_rental), padded = false) {
            HeaderBand(rental = true)
            if (value.rentals.isEmpty()) EmptyRow(str(S.desktop_hub_no_rentals_yet_add_the_first_one_below))
            value.rentals.forEachIndexed { index, row ->
                EntitlementRowFields(
                    row = row,
                    rental = true,
                    editable = editable,
                    state = state,
                    onEvent = onEvent,
                    onChange = { next -> onEvent(edit(value.copy(rentals = value.rentals.replaced(index, next)))) },
                    onRemove = { onEvent(edit(value.copy(rentals = value.rentals.without(index)))) },
                )
            }
            if (editable) {
                Box(Modifier.padding(ZillitTheme.spacing.md)) {
                    GhostAddButton(str(S.dm_allow_add_dialog_title_rental), onClick = {
                        val added = value.rentals + newRow("rental", value.rentals.size, "week")
                        onEvent(edit(value.copy(rentals = added)))
                    })
                }
            }
        }

        SubCard(title = str(S.allowances_label), padded = false) {
            HeaderBand(rental = false)
            if (value.allowances.isEmpty()) EmptyRow(str(S.desktop_hub_no_allowances_yet_add_the_first_one_below))
            value.allowances.forEachIndexed { index, row ->
                EntitlementRowFields(
                    row = row,
                    rental = false,
                    editable = editable,
                    state = state,
                    onEvent = onEvent,
                    onChange = { next ->
                        onEvent(edit(value.copy(allowances = value.allowances.replaced(index, next))))
                    },
                    onRemove = { onEvent(edit(value.copy(allowances = value.allowances.without(index)))) },
                )
            }
            if (editable) {
                Box(Modifier.padding(ZillitTheme.spacing.md)) {
                    GhostAddButton(str(S.dm_allow_add_dialog_title_allowance), onClick = {
                        val added = value.allowances + newRow("allow", value.allowances.size, "day")
                        onEvent(edit(value.copy(allowances = added)))
                    })
                }
            }
        }
    }
}

/** The left column's count of named rows in each list — the web's `AtAGlance`. */
@Composable
private fun AtAGlance(value: AllowancesRentals) {
    Column(
        modifier = Modifier.padding(top = ZillitTheme.spacing.sm),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        MonoLabel(str(S.desktop_at_a_glance))
        GlanceLine(str(S.dm_allow_card_rentals), value.rentals.count { it.name.isNotBlank() })
        GlanceLine(str(S.allowances_label), value.allowances.count { it.name.isNotBlank() })
    }
}

@Composable
private fun GlanceLine(label: String, count: Int) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        ZillitText(text = label, style = ZillitTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        ZillitText(
            text = count.toString(),
            style = ZillitTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
        )
    }
}

private fun edit(value: AllowancesRentals) = AccountHubEvent.EditAllowances(value)

private fun List<EntitlementRow>.replaced(index: Int, row: EntitlementRow) =
    mapIndexed { i, existing -> if (i == index) row else existing }

private fun List<EntitlementRow>.without(index: Int) = filterIndexed { i, _ -> i != index }

/**
 * A new row's id and basis.
 *
 * The id is client-side and stable for the life of the edit — the server
 * keeps whatever id it is given, and the row has to be addressable before
 * it is ever saved. The basis is the web's default for the list: rentals are
 * usually weekly, allowances daily.
 */
private fun newRow(kind: String, at: Int, basis: String) = EntitlementRow(id = "$kind-new-$at", basis = basis)

/** The column titles over each list, weighted exactly as the rows below them. */
@Composable
private fun HeaderBand(rental: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(ZillitTheme.colors.surfaceSunken)
            .padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.xs),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HeaderCell(str(S.name), NAME_WEIGHT)
        HeaderCell(str(S.amount), AMOUNT_WEIGHT)
        HeaderCell(str(S.desktop_frequency), BASIS_WEIGHT)
        HeaderCell(str(S.dm_allow_applies_to), APPLIES_WEIGHT)
        if (rental) HeaderCell(str(S.dm_allow_cap_type), CAP_WEIGHT)
        MonoLabel("GL", modifier = Modifier.width(NOMINAL_WIDTH))
        Box(Modifier.width(REMOVE_WIDTH))
    }
}

@Composable
private fun RowScope.HeaderCell(text: String, weight: Float) {
    MonoLabel(text, modifier = Modifier.weight(weight))
}

@Composable
private fun EmptyRow(text: String) {
    Box(Modifier.padding(ZillitTheme.spacing.md)) { FieldHint(text) }
}

/**
 * One row of either list.
 *
 * The cap pair is rendered for a rental only, because only a rental has one on
 * the wire — an allowance sent with `cap_type` is a field the validator does
 * not know. The cap amount stacks under its select inside the same cell, as
 * the web's does: side by side there is no room for a usable input.
 */
@Suppress("LongMethod") // One row of fields, read left to right.
@Composable
private fun EntitlementRowFields(
    row: EntitlementRow,
    rental: Boolean,
    editable: Boolean,
    state: AccountHubUiState,
    onEvent: (AccountHubEvent) -> Unit,
    onChange: (EntitlementRow) -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.xs),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.Top,
    ) {
        ZillitTextField(
            value = row.name,
            onValueChange = { onChange(row.copy(name = it)) },
            placeholder = if (rental) str(S.desktop_rental_name) else str(S.desktop_allowance_name),
            enabled = editable,
            modifier = Modifier.weight(NAME_WEIGHT),
        )
        CalcField(
            value = row.amount,
            onValueChange = { onChange(row.copy(amount = it)) },
            placeholder = "0.00",
            enabled = editable,
            modifier = Modifier.weight(AMOUNT_WEIGHT),
        )
        // A retired basis stays selectable-as-shown rather than blanking: the
        // stored value is somebody's agreed cadence, and losing it silently is
        // worse than showing a value that has to be re-picked.
        // The picker rather than the design system's select: that one carries
        // a 170dp minimum and overflowed the row, which is why the two cells
        // once drew as empty slivers.
        HubSelect(
            value = row.basis.takeIf { it.isNotBlank() },
            options = PayBasis.entries.map { it.wire }.let { live ->
                if (row.basis.isNotBlank() && row.basis !in live) live + row.basis else live
            },
            onSelect = { picked -> if (picked != null) onChange(row.copy(basis = picked)) },
            label = { PayBasis.labelFor(it) },
            placeholder = str(S.desktop_frequency),
            searchable = false,
            enabled = editable,
            modifier = Modifier.weight(BASIS_WEIGHT),
        )
        HubSelect(
            value = row.appliesTo.takeIf { it.isNotBlank() },
            options = if (rental) {
                RentalApplies.entries.map { it.wire }
            } else {
                AllowanceApplies.entries.map { it.wire }
            },
            onSelect = { picked -> if (picked != null) onChange(row.copy(appliesTo = picked)) },
            label = { wire -> appliesLabel(wire, rental) },
            placeholder = str(S.desktop_applies_to_dashes),
            searchable = false,
            enabled = editable,
            modifier = Modifier.weight(APPLIES_WEIGHT),
        )
        if (rental) {
            Column(
                modifier = Modifier.weight(CAP_WEIGHT),
                verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
            ) {
                HubSelect(
                    value = row.capped,
                    options = listOf(false, true),
                    onSelect = { picked -> if (picked != null) onChange(row.copy(capped = picked)) },
                    label = { if (it) str(S.desktop_capped) else str(S.desktop_uncapped) },
                    searchable = false,
                    enabled = editable,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (row.capped) {
                    CalcField(
                        value = row.capAmount,
                        onValueChange = { onChange(row.copy(capAmount = it)) },
                        placeholder = str(S.dm_allow_cap_amount),
                        enabled = editable,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
        CoaCodeField(
            value = row.nominalCode,
            onValueChange = { onChange(row.copy(nominalCode = it)) },
            accounts = state.chart.accounts,
            placeholder = "GL",
            enabled = editable,
            modifier = Modifier.width(NOMINAL_WIDTH),
            onCreate = quickCreateHandler(state, onEvent),
        )
        Box(Modifier.width(REMOVE_WIDTH), contentAlignment = Alignment.Center) {
            if (editable) {
                ZillitIconButton(
                    icon = ZillitIcons.Close,
                    contentDescription = str(S.desktop_remove_row),
                    onClick = onRemove,
                )
            }
        }
    }
}

private fun appliesLabel(wire: String, rental: Boolean): String = when {
    rental -> RentalApplies.entries.firstOrNull { it.wire == wire }?.label ?: wire
    else -> AllowanceApplies.entries.firstOrNull { it.wire == wire }?.label ?: wire
}

// The web's grid: `1.4fr 0.9fr 1fr 1.2fr [1.1fr] 110px 36px`.
private const val NAME_WEIGHT = 1.4f
private const val AMOUNT_WEIGHT = 0.9f
private const val BASIS_WEIGHT = 1f
private const val APPLIES_WEIGHT = 1.2f
private const val CAP_WEIGHT = 1.1f
private val NOMINAL_WIDTH = 96.dp
private val REMOVE_WIDTH = 32.dp
