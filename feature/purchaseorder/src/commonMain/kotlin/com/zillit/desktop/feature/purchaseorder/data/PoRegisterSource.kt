package com.zillit.desktop.feature.purchaseorder.data

import com.zillit.desktop.core.common.CurrencyCodeSerializer
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.common.map
import com.zillit.desktop.core.common.toEpochMillisOrNull
import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.HttpVerb
import com.zillit.desktop.core.network.RequestModule
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.purchaseorder.domain.PoAddress
import com.zillit.desktop.feature.purchaseorder.domain.PoDeliveryAddress
import com.zillit.desktop.feature.purchaseorder.domain.PoTemplate
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull

/**
 * The two registers hanging off the purchase-order service: saved order shapes
 * (`/templates`) and the production's delivery address book
 * (`/delivery-addresses`) — the web's `po-templates.js` and
 * `po-delivery-addresses.js`.
 *
 * Its own class so the repository stays about orders, and because
 * `PurchaseOrderRepositoryImpl` had already reached detekt's LargeClass line
 * once; the codebase's answer to that is extraction, not a suppression.
 */
internal class PoRegisterSource(private val apiClient: ApiClient, config: AppConfig) {

    private val templatesUrl = "${config.baseUrl(ZillitService.PurchaseOrder)}/api/v2/purchase-orders/templates"
    private val addressesUrl =
        "${config.baseUrl(ZillitService.PurchaseOrder)}/api/v2/purchase-orders/delivery-addresses"

    suspend fun templates(): ZillitResult<List<PoTemplate>> = apiClient.request(
        verb = HttpVerb.Get,
        url = templatesUrl,
        serializer = ListSerializer(PoTemplateDto.serializer()),
        module = RequestModule.ProjectUser,
    ).map { rows -> rows.mapNotNull { it.toDomain() } }

    /**
     * Creates or replaces one.
     *
     * A blank id means create (`POST`), an id means replace (`PATCH`) — the
     * same two calls the web's Save / Save as Template pair makes, decided
     * here so no screen has to.
     */
    suspend fun saveTemplate(template: PoTemplate): ZillitResult<PoTemplate> {
        val existing = template.id.takeIf { it.isNotBlank() }
        return apiClient.request(
            verb = if (existing == null) HttpVerb.Post else HttpVerb.Patch,
            url = if (existing == null) templatesUrl else "$templatesUrl/$existing",
            serializer = PoTemplateDto.serializer(),
            module = RequestModule.ProjectUser,
            body = template.body(),
        ).map { dto -> dto.toDomain() ?: template }
    }

    suspend fun deleteTemplate(id: String): ZillitResult<Unit> =
        apiClient.envelope(HttpVerb.Delete, "$templatesUrl/$id", RequestModule.ProjectUser).map { }

    suspend fun deliveryAddresses(): ZillitResult<List<PoDeliveryAddress>> = apiClient.request(
        verb = HttpVerb.Get,
        url = addressesUrl,
        serializer = ListSerializer(PoDeliveryAddressDto.serializer()),
        module = RequestModule.ProjectUser,
    ).map { rows -> rows.mapNotNull { it.toDomain() } }

    /**
     * Saves an address, de-duped server-side.
     *
     * Both verbs wrap the parts in an `address` object rather than sending them
     * at the top level; the server reads nothing else. Editing is permission-
     * checked there too (an accountant may edit any row, everyone else only
     * their own), so a 403 here is the answer, not a bug.
     */
    suspend fun saveDeliveryAddress(id: String?, address: PoAddress): ZillitResult<PoDeliveryAddress> {
        val existing = id?.takeIf { it.isNotBlank() }
        return apiClient.request(
            verb = if (existing == null) HttpVerb.Post else HttpVerb.Patch,
            url = if (existing == null) addressesUrl else "$addressesUrl/$existing",
            serializer = PoDeliveryAddressDto.serializer(),
            module = RequestModule.ProjectUser,
            body = buildJsonObject { put("address", address.toJson()) },
        ).map { dto ->
            dto.toDomain() ?: PoDeliveryAddress(
                id = existing.orEmpty(),
                label = address.oneLine,
                address = address,
            )
        }
    }
}

