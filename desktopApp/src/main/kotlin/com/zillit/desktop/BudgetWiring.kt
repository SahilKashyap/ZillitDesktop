@file:Suppress("MatchingDeclarationName") // Wiring file: one small holder class among the host functions.

package com.zillit.desktop

import com.zillit.desktop.core.badges.LedgerRead
import com.zillit.desktop.core.badges.NotificationRecord
import com.zillit.desktop.core.common.ZillitLog
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.common.map
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.permissions.ProjectPermissions
import com.zillit.desktop.core.socket.NotificationReadDto
import com.zillit.desktop.core.socket.ZillitSocketEvents
import com.zillit.desktop.feature.budget.data.BudgetRepositoryImpl
import com.zillit.desktop.feature.budget.domain.BudgetDepartment
import com.zillit.desktop.feature.budget.domain.BudgetDocument
import com.zillit.desktop.feature.budget.domain.BudgetFile
import com.zillit.desktop.feature.budget.domain.BudgetMode
import com.zillit.desktop.feature.budget.domain.BudgetViewer
import com.zillit.desktop.feature.budget.ui.BudgetBadges
import com.zillit.desktop.feature.budget.ui.BudgetContext
import com.zillit.desktop.feature.budget.ui.BudgetHost
import com.zillit.desktop.feature.budget.ui.BudgetPerson
import com.zillit.desktop.feature.budget.ui.BudgetScreenSeams
import com.zillit.desktop.feature.budget.ui.BudgetToolProvider
import com.zillit.desktop.feature.budget.ui.BudgetUnread
import com.zillit.desktop.feature.budget.ui.BudgetViewModel
import com.zillit.desktop.feature.budget.ui.PickedBudgetFile
import com.zillit.desktop.feature.home.domain.NoticeAttachment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/**
 * The two budget tiles' view models — one per tile, as the web mounts
 * `FullBudget` and `DepartmentBudget` separately.
 */
internal class BudgetViewModels(
    val main: BudgetViewModel,
    val department: BudgetViewModel,
)

/**
 * The production's departments by id, fetched once when a production opens.
 *
 * Deliberately a small copy of the invoices tool's reference data rather than
 * a shared object: the two tools want the same map for different reasons, and
 * coupling a film tool to another film tool's wiring to save ten lines is a
 * worse trade than the duplication.
 */
private class BudgetDepartments(graph: AppGraph.Ready, scope: CoroutineScope) {

    private val byId = AtomicReference<Map<String, String>>(emptyMap())
    private var loadedFor: String? = null

    init {
        scope.launch {
            graph.projectContext?.context?.collect { context ->
                val projectId = context.project?.projectId ?: return@collect
                if (projectId == loadedFor) return@collect
                loadedFor = projectId
                byId.set(emptyMap())
                (graph.adminRepository.departments() as? ZillitResult.Success)?.data?.let { rows ->
                    byId.set(rows.associate { it.id to it.name.localised() })
                }
            }
        }
    }

    fun all(): List<BudgetDepartment> = byId.get().map { (id, name) -> BudgetDepartment(id, name) }
}

internal fun AppGraph.Ready.buildBudget(
    permissions: () -> ProjectPermissions,
    scope: CoroutineScope,
): BudgetViewModels {
    val departments = BudgetDepartments(this, scope)
    val host = budgetHost(departments)
    val badges = budgetBadges()
    fun of(mode: BudgetMode) = BudgetViewModel(
        mode = mode,
        repository = budgetRepository(),
        viewer = { BudgetViewer.from(permissions()) },
        host = host,
        badges = badges,
        events = socketEvents,
        rights = rightsRequests,
    )
    return BudgetViewModels(main = of(BudgetMode.Main), department = of(BudgetMode.Department))
}

internal fun AppGraph.Ready.budgetRepository() = BudgetRepositoryImpl(
    apiClient = apiClient,
    config = config,
    socket = socketEvents,
    projectId = { projectContext?.context?.value?.project?.projectId },
    userId = { projectContext?.context?.value?.profile?.userId },
)

