package com.zillit.desktop

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.permissions.ProjectPermissions
import com.zillit.desktop.feature.budget.data.BudgetRepositoryImpl
import com.zillit.desktop.feature.budget.domain.BudgetDocument
import com.zillit.desktop.feature.budget.domain.BudgetFile
import com.zillit.desktop.feature.budget.domain.BudgetViewer
import com.zillit.desktop.feature.budget.ui.BudgetToolProvider
import com.zillit.desktop.feature.budget.ui.BudgetViewModel
import com.zillit.desktop.feature.home.domain.NoticeAttachment
import com.zillit.desktop.core.localization.localised
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope

/**
 * The Budget tool's host wiring.
 *
 * The rights come from the tools call [HomeViewModel] already makes, so the
 * screen costs no extra round trip; the file picker and the storage route are
 * the board's, since a budget document is an attachment like any other.
 */
/**
 * The production's departments by id, fetched once when a production opens.
 *
 * Deliberately a small copy of the invoices tool's reference data rather than
 * a shared object: the two tools want the same map for different reasons, and
 * coupling a film tool to another film tool's wiring to save ten lines is a
 * worse trade than the duplication.
 */
private class BudgetDepartments(graph: AppGraph.Ready, scope: CoroutineScope) {

    private val byId = atomicMap()
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

    fun names(): Map<String, String> = byId.get()
}

private fun atomicMap() = java.util.concurrent.atomic.AtomicReference<Map<String, String>>(emptyMap())

internal fun AppGraph.Ready.buildBudget(
    permissions: () -> ProjectPermissions,
    scope: CoroutineScope,
): BudgetViewModel {
    val departments = BudgetDepartments(this, scope)
    return budgetViewModel(permissions) { departments.names() }
}

private fun AppGraph.Ready.budgetViewModel(
    permissions: () -> ProjectPermissions,
    departmentNames: () -> Map<String, String>,
) = BudgetViewModel(
    repository = BudgetRepositoryImpl(apiClient, config),
    viewer = { BudgetViewer.from(permissions()) },
    // Whose budget this person may upload — their own department's, as the
    // web reads it from the profile (`BudgetMain.jsx:57`, `:146`).
    departmentId = { projectContext?.context?.value?.profile?.departmentId.orEmpty() },
    pickFile = { pickBudgetFile(this) },
    departmentName = { id -> departmentNames()[id] },
)

internal fun AppGraph.Ready.budgetProvider(
    viewModel: BudgetViewModel,
    path: String,
    scope: CoroutineScope,
) = BudgetToolProvider(
    viewModel = viewModel,
    path = path,
    onOpenFile = { document, _ -> openBudgetFile(this, scope, document) },
    // The discussion that belongs to the budget on screen — the chat tool's
    // own thread under this budget's tool and department.
    conversation = { document, canPost -> BudgetConversationPane(this, document, canPost) },
)

/**
 * One file, through the board's routed store.
 *
 * A budget is a document — the desktop takes one file at a time, and the
 * upload returns the descriptor the service wants under `attachment`.
 */
private suspend fun pickBudgetFile(ready: AppGraph.Ready): BudgetFile? {
    val media = homeMediaCapture(ready)
    val picked = media.pickOf(com.zillit.desktop.core.media.PreviewKind.Document).firstOrNull() ?: return null
    val stored = (media.upload?.invoke(picked) { } as? ZillitResult.Success)?.data ?: return null
    return BudgetFile(
        media = stored.media,
        name = stored.fileName,
        contentType = stored.contentType,
        bucket = stored.bucket.orEmpty(),
        region = stored.region.orEmpty(),
        sizeBytes = picked.bytes.size.toLong(),
    )
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
