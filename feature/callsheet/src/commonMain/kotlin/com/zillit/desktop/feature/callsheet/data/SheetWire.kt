package com.zillit.desktop.feature.callsheet.data

import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.callsheet.domain.AccessPage
import com.zillit.desktop.feature.callsheet.domain.AccessPerson
import com.zillit.desktop.feature.callsheet.domain.ApprovalRequest
import com.zillit.desktop.feature.callsheet.domain.CallSheetDetail
import com.zillit.desktop.feature.callsheet.domain.CallSheetStatus
import com.zillit.desktop.feature.callsheet.domain.CallSheetSummary
import com.zillit.desktop.feature.callsheet.domain.SavedTemplate
import com.zillit.desktop.feature.callsheet.domain.SheetComment
import com.zillit.desktop.feature.callsheet.domain.SheetMetadata
import com.zillit.desktop.feature.callsheet.domain.SheetReminder
import com.zillit.desktop.feature.callsheet.domain.SheetRevision
import com.zillit.desktop.feature.callsheet.domain.StockTemplate
import com.zillit.desktop.feature.callsheet.domain.ToolAccessGrant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Reading the service's documents. Every reader accepts the stored
 * snake_case and the camelCased spelling, and every timestamp may be epoch
 * milliseconds, epoch seconds or ISO-8601.
 */
@Suppress("TooManyFunctions") // One reader per record the service sends.
internal object SheetWire {

    fun metadata(element: JsonElement?): SheetMetadata {
        val obj = element as? JsonObject ?: return SheetMetadata()
        return SheetMetadata(
            currentShootDay = obj.long("current_shoot_day", "currentShootDay")?.toInt() ?: 0,
            totalDays = obj.text("total_days", "totalDays").let { if (it == "0") "" else it },
            dayTypes = SheetMetadata.mergeDayTypes(obj.strings("day_types", "dayTypes")),
            finalApproverIds = obj.strings("final_approver_ids", "finalApproverIds"),
            internalReceiverIds = obj.strings("internal_distribution_receivers", "internalDistributionReceivers"),
        )
    }

    fun summaries(element: JsonElement?): List<CallSheetSummary> {
        val list = when (element) {
            is JsonArray -> element
            is JsonObject -> element.firstOf("call_sheets", "callSheets") as? JsonArray
            else -> null
        }
        return list.elements().mapNotNull { summary(it as? JsonObject) }
    }

    fun sheetOf(element: JsonElement?): JsonObject? =
        (element as? JsonObject)?.let { obj -> obj.firstOf("call_sheet", "callSheet") as? JsonObject ?: obj }

    fun summary(obj: JsonObject?): CallSheetSummary? {
        if (obj == null) return null
        val id = obj.text("_id", "id")
        if (id.isBlank()) return null
        val rawStatus = obj.text("status")
        val revision = obj.firstOf("currentRevision", "current_revision") as? JsonObject
        val payload = (revision?.firstOf("payload") ?: obj.firstOf("payload")) as? JsonObject
        val shared = (payload?.firstOf("shared") ?: obj.firstOf("shared")) as? JsonObject
        val requests = obj.firstOf("approval_requests", "approvalRequests") as? JsonArray
        return CallSheetSummary(
            id = id,
            serialNo = obj.text("serial_no", "serialNo"),
            name = obj.text("name"),
            status = CallSheetStatus.fromWire(rawStatus),
            rawStatus = rawStatus,
            createdBy = obj.text("created_by", "createdBy"),
            createdById = obj.text("created_by_id", "createdById"),
            createdOn = obj.millis("created_on", "createdOn", "created_at", "createdAt"),
            updatedOn = obj.millis("updated_on", "updatedOn", "updated_at", "updatedAt"),
            publishedOn = obj.millis("published_on", "publishedOn", "published_at", "publishedAt"),
            shared = shared?.let { PayloadWire.parseShared(it) },
            hasCells = PayloadWire.hasCells(payload),
            approvals = requests.elements().mapNotNull { approval(it as? JsonObject) },
            approvalsIncluded = requests != null,
            reminders = (obj.firstOf("reminders") as? JsonArray).elements().mapNotNull { reminder(it as? JsonObject) },
        )
    }

    fun detail(element: JsonElement?): CallSheetDetail? {
        val obj = sheetOf(element) ?: return null
        val summary = summary(obj) ?: return null
        val revision = obj.firstOf("currentRevision", "current_revision") as? JsonObject
        val payload = revision?.firstOf("payload") ?: obj.firstOf("payload")
        return CallSheetDetail(
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
            status = obj.text("status").ifBlank { "PENDING" },
            reason = obj.text("reason"),
            round = obj.long("round")?.toInt()?.takeIf { it > 0 } ?: 1,
            actedOn = obj.millis("acted_on", "actedOn", "acted_at", "actedAt"),
            createdOn = obj.millis("created_on", "createdOn", "created_at", "createdAt"),
            revisionId = obj.text("revision_id", "revisionId"),
            revisionVersion = obj.long("revision_version", "revisionVersion")?.toInt(),
        )
    }

