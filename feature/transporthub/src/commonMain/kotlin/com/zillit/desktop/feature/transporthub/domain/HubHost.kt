package com.zillit.desktop.feature.transporthub.domain

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/** What a picker is asked for: a photograph, or a photograph-or-PDF document. */
enum class HubMediaKind { Image, ImageOrPdf }

/** A file just chosen and stored — its pointer for the record, its bytes for the thumbnail. */
class PickedHubMedia(val attachment: HubAttachment, val bytes: ByteArray)

/**
 * What the hub borrows from the desktop around it.
 *
 * The transport service takes **pointers** on every route that involves a
 * file — a vehicle photograph, a driver's licence — and accepts no upload of
 * its own, so both halves are the host's: the file chooser, and the object
 * store the web reaches through `uploadFile`.
 */
interface HubHost {

    /**
     * Picks files of [kind] and stores them. An empty success is a cancelled
     * picker — the person changed their mind, which is not a failure.
     */
    suspend fun pickAndStore(
        kind: HubMediaKind,
        multiple: Boolean,
    ): ZillitResult<List<PickedHubMedia>>

    /**
     * Unread counts per [HubBadgeBucket], keyed by the bucket's name.
     *
     * The hub's badge units are **not** the legacy tool's, and two of them
     * carry several pages' notifications at once — which is what
     * [HubBadgeBucket] exists to split. Empty when the host keeps no ledger.
     */
    val badges: Flow<Map<String, Int>> get() = emptyFlow()

    /** A host with no storage and no badges — tests, and the render harness. */
    object None : HubHost {
        override suspend fun pickAndStore(
            kind: HubMediaKind,
            multiple: Boolean,
        ): ZillitResult<List<PickedHubMedia>> =
            ZillitResult.Failure(ZillitError.Unknown("no transport hub media host"))
    }
}

