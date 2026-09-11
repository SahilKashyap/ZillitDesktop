package com.zillit.desktop.feature.taxfiling.ui.pages

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitSelect
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.taxfiling.domain.TaxCompany
import com.zillit.desktop.feature.taxfiling.ui.RegistrationDraft
import com.zillit.desktop.feature.taxfiling.ui.TaxFilingEvent
import com.zillit.desktop.feature.taxfiling.ui.TaxFilingUiState

private const val NO_COMPANY = ""

/** Adds a company's enrolment. Authorising HMRC is a separate step after it. */
@Composable
fun RegistrationDialog(state: TaxFilingUiState, onEvent: (TaxFilingEvent) -> Unit) {
    val draft = state.draft
    ZillitDialogShell(
        title = "Add a VAT registration",
        subtitle = "The company, its VAT number, and how often it files.",
        icon = ZillitIcons.Receipt,
        visible = draft != null,
        onDismiss = { onEvent(TaxFilingEvent.DismissDraft) },
        actions = {
            ZillitButton(
                text = "Cancel",
                onClick = { onEvent(TaxFilingEvent.DismissDraft) },
                variant = ButtonVariant.Tertiary,
            )
            ZillitButton(
                text = "Add registration",
                onClick = { onEvent(TaxFilingEvent.SaveRegistration) },
                enabled = draft?.problem == null,
                loading = draft?.saving == true,
            )
        },
    ) {
        if (draft == null) return@ZillitDialogShell
        RegistrationFields(draft, state.companies, onEvent)
    }
}

@Composable
private fun ColumnScope.RegistrationFields(
    draft: RegistrationDraft,
    companies: List<TaxCompany>,
    onEvent: (TaxFilingEvent) -> Unit,
) {
    ZillitText(text = "Company", style = ZillitTheme.typography.label)
    ZillitSelect(
        value = draft.companyId,
        options = listOf(NO_COMPANY) + companies.map { it.id },
        onSelect = { onEvent(TaxFilingEvent.EditDraft(draft.copy(companyId = it))) },
        label = { id ->
            companies.firstOrNull { it.id == id }?.name ?: "Choose a company"
        },
        modifier = Modifier.fillMaxWidth(),
    )

    ZillitTextField(
        value = draft.registrationNumber,
        onValueChange = { onEvent(TaxFilingEvent.EditDraft(draft.copy(registrationNumber = it))) },
        label = "VAT registration number",
        placeholder = "123456789",
        helperText = "Nine digits. A GB prefix or spaces are fine.",
        errorText = "A VAT registration number is nine digits."
            .takeIf { draft.registrationNumber.isNotBlank() && draft.digits.length != NINE },
        modifier = Modifier.fillMaxWidth(),
    )

    ZillitText(text = "Filing frequency", style = ZillitTheme.typography.label)
    ZillitSelect(
        value = draft.frequency,
        options = RegistrationDraft.frequencies,
        onSelect = { onEvent(TaxFilingEvent.EditDraft(draft.copy(frequency = it))) },
        label = { it.replaceFirstChar(Char::uppercase) },
        modifier = Modifier.fillMaxWidth(),
    )
}

private const val NINE = 9

/**
 * Removes an enrolment from Zillit.
 *
 * Says what it does not do: HMRC's own authorisation is a grant held at HMRC,
 * and removing the row here does not withdraw it.
 */
@Composable
fun RemoveRegistrationDialog(state: TaxFilingUiState, onEvent: (TaxFilingEvent) -> Unit) {
    val registration = state.removing
    ZillitDialogShell(
        title = "Remove this registration?",
        subtitle = registration?.companyName?.takeIf { it.isNotBlank() },
        icon = ZillitIcons.Warning,
        visible = registration != null,
        onDismiss = { onEvent(TaxFilingEvent.DismissRemove) },
        actions = {
            ZillitButton(
                text = "Keep it",
                onClick = { onEvent(TaxFilingEvent.DismissRemove) },
                variant = ButtonVariant.Tertiary,
            )
            ZillitButton(
                text = "Remove",
                onClick = { onEvent(TaxFilingEvent.ConfirmRemove) },
                variant = ButtonVariant.Danger,
            )
        },
    ) {
        ZillitText(
            text = "Returns already filed for ${registration?.registrationNumber.orEmpty()} stay " +
                "filed. This only stops Zillit from filing for it.",
            style = ZillitTheme.typography.bodyMedium,
        )
        ZillitNotice(
            text = "Withdrawing Zillit's access to HMRC is done in the company's own HMRC " +
                "account, not here.",
            tone = StatusTone.Pending,
            icon = ZillitIcons.Info,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
