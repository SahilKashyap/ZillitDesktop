// The page shell; the department and accountant bodies live under ui/pages.
@file:Suppress("LongMethod", "TooManyFunctions", "CyclomaticComplexMethod")

package com.zillit.desktop.feature.invoices.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitPageHeader
import com.zillit.desktop.core.designsystem.component.ZillitTab
import com.zillit.desktop.core.designsystem.component.ZillitTabStrip
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.invoices.ui.pages.AccountantPageContent
import com.zillit.desktop.feature.invoices.ui.pages.DeleteDialog
import com.zillit.desktop.feature.invoices.ui.pages.DepartmentPage
import com.zillit.desktop.feature.invoices.ui.pages.EnterInvoiceDialog
import com.zillit.desktop.feature.invoices.ui.pages.InvoiceDetailDialog
import com.zillit.desktop.feature.invoices.ui.pages.UploadDialog

/**
 * Invoices: the department board for crew, the register / inbox / approval
 * queue for the accounts department, and the shared detail dialog.
 */
@Composable
fun InvoicesScreen(
    state: InvoicesUiState,
    onEvent: (InvoicesEvent) -> Unit,
    nowMs: Long,
) {
    val accountant = state.isAccountant
    Box(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(ZillitTheme.spacing.lg),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        ) {
            ZillitPageHeader(
                title = "Invoices",
                description = if (accountant) {
                    "Accounts payable — the register, the inbox and the approval queue."
                } else {
                    "Supplier invoices: the ones waiting on you, your department's, and your own uploads."
                },
                actions = {
                    ZillitButton(
                        text = "Refresh",
                        onClick = { onEvent(InvoicesEvent.Refresh) },
                        variant = ButtonVariant.Tertiary,
                        loading = state.loading,
                    )
                    if (accountant && state.page == AccountantPage.Inbox) {
                        ZillitButton(
                            text = "Enter Invoice",
                            onClick = { onEvent(InvoicesEvent.OpenEnter) },
                            leadingIcon = ZillitIcons.Add,
                        )
                    }
                    if (!accountant && state.viewer.mayPost) {
                        ZillitButton(
                            text = "Upload Invoice",
                            onClick = { onEvent(InvoicesEvent.UploadInvoice) },
                            leadingIcon = ZillitIcons.Upload,
                            enabled = state.upload == null,
                        )
                    }
                },
            )
            if (state.viewer.isBlocked) ZillitNotice(text = "You do not have access to the Invoices tool.")
            state.error?.let { message ->
                ZillitNotice(
                    text = message,
                    tone = StatusTone.Rejected,
                    action = {
                        ZillitButton(
                            text = "Dismiss",
                            onClick = { onEvent(InvoicesEvent.DismissError) },
                            variant = ButtonVariant.Tertiary,
                            size = ButtonSize.Small,
                        )
                    },
                )
            }
            if (accountant) {
                ZillitTabStrip(
                    tabs = AccountantPage.entries.map { ZillitTab(it.id, it.label) },
                    activeId = state.page.id,
                    onSelect = { id ->
                        onEvent(InvoicesEvent.SelectPage(AccountantPage.entries.first { it.id == id }))
                    },
                )
                AccountantPageContent(state, onEvent, nowMs)
            } else {
                DepartmentPage(state, onEvent)
            }
        }
        state.detail?.let { InvoiceDetailDialog(state, it, onEvent) }
        state.upload?.let { UploadDialog(state, it, onEvent) }
        state.enter?.let { EnterInvoiceDialog(state, it, onEvent) }
        state.confirmDelete?.let { DeleteDialog(state, it, onEvent) }
    }
}
