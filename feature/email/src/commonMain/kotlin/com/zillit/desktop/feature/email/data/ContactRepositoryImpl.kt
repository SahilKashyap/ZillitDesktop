package com.zillit.desktop.feature.email.data

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.common.map
import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.HttpVerb
import com.zillit.desktop.core.network.RequestModule
import com.zillit.desktop.core.network.jsonBody
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import com.zillit.desktop.feature.email.domain.ContactRepository
import com.zillit.desktop.feature.email.domain.EmailContact

/**
 * The user's own address book.
 *
 * Crew suggestions do **not** come from here — they come from the project users
 * this app already caches when a production opens, so the composer costs one
 * request rather than two.
 */
class ContactRepositoryImpl(
    private val apiClient: ApiClient,
    private val config: AppConfig,
) : ContactRepository {

    private val api get() = config.apiV2(ZillitService.Email)
    override suspend fun contacts(): ZillitResult<List<EmailContact>> =
        apiClient.request(
            verb = HttpVerb.Get,
            url = "${api}email-contact",
            serializer = JsonElement.serializer(),
            module = RequestModule.ProjectUser,
        ).map { payload -> payload.contactRows().mapNotNull(::readContact) }
}

/** Contacts come back as `{"contacts": [...]}`, or as a bare array. */
private fun JsonElement.contactRows(): List<JsonElement> = when (this) {
    is JsonArray -> this
    is JsonObject -> (this["contacts"] as? JsonArray) ?: (this["data"] as? JsonArray) ?: emptyList()
    else -> emptyList()
}
