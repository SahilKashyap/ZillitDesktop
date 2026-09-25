package com.zillit.desktop.feature.auth.ui

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.mvvm.ZillitViewModel
import com.zillit.desktop.feature.auth.domain.AuthRepository
import com.zillit.desktop.feature.auth.domain.NewProductionDraft
import com.zillit.desktop.feature.auth.domain.PresetRepository
import com.zillit.desktop.feature.auth.domain.ProductionField
import com.zillit.desktop.feature.auth.domain.ProductionLanguage
import com.zillit.desktop.feature.auth.domain.ProductionType
import com.zillit.desktop.feature.auth.domain.Project
import com.zillit.desktop.feature.auth.domain.ProjectRepository
import com.zillit.desktop.feature.auth.domain.validate

/**
 * Where the create-production flow is.
 *
 * Three stages, not one form: the email has to be proved before the production
 * can be created, and the code the server returns has to be shown afterwards or
 * the user has no way to invite anyone.
 */
sealed interface CreateStage {

    /** Filling in the form. */
    data object Editing : CreateStage

    /** A code was emailed; waiting for the user to type it back. */
    data class VerifyingEmail(val email: String) : CreateStage

    /** Created. [project] carries the code crew use to join. */
    data class Created(val project: Project) : CreateStage
}

data class CreateProductionUiState(
    val draft: NewProductionDraft = NewProductionDraft(),
    val stage: CreateStage = CreateStage.Editing,
    val types: List<ProductionType> = emptyList(),
    val languages: List<ProductionLanguage> = emptyList(),
    val otp: String = "",
    val isBusy: Boolean = false,
    /** Per-field messages, so each sits under its own input. */
    val fieldErrors: Map<ProductionField, String> = emptyMap(),
    /** Anything not attributable to one field — a failed request, mostly. */
    val error: String? = null,
) {
    val selectedType: ProductionType? get() = types.firstOrNull { it.id == draft.typeId }

    /** The sub-types to offer, including the "add new" sentinel where allowed. */
    val subTypeOptions: List<String>
        get() = selectedType?.let { type ->
            if (type.allowsCustomSubType) {
                type.subTypes + ProductionType.ADD_NEW_SUB_TYPE
            } else {
                type.subTypes
            }
        }.orEmpty()

    val showsCustomSubType: Boolean get() = draft.needsCustomSubType(selectedType)

    val canSubmitOtp: Boolean get() = otp.length >= MIN_OTP && !isBusy

    private companion object {
        const val MIN_OTP = 4
    }
}

sealed interface CreateProductionEvent {
    /** The dialog opened — fetch the reference lists it needs. */
    data object Opened : CreateProductionEvent

    /**
     * One edit to the form — "set the type to X" — rather than a finished draft.
     *
     * The dialog used to build the whole new draft itself from the `draft` it
     * had captured when it last composed, and send that. The dropdowns held on
     * to a stale copy of that callback, so picking a type submitted the draft
     * as it was when the dialog opened: everything typed before it was wiped,
     * and picking a language then wiped the type. An edit applied to the state
     * as it is NOW cannot be stale, whichever callback carries it.
     */
    data class DraftEdited(val edit: NewProductionDraft.() -> NewProductionDraft) : CreateProductionEvent
    data class OtpChanged(val value: String) : CreateProductionEvent

    /** Validate, then send the verification code. */
    data object Submit : CreateProductionEvent
    data object VerifyOtp : CreateProductionEvent
    data object ResendOtp : CreateProductionEvent
    data object BackToForm : CreateProductionEvent
    data object DismissError : CreateProductionEvent
}

sealed interface CreateProductionEffect {
    /** The production exists; the list behind the dialog should reload. */
    data class Created(val project: Project) : CreateProductionEffect
}

/**
 * Drives creating a production.
 *
 * Its own ViewModel rather than more state on [AuthViewModel]: this flow has
 * three stages, two reference lists and ten validated fields, and folding it in
 * would push that class past the point where "which screen am I on" is
 * answerable by reading one sealed type.
 */
