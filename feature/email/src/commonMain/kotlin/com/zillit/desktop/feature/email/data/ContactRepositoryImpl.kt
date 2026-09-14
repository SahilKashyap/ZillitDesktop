package com.zillit.desktop.feature.email.data

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.common.map
import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.HttpVerb
import com.zillit.desktop.core.network.RequestModule
import com.zillit.desktop.core.network.jsonBody
import com.zillit.desktop.feature.email.domain.AddressBookRepository
import com.zillit.desktop.feature.email.domain.ContactRepository
import com.zillit.desktop.feature.email.domain.EmailContact
import com.zillit.desktop.feature.email.domain.MailboxScope
import com.zillit.desktop.feature.email.domain.SavedContact
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * The user's own address book.
 *
 * Crew suggestions do **not** come from here — they come from the project users
 * this app already caches when a production opens, so the composer costs one
 * request rather than two.
 *
 * Two ports over one endpoint family (`email-contact`, Android
 * `ApiUrl.kt:757`, `EmailApi.kt:257-283`): [ContactRepository] is the
 * composer's read-only suggestion list, [AddressBookRepository] the contacts
 * screen's full records. Same rows, read two ways.
 */
class ContactRepositoryImpl(
    private val apiClient: ApiClient,
    private val config: AppConfig,
    /** The shared Accounts mailbox keeps its own address book (web `getMailboxCacheKey`). */
    private val scope: MailboxScope = MailboxScope.Personal,
) : ContactRepository, AddressBookRepository {

    private val api get() = config.apiV2(ZillitService.Email)
    private val url get() = "${api}email-contact"

    override suspend fun contacts(): ZillitResult<List<EmailContact>> =
        rows().map { rows -> rows.mapNotNull(::readContact) }

    // GET email-contact — EmailApi.fetchContacts (EmailApi.kt:259-263).
    override suspend fun savedContacts(): ZillitResult<List<SavedContact>> =
        rows().map { rows -> rows.mapNotNull(::readSavedContact) }

    // POST email-contact {contacts:[{…}]} — CreateContactRequest wraps a list
    // even for one (RequestDto.kt:100-102), EmailApi.saveContact (EmailApi.kt:265-269).
    override suspend fun save(contact: SavedContact): ZillitResult<Unit> =
        apiClient.envelope(
            verb = HttpVerb.Post,
            url = url,
            module = RequestModule.ProjectUser,
            queryParameters = scope.query(),
            body = jsonBody(scope.body(buildJsonObject { putJsonArray("contacts") { add(contactBody(contact)) } })),
        ).map { }

    /**
     * Remembers every address a message was sent to — one row each, named by
     * the address — which is how the web's address book fills itself
     * (`enqueueContactsSave`): the next message to the same person offers
     * them as a suggestion. Crew already known to the production are left
     * out by the caller.
     */
    override suspend fun saveAddresses(addresses: List<String>): ZillitResult<Unit> {
        val rows = addresses.map(String::trim).filter(String::isNotEmpty).distinctBy { it.lowercase() }
        if (rows.isEmpty()) return ZillitResult.Success(Unit)
        return apiClient.envelope(
            verb = HttpVerb.Post,
            url = url,
            module = RequestModule.ProjectUser,
            queryParameters = scope.query(),
            body = jsonBody(
                scope.body(
                    buildJsonObject {
                        putJsonArray("contacts") {
                            rows.forEach { address ->
                                add(
                                    buildJsonObject {
                                        put("contact_name", address)
                                        put("email_address", address)
                                    },
                                )
                            }
                        }
                    },
                ),
            ),
        ).map { }
    }

    // PUT email-contact/{id} {…} — SaveContactRequest bare, not wrapped
    // (EmailApi.updateContact, EmailApi.kt:271-276).
    override suspend fun update(id: String, contact: SavedContact): ZillitResult<Unit> =
        apiClient.envelope(
            verb = HttpVerb.Put,
            url = "$url/$id",
            module = RequestModule.ProjectUser,
            queryParameters = scope.query(),
            body = jsonBody(scope.body(contactBody(contact))),
        ).map { }

    // DELETE email-contact/{id} — EmailApi.deleteContact (EmailApi.kt:278-283).
    override suspend fun delete(id: String): ZillitResult<Unit> =
        apiClient.envelope(
            verb = HttpVerb.Delete,
            url = "$url/$id",
            module = RequestModule.ProjectUser,
            queryParameters = scope.query(),
            body = scope.flagBody()?.let(::jsonBody),
        ).map { }

    private suspend fun rows(): ZillitResult<List<JsonElement>> =
        apiClient.request(
            verb = HttpVerb.Get,
            url = url,
            serializer = JsonElement.serializer(),
            module = RequestModule.ProjectUser,
            queryParameters = scope.query(),
        ).map { payload -> payload.contactRows() }
}

/**
 * Android `SaveContactRequest` (`RequestDto.kt:104-125`), built as
 * `Contact.toRequest()` does (`ContactRepositoryImpl.kt:95-112`): `contact_name`
 * is always `first last`, falling back to the address — whatever the record
 * held before.
 */
internal fun contactBody(contact: SavedContact): JsonObject = buildJsonObject {
    val name = "${contact.firstName} ${contact.lastName}".trim().ifBlank { contact.address }
    put("first_name", contact.firstName)
    put("last_name", contact.lastName)
    put("contact_name", name)
    put("email_address", contact.address.trim())
    put("company_name", contact.company)
    put("phone_number", contact.phone)
    put("country_code", contact.countryCode)
    put("address", contact.street)
    put("city", contact.city)
    put("state", contact.state)
    put("zip_code", contact.zipCode)
    put("country", contact.country)
    put("notes", contact.notes)
}

/** Android `ContactDto` (`EmailDto.kt:112-138`), read tolerantly like every other row here. */
internal fun readSavedContact(row: JsonElement): SavedContact? {
    if (row !is JsonObject) return null
    val address = row.str("email_address") ?: row.str("email") ?: return null
    return SavedContact(
        id = row.str("_id") ?: row.str("id").orEmpty(),
        address = address,
        firstName = row.str("first_name").orEmpty(),
        lastName = row.str("last_name").orEmpty(),
        contactName = row.str("contact_name").orEmpty(),
        company = row.str("company_name").orEmpty(),
        phone = row.str("phone_number").orEmpty(),
        countryCode = row.str("country_code").orEmpty(),
        street = row.str("address").orEmpty(),
        city = row.str("city").orEmpty(),
        state = row.str("state").orEmpty(),
        zipCode = row.str("zip_code").orEmpty(),
        country = row.str("country").orEmpty(),
        notes = row.str("notes").orEmpty(),
    )
}

/** Contacts come back as `{"contacts": [...]}`, or as a bare array. */
private fun JsonElement.contactRows(): List<JsonElement> = when (this) {
    is JsonArray -> this
    is JsonObject -> (this["contacts"] as? JsonArray) ?: (this["data"] as? JsonArray) ?: emptyList()
    else -> emptyList()
}
