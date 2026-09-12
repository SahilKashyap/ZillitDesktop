package com.zillit.desktop.feature.taxfiling.ui.pages

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.component.ZillitDateField
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.taxfiling.domain.TaxFormat
import com.zillit.desktop.feature.taxfiling.domain.TaxFrequency
import com.zillit.desktop.feature.taxfiling.ui.RegistrationDraft
import com.zillit.desktop.feature.taxfiling.ui.TaxFilingEvent
import com.zillit.desktop.feature.taxfiling.ui.TaxFilingUiState
import com.zillit.desktop.feature.taxfiling.ui.components.FieldShape
import com.zillit.desktop.feature.taxfiling.ui.components.MtdButton
import com.zillit.desktop.feature.taxfiling.ui.components.MtdButtonVariant
import com.zillit.desktop.feature.taxfiling.ui.components.MtdDropdown
import com.zillit.desktop.feature.taxfiling.ui.components.MtdFieldLabel
import com.zillit.desktop.feature.taxfiling.ui.components.MtdIconTile
import com.zillit.desktop.feature.taxfiling.ui.components.MtdModal
import com.zillit.desktop.feature.taxfiling.ui.components.MtdOption
import com.zillit.desktop.feature.taxfiling.ui.components.MtdTextInput
import com.zillit.desktop.feature.taxfiling.ui.components.mtdPalette
import com.zillit.desktop.feature.taxfiling.ui.components.mtdText
import com.zillit.desktop.feature.taxfiling.ui.components.rememberLast

/**
 * "Register a VAT number" — the web's `RegisterModal`.
 *
 * Only companies without a registration are offered; the number is nine
 * digits and the field keeps nothing else; the date is optional and the
 * frequency starts at quarterly, as HMRC's most common cadence.
 */
@Composable
internal fun RegisterDialog(state: TaxFilingUiState, onEvent: (TaxFilingEvent) -> Unit) {
    // Kept through the exit animation, so the fields do not blank as it closes.
    val draft = rememberLast(state.draft) ?: RegistrationDraft()
    val saving = draft.saving

    MtdModal(
        visible = state.draft != null,
        title = "Register a VAT number",
        subtitle = "Add a company's VRN so you can connect it to HMRC and file returns.",
        icon = ZillitIcons.Building,
        onDismiss = { onEvent(TaxFilingEvent.DismissDraft) },
        footer = {
            MtdButton(
                text = "Cancel",
                onClick = { onEvent(TaxFilingEvent.DismissDraft) },
                variant = MtdButtonVariant.Ghost,
                enabled = !saving,
            )
            MtdButton(
                text = if (saving) "Registering…" else "Register VRN",
                onClick = { onEvent(TaxFilingEvent.SaveRegistration) },
                variant = MtdButtonVariant.Primary,
                icon = ZillitIcons.Add,
                enabled = draft.canSubmit,
                loading = saving,
            )
        },
    ) {
        RegisterFields(draft, state, onEvent)
    }
}

