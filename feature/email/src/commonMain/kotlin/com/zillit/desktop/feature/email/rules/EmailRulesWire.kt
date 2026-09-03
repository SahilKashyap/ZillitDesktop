package com.zillit.desktop.feature.email.rules

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * `/v2/email-rules` as Android's `EmailRuleDto` and the web's
 * `emailRulesModel` spell it. Read tolerantly — ids arrive as `_id` or
 * `id`, numbers as numbers or strings, lists bare or under `data`.
 */
@Serializable
internal data class EmailRuleDto(
    @SerialName("_id") val id: String? = null,
    @SerialName("id") val altId: String? = null,
    val name: String? = null,
    val enabled: Boolean? = null,
    val priority: JsonPrimitive? = null,
    @SerialName("stop_on_match") val stopOnMatch: Boolean? = null,
    @SerialName("match_type") val matchType: String? = null,
    @SerialName("rule_scope") val ruleScope: String? = null,
    val conditions: List<RuleConditionDto> = emptyList(),
    val actions: List<RuleActionDto> = emptyList(),
    val created: JsonPrimitive? = null,
    val updated: JsonPrimitive? = null,
) {
    fun toDomain(): EmailRule? {
        val key = (id ?: altId)?.takeIf { it.isNotBlank() } ?: return null
        return EmailRule(
            id = key,
            name = name.orEmpty(),
            enabled = enabled ?: true,
            priority = priority?.let { it.intOrNull ?: it.contentOrNull?.toIntOrNull() } ?: 0,
            stopOnMatch = stopOnMatch == true,
            matchType = RuleMatchType.fromWire(matchType),
            conditions = conditions.mapNotNull { it.toDomain() },
            actions = actions.mapNotNull { it.toDomain() },
            createdMillis = created?.let { it.longOrNull ?: it.contentOrNull?.toLongOrNull() } ?: 0,
            updatedMillis = updated?.let { it.longOrNull ?: it.contentOrNull?.toLongOrNull() } ?: 0,
        )
    }
}

@Serializable
internal data class RuleConditionDto(
    val field: String? = null,
    val operator: String? = null,
    val value: String? = null,
) {
    fun toDomain(): RuleCondition? {
        val f = ConditionField.fromWire(field) ?: return null
        val op = ConditionOperator.fromWire(operator)?.takeIf { it in f.operators } ?: f.defaultOperator
        return RuleCondition(f, op, value.orEmpty())
    }
}

@Serializable
internal data class RuleActionDto(
    val type: String? = null,
    @SerialName("drive_folder_id") val driveFolderId: String? = null,
    @SerialName("drive_folder_name") val driveFolderName: String? = null,
    val extensions: List<String>? = null,
    @SerialName("max_size_bytes") val maxSizeBytes: JsonPrimitive? = null,
    @SerialName("folder_name") val folderName: String? = null,
    @SerialName("forward_to_email") val forwardToEmail: String? = null,
) {
    fun toDomain(): RuleAction? = when (RuleActionType.fromWire(type) ?: return null) {
        RuleActionType.SaveAttachmentsToDrive -> RuleAction.SaveAttachmentsToDrive(
            driveFolderId = driveFolderId.orEmpty(),
            driveFolderName = driveFolderName.orEmpty(),
            extensions = extensions.orEmpty()
                .map { it.trim().removePrefix(".").lowercase() }
                .filter { it.isNotEmpty() },
            maxSizeBytes = maxSizeBytes?.let { it.longOrNull ?: it.contentOrNull?.toLongOrNull() } ?: 0,
        )
        RuleActionType.MoveToFolder -> RuleAction.MoveToFolder(folderName.orEmpty())
        RuleActionType.ForwardTo -> RuleAction.ForwardTo(forwardToEmail.orEmpty())
        RuleActionType.MarkRead -> RuleAction.MarkRead
    }
}

