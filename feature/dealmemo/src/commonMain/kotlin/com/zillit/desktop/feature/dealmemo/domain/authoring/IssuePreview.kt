package com.zillit.desktop.feature.dealmemo.domain.authoring

import com.zillit.desktop.feature.dealmemo.domain.DealDoc
import com.zillit.desktop.feature.dealmemo.domain.rates.Js
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * `Step10Preview`'s deal (`steps/Step10Preview.jsx:70-151`): the payload the
 * form would save, named for the memo card, with documents not yet uploaded
 * listed under their file names.
 */
object IssuePreview {

    /** Codes stay bare (no chart wrap), and no payroll defaults or project day types — as the web builds it. */
    fun deal(form: DealForm, context: PayloadContext, now: Long): DealDoc {
        val payload = DealPayload.build(
            form,
            context.copy(coaCodes = null, payrollDefaults = null, projectDayTypes = emptyList()),
        )
        val union = context.selectedUnion
        val territory = (payload["territory_union"] as? JsonObject).orEmpty().toMutableMap()
        fun fill(key: String, value: JsonElement?) {
            if (territory[key] == null || territory[key] is JsonNull) territory[key] = value ?: JsonNull
        }
        fill("union_name", union?.get("name"))
        fill("agreement_name", union?.get("name"))
        fill("branch_name", union?.get("short_label"))
        fill("band_label", form["band"])
        fill("territory_code", JsonPrimitive(form.text("territory")))
        val documents = (payload["additional_documents"] as? JsonObject).orEmpty()
        val docs = form.objects("documents").map { doc ->
            val attachment = doc["attachment"] as? JsonObject
            val file = doc["file"] as? JsonObject
            buildJsonObject {
                put("_id", doc["id"] ?: JsonNull)
                put(
                    "title",
                    doc["title"]?.takeIf(Js::truthy) ?: attachment?.get("name")?.takeIf(Js::truthy)
                        ?: JsonPrimitive(""),
                )
                put("description", doc["description"]?.takeIf(Js::truthy) ?: JsonPrimitive(""))
                put("system", doc["source"]?.takeUnless { it is JsonNull }?.let(Js::text) == "ps")
                put(
                    "document",
                    attachment ?: file?.let { picked ->
                        val name = picked["name"]?.takeUnless { it is JsonNull }?.let(Js::text).orEmpty()
                        buildJsonObject {
                            put("name", name)
                            put("content_subtype", name.substringAfterLast('.', "").lowercase())
                        }
                    } ?: JsonObject(emptyMap()),
                )
            }
        }
        val deal = JsonObject(
            payload + mapOf(
                "territory_union" to JsonObject(territory),
                "additional_documents" to JsonObject(documents + ("docs" to JsonArray(docs))),
                "status" to (payload["status"]?.takeUnless { it is JsonNull } ?: JsonPrimitive("draft")),
                "created_at" to JsonPrimitive(now),
                "updated_at" to JsonPrimitive(now),
            ),
        )
        return DealDoc(deal)
    }
}