internal fun AppGraph.Ready.budgetProvider(
    viewModel: BudgetViewModel,
    path: String,
    scope: CoroutineScope,
    /** The app's one speaker, for voice notes in a budget thread. */
    player: com.zillit.desktop.core.designsystem.component.AudioPlayer? = null,
) = BudgetToolProvider(
    viewModel = viewModel,
    path = path,
    onOpenFile = { document, _ -> openBudgetFile(this, scope, document) },
    seams = BudgetScreenSeams(
        nameOf = { id -> crewNameOf(this, id) },
        loadAvatar = crewFaceLoader(this),
        // The discussion that belongs to the budget on screen — the chat
        // tool's own thread under this budget's tool, department and document.
        conversation = { state -> BudgetConversationPane(this, state, player) },
    ),
)

/** The OS picker, the production's storage, the crew list, and the admins' inbox. */
private fun AppGraph.Ready.budgetHost(departments: BudgetDepartments): BudgetHost = object : BudgetHost {

    override suspend fun pickPdf(): PickedBudgetFile? =
        com.zillit.desktop.pickPdf()?.let { (name, bytes) -> PickedBudgetFile(name, bytes) }

    /** The same routed uploader (S3 or Box by production) every attachment takes. */
    override suspend fun store(name: String, bytes: ByteArray): ZillitResult<BudgetFile> =
        attachmentUploader.upload(name, PDF_TYPE, bytes).map { stored ->
            BudgetFile(
                media = stored.media,
                name = stored.fileName.ifBlank { name },
                contentType = stored.contentType,
                bucket = stored.bucket,
                region = stored.region,
                sizeBytes = if (stored.sizeBytes > 0) stored.sizeBytes else bytes.size.toLong(),
            )
        }

    override fun context(): BudgetContext {
        val context = projectContext?.context?.value
        val profile = context?.profile
        return BudgetContext(
            userId = profile?.userId.orEmpty(),
            departmentId = profile?.departmentId.orEmpty(),
            departmentName = profile?.departmentName?.localised().orEmpty(),
            isTelevision = context?.project?.subType?.contains("television", ignoreCase = true) == true,
            isBox = context?.project?.storageType?.equals("BOX", ignoreCase = true) == true,
            departments = departments.all(),
        )
    }

    // The keep-name-private honour is applied here, before the screen ever
    // sees the list — the same rule the chat tool's directory keeps.
    override fun crew(): List<BudgetPerson> =
        projectContext?.context?.value?.users.orEmpty()
            .filterNot { it.keepNamePrivate }
            .filter { it.fullName.isNotBlank() }
            .map { user ->
                BudgetPerson(
                    userId = user.userId,
                    fullName = user.fullName,
                    designation = user.designationText().orEmpty(),
                    isAdmin = user.isAdmin,
                    deviceId = user.deviceId.orEmpty(),
                    hasLeft = user.status == "left" || user.status == "removed",
                )
            }

    /**
     * The web's `distributeCncMessage` (`commonFunctionForFilmTools.js:1920`):
     * every accepted admin but oneself hears, in a private message, that a
     * document over 25 MB will not be mailed out.
     */
    override suspend fun announceOversize(fileName: String, sizeBytes: Long) {
        val context = projectContext?.context?.value ?: return
        val me = context.profile?.userId
        val admins = context.users.filter { it.isAdmin && it.userId != me && it.hasJoined() }
        val size = "%.2f MB".format(sizeBytes / BYTES_PER_MB)
        val body = "The file '$fileName' ($size) uploaded to 'Budget' exceeds the 25 MB limit for " +
            "auto-distribution. Crew members with access can view the document directly within the module."
        admins.forEach { admin ->
            val sent = chatRepository.send(
                receiverId = admin.userId,
                body = body,
                uniqueId = UUID.randomUUID().toString(),
                nowMillis = System.currentTimeMillis(),
            )
            if (sent is ZillitResult.Failure) {
                ZillitLog.w(BUDGET_TAG) { "oversize notice to ${admin.userId} not sent: ${sent.error.userMessage}" }
            }
        }
    }
}

/**
 * The unread a budget wears, off the ledger — the web's
 * `badgeUtils.js:1504-1540` over `BadgeDB.getBudgetChatBadgesFromDB`:
 * rows under `main_budget_label` / `department_budget_label`, the
 * `budget_has_created` ones per document (`level_1`), the rest per document
 * and then per sender or room (`reference_data`).
 *
 * A read names the version as the web does (`segment`, `reference_id`,
 * `module` — `readMainBudgetBadges`) and flips the same rows locally, so
 * the badge does not wait for the echo.
 */
