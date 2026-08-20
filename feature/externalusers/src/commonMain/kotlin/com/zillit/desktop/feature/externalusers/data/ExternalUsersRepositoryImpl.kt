package com.zillit.desktop.feature.externalusers.data

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.common.map
import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.ApiEnvelope
import com.zillit.desktop.core.network.HttpVerb
import com.zillit.desktop.core.network.RequestModule
import com.zillit.desktop.feature.externalusers.domain.ExternalUser
import com.zillit.desktop.feature.externalusers.domain.ExternalUserBucket
import com.zillit.desktop.feature.externalusers.domain.ExternalUsersRepository
import com.zillit.desktop.feature.externalusers.domain.LabeledValue
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * `user/external-user` on the project host — one resource, four verbs
 * (web `projectApi.js:429-487`, Android `ApiUrl.kt:840-841`).
 *
 * Bodies follow the web: every key always present, empty string when unset —
 * the safer of the two clients' encodings. The delete carries a body, which
 * the server requires despite the verb.
 */
class ExternalUsersRepositoryImpl(
    private val apiClient: ApiClient,
    private val config: AppConfig,
) : ExternalUsersRepository {

    override suspend fun list(
        bucket: ExternalUserBucket,
        timestampMillis: Long,
        older: Boolean,
    ): ZillitResult<List<ExternalUser>> {
        val parameters = buildMap {
            // `All` omits the parameter: `ExternalUserType=all` answers an
            // empty list (ZL-17425). The web omits; Android still sends it
            // and hides the damage behind its local cache.
            if (bucket != ExternalUserBucket.All) put("ExternalUserType", bucket.wire)
            put("nextPrevious", if (older) "next" else "previous")
            put("timestamp", timestampMillis.toString())
        }
        return apiClient.request(
            verb = HttpVerb.Get,
            url = url(),
            serializer = ListSerializer(ExternalUserDto.serializer()),
            module = RequestModule.ProjectUser,
            queryParameters = parameters,
        ).map { rows -> rows.mapNotNull { it.toModel() } }
    }

    override suspend fun create(user: ExternalUser): ZillitResult<Unit> =
        write(HttpVerb.Post, user.toBody(includeId = false))

    override suspend fun update(user: ExternalUser): ZillitResult<Unit> =
        write(HttpVerb.Put, user.toBody(includeId = true))

    override suspend fun delete(id: String): ZillitResult<Unit> =
        write(
            HttpVerb.Delete,
            buildJsonObject { put("external_user_id", id) },
        )

    private suspend fun write(verb: HttpVerb, body: JsonElement): ZillitResult<Unit> =
        apiClient.envelope(
            verb = verb,
            url = url(),
            module = RequestModule.ProjectUser,
            body = body,
        ).refuseStatusZero().map { }

    private fun url() = "${config.apiV2(ZillitService.Core)}user/external-user"
}

/** The service says no with `status: 0` on a 200 — surface its message. */
internal fun ZillitResult<ApiEnvelope>.refuseStatusZero(): ZillitResult<ApiEnvelope> =
    when (this) {
        is ZillitResult.Failure -> this
        is ZillitResult.Success ->
            if (data.status == 0) {
                ZillitResult.Failure(
                    ZillitError.Validation(data.message ?: "The server refused the change."),
                )
            } else {
                this
            }
    }

/** The web's body: every key, empty when unset (`ExternalUserModal.jsx:127-150`). */
internal fun ExternalUser.toBody(includeId: Boolean): JsonElement = buildJsonObject {
    put("full_name", fullName)
    put("email", email)
    put("phone", phone)
    put("gender", gender)
    put("country_code", countryCode)
    put("external_user_type", userType)
    put("department_id", departmentId)
    put("designation_id", designationId)
    put(
        "other_info",
        buildJsonArray {
            otherInfo.filter { it.label.isNotBlank() && it.value.isNotBlank() }.forEach { row ->
                add(
                    buildJsonObject {
                        put("label", row.label)
                        put("value", row.value)
                    },
                )
            }
        },
    )
    if (includeId) put("external_user_id", id)
}

@Serializable
internal data class ExternalUserDto(
    @SerialName("_id") val id: String? = null,
    @SerialName("full_name") val fullName: String? = null,
    @SerialName("email") val email: String? = null,
    @SerialName("phone") val phone: String? = null,
    @SerialName("country_code") val countryCode: String? = null,
    @SerialName("gender") val gender: String? = null,
    @SerialName("external_user_type") val userType: String? = null,
    @SerialName("department_id") val departmentId: String? = null,
    @SerialName("designation_id") val designationId: String? = null,
    @SerialName("other_info") val otherInfo: JsonElement? = null,
    @SerialName("created_by") val createdBy: String? = null,
    @SerialName("updated_on") val updatedOn: Long? = null,
    @SerialName("deleted_on") val deletedOn: Long? = null,
) {
    /** Soft-deleted rows answer null — the server tombstones, never removes. */
    fun toModel(): ExternalUser? {
        val resolved = id ?: return null
        if ((deletedOn ?: 0L) > 0L) return null
        return ExternalUser(
            id = resolved,
            fullName = fullName.orEmpty(),
            email = email.orEmpty(),
            phone = phone.orEmpty(),
            countryCode = countryCode.orEmpty(),
            gender = gender.orEmpty(),
            userType = userType.orEmpty(),
            departmentId = departmentId.orEmpty(),
            designationId = designationId.orEmpty(),
            otherInfo = otherInfo.toLabeledValues(),
            createdBy = createdBy.orEmpty(),
            updatedOnMillis = updatedOn ?: 0L,
        )
    }
}

/** `[{label, value}]`, read tolerantly — rows missing either half are kept as-is. */
private fun JsonElement?.toLabeledValues(): List<LabeledValue> {
    val rows = this as? JsonArray ?: return emptyList()
    return rows.mapNotNull { element ->
        val row = element as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
        LabeledValue(
            label = (row["label"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty(),
            value = (row["value"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty(),
        )
    }
}
