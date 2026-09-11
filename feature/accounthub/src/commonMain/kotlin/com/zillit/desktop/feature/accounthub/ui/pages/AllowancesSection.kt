package com.zillit.desktop.feature.accounthub.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitCheckbox
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitSectionLabel
import com.zillit.desktop.core.designsystem.component.ZillitSelect
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.accounthub.domain.AllowanceApplies
import com.zillit.desktop.feature.accounthub.domain.AllowancesRentals
import com.zillit.desktop.feature.accounthub.domain.EntitlementRow
import com.zillit.desktop.feature.accounthub.domain.PayBasis
import com.zillit.desktop.feature.accounthub.domain.RentalApplies
import com.zillit.desktop.feature.accounthub.ui.AccountHubEvent
import com.zillit.desktop.feature.accounthub.ui.AccountHubUiState
import com.zillit.desktop.feature.accounthub.ui.SetupSection

private const val NAME_WIDTH = 200
private const val AMOUNT_WIDTH = 110
private const val NOMINAL_WIDTH = 110

/**
 * The production's default allowances and equipment rentals.
 *
 * These seed a new deal memo's entitlements step, which is why the basis
 * catalogue is shared with it: an option offered here and not there saves a
 * row the wizard renders as a blank required field.
 *
 * Rentals come first, as on the web — they are the larger, more-edited list.
 *
 * No per-row enable toggle, deliberately: this is the *defaults* surface,
 * where an unwanted default is deleted rather than switched off. A stored
 * `enable: false` from the deal wizard round-trips untouched all the same.
 */
@Composable
internal fun AllowancesSection(
    state: AccountHubUiState,
    onEvent: (AccountHubEvent) -> Unit,
) {
    val section = state.setup.allowances
    val value = section.edited
    val editable = state.viewer.canEdit

    SetupSectionCard(
        title = "Allowances & Rentals",
        description = "What a new deal memo offers by default — kit rentals first, then " +
            "allowances. Amounts and codes here are the starting point, not a ceiling.",
        dirty = section.dirty,
        saving = section.saving,
        onSave = { onEvent(AccountHubEvent.SaveSection(SetupSection.Allowances)) },
        onRevert = { onEvent(AccountHubEvent.RevertSection(SetupSection.Allowances)) },
        editable = editable,
    ) {
        ZillitSectionLabel("Equipment Rentals")
        if (value.rentals.isEmpty()) EmptyLine("No default rentals yet.")
        value.rentals.forEachIndexed { index, row ->
            EntitlementRowFields(
                row = row,
                rental = true,
                editable = editable,
                onChange = { next -> onEvent(edit(value.copy(rentals = value.rentals.replaced(index, next)))) },
                onRemove = { onEvent(edit(value.copy(rentals = value.rentals.without(index)))) },
            )
        }
        if (editable) {
            AddRowButton("Add rental") {
                onEvent(edit(value.copy(rentals = value.rentals + newRow("rental", value.rentals.size))))
            }
        }

        ZillitSectionLabel("Allowances")
        if (value.allowances.isEmpty()) EmptyLine("No default allowances yet.")
        value.allowances.forEachIndexed { index, row ->
            EntitlementRowFields(
                row = row,
                rental = false,
                editable = editable,
                onChange = { next ->
                    onEvent(edit(value.copy(allowances = value.allowances.replaced(index, next))))
                },
                onRemove = { onEvent(edit(value.copy(allowances = value.allowances.without(index)))) },
            )
        }
        if (editable) {
            AddRowButton("Add allowance") {
                onEvent(edit(value.copy(allowances = value.allowances + newRow("allow", value.allowances.size))))
            }
        }
    }
}

private fun edit(value: AllowancesRentals) = AccountHubEvent.EditAllowances(value)

private fun List<EntitlementRow>.replaced(index: Int, row: EntitlementRow) =
    mapIndexed { i, existing -> if (i == index) row else existing }

private fun List<EntitlementRow>.without(index: Int) = filterIndexed { i, _ -> i != index }

/**
 * A new row's id.
 *
 * Client-side and stable for the life of the edit — the server keeps whatever
 * id it is given, and the row has to be addressable before it is ever saved.
 */
private fun newRow(kind: String, at: Int) = EntitlementRow(id = "$kind-new-$at")

@Composable
private fun AddRowButton(text: String, onClick: () -> Unit) {
    ZillitButton(
        text = text,
        onClick = onClick,
        variant = ButtonVariant.Tertiary,
        size = ButtonSize.Small,
        leadingIcon = ZillitIcons.Add,
    )
}

/**
 * One row of either list.
 *
 * The cap pair is rendered for a rental only, because only a rental has one on
 * the wire — an allowance sent with `cap_type` is a field the validator does
 * not know.
 */
@Suppress("LongMethod") // One row of fields, read left to right.
@Composable
private fun EntitlementRowFields(
    row: EntitlementRow,
    rental: Boolean,
    editable: Boolean,
    onChange: (EntitlementRow) -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitTextField(
            value = row.name,
            onValueChange = { onChange(row.copy(name = it)) },
            label = "Name",
            enabled = editable,
            modifier = Modifier.width(NAME_WIDTH.dp),
        )
        ZillitTextField(
            value = row.amount,
            onValueChange = { onChange(row.copy(amount = it)) },
            label = "Amount",
            enabled = editable,
            modifier = Modifier.width(AMOUNT_WIDTH.dp),
        )
        // A retired basis stays selectable-as-shown rather than blanking: the
        // stored value is somebody's agreed cadence, and losing it silently is
        // worse than showing a value that has to be re-picked.
        ZillitSelect(
            value = row.basis,
            options = PayBasis.entries.map { it.wire }.let { live ->
                if (row.basis.isNotBlank() && row.basis !in live) live + row.basis else live
            },
            onSelect = { onChange(row.copy(basis = it)) },
            label = { if (it.isBlank()) "Basis" else PayBasis.labelFor(it) },
            enabled = editable,
            modifier = Modifier.weight(1f),
        )
        ZillitSelect(
            value = row.appliesTo,
            options = if (rental) {
                RentalApplies.entries.map { it.wire }
            } else {
                AllowanceApplies.entries.map { it.wire }
            },
            onSelect = { onChange(row.copy(appliesTo = it)) },
            label = { wire -> appliesLabel(wire, rental) },
            enabled = editable,
            modifier = Modifier.weight(1f),
        )
        if (rental) {
            ZillitCheckbox(
                checked = row.capped,
                onCheckedChange = { onChange(row.copy(capped = it)) },
                label = "Capped",
                enabled = editable,
            )
            if (row.capped) {
                ZillitTextField(
                    value = row.capAmount,
                    onValueChange = { onChange(row.copy(capAmount = it)) },
                    label = "Cap",
                    enabled = editable,
                    modifier = Modifier.width(AMOUNT_WIDTH.dp),
                )
            }
        }
        ZillitTextField(
            value = row.nominalCode,
            onValueChange = { onChange(row.copy(nominalCode = it)) },
            label = "Nominal",
            enabled = editable,
            modifier = Modifier.width(NOMINAL_WIDTH.dp),
        )
        if (editable) {
            ZillitIconButton(icon = ZillitIcons.Trash, contentDescription = "Remove row", onClick = onRemove)
        }
    }
}

private fun appliesLabel(wire: String, rental: Boolean): String = when {
    wire.isBlank() -> "Applies to"
    rental -> RentalApplies.entries.firstOrNull { it.wire == wire }?.label ?: wire
    else -> AllowanceApplies.entries.firstOrNull { it.wire == wire }?.label ?: wire
}
