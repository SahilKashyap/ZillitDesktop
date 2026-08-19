package com.zillit.desktop.feature.sos.data

import com.zillit.desktop.core.common.MessageElement
import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.common.map
import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.ApiEnvelope
import com.zillit.desktop.core.network.CallOptions
import com.zillit.desktop.core.network.HttpVerb
import com.zillit.desktop.core.network.RequestModule
import com.zillit.desktop.feature.sos.domain.ExternalContactDraft
import com.zillit.desktop.feature.sos.domain.IsdCode
import com.zillit.desktop.feature.sos.domain.SosAlert
import com.zillit.desktop.feature.sos.domain.SosContact
import com.zillit.desktop.feature.sos.domain.SosContactKind
import com.zillit.desktop.feature.sos.domain.SosFix
import com.zillit.desktop.feature.sos.domain.SosRelation
import com.zillit.desktop.feature.sos.domain.SosRepository
import com.zillit.desktop.feature.sos.domain.SosTextDecoder
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Every SOS route, as a function of the host it lives on — pinned by test so
 * the wire cannot drift under a refactor.
 *
 * SOS is two backends, not one. The **project** service owns the alarm and the
 * receiver list (the web's `config.projectBase`, `api/sosapi/api.js:5`; Android's
 * `ApiUrl.SLUG_SOS`, `network/ApiUrl.kt:182`). The **notification** service owns
 * the alert feed itself, because an SOS alert is a notification on the
 * `sos_label` segment (`api.js:40-44`; `ApiUrl.kt:642`).
 */
object SosEndpoints {

    /** The feed's segment — Android `Constants.SOS_LABEL` (`utils/Constants.kt:1051`). */
    const val SEGMENT = "sos_label"

    /** What one page holds; the web freezes it at fifty (`SOSMain.jsx:84`). */
    const val PAGE_LIMIT = 50

    /**
     * `GET project/notifications/{cursor}/{previous|next}` on the notification
     * host — `getSosList` (`api.js:42-45`), Android
     * `SosVM.fireSosListRequest` (`bottomNav/sos/viewmodel/SosVM.kt:130-134`).
     *
     * `previous` walks older, `next` tops up with anything newer than the head;
     * the query carries the page size and the segment (`api.js:43`).
     */
    fun alerts(notificationBase: String, cursorMillis: Long, older: Boolean): String =
        "${notificationBase}project/notifications/$cursorMillis/${if (older) PREVIOUS else NEXT}"

    /**
     * The one name the newest page is kept under.
     *
     * The first page is asked for from "now" — a different URL every time, the
     * same question every time — so without a stable name the read cache would
     * keep an answer nobody ever asks for again and the list would be blank
     * offline. See [CallOptions.cacheAs].
     */
    fun newestPageName(notificationBase: String): String = "${notificationBase}project/notifications/sos/newest"

    /**
     * `PUT levelmarkread/sos_label/{timestamp}` — Android fires this on every
     * feed fetch (`SosVM.kt:135-139` calling `BadgesHandler.markRead(SOS_LABEL,
     * now, BadgeOptions.Read)`, whose REST leg is `ApiUrl.MARK_READ_URL`,
     * `badgesHandler/BadgesHandler.kt:292`). The web does the same act over the
     * socket instead (`emitForNotificationRead`, `SOSMain.jsx:139-149`).
     */
    fun markRead(notificationBase: String, timestampMillis: Long): String =
        "$notificationBase$MARK_READ_ROUTE/$SEGMENT/$timestampMillis"

    /**
     * `DELETE markdelete/{alertId}/{timestamp}` — how both shipping clients
     * actually delete one alert: Android passes the row's `_id` where a segment
     * normally goes (`bottomNav/sos/view/Sos.kt:433-435` into
     * `BadgesHandler.kt:266-268`), and the web emits `notification:delete` with
     * `segment: record._id` (`SOSMain.jsx:236-250`), which is the same act over
     * the socket. See [alertFallback] for the route `api.js` declares.
     */
    fun deleteAlert(notificationBase: String, alertId: String, timestampMillis: Long): String =
        "$notificationBase$DELETE_ROUTE/$alertId/$timestampMillis"

    /**
     * `DELETE markdelete/sos_label/{timestamp}` — Clear All, again as both
     * clients perform it: Android's `tvClearAll` (`Sos.kt:174-180`) and the
     * web's `handleClearAll` (`SOSMain.jsx:270-282`) both name the *segment*
     * rather than a row.
     */
    fun deleteAllAlerts(notificationBase: String, timestampMillis: Long): String =
        "$notificationBase$DELETE_ROUTE/$SEGMENT/$timestampMillis"

