package com.zillit.desktop.feature.productionreport.data

import com.zillit.desktop.feature.productionreport.domain.ApprovalRequest
import com.zillit.desktop.feature.productionreport.domain.ReportComment
import com.zillit.desktop.feature.productionreport.domain.ReportDetail
import com.zillit.desktop.feature.productionreport.domain.ReportReminder
import com.zillit.desktop.feature.productionreport.domain.ReportRevision
import com.zillit.desktop.feature.productionreport.domain.ReportStatus
import com.zillit.desktop.feature.productionreport.domain.ReportSummary
import com.zillit.desktop.feature.productionreport.domain.SavedTemplate
import com.zillit.desktop.feature.productionreport.domain.SheetMetadata
import com.zillit.desktop.feature.productionreport.domain.StockTemplate
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Reading the service's documents. Every reader accepts the stored
 * snake_case and the camelCased spelling, and every timestamp may be epoch
 * milliseconds, epoch seconds or ISO-8601.
 */
internal object ReportWire {

    fun metadata(element: JsonElement?): SheetMetadata {
        val obj = element as? JsonObject ?: return SheetMetadata()
        return SheetMetadata(
            currentShootDay = obj.long("current_shoot_day", "currentShootDay")?.toInt() ?: 0,
            totalDays = obj.text("total_days", "totalDays"),
            dayTypes = SheetMetadata.mergeDayTypes(obj.strings("day_types", "dayTypes")),
            finalApproverIds = obj.strings("final_approver_ids", "finalApproverIds"),
            internalReceiverIds = obj.strings("internal_distribution_receivers", "internalDistributionReceivers"),
        )
    }

    fun summaries(element: JsonElement?): List<ReportSummary> {
        val list = when (element) {
            is JsonArray -> element
            is JsonObject -> element.firstOf("production_reports", "productionReports") as? JsonArray
            else -> null
        }
        return list.elements().mapNotNull { summary(it as? JsonObject) }
    }

    fun reportOf(element: JsonElement?): JsonObject? =
        (element as? JsonObject)?.let { obj ->
            obj.firstOf("production_report", "productionReport") as? JsonObject ?: obj
        }

    fun summary(obj: JsonObject?): ReportSummary? {
        if (obj == null) return null
        val id = obj.text("_id", "id")
        if (id.isBlank()) return null
        val rawStatus = obj.text("status")
        val revision = obj.firstOf("currentRevision", "current_revision") as? JsonObject
        val payload = (revision?.firstOf("payload") ?: obj.firstOf("payload")) as? JsonObject
        val shared = (payload?.firstOf("shared") ?: obj.firstOf("shared")) as? JsonObject
        return ReportSummary(
            id = id,
            serialNo = obj.text("serial_no", "serialNo"),
            name = obj.text("name"),
            status = ReportStatus.fromWire(rawStatus),
            rawStatus = rawStatus,
            createdBy = obj.text("created_by", "createdBy"),
            createdById = obj.text("created_by_id", "createdById"),
            createdOn = obj.millis("created_on", "createdOn", "created_at", "createdAt"),
            updatedOn = obj.millis("updated_on", "updatedOn", "updated_at", "updatedAt"),
            publishedOn = obj.millis("published_on", "publishedOn", "published_at", "publishedAt"),
            reportType = shared?.text("reportType", "report_type").orEmpty().trim().lowercase(),
            shared = shared?.let { PayloadWire.parse(JsonObject(mapOf("shared" to it))).shared },
            approvals = (obj.firstOf("approval_requests", "approvalRequests") as? JsonArray)
                .elements().mapNotNull { approval(it as? JsonObject) },
            reminders = (obj.firstOf("reminders") as? JsonArray).elements().mapNotNull { reminder(it as? JsonObject) },
        )
    }

    fun detail(element: JsonElement?): ReportDetail? {
        val obj = reportOf(element) ?: return null
        val summary = summary(obj) ?: return null
        val revision = obj.firstOf("currentRevision", "current_revision") as? JsonObject
        val payload = revision?.firstOf("payload") ?: obj.firstOf("payload")
        return ReportDetail(
            summary = summary,
            payload = PayloadWire.parse(payload),
            revisions = (obj.firstOf("revisions") as? JsonArray).elements().mapNotNull { revision(it as? JsonObject) },
            hasPayload = payload is JsonObject,
        )
    }

    private fun approval(obj: JsonObject?): ApprovalRequest? {
        if (obj == null) return null
        val id = obj.text("_id", "id")
        if (id.isBlank()) return null
        return ApprovalRequest(
            id = id,
            assigneeId = obj.text("assignee_id", "assigneeId"),
            assigneeName = obj.text("assignee_name", "assigneeName"),
            role = obj.text("role"),
            stage = obj.text("stage").ifBlank { "FINAL" },
            status = obj.text("status"),
            reason = obj.text("reason"),
            round = obj.long("round")?.toInt()?.takeIf { it > 0 } ?: 1,
            actedOn = obj.millis("acted_at", "actedAt", "acted_on", "actedOn"),
            createdOn = obj.millis("created_on", "createdOn", "created_at", "createdAt"),
            revisionId = obj.text("revision_id", "revisionId"),
            revisionVersion = obj.long("revision_version", "revisionVersion")?.toInt(),
        )
    }

