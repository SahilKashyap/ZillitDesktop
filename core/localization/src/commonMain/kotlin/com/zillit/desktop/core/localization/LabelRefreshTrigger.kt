package com.zillit.desktop.core.localization

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map

/**
 * When to fetch, and in which language.
 *
 * ## The bug this exists to prevent
 *
 * Every `preset` call carries a `moduledata` header containing this machine's
 * device id, and the server answers a request whose device id is empty with
 * `406 libs_module_data_invalid`. The id is not known at startup: it is
 * restored from the keychain a second or so in, or it does not exist at all
 * until the machine is linked by QR.
 *
 * Fetching as soon as the app composed therefore lost whichever dictionary
 * happened to go first — reproducibly, on every launch, and invisibly, because
 * a missing dictionary degrades to humanised keys rather than an error. It was
 * found by reversing the fetch order and watching the failure move from
 * `labels` to `identifiers`: the loss followed the position, not the endpoint.
 *
 * So the trigger waits. On a linked machine that is the startup delay; on a
 * fresh one it means the labels arrive when the device does, rather than never.
 *
 * @param language the UI language, which may change while the app runs.
 * @param deviceId this machine's id — null or blank until it has one.
 */
fun labelRefreshTrigger(
    language: Flow<String>,
    deviceId: Flow<String?>,
): Flow<String> =
    combine(
        language,
        // Only the presence of an id matters, not its value: re-linking the
        // same machine issues a new id, and re-fetching a dictionary that is
        // already loaded and unchanged would be work for nothing.
        deviceId.map { !it.isNullOrBlank() }.distinctUntilChanged(),
    ) { code, hasDevice -> code.takeIf { hasDevice } }
        .filterNotNull()
        // A language that is already loaded does not need fetching again just
        // because the device id was replaced.
        .distinctUntilChanged()