class CreateProductionViewModel(
    private val projectRepository: ProjectRepository,
    private val presetRepository: PresetRepository,
    private val authRepository: AuthRepository,
) : ZillitViewModel<CreateProductionUiState, CreateProductionEvent, CreateProductionEffect>(
    CreateProductionUiState(),
) {

    // No eager load: the preset calls carry a device id, which does not exist
    // until the device is linked. Fired at construction they 406. The dialog
    // asks for them when it opens.

    override fun onEvent(event: CreateProductionEvent) {
        when (event) {
            is CreateProductionEvent.DraftEdited -> setState {
                // Errors clear as the user types rather than persisting until
                // the next submit, which would leave a red field they have
                // already fixed.
                copy(draft = draft.(event.edit)(), fieldErrors = emptyMap(), error = null)
            }
            is CreateProductionEvent.OtpChanged -> setState {
                copy(otp = event.value.filter(Char::isDigit), error = null)
            }
            CreateProductionEvent.Opened -> if (currentState.types.isEmpty()) loadPresets()
            CreateProductionEvent.Submit -> submit()
            CreateProductionEvent.VerifyOtp -> verifyAndCreate()
            CreateProductionEvent.ResendOtp -> sendCode(resend = true)
            CreateProductionEvent.BackToForm -> setState {
                copy(stage = CreateStage.Editing, otp = "", error = null)
            }
            CreateProductionEvent.DismissError -> setState { copy(error = null) }
        }
    }

    private fun loadPresets() {
        // Both lists, sequentially — they are small and cached server-side, and
        // a failure of either is reported the same way.
        launchResult(
            block = { presetRepository.productionTypes() },
            onSuccess = { types -> setState { copy(types = types) } },
            onError = { setState { copy(error = it.localised()) } },
        )
        launchResult(
            block = { presetRepository.languages() },
            onSuccess = { languages -> setState { copy(languages = languages) } },
            onError = { setState { copy(error = it.localised()) } },
        )
    }

    private fun submit() {
        val errors = currentState.draft.validate(currentState.selectedType)
        if (errors.isNotEmpty()) {
            setState { copy(fieldErrors = errors) }
            return
        }
        sendCode()
    }

    /**
     * Emails a one-time code to the address on the form.
     *
     * The same endpoint sign-in uses. The address being created against is not
     * necessarily the one this device signed in with, so it has to be proved
     * separately.
     */
    private fun sendCode(resend: Boolean = false) {
        val email = currentState.draft.email.trim()
        setState { copy(isBusy = true, error = null) }

        launchResult(
            block = { authRepository.requestOtp(email, currentState.draft.languageCode ?: "en") },
            onSuccess = {
                setState {
                    copy(
                        isBusy = false,
                        stage = CreateStage.VerifyingEmail(email),
                        otp = if (resend) "" else otp,
                    )
                }
            },
            onError = { setState { copy(isBusy = false, error = it.localised()) } },
        )
    }

    /**
     * Verifies the code, then creates — one user action, two calls.
     *
     * Deliberately not split: the confirm code is single-use and short-lived, so
     * a "verified, now press create" step would let it expire while the user
     * reads the screen.
     */
    private fun verifyAndCreate() {
        val email = (currentState.stage as? CreateStage.VerifyingEmail)?.email ?: return
        setState { copy(isBusy = true, error = null) }

        launch {
            when (val verified = authRepository.verifyOtp(email, currentState.otp)) {
                is ZillitResult.Failure ->
                    setState { copy(isBusy = false, error = verified.error.localised()) }
                is ZillitResult.Success -> create(verified.data)
            }
        }
    }

    private suspend fun create(confirmCode: String) {
        val state = currentState
        when (val created = projectRepository.create(state.draft, state.selectedType, confirmCode)) {
            is ZillitResult.Failure ->
                // Back to the form, not stuck on the code screen: whatever the
                // server objected to is almost certainly on the form.
                setState {
                    copy(isBusy = false, stage = CreateStage.Editing, error = created.error.localised())
                }
            is ZillitResult.Success -> {
                setState { copy(isBusy = false, stage = CreateStage.Created(created.data)) }
                sendEffect(CreateProductionEffect.Created(created.data))
            }
        }
    }
}
