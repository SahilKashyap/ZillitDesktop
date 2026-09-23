package com.zillit.desktop.feature.budget.data

import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.budget.domain.BudgetActivityRow
import com.zillit.desktop.feature.budget.domain.BudgetChatEntry
import com.zillit.desktop.feature.budget.domain.BudgetDocument
import com.zillit.desktop.feature.budget.domain.BudgetFile
import com.zillit.desktop.feature.budget.domain.BudgetMember
import com.zillit.desktop.feature.budget.domain.BudgetMode
import com.zillit.desktop.feature.budget.domain.BudgetType
import com.zillit.desktop.feature.budget.domain.BudgetUpload
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
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
    @SerialName("budget_title") val budgetTitle: String? = null,
    @SerialName("episode") val episode: JsonElement? = null,
    @SerialName("attachment") val attachment: BudgetFileDto? = null,
    @SerialName("user_id") val userId: String? = null,
    @SerialName("full_name") val fullName: String? = null,
    @SerialName("created") val created: Long? = null,
    @SerialName("updated") val updated: Long? = null,
    @SerialName("user_visit") val userVisit: Long? = null,
    @SerialName("deleted") val deleted: JsonElement? = null,
)

@Serializable
internal data class BudgetFileDto(
    @SerialName("media") val media: String? = null,
    @SerialName("name") val name: String? = null,
    @SerialName("content_type") val contentType: String? = null,
    @SerialName("bucket") val bucket: String? = null,
    @SerialName("region") val region: String? = null,
    @SerialName("size") val size: JsonElement? = null,
    @SerialName("file_size") val fileSize: JsonElement? = null,
    @SerialName("thumbnail") val thumbnail: String? = null,
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

/**
 * `{main_budget_users: [...], department_budget_users: [...]}`.
 *
 * Each list is either user ids or user objects — the web treats the answer
 * as ids (`MembersModal.jsx:64`, `ChatUserAndGroupList.jsx:178`), so both
 * shapes are read.
 */
@Serializable
internal data class BudgetMembersDto(
    @SerialName("main_budget_users") val main: JsonArray? = null,
    @SerialName("department_budget_users") val department: JsonArray? = null,
)

internal fun BudgetDocumentDto.toDocument(): BudgetDocument? {
    val identifier = id?.takeIf { it.isNotBlank() } ?: return null
    return BudgetDocument(
        id = identifier,
        type = BudgetType.ofWire(budgetType),
        departmentId = departmentId.orEmpty(),
        departmentName = departmentName.orEmpty(),
        title = budgetTitle.orEmpty(),
        episode = episode.primitiveText(),
        file = attachment?.toFile(),
        uploadedById = userId.orEmpty(),
        uploadedByName = fullName.orEmpty(),
        createdMillis = created ?: 0,
        updatedMillis = updated ?: created ?: 0,
        userVisit = userVisit ?: 0,
        // `deleted == 0` is the web's "alive" test (`budgetutil.js:157`); the
        // flag has been seen as a number, a boolean and a stamp.
        deleted = deleted.let { flag ->
            val primitive = flag as? JsonPrimitive
            primitive?.booleanOrNull == true || (primitive?.longOrNull ?: 0L) > 0L
        },
    )
}

/**
 * An emptied document keeps its row: deleting a budget leaves `attachment` as
 * `{}` rather than removing the document, and a file with no key is nothing
 * to open.
 */
internal fun BudgetFileDto.toFile(): BudgetFile? {
    val key = media?.takeIf { it.isNotBlank() } ?: return null
    return BudgetFile(
        media = key,
        name = name.orEmpty().ifBlank { key.substringAfterLast('/') },
        contentType = contentType.orEmpty(),
        bucket = bucket.orEmpty(),
        region = region.orEmpty(),
        sizeBytes = (size ?: fileSize).primitiveLong(),
        thumbnail = thumbnail.orEmpty(),
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

/** A member list that is ids, objects, or a mix of the two. */
internal fun JsonArray?.toMembers(json: Json): List<BudgetMember> = orEmpty().mapNotNull { element ->
    when (element) {
        is JsonPrimitive -> element.contentOrNull
            ?.takeIf { it.isNotBlank() }
            ?.let { BudgetMember(userId = it, fullName = "") }
        is JsonObject -> runCatching { json.decodeFromJsonElement(BudgetMemberDto.serializer(), element) }
            .getOrNull()?.toMember()
        else -> null
    }
}

/**
 * The upload body (`CommonBudget.jsx:961-1005`, `AddAndShowDepartmentList.jsx:
 * 337-349`): type, the file with the web's stock thumbnail already pinned on
 * it, the title, the department for a department budget, and the episode
 * only when one was given.
 */
internal fun postBody(upload: BudgetUpload): JsonObject = buildJsonObject {
    put("budget_type", upload.type.wire)
    if (upload.type == BudgetType.Department && upload.departmentId.isNotBlank()) {
        put("department_id", upload.departmentId)
    }
    put("budget_title", upload.title)
    if (upload.episode.isNotBlank()) put("episode", upload.episode)
    put("attachment", attachmentBody(upload.file))
}

/** The attachment as the web's `sendMSGModal` shapes a document. */
internal fun attachmentBody(file: BudgetFile): JsonObject = buildJsonObject {
    put("media", file.media)
    put("name", file.name)
    put("content_type", "document")
    put("content_subtype", file.name.substringAfterLast('.', "pdf").lowercase())
    put("caption", "document")
    put("bucket", file.bucket)
    put("region", file.region)
    put("size", file.sizeBytes)
    put("file_size", file.sizeBytes)
    if (file.thumbnail.isNotBlank()) put("thumbnail", file.thumbnail)
}

/** `{budget_ids: [...]}` — a list even for one. */
internal fun deleteBody(documentIds: List<String>): JsonObject = buildJsonObject {
    put("budget_ids", buildJsonArray { documentIds.forEach { add(JsonPrimitive(it)) } })
}

/**
 * The `budget:recent:list` ask (`CommonBudget.jsx:getChatUserList`): the
 * tool, the production, the asker, the document — and the department only
 * off the department tile, where the web adds it after the fact.
 */
internal fun chatListBody(
    mode: BudgetMode,
    projectId: String,
    userId: String,
    departmentId: String,
    documentId: String,
): JsonObject = buildJsonObject {
    put("chat_tool", mode.tool)
    put("project_id", projectId)
    put("user_id", userId)
    put("budget_document_id", documentId)
    if (mode == BudgetMode.Department) put("department_id", departmentId)
}

/** `POST chat-room` for a budget room — `CommonBudget.jsx:createGroup`. */
internal fun createRoomBody(
    mode: BudgetMode,
    departmentId: String,
    documentId: String,
    ownerId: String,
    name: String,
    memberIds: List<String>,
): JsonObject = buildJsonObject {
    put("room_name", name)
    put("department_id", departmentId)
    put("room_tool", mode.tool)
    put("owned_by", ownerId)
    put("members", buildJsonArray { memberIds.forEach { add(JsonPrimitive(it)) } })
    put("budget_document_id", documentId)
}

/** A JSON element as a document list, whether it arrives wrapped or bare. */
internal fun documentsOf(data: JsonElement?, json: Json): List<BudgetDocument> {
    if (data == null) return emptyList()
    val wrapped = runCatching { json.decodeFromJsonElement(BudgetListDto.serializer(), data) }.getOrNull()
    if (wrapped?.budgets != null) return wrapped.budgets.mapNotNull { it.toDocument() }
    val bare = runCatching {
        json.decodeFromJsonElement(ListSerializer(BudgetDocumentDto.serializer()), data)
    }.getOrNull()
    if (bare != null) return bare.mapNotNull { it.toDocument() }
    // A single document, as the upload and the view/download record answer.
    return runCatching { json.decodeFromJsonElement(BudgetDocumentDto.serializer(), data) }
        .getOrNull()?.toDocument()?.let(::listOf).orEmpty()
}

/** The count endpoint's rows: `[{user_id, view_count, download_count}]`, wrapped or bare. */
internal fun activityRowsOf(data: JsonElement?): List<BudgetActivityRow> {
    val rows = when (data) {
        is JsonArray -> data
        is JsonObject -> (data["data"] ?: data["users"] ?: data["count"]) as? JsonArray
        else -> null
    } ?: return emptyList()
    return rows.mapNotNull { row ->
        val obj = row as? JsonObject ?: return@mapNotNull null
        val userId = obj.text("user_id") ?: obj.text("_id") ?: return@mapNotNull null
        BudgetActivityRow(
            userId = userId,
            viewCount = obj.int("view_count"),
            downloadCount = obj.int("download_count"),
        )
    }
}

/**
 * The `budget:recent:list` ack: one array of people (`user_id`) and rooms
 * (`_id` + `room_name`, `is_group`), which the web tells apart by the keys
 * each carries (`CommonBudget.jsx:getUsersDetails`). Ordered as the server
 * orders them.
 */
internal fun chatEntriesOf(ack: JsonElement?): List<BudgetChatEntry> {
    val rows = when (ack) {
        is JsonArray -> ack
        is JsonObject -> (ack["detail"] as? JsonArray)
            ?: ((ack["detail"] as? JsonObject)?.let { it["list"] ?: it["data"] } as? JsonArray)
            ?: (ack["data"] as? JsonArray)
        else -> null
    } ?: return emptyList()
    return rows.mapNotNull { row -> (row as? JsonObject)?.let(::chatEntryOf) }
}

internal fun chatEntryOf(row: JsonObject): BudgetChatEntry? {
    val userId = row.text("user_id")
    val isGroup = (row["is_group"] as? JsonPrimitive)?.booleanOrNull == true || row.text("room_name") != null
    return if (userId != null && !isGroup) {
        BudgetChatEntry.Person(
            userId = userId,
            name = row.text("full_name").orEmpty(),
            designation = (row.text("designation_name") ?: row.text("designation").orEmpty()).localised(),
            isAdmin = (row["is_admin"] as? JsonPrimitive)?.booleanOrNull == true,
            deviceId = row.text("device_id").orEmpty(),
            hasLeft = row.text("status") in LEFT_STATUSES,
        )
    } else {
        val roomId = row.text("_id") ?: return null
        BudgetChatEntry.Group(
            roomId = roomId,
            name = row.text("room_name") ?: str(S.group),
            ownedBy = row.text("owned_by").orEmpty(),
            memberIds = (row["members"] as? JsonArray).orEmpty().mapNotNull { member ->
                when (member) {
                    is JsonPrimitive -> member.contentOrNull
                    is JsonObject -> member.takeIf {
                        (it["enabled"] as? JsonPrimitive)?.booleanOrNull != false
                    }?.let { it.text("user_id") ?: it.text("_id") }
                    else -> null
                }
            },
            pictureMedia = (row["group_picture"] as? JsonObject)?.text("media").orEmpty(),
            departmentId = row.text("department_id").orEmpty(),
            budgetDocumentId = row.text("budget_document_id").orEmpty(),
        )
    }
}

/** The created room out of `data.chat_room`, or the row bare. */
internal fun createdGroupOf(data: JsonElement?): BudgetChatEntry.Group? {
    val obj = data as? JsonObject ?: return null
    val room = (obj["chat_room"] as? JsonObject) ?: obj
    return chatEntryOf(room) as? BudgetChatEntry.Group
}

private val LEFT_STATUSES = setOf("left", "removed")

internal fun JsonObject.text(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

private fun JsonObject.int(key: String): Int =
    (this[key] as? JsonPrimitive)?.let { it.intOrNull ?: it.contentOrNull?.toIntOrNull() } ?: 0

private fun JsonElement?.primitiveText(): String =
    (this as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() && it != "null" }.orEmpty()

private fun JsonElement?.primitiveLong(): Long =
    (this as? JsonPrimitive)?.let { it.longOrNull ?: it.contentOrNull?.toDoubleOrNull()?.toLong() } ?: 0L
