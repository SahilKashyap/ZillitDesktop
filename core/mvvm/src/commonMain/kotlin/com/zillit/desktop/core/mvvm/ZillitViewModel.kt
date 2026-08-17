package com.zillit.desktop.core.mvvm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Base class for every screen's ViewModel — the VM in MVVM.
 *
 * Enforces one shape across the app so any screen reads the same way:
 *
 * ```
 * UiEvent → onEvent() → useCase → StateFlow<UiState> → Composable
 *                    ↘ effect()  → Flow<Effect>      → one-shot UI actions
 * ```
 *
 * [State] is what the screen renders (always available, always complete).
 * [Effect] is a one-shot thing that must not replay on recomposition — a toast,
 * a navigation, a focus request. Keeping them separate is what stops "the
 * error dialog reappears when I resize the window".
 */
abstract class ZillitViewModel<State : Any, Event : Any, Effect : Any>(
    initialState: State,
) : ViewModel() {

    private val _state = MutableStateFlow(initialState)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<Effect>(
        replay = 0,
        extraBufferCapacity = EFFECT_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val effects: Flow<Effect> = _effects.asSharedFlow()

    val currentState: State get() = _state.value

    /** Single entry point for everything the UI can do. */
    abstract fun onEvent(event: Event)

    protected fun setState(reducer: State.() -> State) = _state.update(reducer)

    protected fun sendEffect(effect: Effect) {
        viewModelScope.launch { _effects.emit(effect) }
    }

    protected fun launch(block: suspend CoroutineScope.() -> Unit): Job =
        viewModelScope.launch(block = block)

    /**
     * Runs [block], routing the outcome to [onSuccess] or [onError].
     *
     * Exists so no screen has to re-implement the load/success/error dance —
     * the repetition this avoids is what made the Android app's ViewModels
     * diverge in their error handling.
     */
    protected fun <T> launchResult(
        block: suspend () -> ZillitResult<T>,
        onSuccess: (T) -> Unit,
        onError: (ZillitError) -> Unit = {},
    ): Job = launch {
        when (val result = block()) {
            is ZillitResult.Success -> onSuccess(result.data)
            is ZillitResult.Failure -> onError(result.error)
        }
    }

    private companion object {
        const val EFFECT_BUFFER = 16
    }
}