/** The create/update body — the web's `toConditionPayload` / `toActionPayload`, key for key. */
internal fun EmailRule.toWire(): JsonObject = buildJsonObject {
    put("name", name.trim())
    put("enabled", enabled)
    put("stop_on_match", stopOnMatch)
    put("match_type", matchType.wire)
    put(
        "conditions",
        buildJsonArray {
            conditions.forEach { c ->
                add(
                    buildJsonObject {
                        put("field", c.field.wire)
                        put("operator", c.operator.wire)
                        if (c.field.needsValue) put("value", c.value.trim())
                    },
                )
            }
        },
    )
    put("actions", buildJsonArray { actions.forEach { add(it.toWire()) } })
}

internal fun RuleAction.toWire(): JsonObject = buildJsonObject {
    put("type", type.wire)
    when (val action = this@toWire) {
        is RuleAction.SaveAttachmentsToDrive -> {
            put("drive_folder_id", action.driveFolderId)
            if (action.extensions.isNotEmpty()) {
                put("extensions", buildJsonArray { action.extensions.forEach { add(JsonPrimitive(it)) } })
            }
            if (action.maxSizeBytes > 0) put("max_size_bytes", action.maxSizeBytes)
        }
        is RuleAction.MoveToFolder -> put("folder_name", action.folderName.trim())
        is RuleAction.ForwardTo -> put("forward_to_email", action.email.trim())
        RuleAction.MarkRead -> Unit
    }
}

@Serializable
internal data class RuleExecutionDto(
    @SerialName("_id") val id: String? = null,
    @SerialName("id") val altId: String? = null,
    @SerialName("rule_id") val ruleId: String? = null,
    @SerialName("mailbox_email") val mailboxEmail: String? = null,
    @SerialName("message_id") val messageId: String? = null,
    val status: String? = null,
    val attempts: JsonPrimitive? = null,
    val results: List<ExecutionResultDto> = emptyList(),
    val error: String? = null,
    val created: JsonPrimitive? = null,
) {
    fun toDomain(): RuleExecution? {
        val key = (id ?: altId)?.takeIf { it.isNotBlank() } ?: return null
        return RuleExecution(
            id = key,
            ruleId = ruleId.orEmpty(),
            mailboxEmail = mailboxEmail.orEmpty(),
            messageId = messageId.orEmpty(),
            status = ExecutionStatus.fromWire(status),
            attempts = attempts?.let { it.intOrNull ?: it.contentOrNull?.toIntOrNull() } ?: 0,
            results = results.map {
                ExecutionActionResult(RuleActionType.fromWire(it.type), ExecutionStatus.fromWire(it.status), it.detail)
            },
            error = error?.takeIf { it.isNotBlank() },
            createdMillis = created?.let { it.longOrNull ?: it.contentOrNull?.toLongOrNull() } ?: 0,
        )
    }
}

@Serializable
internal data class ExecutionResultDto(val type: String? = null, val status: String? = null, val detail: String? = null)

/** The rows of a list answer, wherever this server version filed them: bare, `rules`/`executions`, or under `data`. */
internal fun JsonElement.listRows(vararg keys: String): List<JsonElement> = when (this) {
    is JsonArray -> this
    is JsonObject -> {
        val inner = keys.firstNotNullOfOrNull { this[it] }
        when (inner) {
            is JsonArray -> inner
            is JsonObject -> inner.listRows(*keys)
            else -> (this["data"] as? JsonElement)?.listRows(*keys).orEmpty()
        }
    }
    else -> emptyList()
}

/** A single-record answer: the object itself, or under `rule` / `data`. */
internal fun JsonElement.record(vararg keys: String): JsonObject? = when (this) {
    is JsonObject ->
        keys.firstNotNullOfOrNull { this[it] as? JsonObject } ?: (this["data"] as? JsonElement)?.record(*keys) ?: this
    else -> null
}

/** `total` of an executions page, wherever it sits; the row count when absent. */
internal fun JsonElement.totalOr(fallback: Int): Int {
    val obj = this as? JsonObject ?: return fallback
    val direct = (obj["total"] as? JsonPrimitive)?.let { it.intOrNull ?: it.contentOrNull?.toIntOrNull() }
    return direct ?: (obj["data"] as? JsonElement)?.totalOr(fallback) ?: fallback
}
