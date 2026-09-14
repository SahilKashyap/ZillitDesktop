package com.zillit.desktop.feature.email.data

import com.zillit.desktop.feature.email.domain.MailboxScope
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * The shared-mailbox opt-in, as the mail service reads it — the web's
 * `withMailboxScope`: reads carry `use_project_account_mailbox=true` as a
 * query parameter, writes carry it in the body as well (the backend accepts
 * either, and sending both covers every method shape). A write with no
 * payload of its own — emptying Trash — still needs the flag in a body.
 *
 * Nothing is added while the personal mailbox is active, so a build without
 * a switcher makes byte-identical requests to the ones it made before.
 */
internal const val MAILBOX_FLAG = "use_project_account_mailbox"

/** The query parameters this call should carry. */
internal fun MailboxScope.query(): Map<String, Any?> =
    if (isAccountsActive()) mapOf(MAILBOX_FLAG to true) else emptyMap()

/** [body] with the flag added while the shared mailbox is active. */
internal fun MailboxScope.body(body: JsonObject): JsonObject =
    if (!isAccountsActive()) {
        body
    } else {
        buildJsonObject {
            body.forEach { (key, value) -> put(key, value) }
            put(MAILBOX_FLAG, JsonPrimitive(true))
        }
    }

/** A body for a write that has none of its own; null keeps the call bodiless. */
internal fun MailboxScope.flagBody(): JsonObject? =
    if (isAccountsActive()) buildJsonObject { put(MAILBOX_FLAG, JsonPrimitive(true)) } else null