/**
 * The template's save body — the web's `handleSaveTemplate`, key for key.
 *
 * Its blanks go out as explicit `null` (and `""` for the address), not as a
 * missing key: the edit is a PATCH, and a key left out leaves the stored value
 * behind, so a company, episode or date cleared in the editor would come back
 * on the next open. The address object is sent even when empty for the same
 * reason; `vat_treatment` falls back to `pending` as the web's does.
 */
internal fun PoTemplate.body() = buildJsonObject {
    put("template_name", JsonPrimitive(name))
    putIfPresent("vendor_id", vendorId)
    putIfPresent("department_id", departmentId)
    putIfPresent("nominal_code", nominalCode)
    put("description", JsonPrimitive(description))
    put("currency", JsonPrimitive(currency?.takeIf { it.isNotBlank() } ?: "GBP"))
    putIfPresent("notes", notes)
    put("company_id", companyId.orJsonNull())
    put("episode", episode.orJsonNull())
    put("delivery_address", (deliveryAddress ?: PoAddress()).toJson())
    put("delivery_address_id", deliveryAddressId.orJsonNull())
    put("delivery_date", deliveryDate?.let { JsonPrimitive(it) } ?: JsonNull)
    put("effective_date", effectiveDate?.let { JsonPrimitive(it) } ?: JsonNull)
    put("vat_treatment", JsonPrimitive(vatTreatment?.takeIf { it.isNotBlank() } ?: "pending"))
    put("custom_fields", customFields.customFieldsJson())
    put("attachments", attachments.attachmentsJson())
    put("line_items", buildJsonArray { lines.forEach { add(it.body()) } })
}

private fun String?.orJsonNull(): JsonElement =
    this?.trim()?.takeIf { it.isNotEmpty() }?.let { JsonPrimitive(it) } ?: JsonNull

@Serializable
internal data class PoTemplateDto(
    @SerialName("id") val id: String? = null,
    @SerialName("template_name") val name: String? = null,
    @SerialName("vendor_id") val vendorId: String? = null,
    @SerialName("vendor_name") val vendorName: String? = null,
    @SerialName("department_id") val departmentId: String? = null,
    @SerialName("nominal_code") val nominalCode: String? = null,
    @SerialName("description") val description: String? = null,
    @Serializable(with = CurrencyCodeSerializer::class)
    @SerialName("currency") val currency: String? = null,
    @SerialName("notes") val notes: String? = null,
    // An array, or a JSON string holding one — templates echo whichever the
    // writer sent, so both are read (the same tolerance `line_items` needs on
    // an order itself).
    @SerialName("line_items") val lineItems: JsonElement? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("created_by") val createdBy: String? = null,
    @SerialName("user_id") val userId: String? = null,
    @SerialName("company_id") val companyId: String? = null,
    @SerialName("episode") val episode: String? = null,
    // An object from the form, a JSON string or plain text on older rows —
    // typed `String` it would fail the decode of the whole list.
    @SerialName("delivery_address") val deliveryAddress: JsonElement? = null,
    @SerialName("delivery_address_id") val deliveryAddressId: String? = null,
    @SerialName("delivery_date") val deliveryDate: String? = null,
    @SerialName("effective_date") val effectiveDate: String? = null,
    @SerialName("vat_treatment") val vatTreatment: String? = null,
    @SerialName("custom_fields") val customFields: JsonElement? = null,
    // An array, or a JSON string holding one, like `line_items`.
    @SerialName("attachments") val attachments: JsonElement? = null,
) {
    fun toDomain(): PoTemplate? {
        val identifier = id?.takeIf { it.isNotBlank() } ?: return null
        return PoTemplate(
            id = identifier,
            // A template with no name is still a template; the list says
            // "Untitled" rather than dropping a row somebody saved.
            name = name?.takeIf { it.isNotBlank() } ?: str(S.untitled),
            vendorId = vendorId,
            vendorName = vendorName.orEmpty(),
            departmentId = departmentId,
            nominalCode = nominalCode,
            description = description.orEmpty(),
            currency = currency,
            notes = notes,
            lines = lineItems.asLineItems().map { it.toDomain() },
            createdAt = createdAt.toEpochMillisOrNull(),
            createdBy = createdBy ?: userId,
            companyId = companyId?.takeIf { it.isNotBlank() },
            episode = episode?.takeIf { it.isNotBlank() },
            deliveryAddressId = deliveryAddressId?.takeIf { it.isNotBlank() },
            deliveryAddress = deliveryAddress.asAddress(),
            deliveryDate = deliveryDate.toEpochMillisOrNull(),
            effectiveDate = effectiveDate.toEpochMillisOrNull(),
            vatTreatment = vatTreatment?.takeIf { it.isNotBlank() },
            customFields = customFields.asEmbeddedArray().asCustomFieldGroups(),
            attachments = (attachments.asEmbeddedArray() as? JsonArray).orEmpty()
                .mapNotNull { (it as? JsonObject)?.asAttachment() },
        )
    }
}

