package com.zillit.desktop.feature.maps.ui

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.localization.localisedMessage
import com.zillit.desktop.core.permissions.RightsKind
import com.zillit.desktop.core.permissions.RightsRequestBus
import com.zillit.desktop.core.permissions.rightsRefusalMessage
import com.zillit.desktop.feature.maps.data.MapCanvasClient
import com.zillit.desktop.feature.maps.domain.MapHost
import com.zillit.desktop.feature.maps.domain.MapRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job

/**
 * What the map's controllers share: the state, the ways to change it, and
 * the collaborators. The view model is the only implementation; the
 * controllers exist so each area of the tool reads on its own rather than as
 * one two-thousand-line class.
 */
internal interface MapStore {
    val state: MapUiState
    val repository: MapRepository
    val canvas: MapCanvasClient
    val host: MapHost
    val rights: RightsRequestBus?

    fun update(reducer: MapUiState.() -> MapUiState)
    fun effect(effect: MapEffect)
    fun spawn(block: suspend CoroutineScope.() -> Unit): Job

    /** Cross-controller hooks the view model wires together. */
    val hooks: MapHooks
}

/** The handful of acts one area needs from another. */
internal interface MapHooks {
    fun reloadCities()
    fun reloadLocations()
    fun reloadZones()
    fun selectCity(cityId: String)
    fun closeAllPanels()
}

internal fun MapStore.notice(message: String, tone: NoticeTone) = effect(MapEffect.Notice(message, tone))

/** A failed call, as the web toasts it: the server's words, else ours. */
internal fun MapStore.failed(error: ZillitError, fallback: String) {
    val text = when (error) {
        is ZillitError.Http -> error.localised().ifBlank { fallback }
        else -> error.localised().ifBlank { fallback }
    }
    notice(text, NoticeTone.Error)
}

/** A write that worked: the server's message translated, else the web's fallback. */
internal fun MapStore.succeeded(message: String?, fallback: String) =
    notice(message?.takeIf { it.isNotBlank() }?.localisedMessage()?.ifBlank { null } ?: fallback, NoticeTone.Success)

/**
 * The web's `denyAction` — someone without posting rights pressed a write
 * control. The frame asks an administrator; this says so.
 */
internal fun MapStore.denyPost() {
    rights?.ask(MODULE_LABEL, RightsKind.Post)
    notice(rightsRefusalMessage(MODULE_LABEL, RightsKind.Post, asked = rights != null), NoticeTone.Warning)
}

/** Runs [action] only with posting rights; otherwise asks for them. */
internal inline fun MapStore.withPost(action: () -> Unit) {
    if (state.viewer.mayPost) action() else denyPost()
}

internal fun MapStore.pushPanel(panel: MapPanel) = update { copy(panels = panels.filterNot { it == panel } + panel) }

/** Pops the top panel when it is [panel]'s kind; leaves the rest of the stack. */
internal fun MapStore.popPanel(matches: (MapPanel) -> Boolean) = update {
    if (panels.lastOrNull()?.let(matches) == true) copy(panels = panels.dropLast(1)) else copy(panels = panels.filterNot(matches))
}

/** What the tool is called in a rights request an admin reads. */
internal const val MODULE_LABEL = "Map"
