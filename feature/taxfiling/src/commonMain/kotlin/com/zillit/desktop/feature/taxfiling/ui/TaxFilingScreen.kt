package com.zillit.desktop.feature.taxfiling.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitPageHeader
import com.zillit.desktop.core.designsystem.component.ZillitScrollColumn
import com.zillit.desktop.core.designsystem.component.ZillitToast
import com.zillit.desktop.core.designsystem.component.ZillitToastTone
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.taxfiling.ui.pages.RegistrationDialog
import com.zillit.desktop.feature.taxfiling.ui.pages.RegistrationsPage
import com.zillit.desktop.feature.taxfiling.ui.pages.RemoveRegistrationDialog
import com.zillit.desktop.feature.taxfiling.ui.pages.ReturnPage
import com.zillit.desktop.feature.taxfiling.ui.pages.SubmitDialog

/**
 * Tax filing — HMRC's Making Tax Digital for VAT.
 *
 * Two surfaces, one behind the other: the companies enrolled with the
 * authority, and then one company's periods and returns.
 */
@Composable
fun TaxFilingScreen(state: TaxFilingUiState, onEvent: (TaxFilingEvent) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Header(state, onEvent)

        ZillitScrollColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(ZillitTheme.spacing.lg),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        ) {
            AuthorityWarning(state)
            when (state.view) {
                TaxFilingView.Registrations -> RegistrationsPage(state, onEvent)
                TaxFilingView.Return -> ReturnPage(state.returnState, state.canReachAuthority, onEvent)
            }
        }
    }

    RegistrationDialog(state, onEvent)
    RemoveRegistrationDialog(state, onEvent)
    SubmitDialog(state.returnState, onEvent)

    ZillitToast(
        message = state.notice,
        onDismiss = { onEvent(TaxFilingEvent.ClearNotice) },
        tone = ZillitToastTone.Success,
    )
}

@Composable
private fun Header(state: TaxFilingUiState, onEvent: (TaxFilingEvent) -> Unit) {
    val onReturns = state.view == TaxFilingView.Return
    ZillitPageHeader(
        title = if (onReturns) {
            state.returnState.registration?.companyName?.ifBlank { "VAT return" } ?: "VAT return"
        } else {
            "Tax filing"
        },
        eyebrow = "HMRC Making Tax Digital",
        description = if (onReturns) {
            state.returnState.registration?.registrationNumber?.let { "VAT $it" }
        } else {
            "Companies enrolled to file VAT through Zillit."
        },
        actions = {
            if (onReturns) {
                ZillitButton(
                    text = "All registrations",
                    onClick = { onEvent(TaxFilingEvent.BackToRegistrations) },
                    variant = ButtonVariant.Tertiary,
                    leadingIcon = ZillitIcons.ArrowLeft,
                )
            } else {
                ZillitButton(
                    text = "Refresh",
                    onClick = { onEvent(TaxFilingEvent.Load) },
                    variant = ButtonVariant.Tertiary,
                    leadingIcon = ZillitIcons.Reload,
                    loading = state.loading,
                )
                ZillitButton(
                    text = "Add registration",
                    onClick = { onEvent(TaxFilingEvent.ComposeRegistration) },
                    leadingIcon = ZillitIcons.Add,
                )
            }
        },
    )
}

/**
 * Said once, at the top, when this machine cannot be described to HMRC.
 *
 * The two calls that reach the authority are refused in that state, and an
 * accountant deserves to know that before they map seven boxes.
 */
@Composable
private fun ColumnScope.AuthorityWarning(state: TaxFilingUiState) {
    if (state.canReachAuthority) return
    ZillitNotice(
        text = "This installation cannot send HMRC the machine details every filing requires, " +
            "so periods cannot be refreshed and nothing can be filed from here.",
        tone = StatusTone.Rejected,
        icon = ZillitIcons.Warning,
        modifier = Modifier.fillMaxWidth(),
    )
}