/**
 * The `delivery_address` object back into its parts. A JSON string holding one
 * is read too; plain text (an older template) has no parts to recover, so it
 * becomes the first address line rather than being dropped.
 */
internal fun JsonElement?.asAddress(): PoAddress? {
    val parts = when (this) {
        is JsonObject -> this
        is JsonPrimitive -> {
            val text = contentOrNull?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return PoAddress(line1 = text)
        }

        else -> return null
    }
    return runCatching { PoAddressJson.decodeFromJsonElement(PoAddressDto.serializer(), parts).toDomain() }
        .getOrNull()
        ?.takeUnless { it.isEmpty }
}

/** An array column, or a JSON string holding one — the same tolerance `line_items` needs. */
internal fun JsonElement?.asEmbeddedArray(): JsonElement? = when (this) {
    is JsonArray -> this
    is JsonPrimitive -> contentOrNull?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() }
    else -> null
}

private val PoAddressJson = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

@Serializable
internal data class PoDeliveryAddressDto(
    @SerialName("id") val id: String? = null,
    @SerialName("label") val label: String? = null,
    @SerialName("address") val address: PoAddressDto? = null,
    @SerialName("created_by") val createdBy: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_by") val updatedBy: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
) {
    fun toDomain(): PoDeliveryAddress? {
        val identifier = id?.takeIf { it.isNotBlank() } ?: return null
        val parts = address?.toDomain() ?: PoAddress()
        return PoDeliveryAddress(
            id = identifier,
            label = label?.takeIf { it.isNotBlank() } ?: parts.oneLine,
            address = parts,
            createdBy = createdBy,
            createdAt = createdAt.toEpochMillisOrNull(),
            updatedBy = updatedBy,
            updatedAt = updatedAt.toEpochMillisOrNull(),
        )
    }
}

/**
 * The address parts.
 *
 * Note the camelCase on three of them: the web sends `phoneCode` and
 * `postalCode` inside the `address` object while every other key on this
 * service is snake_case, and the server stores the object verbatim. Renaming
 * them here would save an address the form could not read back.
 */
@Serializable
internal data class PoAddressDto(
    @SerialName("name") val name: String? = null,
    @SerialName("email") val email: String? = null,
    @SerialName("phoneCode") val phoneCode: String? = null,
    @SerialName("phone") val phone: String? = null,
    @SerialName("line1") val line1: String? = null,
    @SerialName("line2") val line2: String? = null,
    @SerialName("city") val city: String? = null,
    @SerialName("state") val state: String? = null,
    @SerialName("postalCode") val postalCode: String? = null,
    @SerialName("country") val country: String? = null,
) {
    fun toDomain() = PoAddress(
        name = name.orEmpty(),
        email = email.orEmpty(),
        phoneCode = phoneCode.orEmpty(),
        phone = phone.orEmpty(),
        line1 = line1.orEmpty(),
        line2 = line2.orEmpty(),
        city = city.orEmpty(),
        state = state.orEmpty(),
        postalCode = postalCode.orEmpty(),
        country = country.orEmpty(),
    )
}
