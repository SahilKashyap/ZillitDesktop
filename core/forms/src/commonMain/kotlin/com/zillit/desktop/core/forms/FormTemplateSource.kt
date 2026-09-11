package com.zillit.desktop.core.forms

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.common.map
import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.HttpVerb
import com.zillit.desktop.core.network.RequestModule
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * A module's form template: the sections and fields its form is built from.
 *
 * Its own class rather than eight more calls on the repository, for the same
 * A core module rather than the account hub's own, because two sides read
 * it: the hub edits the template, and the module whose form it describes has
 * to render from it.
 */
class FormTemplateSource(
    private val apiClient: ApiClient,
    config: AppConfig,
) {

    private val base = "${config.baseUrl(ZillitService.AccountHub)}/api/v2/account-hub/form-templates"

    /** The saved template, or the system defaults the server creates on first ask. */
    suspend fun template(module: FormModule): ZillitResult<FormTemplate> = apiClient.request(
        verb = HttpVerb.Get,
        url = base,
        serializer = TemplateDto.serializer(),
        module = RequestModule.ProjectUser,
        queryParameters = mapOf("module" to module.wire),
    ).map { parseFormTemplate(it.template) }

    /**
     * Replaces the module's template.
     *
     * The whole document goes, which is why every field carries the keys this
     * client does not model — see [FormField.extras].
     */
    suspend fun save(module: FormModule, template: FormTemplate): ZillitResult<Unit> =
        apiClient.envelope(
            verb = HttpVerb.Post,
            url = base,
            module = RequestModule.ProjectUser,
            queryParameters = mapOf("module" to module.wire),
            body = formTemplateBody(template),
        ).map { }

    /** Throws away the production's changes and answers with the defaults. */
    suspend fun reset(module: FormModule): ZillitResult<FormTemplate> = apiClient.request(
        verb = HttpVerb.Post,
        url = "$base/reset",
        serializer = TemplateDto.serializer(),
        module = RequestModule.ProjectUser,
        queryParameters = mapOf("module" to module.wire),
    ).map { parseFormTemplate(it.template) }
}

/**
 * The template, which is sometimes a document and sometimes the text of one.
 *
 * The service stores it as text and does not always parse it on the way out,
 * so both shapes arrive from the same route.
 */
@Serializable
internal data class TemplateDto(@SerialName("template") val template: JsonElement? = null)

private val lenient = Json { ignoreUnknownKeys = true; isLenient = true }

private val FIELD_KEYS = setOf(
    "order", "name", "type", "label", "required", "system_default", "hide", "selection_type",
)
private val SECTION_KEYS = setOf("key", "label", "order", "system_default", "fields")

/**
 * Reads a template, keeping every key it does not understand.
 *
 * Saving replaces the whole document, so anything dropped on the way in is
 * deleted on the way out — for every field on the form at once, and without an
 * error to notice it by.
 */
fun parseFormTemplate(element: JsonElement?): FormTemplate {
    val array = element.asArray() ?: return FormTemplate()
    val sections = array.filterIsInstance<JsonObject>().mapIndexed { index, row ->
        FormSection(
            key = row.text("key"),
            label = row.text("label"),
            order = row.int("order") ?: (index + 1),
            systemDefault = row.bool("system_default"),
            fields = (row["fields"] as? JsonArray).orEmpty().toFields(),
            extras = row.filterKeys { it !in SECTION_KEYS },
        )
    }
    return FormTemplate(sections)
}

private fun List<JsonElement>.toFields(): List<FormField> =
    filterIsInstance<JsonObject>().mapIndexed { index, row ->
        FormField(
            label = row.text("label"),
            name = row.text("name"),
            type = row.text("type").ifBlank { "text" },
            order = row.int("order") ?: (index + 1),
            required = row.bool("required"),
            systemDefault = row.bool("system_default"),
            hidden = row.bool("hide"),
            selectionType = row.text("selection_type").takeIf { it.isNotBlank() },
            extras = row.filterKeys { it !in FIELD_KEYS },
        )
    }

/** The save body: `{ template: [ …sections ] }`, in display order. */
fun formTemplateBody(template: FormTemplate): JsonObject = buildJsonObject {
    put(
        "template",
        buildJsonArray { template.ordered.forEach { add(it.toJson()) } },
    )
}

private fun FormSection.toJson(): JsonObject = buildJsonObject {
    extras.forEach { (key, value) -> put(key, value) }
    put("key", JsonPrimitive(key))
    put("label", JsonPrimitive(label))
    put("order", JsonPrimitive(order))
    put("system_default", JsonPrimitive(systemDefault))
    put("fields", buildJsonArray { ordered.forEach { add(it.toJson()) } })
}

private fun FormField.toJson(): JsonObject = buildJsonObject {
    extras.forEach { (key, value) -> put(key, value) }
    put("order", JsonPrimitive(order))
    put("name", JsonPrimitive(name))
    put("type", JsonPrimitive(type))
    put("label", JsonPrimitive(label))
    put("required", JsonPrimitive(required))
    put("system_default", JsonPrimitive(systemDefault))
    put("hide", JsonPrimitive(hidden))
    put("selection_type", selectionType?.let(::JsonPrimitive) ?: JsonNull)
}

/** An array, the text of one, or neither. */
private fun JsonElement?.asArray(): JsonArray? = when {
    this == null || this is JsonNull -> null
    this is JsonArray -> this
    this is JsonPrimitive && isString ->
        runCatching { lenient.parseToJsonElement(content) as? JsonArray }.getOrNull()
    else -> null
}

private fun JsonObject.text(key: String): String =
    (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content.orEmpty()

private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

private fun JsonObject.bool(key: String): Boolean {
    val value = this[key] as? JsonPrimitive ?: return false
    return value.booleanOrNull ?: (value.content == "1" || value.content.equals("true", true))
}
