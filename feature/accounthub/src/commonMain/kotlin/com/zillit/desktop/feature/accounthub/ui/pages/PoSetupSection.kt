package com.zillit.desktop.feature.accounthub.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitCheckbox
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitSectionLabel
import com.zillit.desktop.core.designsystem.component.ZillitSelect
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.forms.FormModule
import com.zillit.desktop.feature.accounthub.domain.PoDescriptionFormat
import com.zillit.desktop.feature.accounthub.domain.PoSplitType
import com.zillit.desktop.feature.accounthub.domain.PurchaseOrderSetup
import com.zillit.desktop.feature.accounthub.ui.AccountHubEvent
import com.zillit.desktop.feature.accounthub.ui.AccountHubUiState
import com.zillit.desktop.feature.accounthub.ui.SetupSection

/**
 * How purchase orders read, split and number themselves.
 *
 * The web edits this in a drill-down modal *and* again on the Purchase Orders
 * module's own settings page. The desktop's PO tool has no settings surface,
 * so this section is the only place these live here — which is why it is a
 * section like its neighbours rather than a tile pointing somewhere.
 *
 * Two settings the web shows are deliberately absent. "Require an effective
 * date" and "Enforce period close" are forced on by the service whatever is
 * stored, and the web renders them as a static "Always"; a switch this client
 * could not turn off would be a lie about what happens. Amendments after
 * approval are built but paused behind the web's own flag, so there is nothing
 * to reach.
 */
@Composable
internal fun PoSetupSection(
    state: AccountHubUiState,
    onEvent: (AccountHubEvent) -> Unit,
    canAttach: Boolean,
) {
    val section = state.setup.poSetup
    val value = section.edited
    val editable = state.viewer.canEdit

    SetupSectionCard(
        title = "Purchase Order Setup",
        description = "The line description every order builds, how a rental splits into " +
            "periods, and the prefix a new order's number starts with.",
        dirty = section.dirty,
        saving = section.saving,
        onSave = { onEvent(AccountHubEvent.SaveSection(SetupSection.PoSetup)) },
        onRevert = { onEvent(AccountHubEvent.RevertSection(SetupSection.PoSetup)) },
        editable = editable,
    ) {
        DescriptionFormatField(value, editable, onEvent)
        RentalSplitFields(value, editable, onEvent)
        NumberPrefixField(value, editable, onEvent)
        TermsDocumentField(state, value, editable && canAttach, onEvent)
        FormFieldsField(onEvent)
    }
}

/**
 * The way through to this module's form.
 *
 * The web puts the same card here, and again on the Purchase Orders module's
 * own settings page. Here it hands off to the console's own Forms
 * Configuration screen rather than opening an overlay: this is one window with
 * a sidebar, and the page it goes to is already in it.
 */
@Composable
private fun FormFieldsField(onEvent: (AccountHubEvent) -> Unit) {
    ZillitSectionLabel("Form fields")
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitText(
            text = "Which fields and sections a purchase order shows, and which are required.",
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textSecondary,
            modifier = Modifier.weight(1f),
        )
        ZillitButton(
            text = "Configure form",
            onClick = { onEvent(AccountHubEvent.OpenFormConfig(FormModule.PurchaseOrders)) },
            variant = ButtonVariant.Secondary,
            size = ButtonSize.Small,
            leadingIcon = ZillitIcons.Edit,
        )
    }
}

@Composable
private fun DescriptionFormatField(
    value: PurchaseOrderSetup,
    editable: Boolean,
    onEvent: (AccountHubEvent) -> Unit,
) {
    ZillitSectionLabel("Description format")
    ZillitSelect(
        value = value.descriptionFormat,
        options = PoDescriptionFormat.entries,
        onSelect = { onEvent(AccountHubEvent.EditPoSetup(value.copy(descriptionFormat = it))) },
        label = { it.label },
        enabled = editable,
        modifier = Modifier.fillMaxWidth(),
    )
    // The sample, not the pattern: nobody reads DDMON_ITEM and pictures the
    // line it produces.
    ZillitText(
        text = "Looks like: ${value.descriptionFormat.sample}",
        style = ZillitTheme.typography.bodySmall,
        color = ZillitTheme.colors.textSecondary,
    )
}

@Composable
private fun RentalSplitFields(
    value: PurchaseOrderSetup,
    editable: Boolean,
    onEvent: (AccountHubEvent) -> Unit,
) {
    ZillitSectionLabel("Rentals")
    ZillitCheckbox(
        checked = value.autoSplitRentals,
        onCheckedChange = { onEvent(AccountHubEvent.EditPoSetup(value.copy(autoSplitRentals = it))) },
        label = "Split rental orders into periods automatically",
        enabled = editable,
    )
    if (value.autoSplitRentals) {
        ZillitSelect(
            value = value.splitType,
            options = PoSplitType.entries,
            onSelect = { onEvent(AccountHubEvent.EditPoSetup(value.copy(splitType = it))) },
            label = { it.label },
            enabled = editable,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun NumberPrefixField(
    value: PurchaseOrderSetup,
    editable: Boolean,
    onEvent: (AccountHubEvent) -> Unit,
) {
    ZillitSectionLabel("Numbering")
    ZillitTextField(
        value = value.numberPrefix,
        onValueChange = { text ->
            // Normalised as it is typed: the field only ever holds what would
            // be sent, so what is on screen is what the next order gets.
            onEvent(
                AccountHubEvent.EditPoSetup(
                    value.copy(numberPrefix = PurchaseOrderSetup.normalisePrefix(text)),
                ),
            )
        },
        label = "Order number prefix",
        placeholder = "Up to ${PurchaseOrderSetup.PREFIX_MAX} letters or digits",
        enabled = editable,
    )
    ZillitText(
        text = "Goes at the start of every new order number. Orders already raised keep theirs.",
        style = ZillitTheme.typography.bodySmall,
        color = ZillitTheme.colors.textSecondary,
    )
}

@Composable
private fun TermsDocumentField(
    state: AccountHubUiState,
    value: PurchaseOrderSetup,
    editable: Boolean,
    onEvent: (AccountHubEvent) -> Unit,
) {
    val terms = value.termsDocument

    ZillitSectionLabel("Terms and conditions")
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitText(
            text = terms?.title?.ifBlank { terms.name } ?: "No document issued with orders.",
            style = ZillitTheme.typography.bodySmall,
            color = if (terms == null) ZillitTheme.colors.textSecondary else ZillitTheme.colors.textPrimary,
            modifier = Modifier.weight(1f),
        )
        if (editable) {
            ZillitButton(
                text = if (terms == null) "Attach PDF" else "Replace",
                onClick = { onEvent(AccountHubEvent.PickPoTerms) },
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
                leadingIcon = ZillitIcons.Paperclip,
                loading = state.setup.poTermsUploading,
                enabled = !state.setup.poTermsUploading,
            )
            if (terms != null) {
                ZillitButton(
                    text = "Remove",
                    onClick = { onEvent(AccountHubEvent.ClearPoTerms) },
                    variant = ButtonVariant.Tertiary,
                    size = ButtonSize.Small,
                    enabled = !state.setup.poTermsUploading,
                )
            }
        }
    }
    if (!editable && state.viewer.canEdit) {
        ZillitNotice(
            text = "Attaching is unavailable — this window has no file storage wired.",
            tone = StatusTone.Neutral,
            icon = ZillitIcons.Info,
        )
    }
}
