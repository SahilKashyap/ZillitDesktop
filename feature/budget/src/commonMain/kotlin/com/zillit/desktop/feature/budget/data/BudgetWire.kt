package com.zillit.desktop.feature.budget.data

import com.zillit.desktop.feature.budget.domain.BudgetDocument
import com.zillit.desktop.feature.budget.domain.BudgetFile
import com.zillit.desktop.feature.budget.domain.BudgetMember
import com.zillit.desktop.feature.budget.domain.BudgetType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * A budget document as the service sends it.
 *
 * Every string is nullable: this server omits fields it has nothing for, and
 * a strict field would fail the whole list over one thin row.
 */
@Serializable
internal data class BudgetDocumentDto(
    @SerialName("_id") val id: String? = null,
    @SerialName("budget_type") val budgetType: String? = null,
    @SerialName("department_id") val departmentId: String? = null,
    @SerialName("department_name") val departmentName: String? = null,
    @SerialName("attachment") val attachment: BudgetFileDto? = null,
    @SerialName("full_name") val fullName: String? = null,
    @SerialName("created") val created: Long? = null,
)

@Serializable
internal data class BudgetFileDto(
    @SerialName("media") val media: String? = null,
    @SerialName("name") val name: String? = null,
    @SerialName("content_type") val contentType: String? = null,
    @SerialName("bucket") val bucket: String? = null,
    @SerialName("region") val region: String? = null,
    @SerialName("size") val size: Long? = null,
)

@Serializable
internal data class BudgetMemberDto(
    @SerialName("user_id") val userId: String? = null,
    @SerialName("_id") val id: String? = null,
    @SerialName("full_name") val fullName: String? = null,
    @SerialName("profile_media") val profileMedia: String? = null,
    @SerialName("department_name") val departmentName: String? = null,
)

/** `{budgets: [...]}` — the list endpoint's one wrapper key. */
@Serializable
internal data class BudgetListDto(
    @SerialName("budgets") val budgets: List<BudgetDocumentDto>? = null,
)

/** `{main_budget_users: [...], department_budget_users: [...]}`. */
@Serializable
internal data class BudgetMembersDto(
    @SerialName("main_budget_users") val main: List<BudgetMemberDto>? = null,
    @SerialName("department_budget_users") val department: List<BudgetMemberDto>? = null,
)

/** `{count: n}` — or a bare number, which this server has also been seen to send. */
@Serializable
internal data class BudgetCountDto(
    @SerialName("count") val count: Int? = null,
)

internal fun BudgetDocumentDto.toDocument(): BudgetDocument? {
    val identifier = id?.takeIf { it.isNotBlank() } ?: return null
    return BudgetDocument(
        id = identifier,
        type = BudgetType.ofWire(budgetType),
        departmentId = departmentId.orEmpty(),
        departmentName = departmentName.orEmpty(),
        file = attachment?.toFile(),
        uploadedByName = fullName.orEmpty(),
        uploadedAtMillis = created ?: 0,
    )
}

/**
 * An emptied document keeps its row: deleting a budget leaves `attachment` as
 * `{}` rather than removing the document (`BudgetPrimaryComponent.jsx:240-243`),
 * and a file with no key is nothing to open.
 */
internal fun BudgetFileDto.toFile(): BudgetFile? {
    val key = media?.takeIf { it.isNotBlank() } ?: return null
    return BudgetFile(
        media = key,
        name = name.orEmpty().ifBlank { key.substringAfterLast('/') },
        contentType = contentType.orEmpty(),
        bucket = bucket.orEmpty(),
        region = region.orEmpty(),
        sizeBytes = size ?: 0,
    )
}

internal fun BudgetMemberDto.toMember(): BudgetMember? {
    val identifier = userId?.takeIf { it.isNotBlank() } ?: id?.takeIf { it.isNotBlank() } ?: return null
    return BudgetMember(
        userId = identifier,
        fullName = fullName.orEmpty(),
        profileMedia = profileMedia.orEmpty(),
        departmentName = departmentName.orEmpty(),
    )
}

/**
 * The upload body: type, the file, and — for a department's budget — whose
 * department it is (`BudgetPrimaryComponent.jsx:145-149`, `BudgetView.jsx:77-80`,
 * which omits the department for a main budget rather than sending it empty).
 */
internal fun postBody(type: BudgetType, departmentId: String, file: BudgetFile): JsonObject =
    buildJsonObject {
        put("budget_type", type.wire)
        if (type == BudgetType.Department && departmentId.isNotBlank()) {
            put("department_id", departmentId)
        }
        put(
            "attachment",
            buildJsonObject {
                put("media", file.media)
                put("name", file.name)
                put("content_type", file.contentType)
                put("bucket", file.bucket)
                put("region", file.region)
                put("size", file.sizeBytes)
            },
        )
    }

/** `{budget_ids: [...]}` — a list even for one (`BudgetPrimaryComponent.jsx:233-236`). */
internal fun deleteBody(documentIds: List<String>): JsonObject = buildJsonObject {
    put(
        "budget_ids",
        kotlinx.serialization.json.buildJsonArray {
            documentIds.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) }
        },
    )
}

/** A JSON element as a document list, whether it arrives wrapped or bare. */
internal fun documentsOf(data: JsonElement?, json: kotlinx.serialization.json.Json): List<BudgetDocument> {
    if (data == null) return emptyList()
    val wrapped = runCatching { json.decodeFromJsonElement(BudgetListDto.serializer(), data) }.getOrNull()
    if (wrapped?.budgets != null) return wrapped.budgets.mapNotNull { it.toDocument() }
    val bare = runCatching {
        json.decodeFromJsonElement(kotlinx.serialization.builtins.ListSerializer(BudgetDocumentDto.serializer()), data)
    }.getOrNull()
    if (bare != null) return bare.mapNotNull { it.toDocument() }
    // A single document, as the upload and the per-department reads answer.
    return runCatching { json.decodeFromJsonElement(BudgetDocumentDto.serializer(), data) }
        .getOrNull()?.toDocument()?.let(::listOf).orEmpty()
}