@Composable
private fun RegisterFields(draft: RegistrationDraft, state: TaxFilingUiState, onEvent: (TaxFilingEvent) -> Unit) {
    val edit: (RegistrationDraft) -> Unit = { onEvent(TaxFilingEvent.EditDraft(it)) }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        CompanyField(draft, state, edit)
        NumberField(draft, edit)
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                MtdFieldLabel("Registration date")
                ZillitDateField(
                    value = draft.registrationDate,
                    onValueChange = { edit(draft.copy(registrationDate = it)) },
                    errorText = "Use YYYY-MM-DD".takeIf { draft.dateInvalid },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Column(Modifier.weight(1f)) {
                MtdFieldLabel("Filing frequency")
                MtdDropdown(
                    value = TaxFrequency.from(draft.frequency),
                    options = TaxFrequency.entries.map { MtdOption(it, it.label) },
                    onChange = { chosen -> chosen?.let { edit(draft.copy(frequency = it.wire)) } },
                    placeholder = "Select…",
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** The companies still without a registration — or a note that there are none left. */
@Composable
private fun CompanyField(draft: RegistrationDraft, state: TaxFilingUiState, edit: (RegistrationDraft) -> Unit) {
    val palette = mtdPalette()
    Column {
        MtdFieldLabel("Company")
        val options = state.availableCompanies.map { MtdOption(it.id, it.pickerLabel) }
        if (options.isEmpty()) {
            ZillitText(
                text = "Every available company is already registered.",
                style = mtdText(13.sp),
                color = palette.muted,
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, palette.border2, FieldShape)
                    .padding(horizontal = 14.dp, vertical = 11.dp),
            )
        } else {
            MtdDropdown(
                value = draft.companyId.takeIf { it.isNotBlank() },
                options = options,
                onChange = { edit(draft.copy(companyId = it.orEmpty())) },
                placeholder = "Select a company…",
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** Nine digits, counted as they are typed, ticked once they are all there. */
@Composable
private fun NumberField(draft: RegistrationDraft, edit: (RegistrationDraft) -> Unit) {
    val palette = mtdPalette()
    Column {
        MtdFieldLabel("VAT registration number (VRN)", hint = "9 digits, no spaces")
        MtdTextInput(
            value = draft.registrationNumber,
            onValueChange = { edit(draft.copy(registrationNumber = RegistrationDraft.cleanNumber(it))) },
            placeholder = "123456789",
            mono = true,
            keyboardType = KeyboardType.Number,
            modifier = Modifier.fillMaxWidth(),
            trailing = {
                when {
                    draft.numberValid -> ZillitIcon(icon = ZillitIcons.Check, tint = palette.green, size = 15.dp)
                    draft.registrationNumber.isNotEmpty() -> ZillitText(
                        text = "${draft.registrationNumber.length}/${RegistrationDraft.VRN_DIGITS}",
                        style = mtdText(11.sp, mono = true),
                        color = palette.muted,
                    )
                }
            },
        )
    }
}

/**
 * "Remove this registration?" — the web's `RemoveConfirm`, with its warning
 * word for word: what the service does to the registration, and what it keeps.
 */
@Composable
internal fun RemoveDialog(state: TaxFilingUiState, onEvent: (TaxFilingEvent) -> Unit) {
    val target = rememberLast(state.removing?.named(state.companies))
    val palette = mtdPalette()

    MtdModal(
        visible = state.removing != null,
        title = "Remove this registration?",
        onDismiss = { onEvent(TaxFilingEvent.DismissRemove) },
        width = 460.dp,
        footer = {
            MtdButton(
                text = "Cancel",
                onClick = { onEvent(TaxFilingEvent.DismissRemove) },
                variant = MtdButtonVariant.Ghost,
                enabled = !state.removeInFlight,
            )
            MtdButton(
                text = if (state.removeInFlight) "Removing…" else "Remove registration",
                onClick = { onEvent(TaxFilingEvent.ConfirmRemove) },
                variant = MtdButtonVariant.RedSolid,
                icon = ZillitIcons.Trash,
                loading = state.removeInFlight,
            )
        },
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(13.dp), verticalAlignment = Alignment.Top) {
            DangerTile()
            ZillitText(
                text = buildAnnotatedString {
                    append("Removing ")
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = palette.ink)) {
                        append(target?.companyName.orEmpty())
                    }
                    append(" (VRN ${TaxFormat.vrn(target?.registrationNumber.orEmpty())}) disconnects it from HMRC ")
                    append("and clears its obligation and return history. The box mapping is kept. ")
                    append("This can’t be undone.")
                },
                style = mtdText(13.5.sp),
                color = palette.ink2,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun DangerTile() {
    val palette = mtdPalette()
    MtdIconTile(
        icon = ZillitIcons.Trash,
        size = 40.dp,
        iconSize = 18.dp,
        radius = 11.dp,
        tint = palette.red,
        wash = palette.redWash,
        edge = palette.redBorder,
    )
}
