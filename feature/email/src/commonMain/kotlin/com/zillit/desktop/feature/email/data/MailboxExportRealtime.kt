package com.zillit.desktop.feature.email.data

import com.zillit.desktop.core.socket.SocketEventBus
import com.zillit.desktop.core.socket.SocketMessage
import com.zillit.desktop.core.socket.ZillitSocketEvents
import com.zillit.desktop.feature.email.domain.MailboxExport
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * One `email:export:updated` event: the whole export, and which mailbox it
 * belongs to.
 *
 * The mailbox is carried separately because the payload says it in the
 * envelope (`isAccountsMailbox`) rather than inside the export, and a
 * personal-mailbox screen must not take an accounts-mailbox update — one
 * window can be showing either.
 */
data class MailboxExportUpdate(
    val export: MailboxExport,
    val isAccountsMailbox: Boolean,
)

/**
 * The export's live progress, off the socket the mailbox already has open.
 *
 * The server pushes on starting work, roughly every 30 seconds while
 * building, as each ZIP part finishes, on completion and on final failure.
 * It does **not** push a cancellation, a removal, an expiry, or the step back
 * to `queued` that a retry makes — so this stream is never the only way the
 * screen learns something, and the fallback poll exists for the rest.
 *
 * Only the person who asked for the export is sent the event. Other members
 * of the accounts department see the shared mailbox's exports change when
 * they open or refresh the page.
 */
class MailboxExportRealtimeSource(private val events: SocketEventBus) {

    val stream: Flow<MailboxExportUpdate> =
        events.on(ZillitSocketEvents.Email.ExportUpdated).mapNotNull(::toMailboxExportUpdate)
}

/**
 * Maps one socket message.
 *
 * Top-level and `internal` so the mapping is tested for real rather than in
 * a copy, and without standing up a socket to do it.
 */
internal fun toMailboxExportUpdate(message: SocketMessage): MailboxExportUpdate? {
    val envelope = message.payload as? JsonObject ?: return null
    // Same envelope as the rest of the mail events: the record is under
    // `data`, sometimes as a JSON *string* rather than an object.
    val record = envelope["data"]?.unwrapMailData() ?: return null
    val export = runCatching { EXPORT_JSON.decodeFromJsonElement(MailboxExportDto.serializer(), record) }
        .getOrNull()
        ?.toDomain()
        ?: return null

    return MailboxExportUpdate(
        export = export,
        // The envelope's word is the authority; the record repeats it, and
        // either spelling of the key may be the one that arrives.
        isAccountsMailbox = envelope.flag("isAccountsMailbox")
            ?: envelope.flag("is_accounts_mailbox")
            ?: export.isAccountsMailbox,
    )
}

private fun JsonObject.flag(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull

// `coerceInputValues` for the same reason as the repository's reader: a null
// where a list is declared must not drop the whole update.
private val EXPORT_JSON = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }
