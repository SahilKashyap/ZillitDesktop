package com.zillit.desktop.feature.auth.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitCheckbox
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitSelect
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.auth.domain.NewProductionDraft
import com.zillit.desktop.feature.auth.domain.ProductionField
import com.zillit.desktop.feature.auth.domain.ProductionLanguage
import com.zillit.desktop.feature.auth.data.humanise
import com.zillit.desktop.feature.auth.domain.ProductionType

/**
 * Creating a production — a port of the web's `StartProject` modal.
 *
 * Three stages behind one dialog: the form, the email check, and the code the
 * server hands back. The web shows the code in a second modal stacked on the
 * first; here it replaces the body, because a dialog over a dialog is a thing
 * desktop users have to dismiss twice.
 */
@Composable
internal fun CreateProductionDialog(
    state: CreateProductionUiState,
    onEvent: (CreateProductionEvent) -> Unit,
    onDismiss: () -> Unit,
    visible: Boolean,
    modifier: Modifier = Modifier,
) {
    ZillitDialogShell(
        visible = visible,
        title = when (state.stage) {
            CreateStage.Editing -> str(S.desktop_start_a_project)
            is CreateStage.VerifyingEmail -> str(S.desktop_confirm_your_email)
            is CreateStage.Created -> str(S.desktop_project_created)
        },
        subtitle = when (state.stage) {
            CreateStage.Editing -> str(S.desktop_create_project_editing_subtitle)
            is CreateStage.VerifyingEmail -> str(S.desktop_create_project_verifying_subtitle)
            is CreateStage.Created -> str(S.desktop_create_project_created_subtitle)
        },
        icon = when (state.stage) {
            CreateStage.Editing -> ZillitIcons.Add
            is CreateStage.VerifyingEmail -> ZillitIcons.Mail
            is CreateStage.Created -> ZillitIcons.Check
        },
        onDismiss = onDismiss,
        width = DIALOG_WIDTH,
        maxHeight = DIALOG_MAX_HEIGHT,
        modifier = modifier,
    ) {
        when (val stage = state.stage) {
            CreateStage.Editing -> ProductionForm(state, onEvent)
            is CreateStage.VerifyingEmail -> EmailVerification(stage.email, state, onEvent)
            is CreateStage.Created -> CreatedSummary(stage, onDismiss)
        }

        state.error?.let {
            ZillitText(
                text = it,
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.danger,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(ZillitTheme.shapes.small)
                    .background(ZillitTheme.colors.dangerSoft)
                    .padding(ZillitTheme.spacing.sm),
            )
        }
    }
}

/**
 * A quiet heading between form sections — the label and a hairline, so a
 * twelve-field form reads as three questions instead of one wall.
 */
@Composable
private fun SectionLabel(text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        ZillitText(
            text = text,
            style = ZillitTheme.typography.label,
            color = ZillitTheme.colors.textMuted,
        )
        Box(
            Modifier
                .weight(1f)
                .height(HAIRLINE)
                .background(ZillitTheme.colors.border),
        )
    }
}

@Composable
private fun ColumnScope.ProductionForm(
    state: CreateProductionUiState,
    onEvent: (CreateProductionEvent) -> Unit,
) {
    // Sends the edit, never a finished draft — see CreateProductionEvent.DraftEdited.
    fun update(block: NewProductionDraft.() -> NewProductionDraft) =
        onEvent(CreateProductionEvent.DraftEdited(block))

    // A plain Column: ZillitDialogShell's body already scrolls. A second scroll
    // with a `weight` inside it measured the form under an infinite height,
    // where the weight resolves to zero — so the form was never placed. The
    // dialog opened as a card with a title and a Continue button and no fields,
    // which is the "Start Project does nothing" report; Continue looked dead
    // too, since its validation errors render inside the same missing form.
    Column(
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        SectionLabel(str(S.desktop_section_who_you_are))
        WhoSection(state, ::update)
        SectionLabel(str(S.desktop_section_the_project))
        WhatSection(state, ::update)
        SectionLabel(str(S.contact))
        ContactSection(state, ::update)
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        ZillitButton(
            text = str(S.continue_text),
            onClick = { onEvent(CreateProductionEvent.Submit) },
            modifier = Modifier.weight(1f),
            loading = state.isBusy,
        )
    }
}

