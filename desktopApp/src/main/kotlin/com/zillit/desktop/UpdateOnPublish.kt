package com.zillit.desktop

import com.zillit.desktop.core.appupdate.UpdateStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Asks whether there is a newer build each time Remote Config publishes.
 *
 * The banner reads the same console template the flags do —
 * `desktop_latest_version` and the installer keys — and asks every six hours
 * (`rememberUpdateStatus`). Six hours is the right cadence for a question
 * nobody has a reason to ask; a publish *is* that reason, and it is also the
 * moment a release goes out. Without this, the person who left Zillit open on
 * Friday learns about Monday's build on Monday afternoon.
 *
 * Plain and suspending rather than a `LaunchedEffect`, so the rule it carries
 * — below — can be tested without a composition.
 *
 * ## Unknown is doubt, not news
 *
 * [check] answers [UpdateStatus.Unknown] for everything short of a clear
 * verdict: offline, a refused fetch, a template with no desktop keys. Writing
 * that into [status] would take a banner down that was correct, and the next
 * publish would be the only thing to bring it back. So an unclear answer
 * leaves what the banner already knew — the same rule the six-hourly poll and
 * Settings' manual check both apply.
 */
internal suspend fun checkOnConfigPublish(
    published: Flow<Long>,
    check: suspend () -> UpdateStatus,
    status: MutableStateFlow<UpdateStatus>,
) {
    published.collect {
        check().let { if (it != UpdateStatus.Unknown) status.value = it }
    }
}