    /**
     * `DELETE sos/notification/{sosId}` on the project host — `deleteSingleSOS`
     * (`api.js:17-25`); Android declares it as `ApiUrl.PATH_DELETE_SOS`
     * (`ApiUrl.kt:189`).
     *
     * Neither client calls it: the web's SOS page moved to the socket delete
     * and Android's constant is unreferenced. Kept as the fallback for
     * [deleteAlert] rather than the primary for exactly that reason — if the
     * notification host refuses, this is the route the service still declares.
     */
    fun alertFallback(projectBase: String, alertId: String): String = "${projectBase}sos/notification/$alertId"

    /** `DELETE sos/notification` — `deleteAllSOS` (`api.js:27-35`). Fallback, as above. */
    fun allAlertsFallback(projectBase: String): String = "${projectBase}sos/notification"

    /**
     * `POST sos/send/alert` — `sendAlert` (`api.js:7-15`), Android
     * `ApiUrl.PATH_SEND_ALERT` (`ApiUrl.kt:183`).
     */
    fun sendAlert(projectBase: String): String = "${projectBase}sos/send/alert"

    /**
     * `GET sos/contacts?entry_type={admin|user}` — `getContactsList`
     * (`api.js:100-110`), Android `ApiUrl.PATH_CONTACT_LIST` (`ApiUrl.kt:185`).
     */
    fun contacts(projectBase: String): String = "${projectBase}sos/contacts"

    /**
     * `POST sos/contact/create` — one route for both kinds, told apart only by
     * the body (`addContactInternal`/`addContactExternal`, `api.js:51-69`;
     * Android `ApiUrl.PATH_ADD_CONTACT`, `ApiUrl.kt:184`).
     */
    fun createContact(projectBase: String): String = "${projectBase}sos/contact/create"

    /**
     * `PUT sos/contact/update/{id}` — `updateContactInternal`/`External`
     * (`api.js:71-89`), Android `ApiUrl.PATH_UPDATE_CONTACT` (`ApiUrl.kt:187`).
     */
    fun updateContact(projectBase: String, contactId: String): String =
        "${projectBase}sos/contact/update/$contactId"

    /**
     * `sos/contact/{id}` — `DELETE` deletes it (`deleteContact`, `api.js:121-129`;
     * Android `ApiUrl.PATH_DELETE_LIST`, `ApiUrl.kt:186`), `GET` reads one back
     * (`getViewContact`, `api.js:91-98`). The desktop only ever deletes: the
     * edit form is filled from the row already on screen, the way the web fills
     * it (`SOS.jsx:386-409`).
     */
    fun contact(projectBase: String, contactId: String): String = "${projectBase}sos/contact/$contactId"

    /**
     * `GET sos/departments` — the relationships the outsider form offers
     * (`getDepartments`, `api.js:112-119`; Android `ApiUrl.PATH_RELATION_LIST`,
     * `ApiUrl.kt:188`). Named "departments" on the wire, "relation" in the form.
     */
    fun relations(projectBase: String): String = "${projectBase}sos/departments"

    /**
     * `GET preset/isd-codes` — the dialling codes (`getCountryName`,
     * `api/presetsapi/presetsApi.js:37-51`; Android `ApiUrl.PATH_ISD_CODE_LIST`,
     * `ApiUrl.kt:85`). A preset route, not an SOS one, but the outsider form is
     * this module's only caller.
     */
    fun isdCodes(projectBase: String): String = "${projectBase}preset/isd-codes"

    private const val MARK_READ_ROUTE = "levelmarkread"
    private const val DELETE_ROUTE = "markdelete"
    private const val PREVIOUS = "previous"
    private const val NEXT = "next"
}

/**
 * The SOS backend, both halves of it.
 *
 * Every call carries the project-user header set — `MODELDATA.WITH_PROJECT_USER_ID`
 * on Android, which is what `SosVM`/`ContactsVM` pass for every SOS request
 * (`bottomNav/sos/viewmodel/SosVM.kt:52`, `ContactsVM.kt:16-40`).
 *
 * Alert bodies arrive as label keys with `{{slots}}`, so rows are decoded into
 * words here through [decoder]; nothing above the repository ever sees a key.
 */
