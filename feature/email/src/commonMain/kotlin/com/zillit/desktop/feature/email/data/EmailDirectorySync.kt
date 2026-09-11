package com.zillit.desktop.feature.email.data

import com.zillit.desktop.core.socket.SocketEventName

/**
 * The mailbox's two side lists, kept live.
 *
 * These are not mail: they are the saved addresses and the sign-offs, each on
 * its own page behind its own tool provider rather than in `EmailViewModel`.
 * That is why the 2026-09-07 realtime audit found them missing — the mailbox
 * had a realtime source and these pages had none, so a contact saved on a
 * phone did not appear here until the page was reopened.
 *
 * All three clients carry both families: Android's `_contactUpdated` /
 * `_signatureUpdated`, iOS's `.emailContact*` / `.emailSignature*`, the web's
 * `email:contact:*` / `email:signature:*` registrations.
 *
 * ## One family that stays unsubscribed
 *
 * Recorded here so the next audit gets an answer rather than re-flagging it,
 * and pinned by `EmailDirectorySyncTest`:
 *
 * * `email:readby:update` — this mailbox shows no read-by receipts. Android
 *   routes it through the *signature* flow, which is a quirk of its own
 *   plumbing rather than a contract worth copying.
 *
 * `email:group:*` was on that list until 2026-09-09, excluded on the grounds
 * that the address book holds no groups. That was true of the address book
 * and false of the mailbox: distribution groups have their own page and their
 * own list (`EmailGroupsPage`), which is exactly what these three reload.
 */
val EMAIL_CONTACTS_SYNC_EVENTS: List<SocketEventName> = listOf(
    SocketEventName("email:contact:saved"),
    SocketEventName("email:contact:updated"),
    SocketEventName("email:contact:deleted"),
)

val EMAIL_SIGNATURE_SYNC_EVENTS: List<SocketEventName> = listOf(
    SocketEventName("email:signature:created"),
    SocketEventName("email:signature:updated"),
    SocketEventName("email:signature:deleted"),
)

/**
 * Distribution groups, saved or removed elsewhere.
 *
 * Both phones carry all three (Android `_emailGroupUpdated`, iOS
 * `.emailGroup*`). The list is small and ordered by the server, so the page
 * re-reads it rather than patching a row.
 */
val EMAIL_GROUPS_SYNC_EVENTS: List<SocketEventName> = listOf(
    SocketEventName("email:group:saved"),
    SocketEventName("email:group:updated"),
    SocketEventName("email:group:deleted"),
)
