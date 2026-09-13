package com.zillit.desktop.feature.maps.ui

import com.zillit.desktop.feature.maps.data.MapCanvasClient
import com.zillit.desktop.feature.maps.domain.LatLng
import com.zillit.desktop.feature.maps.domain.MapLocator

/**
 * Where this machine is — the web's `navigator.geolocation.getCurrentPosition`.
 *
 * Asked of the map page first, as the web asks the browser. Embedded Chromium
 * cannot show the geolocation prompt, so that answer is a refusal on every
 * desktop build today; the host's [MapLocator] then answers approximately from
 * the network. The refusal is remembered for the session so later asks skip
 * straight to the fallback rather than waiting on a prompt that never comes,
 * and a fix is kept for [MAX_AGE_MS] — the web's `maximumAge` of five minutes.
 */
internal class PositionFinder(
    private val canvas: MapCanvasClient,
    private val locator: MapLocator?,
    private val now: () -> Long,
) {
    private var browserRefused = false
    private var fix: Pair<LatLng, Long>? = null

    suspend fun current(): LatLng? {
        fix?.let { (point, at) -> if (now() - at < MAX_AGE_MS) return point }
        val point = fromBrowser() ?: locator?.locate() ?: return null
        fix = point to now()
        return point
    }

    private suspend fun fromBrowser(): LatLng? {
        if (browserRefused) return null
        val point = canvas.currentPosition()
        if (point == null) browserRefused = true
        return point
    }

    private companion object {
        const val MAX_AGE_MS = 5 * 60 * 1000L
    }
}
