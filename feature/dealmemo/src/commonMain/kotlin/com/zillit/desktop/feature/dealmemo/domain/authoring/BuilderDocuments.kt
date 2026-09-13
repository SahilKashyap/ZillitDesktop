package com.zillit.desktop.feature.dealmemo.domain.authoring

import com.zillit.desktop.feature.dealmemo.domain.rates.Js
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The deal's documents (`Step8Documents.jsx`, `customDocValidation.js`): the
 * Production Setup agreement documents it can attach, and the custom PDFs
 * picked from disk.
 */
object BuilderDocuments {

    const val MAX_CUSTOM_BYTES = 20 * 1024 * 1024
    const val SOURCE_PS = "ps"
    const val SOURCE_CUSTOM = "custom"
    private val FLAT_KEYS =
        listOf("name", "media", "bucket", "region", "content_type", "content_subtype", "file_size", "title", "caption")

    /** `rid = row._id || row.id`. */
    fun agreementId(row: JsonObject): String =
        (row["_id"]?.takeIf(Js::truthy) ?: row["id"]?.takeIf(Js::truthy))?.let(Js::text).orEmpty()

    /** `flattenPSDoc`: each attachment key from the row, else from its nested `document`. */
    fun flatten(row: JsonObject): JsonObject {
        val nested = row["document"] as? JsonObject
        return buildJsonObject {
            FLAT_KEYS.forEach { key ->
                val value = row[key]?.takeIf(Js::truthy) ?: nested?.get(key)?.takeIf(Js::truthy)
                if (value != null) put(key, value)
            }
        }
    }

    /** The document's shown file name. */
    fun filename(row: JsonObject): String = text(flatten(row)["name"])

    /** The deal row a Production Setup document is attached as; null when it has nowhere to be read from. */
    fun attachedRow(row: JsonObject, signPolicy: JsonObject?): JsonObject? {
        val flat = flatten(row)
        if (text(flat["media"]).isEmpty() || text(flat["bucket"]).isEmpty() || text(flat["region"]).isEmpty()) {
            return null
        }
        val id = agreementId(row)
        return buildJsonObject {
            put("id", id)
            put("source", SOURCE_PS)
            put("ps_agreement_id", id)
            put(
                "title",
                row["title"]?.takeIf(Js::truthy) ?: flat["title"]?.takeIf(Js::truthy)
                    ?: flat["name"]?.takeIf(Js::truthy)
                    ?: JsonPrimitive("Untitled"),
            )
            put("description", row["description"]?.takeIf(Js::truthy) ?: JsonPrimitive(""))
            put("signRequired", !isFalse(signPolicy?.get(id)))
            put("attachment", flat)
        }
    }

    /** Whether a Production Setup document is on the deal. */
    fun isAttached(documents: List<JsonObject>, rowId: String): Boolean =
        documents.any { text(it["source"]) == SOURCE_PS && text(it["ps_agreement_id"]) == rowId }

    /** The sign policy a Production Setup document shows: the attached row's, else the remembered one, else on. */
    fun signRequired(documents: List<JsonObject>, signPolicy: JsonObject?, rowId: String): Boolean {
        val attached = documents.firstOrNull { text(it["source"]) == SOURCE_PS && text(it["ps_agreement_id"]) == rowId }
        return if (attached != null) !isFalse(attached["signRequired"]) else !isFalse(signPolicy?.get(rowId))
    }

    /** `validateCustomFile`: PDFs only (by type or extension), 20 MB at most; null when the file is fine. */
    fun customFileError(name: String, mime: String, size: Int): String? {
        val typeOk = mime.lowercase() == "application/pdf" || name.substringAfterLast('.', "").lowercase() == "pdf"
        return when {
            !typeOk -> "$name: unsupported file type. Only PDF files are allowed."
            size > MAX_CUSTOM_BYTES -> "$name: exceeds the 20MB per-file limit."
            else -> null
        }
    }

    /** A picked custom document's row; the bytes wait beside the form until a save uploads them. */
    fun customRow(id: String, name: String, size: Int, mime: String): JsonObject = buildJsonObject {
        put("id", id)
        put("source", SOURCE_CUSTOM)
        put("title", if ('.' in name) name.substringBeforeLast('.') else name)
        put("description", "")
        put("signRequired", true)
        put(
            "file",
            buildJsonObject {
                put("name", name)
                put("size", size)
                put("type", mime)
            },
        )
    }

    /** The deal's documents with [id] replaced by [transform]'s answer, or dropped when it answers null. */
    fun mapRow(documents: JsonArray, id: String, transform: (JsonObject) -> JsonObject?): JsonArray = JsonArray(
        documents.mapNotNull { element ->
            val row = element as? JsonObject ?: return@mapNotNull element
            if (text(row["id"]) == id) transform(row) else row
        },
    )

    private fun text(value: JsonElement?): String = when (value) {
        null, JsonNull -> ""
        else -> Js.text(value)
    }

    private fun isFalse(value: JsonElement?): Boolean =
        value is JsonPrimitive && !value.isString && value.content == "false"
}
