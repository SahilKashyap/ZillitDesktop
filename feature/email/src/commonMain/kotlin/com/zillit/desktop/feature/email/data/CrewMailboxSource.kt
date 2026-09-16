package com.zillit.desktop.feature.email.data

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.common.map
import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.HttpVerb
import com.zillit.desktop.core.network.RequestModule
import com.zillit.desktop.feature.email.domain.ContactSource
import com.zillit.desktop.feature.email.domain.EmailContact
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * The crew as the composer offers them — by their **Zillit mailbox**
 * address, not the address they signed up with.
 *
 * Mail between crew goes to the mailbox the production gave each of them
 * (`project/users` → `mail_box_detail.email_address`); the login email the
 * session snapshot carries is a different address that most crew never
 * read for production mail. Both other clients build their To/Cc/Bcc
 * suggestions from this list (web `ComposeModal.handleSearch`, Android
 * `loadProjectUsersForSuggestions`), and the web's "add to contacts" check
 * treats a crew mailbox address as already known.
 *
 * Who is offered follows the web's `dealMemoCondition`: crew who have
 * accepted, plus — for an admin — those still pending signature. Someone
 * who asked to keep their name private is left out unless the viewer is an
 * admin (`isKeepNamePrivateUserHide`).
 */
class CrewMailboxSource(
    private val apiClient: ApiClient,
    private val config: AppConfig,
    private val viewerIsAdmin: () -> Boolean = { false },
) {
    private val api get() = config.apiV2()

    suspend fun crew(): ZillitResult<List<EmailContact>> =
        apiClient.request(
            verb = HttpVerb.Get,
            url = "${api}project/users",
            serializer = JsonElement.serializer(),
            module = RequestModule.ProjectUser,
        ).map { payload -> payload.userRows().mapNotNull { readCrewContact(it, viewerIsAdmin()) } }
}

/** One `project/users` row as a suggestion, or null when it should not be offered. */
internal fun readCrewContact(row: JsonElement, viewerIsAdmin: Boolean): EmailContact? {
    if (row !is JsonObject || !row.isOffered(viewerIsAdmin)) return null
    val address = (row["mail_box_detail"] as? JsonObject)?.str("email_address") ?: return null
    return EmailContact(
        address = address,
        name = row.crewName().orEmpty(),
        source = ContactSource.ProjectUser,
        userId = row.str("user_id") ?: row.str("_id").orEmpty(),
        subtitle = row.str("designation_name") ?: row.str("designation")
            ?: row.str("department_name") ?: row.str("department").orEmpty(),
    )
}

/**
 * Whether a crew row is someone the composer may suggest: accepted or
 * approved members, plus — for an admin — pending ones still to sign. A
 * member who keeps their name private is offered to admins only.
 */
private fun JsonObject.isOffered(viewerIsAdmin: Boolean): Boolean {
    val status = str("status").orEmpty().lowercase()
    val offered = status == "accepted" || status == "approved" ||
        (viewerIsAdmin && status == "pending" && bool("signing_required"))
    return offered && (viewerIsAdmin || !bool("keep_name_private"))
}

private fun JsonObject.crewName(): String? =
    str("full_name")
        ?: str("name")
        ?: listOfNotNull(str("first_name"), str("last_name")).joinToString(" ").takeIf { it.isNotBlank() }

private fun JsonElement.userRows(): List<JsonElement> = when (this) {
    is JsonArray -> this
    is JsonObject -> (this["data"] as? JsonArray) ?: (this["users"] as? JsonArray) ?: emptyList()
    else -> emptyList()
}