class SosRepositoryImpl(
    private val apiClient: ApiClient,
    config: AppConfig,
    private val decoder: SosTextDecoder = SosTextDecoder(),
) : SosRepository {

    /** `config.projectBase` on the web (`api.js:5`), `ApiUrl.SUBFOLDER` on Android. */
    private val projectBase = config.apiV2(ZillitService.Core)

    /** `config.notificationBase` on the web (`api.js:40`). */
    private val notificationBase = config.apiV2(ZillitService.Notification)

    override suspend fun alerts(
        cursorMillis: Long,
        older: Boolean,
        limit: Int,
        newest: Boolean,
    ): ZillitResult<List<SosAlert>> = apiClient.requestOrNull(
        verb = HttpVerb.Get,
        url = SosEndpoints.alerts(notificationBase, cursorMillis, older),
        serializer = ListSerializer(AlertDto.serializer()),
        module = RequestModule.ProjectUser,
        queryParameters = mapOf("limit" to limit, "segment" to SosEndpoints.SEGMENT),
        options = if (newest) {
            CallOptions(cacheAs = SosEndpoints.newestPageName(notificationBase))
        } else {
            CallOptions()
        },
    ).map { rows -> rows.orEmpty().mapNotNull { it.toAlert(decoder) } }

    override suspend fun markRead(timestampMillis: Long): ZillitResult<Unit> =
        write(HttpVerb.Put, SosEndpoints.markRead(notificationBase, timestampMillis))

    /**
     * The alarm.
     *
     * The body is the GPS fix and nothing else — `{lat, long}`, the web's
     * `requestGeoLocation` answer passed straight through (`utils/location.js:9-12`
     * into `SOSMain.jsx:209-211`), Android's `LocationModel`
     * (`model/CommonRequestModel.kt:32-37`, built by `SosVM.createSosRequest`).
     * Both clients refuse to send without a fix; a null [fix] here sends the
     * alarm anyway with no coordinates, because a desktop with no location
     * service must still be able to raise one.
     */
    override suspend fun sendAlert(fix: SosFix?): ZillitResult<Unit> = write(
        verb = HttpVerb.Post,
        url = SosEndpoints.sendAlert(projectBase),
        body = buildJsonObject {
            fix?.let {
                put("lat", it.lat)
                put("long", it.long)
            }
        },
    )

    override suspend fun deleteAlert(alertId: String, timestampMillis: Long): ZillitResult<Unit> =
        withFallback(
            primary = { write(HttpVerb.Delete, SosEndpoints.deleteAlert(notificationBase, alertId, timestampMillis)) },
            fallback = { write(HttpVerb.Delete, SosEndpoints.alertFallback(projectBase, alertId)) },
        )

    override suspend fun deleteAllAlerts(timestampMillis: Long): ZillitResult<Unit> =
        withFallback(
            primary = { write(HttpVerb.Delete, SosEndpoints.deleteAllAlerts(notificationBase, timestampMillis)) },
            fallback = { write(HttpVerb.Delete, SosEndpoints.allAlertsFallback(projectBase)) },
        )

    override suspend fun contacts(entryType: String): ZillitResult<List<SosContact>> = apiClient.requestOrNull(
        verb = HttpVerb.Get,
        url = SosEndpoints.contacts(projectBase),
        serializer = ListSerializer(ContactDto.serializer()),
        module = RequestModule.ProjectUser,
        queryParameters = mapOf("entry_type" to entryType),
    ).map { rows -> rows.orEmpty().mapNotNull { it.toContact() } }

    override suspend fun createInternal(userId: String): ZillitResult<Unit> = write(
        verb = HttpVerb.Post,
        url = SosEndpoints.createContact(projectBase),
        body = internalBody(userId),
    )

    override suspend fun createExternal(draft: ExternalContactDraft): ZillitResult<Unit> = write(
        verb = HttpVerb.Post,
        url = SosEndpoints.createContact(projectBase),
        body = externalBody(draft),
    )

    override suspend fun updateInternal(contactId: String, userId: String): ZillitResult<Unit> = write(
        verb = HttpVerb.Put,
        url = SosEndpoints.updateContact(projectBase, contactId),
        body = internalBody(userId),
    )

    override suspend fun updateExternal(contactId: String, draft: ExternalContactDraft): ZillitResult<Unit> = write(
        verb = HttpVerb.Put,
        url = SosEndpoints.updateContact(projectBase, contactId),
        body = externalBody(draft),
    )

    override suspend fun deleteContact(contactId: String): ZillitResult<Unit> =
        write(HttpVerb.Delete, SosEndpoints.contact(projectBase, contactId))

    override suspend fun relations(): ZillitResult<List<SosRelation>> = apiClient.requestOrNull(
        verb = HttpVerb.Get,
        url = SosEndpoints.relations(projectBase),
        serializer = ListSerializer(RelationDto.serializer()),
        module = RequestModule.ProjectUser,
    ).map { rows -> rows.orEmpty().mapNotNull { it.toRelation() } }

    override suspend fun isdCodes(): ZillitResult<List<IsdCode>> = apiClient.requestOrNull(
        verb = HttpVerb.Get,
        url = SosEndpoints.isdCodes(projectBase),
        serializer = ListSerializer(IsdCodeDto.serializer()),
        module = RequestModule.ProjectUser,
    ).map { rows -> rows.orEmpty().mapNotNull { it.toIsdCode() } }

    /**
     * A write whose answer is only ever "did it happen".
     *
     * `status:0` inside a 200 envelope is a refusal, not a success — the web
     * checks it on every SOS call it makes (`res?.status !== 1`,
     * `SOSMain.jsx:137`), and a repository that returned Success here would
     * have the screen report a contact saved that the server dropped.
     */
    private suspend fun write(verb: HttpVerb, url: String, body: JsonElement? = null): ZillitResult<Unit> =
        when (val envelope = apiClient.envelope(verb, url, RequestModule.ProjectUser, body)) {
            is ZillitResult.Failure -> envelope
            is ZillitResult.Success ->
                if (envelope.data.status == 0) {
                    ZillitResult.Failure(rejected(envelope.data))
                } else {
                    ZillitResult.Success(Unit)
                }
        }

    /**
     * Runs [fallback] only when [primary] failed.
     *
     * The two delete acts have two candidate routes each — the one both
     * clients perform, and the one the service's own API layer declares (see
     * [SosEndpoints.alertFallback]). Trying the second after the first is
     * refused costs one extra call on a path that is already failing, and
     * spares the user a delete that silently does nothing because the backend
     * moved.
     */
    private suspend fun withFallback(
        primary: suspend () -> ZillitResult<Unit>,
        fallback: suspend () -> ZillitResult<Unit>,
    ): ZillitResult<Unit> = when (val first = primary()) {
        is ZillitResult.Success -> first
        is ZillitResult.Failure -> when (val second = fallback()) {
            is ZillitResult.Success -> second
            // The first refusal is the one worth showing: it is the route the
            // shipping clients use, so its message describes the real problem.
            is ZillitResult.Failure -> first
        }
    }

    private fun rejected(envelope: ApiEnvelope): ZillitError =
        ZillitError.Http(status = HTTP_OK, serverMessage = envelope.message ?: "something_went_wrong")

    private companion object {
        const val HTTP_OK = 200
    }
}