/** Who is creating it. */
@Composable
private fun WhoSection(state: CreateProductionUiState, update: (NewProductionDraft.() -> NewProductionDraft) -> Unit) {
    val draft = state.draft
    Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
        ZillitTextField(
            value = draft.firstName,
            onValueChange = { v -> update { copy(firstName = v) } },
            label = str(S.first_name_label),
            errorText = state.fieldErrors[ProductionField.FirstName],
            modifier = Modifier.weight(1f),
        )
        ZillitTextField(
            value = draft.lastName,
            onValueChange = { v -> update { copy(lastName = v) } },
            label = str(S.last_name_label),
            errorText = state.fieldErrors[ProductionField.LastName],
            modifier = Modifier.weight(1f),
        )
    }
}

/** What the production is: name, type, sub-type, language. */
@Composable
private fun ColumnScope.WhatSection(
    state: CreateProductionUiState,
    update: (NewProductionDraft.() -> NewProductionDraft) -> Unit,
) {
    val draft = state.draft

    ZillitTextField(
        value = draft.productionName,
        onValueChange = { v -> update { copy(productionName = v) } },
        label = str(S.project_name),
        errorText = state.fieldErrors[ProductionField.ProductionName],
    )

    LabelledSelect(str(S.project_type), state.fieldErrors[ProductionField.Type]) {
        ZillitSelect(
            value = state.selectedType,
            options = state.types,
            // Changing type invalidates the sub-type — leaving the old one would
            // submit a sub-type that does not belong to the new type.
            onSelect = { type -> update { copy(typeId = type?.id, subType = null, customSubType = "") } },
            label = { it?.label ?: str(S.desktop_choose_a_type) },
            modifier = Modifier.fillMaxWidth(),
        )
    }

    if (state.subTypeOptions.isNotEmpty()) {
        LabelledSelect(str(S.desktop_sub_type), state.fieldErrors[ProductionField.SubType]) {
            ZillitSelect(
                value = draft.subType,
                options = state.subTypeOptions,
                onSelect = { sub -> update { copy(subType = sub) } },
                label = { it?.subTypeLabel() ?: str(S.desktop_choose_a_sub_type) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    if (state.showsCustomSubType) {
        ZillitTextField(
            value = draft.customSubType,
            onValueChange = { v -> update { copy(customSubType = v) } },
            label = str(S.desktop_new_sub_type),
            errorText = state.fieldErrors[ProductionField.CustomSubType],
        )
    }

    LabelledSelect(str(S.desktop_language), state.fieldErrors[ProductionField.Language]) {
        ZillitSelect(
            value = state.languages.firstOrNull { it.code == draft.languageCode },
            options = state.languages,
            onSelect = { language -> update { copy(languageCode = language?.code) } },
            label = { it?.name ?: str(S.desktop_choose_a_language) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** How to reach the creator, plus the terms gate. */
@Composable
private fun ColumnScope.ContactSection(
    state: CreateProductionUiState,
    update: (NewProductionDraft.() -> NewProductionDraft) -> Unit,
) {
    val draft = state.draft

    ZillitTextField(
        value = draft.email,
        onValueChange = { v -> update { copy(email = v) } },
        label = str(S.email),
        placeholder = "you@production.com",
        helperText = str(S.desktop_email_code_helper),
        errorText = state.fieldErrors[ProductionField.Email],
        keyboardType = KeyboardType.Email,
    )

    Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
        ZillitTextField(
            value = draft.countryCode,
            onValueChange = { v -> update { copy(countryCode = v.filter(Char::isDigit)) } },
            label = str(S.dm_loanout_country_code),
            placeholder = "44",
            keyboardType = KeyboardType.Number,
            modifier = Modifier.width(COUNTRY_CODE_WIDTH),
        )
        ZillitTextField(
            value = draft.phone,
            onValueChange = { v -> update { copy(phone = v.filter(Char::isDigit)) } },
            label = str(S.desktop_contact_number_optional),
            errorText = state.fieldErrors[ProductionField.Phone],
            keyboardType = KeyboardType.Number,
            modifier = Modifier.weight(1f),
        )
    }

    ZillitCheckbox(
        checked = draft.agreedToTerms,
        onCheckedChange = { v -> update { copy(agreedToTerms = v) } },
        label = str(S.desktop_accept_terms_label),
        errorText = state.fieldErrors[ProductionField.Terms],
    )
}

/**
 * The email step.
 *
 * Its own stage rather than an inline field: the address being created against
 * is not necessarily the one this device signed in with, so it has to be proved
 * separately, and that is a different thing to ask than "fill in this form".
 */
@Composable
private fun EmailVerification(
    email: String,
    state: CreateProductionUiState,
    onEvent: (CreateProductionEvent) -> Unit,
) {
    ZillitText(
        text = str(S.desktop_create_project_code_sent, email),
        style = ZillitTheme.typography.bodyMedium,
        color = ZillitTheme.colors.textSecondary,
    )

    ZillitTextField(
        value = state.otp,
        onValueChange = { onEvent(CreateProductionEvent.OtpChanged(it)) },
        label = str(S.desktop_verification_code),
        placeholder = "000000",
        keyboardType = KeyboardType.Number,
        imeAction = ImeAction.Go,
        maxLength = OTP_MAX_LENGTH,
        enabled = !state.isBusy,
        onImeAction = { if (state.canSubmitOtp) onEvent(CreateProductionEvent.VerifyOtp) },
    )

    ZillitButton(
        text = str(S.cs_create_project),
        onClick = { onEvent(CreateProductionEvent.VerifyOtp) },
        modifier = Modifier.fillMaxWidth(),
        enabled = state.canSubmitOtp,
        loading = state.isBusy,
    )

    Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
        ZillitButton(
            text = str(S.back),
            onClick = { onEvent(CreateProductionEvent.BackToForm) },
            modifier = Modifier.weight(1f),
            variant = ButtonVariant.Tertiary,
            enabled = !state.isBusy,
        )
        ZillitButton(
            text = str(S.desktop_resend),
            onClick = { onEvent(CreateProductionEvent.ResendOtp) },
            modifier = Modifier.weight(1f),
            variant = ButtonVariant.Tertiary,
            enabled = !state.isBusy,
        )
    }
}

/**
 * The production code, which is the whole point of this screen.
 *
 * Crew join with it, and it is not shown anywhere else in this flow — so it gets
 * the full width and a monospace-weight treatment rather than a line of body
 * text the user will close past.
 */
@Composable
private fun CreatedSummary(stage: CreateStage.Created, onDismiss: () -> Unit) {
    ZillitText(
        text = str(S.desktop_project_ready_share_code, stage.project.name),
        style = ZillitTheme.typography.bodyMedium,
        color = ZillitTheme.colors.textSecondary,
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.medium)
            .background(ZillitTheme.colors.accentSoft)
            .padding(ZillitTheme.spacing.xl),
        contentAlignment = Alignment.Center,
    ) {
        ZillitText(
            text = stage.project.code,
            style = ZillitTheme.typography.displayLarge,
            color = ZillitTheme.colors.accentText,
        )
    }

    ZillitButton(
        text = str(S.done_text),
        onClick = onDismiss,
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * A select with a label above it and an error below.
 *
 * [ZillitSelect] has neither, on purpose — it is also used in the toolbar on the
 * production list, where a floating label would be wrong. This is the form
 * dressing, kept here rather than pushed into the design system for one caller.
 */
@Composable
private fun LabelledSelect(
    label: String,
    error: String?,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
        ZillitText(
            text = label,
            style = ZillitTheme.typography.label,
            color = ZillitTheme.colors.textSecondary,
        )
        content()
        error?.let {
            ZillitText(
                text = it,
                style = ZillitTheme.typography.labelSmall,
                color = ZillitTheme.colors.danger,
            )
        }
    }
}

/**
 * The "add new" sentinel is an internal token, never a label — and the real
 * sub-types are translation keys (`feature_label`), so they need humanising too.
 */
private fun String.subTypeLabel(): String =
    if (this == ProductionType.ADD_NEW_SUB_TYPE) str(S.desktop_add_a_new_sub_type) else humanise()

private val DIALOG_WIDTH = 560.dp
private val DIALOG_MAX_HEIGHT = 720.dp
private val COUNTRY_CODE_WIDTH = 120.dp
private val HAIRLINE = 1.dp
private const val OTP_MAX_LENGTH = 6
