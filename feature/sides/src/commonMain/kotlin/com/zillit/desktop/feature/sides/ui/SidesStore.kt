package com.zillit.desktop.feature.sides.ui

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.designsystem.component.ZillitToastTone
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.permissions.RightsKind
import com.zillit.desktop.feature.sides.domain.SidesRepository
import com.zillit.desktop.feature.sides.domain.SidesTransfer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job

/**
 * What every flow needs from the view model: the state it reduces, the
 * seams it calls, and the rights gate — so the flows stay plain classes a
 * test can drive with a fake store.
 */
internal interface SidesStore {
    val current: SidesUiState
    val repository: SidesRepository
    val transfer: SidesTransfer

    fun update(reducer: SidesUiState.() -> SidesUiState)
    fun effect(effect: SidesEffect)
    fun runTask(block: suspend CoroutineScope.() -> Unit): Job

    /**
     * Refuses an act the viewer lacks the right for, and asks an admin —
     * the web's `requestAccess(forPosting)`. True means "stop here".
     */
    fun refuses(kind: RightsKind): Boolean

    fun notice(message: String) = effect(SidesEffect.Notice(message))
    fun failed(message: String) = effect(SidesEffect.Notice(message, ZillitToastTone.Danger))
    fun failed(error: ZillitError) = failed(error.localised())
}