/** `{user_id}` — Android `AddInternalContactModel` (`bottomNav/sos/model/SosRequestModel.kt:7-9`). */
internal fun internalBody(userId: String): JsonElement = buildJsonObject { put("user_id", userId) }

/**
 * `{contact_name, relation, country_code, phone_number}` — the four fields the
 * web posts (`SOS.jsx:227-232`) and Android's `AddExternalContactModel`
 * (`SosRequestModel.kt:11-17`). `relation` is a department *name*, not its id:
 * the web's option value is `department.department_name` (`SOS.jsx:648-652`).
 */
internal fun externalBody(draft: ExternalContactDraft): JsonElement = buildJsonObject {
    put("contact_name", draft.contactName)
    put("relation", draft.relation)
    put("country_code", draft.countryCode)
    put("phone_number", draft.phoneNumber)
}

// Wire rows ----------------------------------------------------------------

/**
 * One row of the `sos_label` feed — the notification service's
 * `NotificationDataModel` (`model/NotificationDataModel.kt:17-54`), narrowed to
 * the fields an SOS card shows.
 */
@Serializable
internal data class AlertDto(
    @SerialName("_id") val id: String? = null,
    @SerialName("notification_uuid") val uuid: String? = null,
    @SerialName("sender") val sender: String? = null,
    @SerialName("message") val message: String? = null,
    @SerialName("action") val action: String? = null,
    @SerialName("created") val created: Long? = null,
    @SerialName("updated") val updated: Long? = null,
    /** A soft-delete *stamp*, not a flag — non-zero means gone (`Sos.kt:263-267`). */
    @SerialName("deleted") val deleted: Long? = null,
    @SerialName("reference_data") val referenceData: AlertReferenceDto? = null,
)

/** `reference_data` on an SOS row (`NotificationDataModel.kt:57-70`). */
@Serializable
internal data class AlertReferenceDto(
    @SerialName("messageElements") val messageElements: List<MessageElement> = emptyList(),
    @SerialName("contact_info") val contactInfo: String? = null,
)

/**
 * A row to a [SosAlert].
 *
 * Rows without an `_id` are dropped — there is nothing to delete them by.
 */
