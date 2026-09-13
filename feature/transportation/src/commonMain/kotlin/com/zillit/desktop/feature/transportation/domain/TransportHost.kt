package com.zillit.desktop.feature.transportation.domain

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/** What a picker is asked for: a photograph, or a photograph-or-PDF document. */
enum class TransportMediaKind { Image, ImageOrPdf }

/** A file just chosen and stored — its pointer for the record, its bytes for the thumbnail. */
class PickedMedia(val stored: StoredMedia, val bytes: ByteArray)

/**
 * What the transport screens borrow from the desktop around them.
 *
 * The transport service takes **pointers** on every route that involves a
 * file — a licence picture, a driver's document, a vehicle photograph — and
 * accepts no upload of its own. So both halves are the host's: the file
 * chooser, and the object store (the web's `uploadFile` to S3). The dialling
 * codes are a preset the core service answers, which the web reads once for
 * the whole app.
 */
interface TransportHost {

    /**
     * Picks files of [kind] and stores them. An empty success is a cancelled
     * picker — the person changed their mind, which is not a failure.
     */
    suspend fun pickAndStore(kind: TransportMediaKind, multiple: Boolean): ZillitResult<List<PickedMedia>>

    /** The dialling codes the phone fields offer; empty when the preset is unreachable. */
    suspend fun countries(): List<DialCountry>

    /**
     * Unread counts by badge unit under the tool — `transportation_trip_request_pending_label`,
     * `transportation_permanent_allocation_request_label`… — the web's
     * `getBadgeForTile` walk over `toolsUnitBadges`. Empty when the host keeps no ledger.
     */
    val badges: Flow<Map<String, Int>> get() = emptyFlow()

    /** A host with no storage, no presets and no badges — tests, and the render harness. */
    object None : TransportHost {
        override suspend fun pickAndStore(
            kind: TransportMediaKind,
            multiple: Boolean,
        ): ZillitResult<List<PickedMedia>> =
            ZillitResult.Failure(
                ZillitError.Storage(
                    technical = "no transport media host",
                    userMessage = "Uploads are unavailable — this workspace has no file storage configured.",
                ),
            )

        override suspend fun countries(): List<DialCountry> = emptyList()
    }
}