    private fun reminder(obj: JsonObject?): ReportReminder? {
        if (obj == null) return null
        return ReportReminder(
            id = obj.text("_id", "id"),
            approvalRequestId = obj.text("approval_request_id", "approvalRequestId"),
            assigneeId = obj.text("assignee_id", "assigneeId"),
            assigneeName = obj.text("assignee_name", "assigneeName"),
            sentBy = obj.text("sent_by", "sentBy"),
            sentById = obj.text("sent_by_id", "sentById"),
            sentByRole = obj.text("sent_by_role", "sentByRole"),
            message = obj.text("message"),
            createdOn = obj.millis("created_on", "createdOn", "created_at", "createdAt"),
        )
    }

    private fun revision(obj: JsonObject?): ReportRevision? {
        if (obj == null) return null
        return ReportRevision(
            id = obj.text("_id", "id"),
            version = obj.long("version")?.toInt() ?: 0,
            createdBy = obj.text("created_by", "createdBy"),
            createdOn = obj.millis("created_on", "createdOn", "created_at", "createdAt"),
            notes = obj.text("notes"),
        )
    }

    fun comments(element: JsonElement?): List<ReportComment> {
        val list = when (element) {
            is JsonArray -> element
            is JsonObject -> element.firstOf("comments") as? JsonArray
            else -> null
        }
        return list.elements().mapNotNull { comment(it as? JsonObject) }
    }

    fun commentOf(element: JsonElement?): ReportComment? =
        comment((element as? JsonObject)?.let { it.firstOf("comment") as? JsonObject ?: it })

    private fun comment(obj: JsonObject?): ReportComment? {
        if (obj == null) return null
        val id = obj.text("_id", "id")
        if (id.isBlank()) return null
        return ReportComment(
            id = id,
            authorId = obj.text("author_id", "authorId"),
            authorName = obj.text("author_name", "authorName"),
            authorRole = obj.text("author_role", "authorRole"),
            text = obj.text("text"),
            createdOn = obj.millis("created_on", "createdOn", "created_at", "createdAt"),
            updatedOn = obj.millis("updated_on", "updatedOn", "updated_at", "updatedAt"),
        )
    }

    /** `{templates: [...]}` rows, payload-less. */
    fun savedTemplates(element: JsonElement?): List<SavedTemplate> {
        val list = when (element) {
            is JsonArray -> element
            is JsonObject -> element.firstOf("templates") as? JsonArray
            else -> null
        }
        return list.elements().mapNotNull { savedTemplate(it as? JsonObject) }
    }

    /** `{template: {id, name, payload}}`, or the template itself. */
    fun savedTemplateOf(element: JsonElement?): SavedTemplate? =
        savedTemplate((element as? JsonObject)?.let { it.firstOf("template") as? JsonObject ?: it })

    private fun savedTemplate(obj: JsonObject?): SavedTemplate? {
        if (obj == null) return null
        val id = obj.text("_id", "id")
        if (id.isBlank()) return null
        val payload = obj.firstOf("payload") as? JsonObject
        return SavedTemplate(
            id = id,
            name = obj.text("name"),
            createdBy = obj.text("created_by", "createdBy"),
            createdById = obj.text("created_by_id", "createdById"),
            createdOn = obj.millis("created_on", "createdOn", "created_at", "createdAt"),
            updatedOn = obj.millis("updated_on", "updatedOn", "updated_at", "updatedAt"),
            payload = payload?.let { PayloadWire.parse(it) },
        )
    }

    /**
     * `GET /default-template`: `{templates: [...]}` (current) or the legacy
     * `{template: {...}}`, entries that are not template payloads dropped, then
     * `prepareDefaultTemplates`: `standard` becomes the "Create your own
     * template" seed, the others take the backend name or their position.
     */
    fun stockTemplates(element: JsonElement?): List<StockTemplate> {
        val root = element as? JsonObject
        val entries = when {
            element is JsonArray -> element.elements()
            root?.firstOf("templates") is JsonArray -> (root.firstOf("templates") as JsonArray).elements()
            root?.firstOf("template") is JsonObject -> listOf(root.firstOf("template") as JsonObject)
            else -> emptyList()
        }.mapNotNull { it as? JsonObject }.filter { it.isTemplatePayload() }
        var pickable = 0
        return entries.map { entry ->
            val identifier = entry.text("id", "identifier", "key")
            if (identifier == CREATE_YOUR_OWN_ID) {
                StockTemplate(identifier, "Create your own template", PayloadWire.parse(entry), isCreateYourOwn = true)
            } else {
                pickable += 1
                StockTemplate(
                    identifier,
                    entry.text("name", "displayName").ifBlank { "Template $pickable" },
                    PayloadWire.parse(entry),
                )
            }
        }
    }

    private const val CREATE_YOUR_OWN_ID = "standard"

    private fun JsonObject.isTemplatePayload(): Boolean =
        firstOf("page_rows", "pageRows") is JsonArray || firstOf("shared") is JsonObject

    /**
     * A timestamp in any of the three spellings the service has used: epoch
     * milliseconds, epoch seconds, or ISO-8601.
     */
    fun JsonObject.millis(vararg names: String): Long? {
        val primitive = firstOf(*names) as? JsonPrimitive ?: return null
        val number = primitive.longOrNull ?: primitive.doubleOrNull?.toLong()
        if (number != null) {
            return if (number in 1..EPOCH_SECONDS_MAX) number * SECOND_MILLIS else number.takeIf { it > 0 }
        }
        return runCatching { Instant.parse(primitive.content).toEpochMilliseconds() }.getOrNull()
    }

    /** Below this a number is epoch seconds (year 5138) rather than milliseconds. */
    private const val EPOCH_SECONDS_MAX = 99_999_999_999L
    private const val SECOND_MILLIS = 1000L
}