internal fun AlertDto.toAlert(decoder: SosTextDecoder): SosAlert? {
    val rowId = id?.takeIf { it.isNotBlank() } ?: return null
    val elements = referenceData?.messageElements.orEmpty()
    return SosAlert(
        id = rowId,
        uuid = uuid.orEmpty(),
        senderId = sender.orEmpty(),
        senderNameHint = elements.firstOrNull()?.replacer.orEmpty(),
        text = decoder.text(message.orEmpty(), elements),
        mapsUrl = elements.mapsUrl(),
        action = action.orEmpty(),
        contactInfo = referenceData?.contactInfo.orEmpty(),
        createdMillis = created ?: 0L,
        updatedMillis = updated ?: 0L,
        deleted = (deleted ?: 0L) > 0L,
    )
}

/**
 * The map link out of the message elements.
 *
 * Android looks it up by placeholder — `search == "{{location}}"`
 * (`Sos.kt:478-481`) — while the web takes element `[1]` by position
 * (`SOSMain.jsx:519-522`). The named lookup first, the positional one as the
 * fallback: the name survives a reordered payload, and the position covers a
 * backend that spells the slot differently.
 */
private fun List<MessageElement>.mapsUrl(): String {
    val named = firstOrNull { it.search == LOCATION_SLOT }?.replacer
    val url = named ?: getOrNull(1)?.replacer
    return url?.takeIf { it.startsWith("http://") || it.startsWith("https://") }.orEmpty()
}

private const val LOCATION_SLOT = "{{location}}"

/** One saved receiver — Android `SosContactListDataModel` (`SosContactListResponseModel.kt:22-50`). */
@Serializable
internal data class ContactDto(
    @SerialName("_id") val id: String? = null,
    @SerialName("type") val type: String? = null,
    @SerialName("entry_type") val entryType: String? = null,
    @SerialName("user_id") val userId: String? = null,
    @SerialName("contact_name") val contactName: String? = null,
    @SerialName("relation") val relation: String? = null,
    @SerialName("country_code") val countryCode: String? = null,
    @SerialName("phone_number") val phoneNumber: String? = null,
    @SerialName("created") val created: Long? = null,
    @SerialName("user") val user: ContactUserDto? = null,
)

/** The crew member behind an internal row (`SosContactListResponseModel.kt:53-68`). */
@Serializable
internal data class ContactUserDto(
    @SerialName("full_name") val fullName: String? = null,
    @SerialName("designation_name") val designationName: String? = null,
)

internal fun ContactDto.toContact(): SosContact? {
    val rowId = id?.takeIf { it.isNotBlank() } ?: return null
    return SosContact(
        id = rowId,
        kind = SosContactKind.fromWire(type.orEmpty()),
        entryType = entryType.orEmpty(),
        userId = userId.orEmpty(),
        userFullName = user?.fullName.orEmpty(),
        userDesignation = user?.designationName.orEmpty(),
        contactName = contactName.orEmpty(),
        relation = relation.orEmpty(),
        countryCode = countryCode.orEmpty(),
        phoneNumber = phoneNumber.orEmpty(),
        createdMillis = created ?: 0L,
    )
}

/**
 * One relationship — Android `SosRelationListDataModel`
 * (`bottomNav/sos/model/SosRelationListResponseModel.kt:24-31`).
 *
 * `_id` is a **number** on this route where every other Zillit id is a string,
 * so it is read as a primitive and rendered rather than decoded as a `String`.
 */
@Serializable
internal data class RelationDto(
    @SerialName("_id") val id: JsonPrimitive? = null,
    @SerialName("department_name") val departmentName: String? = null,
    @SerialName("identifier") val identifier: String? = null,
    @SerialName("active") val active: Boolean? = null,
)

/**
 * A row to a [SosRelation]. Rows with no `department_name` are dropped: that
 * name *is* the value the form posts as `relation` (`SOS.jsx:650`), so a row
 * without one cannot be chosen.
 */
internal fun RelationDto.toRelation(): SosRelation? {
    val name = departmentName?.takeIf { it.isNotBlank() } ?: return null
    return SosRelation(id = id?.content.orEmpty(), name = name, identifier = identifier.orEmpty())
}

/** One dialling code — Android `CountryISD` (`model/CountryListResponse.kt:55-62`). */
@Serializable
internal data class IsdCodeDto(
    @SerialName("name") val name: String? = null,
    @SerialName("dial_code") val dialCode: String? = null,
    @SerialName("code") val code: String? = null,
)

internal fun IsdCodeDto.toIsdCode(): IsdCode? {
    val dial = dialCode?.takeIf { it.isNotBlank() } ?: return null
    return IsdCode(name = name.orEmpty(), dialCode = dial, code = code.orEmpty())
}