private fun AppGraph.Ready.budgetBadges(): BudgetBadges = object : BudgetBadges {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun unread(mode: BudgetMode): Flow<BudgetUnread> = badgeStore.counts
        .map { budgetUnreadOf(badgeStore.unreadRows(TOOLS_SECTION), mode.badgeLabel) }
        .distinctUntilChanged()

    override fun markDocumentRead(mode: BudgetMode, documentId: String, departmentId: String) {
        val projectId = projectContext?.context?.value?.project?.projectId ?: return
        val label = mode.badgeLabel
        scope.launch {
            runCatching {
                socketEvents.emit(
                    ZillitSocketEvents.Badges.NotificationRead,
                    NotificationReadDto(
                        projectId = projectId,
                        segment = label,
                        module = label,
                        referenceId = documentId,
                        timestamp = System.currentTimeMillis(),
                    ),
                    NotificationReadDto.serializer(),
                )
            }.onFailure { ZillitLog.w(BUDGET_TAG) { "budget read not sent: ${it::class.simpleName}" } }
            badgeStore.markRead(LedgerRead.Levels(tool = label, level1 = documentId, action = CREATED_ACTION))
        }
    }

    /**
     * The chat sends its own `read_untill` on the wire, and the server's
     * `notification:silent` echo prunes the ledger; the chat rows are not
     * flipped here because the ledger's chat rows carry no `level_1` to
     * match a budget by, and a broader match would clear C&C's rows too.
     */
    override fun markChatRead(mode: BudgetMode, documentId: String, key: String) = Unit
}

/** The ledger's rows of one budget label, folded the way the web folds them. */
internal fun budgetUnreadOf(rows: List<NotificationRecord>, label: String): BudgetUnread {
    val ours = rows.filter { it.tool == label }
    val documents = mutableMapOf<String, Int>()
    val chats = mutableMapOf<String, MutableMap<String, Int>>()
    val departments = mutableMapOf<String, Int>()
    ours.forEach { row ->
        val reference = row.reference()
        if (row.action == CREATED_ACTION) {
            val documentId = row.level1.ifBlank { row.referenceId }
            if (documentId.isNotBlank()) documents[documentId] = (documents[documentId] ?: 0) + 1
            val department = row.unit.ifBlank { reference?.text("department_id").orEmpty() }
            if (department.isNotBlank()) departments[department] = (departments[department] ?: 0) + 1
        } else {
            val documentId = reference?.text("budget_document_id") ?: row.level1
            if (documentId.isBlank()) return@forEach
            val key = row.chatRoomId.ifBlank { reference?.text("chat_room_id").orEmpty() }.ifBlank { row.sender }
            val perDocument = chats.getOrPut(documentId) { mutableMapOf() }
            perDocument[key] = (perDocument[key] ?: 0) + 1
            val department = reference?.text("department_id") ?: row.level2
            if (department.isNotBlank()) departments[department] = (departments[department] ?: 0) + 1
        }
    }
    return BudgetUnread(documents = documents, chats = chats, departments = departments)
}


private fun NotificationRecord.reference(): JsonObject? =
    runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
        ?.let { row ->
            when (val reference = row["reference_data"]) {
                is JsonObject -> reference
                is JsonPrimitive -> reference.contentOrNull
                    ?.let { runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
                else -> null
            }
        }

private fun JsonObject.text(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

/** The ledger's word for each tile — `main_budget_label` / `department_budget_label`. */
private val BudgetMode.badgeLabel: String
    get() = when (this) {
        BudgetMode.Main -> "main_budget_label"
        BudgetMode.Department -> "department_budget_label"
    }

/** Fetches the document to Downloads and hands it to the OS, as the boards do. */
private fun openBudgetFile(ready: AppGraph.Ready, scope: CoroutineScope, document: BudgetDocument) {
    val file = document.file ?: return
    openNoticeAttachment(ready, scope)(
        NoticeAttachment(
            media = file.media,
            fileName = file.name,
            bucket = file.bucket,
            region = file.region,
        ),
    )
}

private const val BUDGET_TAG = "BudgetWiring"
private const val BYTES_PER_MB = 1024.0 * 1024.0
private const val PDF_TYPE = "application/pdf"
private const val TOOLS_SECTION = "tools_label"
private const val CREATED_ACTION = "budget_has_created"
