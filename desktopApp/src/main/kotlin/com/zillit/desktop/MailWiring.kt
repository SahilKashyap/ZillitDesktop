@file:Suppress("MatchingDeclarationName") // The mail host seams; the preference store is only one of them.

package com.zillit.desktop

import com.zillit.desktop.core.common.ZillitLog
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.datastore.PreferenceStore
import com.zillit.desktop.core.datastore.ZillitPreferences
import com.zillit.desktop.core.network.HttpVerb
import com.zillit.desktop.core.network.RequestModule
import com.zillit.desktop.core.network.jsonBody
import com.zillit.desktop.feature.email.domain.MailboxKind
import com.zillit.desktop.feature.email.domain.MailboxPreferences
import com.zillit.desktop.feature.email.ui.MailReadBy
import com.zillit.desktop.feature.email.ui.MailReadReceipt
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.awt.Desktop
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import kotlin.io.path.writeText

/**
 * The mailbox's remembered choices, on the project-scoped preference store —
 * the web keys `active_email_mailbox` and the tour's seen-flag by project id
 * for the same reason: a mailbox belongs to a production.
 */
internal class MailboxPreferenceStore(private val preferences: PreferenceStore) : MailboxPreferences {
    override suspend fun activeMailbox(): MailboxKind? =
        preferences.get(ZillitPreferences.EmailActiveMailbox).takeIf { it.isNotBlank() }?.let(MailboxKind::fromWire)

    override suspend fun setActiveMailbox(kind: MailboxKind) =
        preferences.set(ZillitPreferences.EmailActiveMailbox, kind.wire)

    override suspend fun hasSeenMailboxTour(): Boolean = preferences.get(ZillitPreferences.EmailMailboxTourSeen)

    override suspend fun markMailboxTourSeen() = preferences.set(ZillitPreferences.EmailMailboxTourSeen, true)
}

/**
 * Prints a conversation the way the web does — a print-ready page in the
 * browser, whose one permitted script opens the print dialog on load.
 *
 * The page is written to a temporary file and handed to the default
 * browser. `Desktop.browse` on a file URI is deliberate here: the file is
 * one this app just wrote, not a link from a mail body (those go through
 * `openInBrowser`, which refuses anything but http).
 */
internal fun printMailPage(title: String, html: String) {
    runCatching {
        val file = Files.createTempFile("zillit-print-", ".html")
        file.writeText(html, options = arrayOf(StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE))
        file.toFile().deleteOnExit()
        val desktop = Desktop.getDesktop().takeIf { Desktop.isDesktopSupported() }
        if (desktop?.isSupported(Desktop.Action.BROWSE) == true) {
            desktop.browse(file.toUri())
        } else {
            ZillitLog.w(MAIL_TAG) { "this platform cannot open a browser to print \"$title\"" }
        }
    }.onFailure { ZillitLog.e(MAIL_TAG, it) { "could not print" } }
}

/**
 * Who has read a sent message — `POST email-sent-log/read-by {message_id}`
 * (the web's `ReadByUsersModal` with `module = email_label`), answering
 * `message_read_by` / `message_unread_by` rows of user ids that are named
 * from the crew list the production already holds.
 */
internal suspend fun readByForSentMail(ready: AppGraph.Ready, messageId: String): MailReadBy? {
    val api = ready.config.apiV2(ZillitService.Email)
    val result = ready.apiClient.request(
        verb = HttpVerb.Post,
        url = "${api}email-sent-log/read-by",
        serializer = JsonElement.serializer(),
        module = RequestModule.ProjectUser,
        body = jsonBody(buildJsonObject { put("message_id", messageId) }),
    )
    val payload = (result as? ZillitResult.Success)?.data as? JsonObject ?: return null
    val users = ready.projectContext?.context?.value?.users.orEmpty()

    fun receipts(key: String): List<MailReadReceipt> =
        (payload[key] as? JsonArray).orEmpty().mapNotNull { element ->
            val row = element as? JsonObject ?: return@mapNotNull null
            val userId = row.string("userId") ?: row.string("user_id") ?: return@mapNotNull null
            val user = users.firstOrNull { it.userId == userId }
            val rawTime = (row["read_time"] as? JsonPrimitive)
                ?.let { it.longOrNull ?: it.contentOrNull?.toLongOrNull() }
                ?: 0L
            MailReadReceipt(
                name = row.string("user_name") ?: user?.fullName ?: userId,
                designation = row.string("designation_name") ?: user?.designation.orEmpty(),
                // Some rows carry seconds where the rest of this API is millis.
                readAtMillis = if (rawTime in 1 until MILLIS_FLOOR) rawTime * MILLIS_PER_SECOND else rawTime,
            )
        }

    return MailReadBy(read = receipts("message_read_by"), unread = receipts("message_unread_by"))
}

private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }

private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()

private const val MAIL_TAG = "Email"
private const val MILLIS_FLOOR = 10_000_000_000L
private const val MILLIS_PER_SECOND = 1_000L
