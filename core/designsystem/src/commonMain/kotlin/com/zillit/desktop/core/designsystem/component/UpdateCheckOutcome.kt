package com.zillit.desktop.core.designsystem.component

/**
 * What "Check for updates" heard back — the frame's own words for
 * `core:appupdate`'s `UpdateStatus`, so the controls need not depend on it.
 */
enum class UpdateCheckOutcome {
    UpToDate,

    /** A newer build; the app puts the strip up, and the button says nothing more. */
    Available,

    /** Offline, or this build is not set up to check. */
    Failed,
}
