package com.zillit.desktop.feature.formsignature.data

import com.zillit.desktop.core.socket.SocketEventName
import com.zillit.desktop.feature.formsignature.domain.FormSignRefresh

/**
 * The `document:*` family this tool refreshes on. These names are the wire
 * names: `listenerSocket.js:2399-2402` subscribes `formsAndSignEvents`
 * (declared at 2356-2397) and re-emits each under the same string, so the
 * pages' `useCustomEventListener('document:…')` calls double as the wire
 * evidence.
 *
 * Which list an event touches on the web decides its [FormSignRefresh]:
 *
 *  - standard forms: `StandardFormsV2.jsx:175` (added:general), `:227`
 *    (signed), `:236` (counter:signed), `:276` (deleted, ZL-17615);
 *  - documents for signature: `DocumentsForSignature.jsx:113/119/139`
 *    (created/updated/deleted:signature), `FormPage.jsx:261` (sent),
 *    `:426` (signer:removed), `:490` (revised), `:317/343`
 *    (participant added/removed — both refetch the documents list);
 *  - `signed`/`counter:signed` refetch both lists (`FormPage.jsx:506/523`
 *    beside the StandardFormsV2 pair above), so they map to both kinds.
 *
 * The four per-user and library add variants (`document:added:user:form`,
 * `:contract`, `document:added:saved:form`, `:contract`) are here too. They
 * were excluded as "V1-only" because the web's V2 pages ignore them — but
 * iOS's Form & Signature **2.0**, the same tool this ports, refreshes the
 * standard-forms list from all four (`StandardFormsView.swift:40-51`), and a
 * form assigned to this user is exactly what a signing surface must show
 * without being reopened. Corrected 2026-09-09.
 *
 * Deliberately absent: the chat family (`document:message:*`) — the desktop
 * tool has no discussion surface.
 */
internal val FORM_SIGN_SYNC_EVENTS: List<SocketEventName> = listOf(
    SocketEventName("document:added:general"),
    SocketEventName("document:deleted"),
    SocketEventName("document:signed"),
    SocketEventName("document:counter:signed"),
    SocketEventName("document:created:signature"),
    SocketEventName("document:updated:signature"),
    SocketEventName("document:deleted:signature"),
    SocketEventName("document:sent:signature"),
    SocketEventName("document:signer:removed"),
    SocketEventName("document:revised"),
    SocketEventName("document:participant:added"),
    SocketEventName("document:participant:removed"),
    SocketEventName("document:added:user:form"),
    SocketEventName("document:added:user:contract"),
    SocketEventName("document:added:saved:form"),
    SocketEventName("document:added:saved:contract"),
)

private val FORMS_EVENTS = setOf(
    "document:added:general",
    "document:deleted",
    "document:signed",
    "document:counter:signed",
    // Assigned to this user, or added to the library. iOS refreshes the same
    // standard-forms list for all four.
    "document:added:user:form",
    "document:added:user:contract",
    "document:added:saved:form",
    "document:added:saved:contract",
)

private val DOCUMENT_EVENTS = setOf(
    "document:signed",
    "document:counter:signed",
    "document:created:signature",
    "document:updated:signature",
    "document:deleted:signature",
    "document:sent:signature",
    "document:signer:removed",
    "document:revised",
    "document:participant:added",
    "document:participant:removed",
)

/** Which lists [event] must refetch; empty for an event this tool ignores. */
internal fun refreshKindsFor(event: SocketEventName): List<FormSignRefresh> = buildList {
    if (event.value in FORMS_EVENTS) add(FormSignRefresh.Forms)
    if (event.value in DOCUMENT_EVENTS) add(FormSignRefresh.Documents)
}
