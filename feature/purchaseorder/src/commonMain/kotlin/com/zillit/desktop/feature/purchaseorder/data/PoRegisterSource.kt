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
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

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

private fun PoTemplate.body() = buildJsonObject {
    put("template_name", JsonPrimitive(name))
    putIfPresent("vendor_id", vendorId)
    putIfPresent("department_id", departmentId)
    putIfPresent("nominal_code", nominalCode)
    put("description", JsonPrimitive(description))
    put("currency", JsonPrimitive(currency?.takeIf { it.isNotBlank() } ?: "GBP"))
    putIfPresent("notes", notes)
    put("line_items", buildJsonArray { lines.forEach { add(it.body()) } })
}

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
        )
    }
}

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