    private fun reminder(obj: JsonObject?): SheetReminder? {
        if (obj == null) return null
        return SheetReminder(
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

    private fun revision(obj: JsonObject?): SheetRevision? {
        if (obj == null) return null
        return SheetRevision(
            id = obj.text("_id", "id"),
            version = obj.long("version")?.toInt() ?: 0,
            createdBy = obj.text("created_by", "createdBy"),
            createdById = obj.text("created_by_id", "createdById"),
            createdOn = obj.millis("created_on", "createdOn", "created_at", "createdAt"),
            notes = obj.text("notes"),
        )
    }

    fun comments(element: JsonElement?): List<SheetComment> {
        val list = when (element) {
            is JsonArray -> element
            is JsonObject -> element.firstOf("comments") as? JsonArray
            else -> null
        }
        return list.elements().mapNotNull { comment(it as? JsonObject) }
    }

    fun commentOf(element: JsonElement?): SheetComment? =
        comment((element as? JsonObject)?.let { it.firstOf("comment") as? JsonObject ?: it })

    fun comment(obj: JsonObject?): SheetComment? {
        if (obj == null) return null
        val id = obj.text("_id", "id")
        if (id.isBlank()) return null
        return SheetComment(
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
     * `GET /default-template` → `extractTemplateList` + `prepareDefaultTemplates`:
     * `{templates: [...]}` (current) or the legacy `{template: {...}}`, entries
     * that are not template payloads dropped. When the three known ids are
     * present, `standard` becomes the "Create your own template" seed and
     * leads, `comfort_and_joy` / `lord_of_misrule` become Template 1 / 2, and
     * any other layout follows, named by its position; otherwise every layout
     * is named by its position.
     */
    fun stockTemplates(element: JsonElement?): List<StockTemplate> {
        val root = element as? JsonObject
        val entries = when {
            element is JsonArray -> element.elements()
            root?.firstOf("templates") is JsonArray -> (root.firstOf("templates") as JsonArray).elements()
            root?.firstOf("template") is JsonObject -> listOf(root.firstOf("template") as JsonObject)
            else -> emptyList()
        }.mapNotNull { it as? JsonObject }.filter { it.isTemplatePayload() }
        val idOf = { entry: JsonObject -> entry.text("id", "identifier", "key") }
        val standard = entries.firstOrNull { idOf(it) == STANDARD }
        val one = entries.firstOrNull { idOf(it) == COMFORT_AND_JOY }
        val two = entries.firstOrNull { idOf(it) == LORD_OF_MISRULE }
        if (standard == null && one == null && two == null) {
            return entries.mapIndexed { index, entry ->
                StockTemplate(idOf(entry), str(S.tpl_template_n, index + 1), PayloadWire.parse(entry))
            }
        }
        val prepared = mutableListOf<StockTemplate>()
        standard?.let { prepared += StockTemplate(
            STANDARD,
            str(S.tpl_create_your_own),
            PayloadWire.parse(it),
            isCreateYourOwn = true,
        ) }
        one?.let { prepared += StockTemplate(COMFORT_AND_JOY, str(S.tpl_template_n, 1), PayloadWire.parse(it)) }
        two?.let { prepared += StockTemplate(LORD_OF_MISRULE, str(S.tpl_template_n, 2), PayloadWire.parse(it)) }
        entries.filter { idOf(it) !in KNOWN_IDS }.forEach { entry ->
            prepared += StockTemplate(idOf(entry), str(S.tpl_template_n, prepared.size + 1), PayloadWire.parse(entry))
        }
        return prepared
    }

    private const val STANDARD = "standard"
    private const val COMFORT_AND_JOY = "comfort_and_joy"
    private const val LORD_OF_MISRULE = "lord_of_misrule"
    private val KNOWN_IDS = setOf(STANDARD, COMFORT_AND_JOY, LORD_OF_MISRULE)

    private fun JsonObject.isTemplatePayload(): Boolean =
        firstOf("page_rows", "pageRows") is JsonArray || firstOf("shared") is JsonObject

    /**
     * The grid's crew axis: rows are heterogeneous arrays — the person first,
     * then one entry per tool unit. Only `callsheet_tool` cells matter here;
     * a person with none is not listed, and neither is the viewer.
     */
    fun accessPage(element: JsonElement?, currentUserId: String?): AccessPage {
        val obj = element as? JsonObject ?: return AccessPage(emptyList(), 0)
        val me = currentUserId?.trim().orEmpty()
        val people = (obj.firstOf("rows") as? JsonArray).elements().mapNotNull { row ->
            val entries = (row as? JsonArray).elements().mapNotNull { it as? JsonObject }
            val subject = entries.firstOrNull { it.text("user_id", "userId").isNotBlank() } ?: return@mapNotNull null
            val userId = subject.text("user_id", "userId")
            if (me.isNotEmpty() && userId == me) return@mapNotNull null
            val cell = entries.firstOrNull { it.text("identifier") == CALLSHEET_TOOL } ?: return@mapNotNull null
            AccessPerson(
                userId = userId,
                fullName = subject.text("full_name", "fullName"),
                designation = subject.text("designation_name", "designationName"),
                department = subject.text("department_name", "departmentName"),
                isAdmin = subject.bool("admin_access", "adminAccess") == true,
                unitName = cell.text("unit_name", "unitName"),
                unitId = cell.text("unit_id", "unitId"),
                canView = cell.bool("view_access", "viewAccess") == true,
                canPost = cell.bool("posting_access", "postingAccess") == true,
                canDownload = cell.bool("download_access", "downloadAccess") == true,
            )
        }.distinctBy { it.userId }
        val total = obj.long("total_users", "totalUsers")?.toInt()?.takeIf { it > 0 } ?: people.size
        return AccessPage(people, total)
    }

    private const val CALLSHEET_TOOL = "callsheet_tool"

    fun toolAccess(element: JsonElement?, enabled: Boolean, message: String): ToolAccessGrant {
        val obj = element as? JsonObject
        return ToolAccessGrant(
            canView = obj?.bool("viewing_access", "viewingAccess", "view_access") ?: enabled,
            canPost = obj?.bool("posting_access", "postingAccess") ?: enabled,
            canDownload = obj?.bool("download_access", "downloadAccess") ?: enabled,
            message = message,
        )
    }
}
