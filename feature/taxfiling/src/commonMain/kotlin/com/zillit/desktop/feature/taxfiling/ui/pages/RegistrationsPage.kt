package com.zillit.desktop.feature.taxfiling.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ColumnWidth
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.TableColumn
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDataTable
import com.zillit.desktop.core.designsystem.component.ZillitSectionCard
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.textColumn
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.taxfiling.domain.TaxRegistration
import com.zillit.desktop.feature.taxfiling.ui.TaxFilingEvent
import com.zillit.desktop.feature.taxfiling.ui.TaxFilingUiState

private val NUMBER_WIDTH = 160.dp
private val FREQUENCY_WIDTH = 120.dp
private val STATUS_WIDTH = 140.dp
private val ACTIONS_WIDTH = 240.dp

/** The companies enrolled with a tax authority, and what each still needs. */
@Composable
fun ColumnScope.RegistrationsPage(state: TaxFilingUiState, onEvent: (TaxFilingEvent) -> Unit) {
    state.filings.firstOrNull()?.let { filing ->
        ZillitSectionCard(title = filing.title, meta = filing.countryName) {
            ZillitText(
                text = filing.description.ifBlank { filing.subtitle },
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textSecondary,
            )
        }
    }

    ZillitDataTable(
        rows = state.registrations,
        columns = registrationColumns(onEvent),
        key = { it.id },
        onRowClick = { onEvent(TaxFilingEvent.Open(it)) },
        loading = state.loading,
        emptyTitle = "No registrations yet",
        emptyMessage = "Add the company's VAT registration number, then authorise HMRC " +
            "to file on its behalf.",
        virtualised = false,
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun registrationColumns(onEvent: (TaxFilingEvent) -> Unit): List<TableColumn<TaxRegistration>> =
    listOf(
        textColumn(header = "Company") { it.companyName.ifBlank { it.companyId } },
        textColumn(header = "VAT number", width = ColumnWidth.Fixed(NUMBER_WIDTH)) {
            it.registrationNumber
        },
        textColumn(
            header = "Frequency",
            width = ColumnWidth.Fixed(FREQUENCY_WIDTH),
            muted = true,
        ) { it.filingFrequency.replaceFirstChar(Char::uppercase) },
        TableColumn(
            header = "HMRC",
            width = ColumnWidth.Fixed(STATUS_WIDTH),
            cell = { registration ->
                ZillitStatusPill(
                    label = if (registration.connected) "Authorised" else "Not authorised",
                    tone = if (registration.connected) StatusTone.Done else StatusTone.Pending,
                    dot = true,
                )
            },
        ),
        TableColumn(
            header = "",
            width = ColumnWidth.Fixed(ACTIONS_WIDTH),
            cell = { registration -> RowActions(registration, onEvent) },
        ),
    )

@Composable
private fun RowActions(registration: TaxRegistration, onEvent: (TaxFilingEvent) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
        // Named for what it does rather than "Connect": the accountant is
        // about to sign in to HMRC and grant Zillit the right to file for
        // them, which is not a settings toggle.
        ZillitButton(
            text = if (registration.connected) "Re-authorise" else "Authorise HMRC",
            onClick = { onEvent(TaxFilingEvent.Connect(registration)) },
            variant = ButtonVariant.Tertiary,
            size = ButtonSize.Small,
        )
        ZillitButton(
            text = "Remove",
            onClick = { onEvent(TaxFilingEvent.AskRemove(registration)) },
            variant = ButtonVariant.Tertiary,
            size = ButtonSize.Small,
            leadingIcon = ZillitIcons.Trash,
        )
    }
}
