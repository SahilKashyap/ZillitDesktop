package com.zillit.desktop.feature.calls.domain

import kotlinx.coroutines.flow.Flow

/**
 * One thing the user can share: a whole screen, or a single window.
 *
 * [id] is Chromium's own DesktopMediaID spelling — `screen:<display>:0` or
 * `window:<id>:0` — and is carried untranslated all the way to the page, which
 * hands it to the capture constraints. Giving it a friendlier identity here
 * would only mean translating it back at the far end.
 */
data class ShareSource(
    val id: String,
    /** "Entire screen", or the window's title. */
    val name: String,
    /** The owning application, for a window. Blank for a screen. */
    val app: String = "",
    val isScreen: Boolean = false,
    /**
     * A PNG preview, base64-encoded, or blank when none has arrived.
     *
     * Blank is a normal, temporary state rather than a failure: previews are
     * real screen captures and arrive one at a time, so the picker draws every
     * tile immediately and fills the pictures in behind them.
     */
    val previewPng: String = "",
) {
    /** What the tile says underneath the picture. */
    val caption: String get() = if (app.isBlank()) name else app
}

/**
 * Where the share picker's list of screens and windows comes from.
 *
 * A seam because the answer is per-platform and, on macOS, is a separate
 * signed helper process rather than anything this module could call: the API
 * that can enumerate windows with pictures is ScreenCaptureKit, and the older
 * one that a JVM could have reached through JNA no longer exists in the
 * current SDK.
 */
fun interface ScreenSources {

    /**
     * The sources, re-emitted as each preview lands, ending when they have all
     * arrived.
     *
     * An empty first emission means the list could not be built — usually
     * Screen Recording permission, which is the one screen-share failure that
     * genuinely is a permission problem. The caller falls back to sharing the
     * whole screen rather than showing an empty picker.
     */
    fun list(): Flow<List<ShareSource>>
}
