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
import com.zillit.desktop.core.designsystem.component.zillitVerticalScroll
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitCheckbox
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitSelect
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
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
            CreateStage.Editing -> "Start a project"
            is CreateStage.VerifyingEmail -> "Confirm your email"
            is CreateStage.Created -> "Project created"
        },
        subtitle = when (state.stage) {
            CreateStage.Editing -> "Name it and say who runs it — a minute of form."
            is CreateStage.VerifyingEmail -> "One code, and the project is yours."
            is CreateStage.Created -> "Share the code and the crew can join."
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
    val draft = state.draft
    fun update(block: NewProductionDraft.() -> NewProductionDraft) =
        onEvent(CreateProductionEvent.DraftChanged(draft.block()))

    Column(
        modifier = Modifier.zillitVerticalScroll().weight(1f, fill = false),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        SectionLabel("Who you are")
        WhoSection(state, ::update)
        SectionLabel("The project")
        WhatSection(state, ::update)
        SectionLabel("Contact")
        ContactSection(state, ::update)
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        ZillitButton(
            text = "Continue",
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
            label = "First name",
            errorText = state.fieldErrors[ProductionField.FirstName],
            modifier = Modifier.weight(1f),
        )
        ZillitTextField(
            value = draft.lastName,
            onValueChange = { v -> update { copy(lastName = v) } },
            label = "Last name",
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
        label = "Project name",
        errorText = state.fieldErrors[ProductionField.ProductionName],
    )

    LabelledSelect("Project type", state.fieldErrors[ProductionField.Type]) {
        ZillitSelect(
            value = state.selectedType,
            options = state.types,
            // Changing type invalidates the sub-type — leaving the old one would
            // submit a sub-type that does not belong to the new type.
            onSelect = { type -> update { copy(typeId = type?.id, subType = null, customSubType = "") } },
            label = { it?.label ?: "Choose a type" },
            modifier = Modifier.fillMaxWidth(),
        )
    }

    if (state.subTypeOptions.isNotEmpty()) {
        LabelledSelect("Sub-type", state.fieldErrors[ProductionField.SubType]) {
            ZillitSelect(
                value = draft.subType,
                options = state.subTypeOptions,
                onSelect = { sub -> update { copy(subType = sub) } },
                label = { it?.subTypeLabel() ?: "Choose a sub-type" },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    if (state.showsCustomSubType) {
        ZillitTextField(
            value = draft.customSubType,
            onValueChange = { v -> update { copy(customSubType = v) } },
            label = "New sub-type",
            errorText = state.fieldErrors[ProductionField.CustomSubType],
        )
    }

    LabelledSelect("Language", state.fieldErrors[ProductionField.Language]) {
        ZillitSelect(
            value = state.languages.firstOrNull { it.code == draft.languageCode },
            options = state.languages,
            onSelect = { language -> update { copy(languageCode = language?.code) } },
            label = { it?.name ?: "Choose a language" },
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
        label = "Email",
        placeholder = "you@production.com",
        helperText = "We'll send a one-time code here to confirm it.",
        errorText = state.fieldErrors[ProductionField.Email],
        keyboardType = KeyboardType.Email,
    )

    Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
        ZillitTextField(
            value = draft.countryCode,
            onValueChange = { v -> update { copy(countryCode = v.filter(Char::isDigit)) } },
            label = "Country code",
            placeholder = "44",
            keyboardType = KeyboardType.Number,
            modifier = Modifier.width(COUNTRY_CODE_WIDTH),
        )
        ZillitTextField(
            value = draft.phone,
            onValueChange = { v -> update { copy(phone = v.filter(Char::isDigit)) } },
            label = "Contact number (optional)",
            errorText = state.fieldErrors[ProductionField.Phone],
            keyboardType = KeyboardType.Number,
            modifier = Modifier.weight(1f),
        )
    }

    ZillitCheckbox(
        checked = draft.agreedToTerms,
        onCheckedChange = { v -> update { copy(agreedToTerms = v) } },
        label = "I accept the Zillit terms of use and privacy policy.",
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
        text = "We sent a code to $email. Enter it to create the project.",
        style = ZillitTheme.typography.bodyMedium,
        color = ZillitTheme.colors.textSecondary,
    )

    ZillitTextField(
        value = state.otp,
        onValueChange = { onEvent(CreateProductionEvent.OtpChanged(it)) },
        label = "Verification code",
        placeholder = "000000",
        keyboardType = KeyboardType.Number,
        imeAction = ImeAction.Go,
        maxLength = OTP_MAX_LENGTH,
        enabled = !state.isBusy,
        onImeAction = { if (state.canSubmitOtp) onEvent(CreateProductionEvent.VerifyOtp) },
    )

    ZillitButton(
        text = "Create project",
        onClick = { onEvent(CreateProductionEvent.VerifyOtp) },
        modifier = Modifier.fillMaxWidth(),
        enabled = state.canSubmitOtp,
        loading = state.isBusy,
    )

    Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
        ZillitButton(
            text = "Back",
            onClick = { onEvent(CreateProductionEvent.BackToForm) },
            modifier = Modifier.weight(1f),
            variant = ButtonVariant.Tertiary,
            enabled = !state.isBusy,
        )
        ZillitButton(
            text = "Resend",
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
        text = "${stage.project.name} is ready. Share this code with your crew so they can join.",
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
        text = "Done",
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
    if (this == ProductionType.ADD_NEW_SUB_TYPE) "Add a new sub-type…" else humanise()

private val DIALOG_WIDTH = 560.dp
private val DIALOG_MAX_HEIGHT = 720.dp
private val COUNTRY_CODE_WIDTH = 120.dp
private val HAIRLINE = 1.dp
private const val OTP_MAX_LENGTH = 6
