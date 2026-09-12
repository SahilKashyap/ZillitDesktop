package com.zillit.desktop.feature.assetreport.ui

import com.zillit.desktop.core.common.orDash
import com.zillit.desktop.core.common.looksLikeRawId
import com.zillit.desktop.core.permissions.RightsKind
import com.zillit.desktop.core.permissions.RightsRequestBus
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.mvvm.ZillitViewModel
import com.zillit.desktop.feature.assetreport.domain.AssetCategory
import com.zillit.desktop.feature.assetreport.domain.AssetExport
import com.zillit.desktop.feature.assetreport.domain.AssetLine
import com.zillit.desktop.feature.assetreport.domain.AssetRecord
import com.zillit.desktop.feature.assetreport.domain.AssetRepository
import com.zillit.desktop.feature.assetreport.domain.AssetViewer

/** The category chip strip's three positions. */
enum class CategoryFilter(val label: String) {
    All("All"),
    Keep("Keep"),
    Sell("Sell"),
}

data class AssetUiState(
    val lines: List<AssetLine> = emptyList(),
    val vendors: Map<String, String> = emptyMap(),
    /** Department id → display name, resolved by the host. */
    val departments: Map<String, String> = emptyMap(),
    val isLoading: Boolean = false,
    val viewer: AssetViewer = AssetViewer(),
    val query: String = "",
    val categoryFilter: CategoryFilter = CategoryFilter.All,
    /** The opened line and its register detail; null = the table. */
    val detail: AssetDetail? = null,
    val isExporting: Boolean = false,
    val error: String? = null,
) {
    fun vendorName(id: String): String = vendors[id] ?: id.ifBlank { "—" }

    /**
     * A department's name.
     *
     * The register stores a *label key* ("accounts_department_label") as often
     * as an id, and both were being printed raw. The dictionary resolves the
     * key; an id nobody can resolve shows as an em dash rather than as hex.
     */
    fun departmentName(id: String): String =
        departments[id] ?: id.takeIf { it.isNotBlank() && !it.looksLikeRawId() }?.localised().orDash()

    /** The web's haystack: description, vendor name, code, ref, department. */
    val visible: List<AssetLine>
        get() = lines
            .filter { line ->
                when (categoryFilter) {
                    CategoryFilter.All -> true
                    CategoryFilter.Keep -> line.category == AssetCategory.Keep
                    CategoryFilter.Sell -> line.category == AssetCategory.Sell
                }
            }
            .filter { line ->
                val needle = query.trim()
                needle.isBlank() || listOf(
                    line.description,
                    vendorName(line.vendorId),
                    line.account,
                    line.poNumber,
                    departmentName(line.departmentId),
                ).any { it.contains(needle, ignoreCase = true) }
            }
}

/**
 * One open row. [record] arrives by hydration; editing stays locked until it
 * has — saving a blank note over one merely not fetched destroys it.
 */
data class AssetDetail(
    val line: AssetLine,
    val isHydrating: Boolean = true,
    val record: AssetRecord? = null,
    val categoryDraft: AssetCategory = AssetCategory.None,
    val noteDraft: String = "",
    val isSaving: Boolean = false,
) {
    val isNew: Boolean get() = (record?.id).isNullOrBlank()
    val categoryDirty: Boolean get() = categoryDraft != (record?.category ?: line.category)
    val noteDirty: Boolean get() = noteDraft != (record?.comments ?: "")
}

sealed interface AssetEvent {
    data object Refresh : AssetEvent
    data class Search(val query: String) : AssetEvent
    data class Filter(val filter: CategoryFilter) : AssetEvent
    data class Open(val lineItemId: String) : AssetEvent
    data object CloseDetail : AssetEvent
    data class PickCategory(val category: AssetCategory) : AssetEvent
    data class NoteChanged(val text: String) : AssetEvent
    data object SaveCategory : AssetEvent
    data object SaveNote : AssetEvent
    data class Export(val format: String) : AssetEvent
    data object DismissError : AssetEvent
}

sealed interface AssetEffect {
    data class Notice(val text: String) : AssetEffect
}

/**
 * The Asset Register: the PO lines as one table, a per-line register record,
 * and the export — the new module both phones route to.
 */
class AssetViewModel(
    private val repository: AssetRepository,
    private val export: AssetExport,
    private val resolveViewer: () -> AssetViewer,
    private val loadDepartments: suspend () -> Map<String, String>,
    /**
     * Where "ask an admin for this right" goes; null leaves the plain refusal.
     *
     * The frame answers it with the admin picker and sends the request as a
     * chat message — the phones' flow, hosted once. See `RightsRequestSurface`.
     */
    private val rights: RightsRequestBus? = null,
) : ZillitViewModel<AssetUiState, AssetEvent, AssetEffect>(AssetUiState()) {

    /** Lines whose record fetch already ran — re-opening must not refetch. */
    private val hydrated = mutableMapOf<String, AssetRecord?>()

    fun start() {
        setState { copy(viewer = resolveViewer()) }
        refresh()
    }

    @Suppress("CyclomaticComplexMethod") // Event fan-out: one line per act.
    override fun onEvent(event: AssetEvent) {
        when (event) {
            AssetEvent.Refresh -> refresh()
            is AssetEvent.Search -> setState { copy(query = event.query) }
            is AssetEvent.Filter -> setState { copy(categoryFilter = event.filter) }
            is AssetEvent.Open -> open(event.lineItemId)
            AssetEvent.CloseDetail -> setState { copy(detail = null) }
            is AssetEvent.PickCategory -> editDetail {
                // Clicking the chosen card clears it — the web's toggle.
                copy(categoryDraft = if (categoryDraft == event.category) AssetCategory.None else event.category)
            }
            is AssetEvent.NoteChanged -> editDetail { copy(noteDraft = event.text) }
            AssetEvent.SaveCategory -> saveCategory()
            AssetEvent.SaveNote -> saveNote()
            is AssetEvent.Export -> runExport(event.format)
            AssetEvent.DismissError -> setState { copy(error = null) }
        }
    }

    private fun editDetail(change: AssetDetail.() -> AssetDetail) {
        val detail = currentState.detail ?: return
        if (detail.isHydrating) return
        setState { copy(detail = detail.change()) }
    }

    private fun refresh() {
        setState { copy(isLoading = true) }
        hydrated.clear()
        launchResult(
            block = { repository.lines() },
            onSuccess = { rows -> setState { copy(lines = rows, isLoading = false) } },
            onError = { error -> setState { copy(isLoading = false, error = error.localised()) } },
        )
        launchResult(
            block = { repository.vendors() },
            onSuccess = { names -> setState { copy(vendors = names) } },
        )
        launch {
            val names = loadDepartments()
            setState { copy(departments = names) }
        }
    }

    private fun open(lineItemId: String) {
        val line = currentState.lines.firstOrNull { it.lineItemId == lineItemId } ?: return

        if (lineItemId in hydrated) {
            val known = hydrated[lineItemId]
            setState { copy(detail = detailFrom(line, known)) }
            return
        }

        setState { copy(detail = AssetDetail(line = line, isHydrating = true)) }
        launchResult(
            block = { repository.recordForLine(line) },
            onSuccess = { record ->
                hydrated[lineItemId] = record
                // Patch even if the detail closed meanwhile — keyed by line,
                // landing late is correct; skipping reopens the blank hole.
                if (currentState.detail?.line?.lineItemId == lineItemId) {
                    setState { copy(detail = detailFrom(line, record)) }
                }
            },
            onError = { error ->
                // Retry stays possible: the failure is not remembered.
                if (currentState.detail?.line?.lineItemId == lineItemId) {
                    setState { copy(detail = null, error = error.localised()) }
                }
            },
        )
    }

    private fun detailFrom(line: AssetLine, record: AssetRecord?) = AssetDetail(
        line = line,
        isHydrating = false,
        record = record,
        categoryDraft = record?.category ?: line.category,
        noteDraft = record?.comments.orEmpty(),
    )

    private fun saveCategory() = save(note = false)

    private fun saveNote() = save(note = true)

    /**
     * Refuses, and offers the way forward the phones offer on every refusal.
     *
     * The frame answers the request with its admin picker; without one wired
     * the tool simply says what is missing, as it did before.
     */
    private fun refuseAndAsk() {
        rights?.ask("Asset Register", RightsKind.Post)
        sendEffect(
            AssetEffect.Notice(
                if (rights == null) {
                    "You don't have posting rights on the Asset Register."
                } else {
                    "You don't have posting rights on the Asset Register — asking an administrator."
                },
            ),
        )
    }

    private fun save(note: Boolean) {
        val detail = currentState.detail ?: return
        if (detail.isHydrating || detail.isSaving) return
        if (!currentState.viewer.mayEdit) {
            refuseAndAsk()
            return
        }
        setState { copy(detail = detail.copy(isSaving = true)) }
        launchResult(
            block = {
                val existing = detail.record?.id
                when {
                    existing.isNullOrBlank() -> repository.create(
                        poId = detail.line.poId,
                        lineItemId = detail.line.lineItemId,
                        category = detail.categoryDraft,
                        comments = if (note) detail.noteDraft else "",
                    )
                    note -> repository.updateComment(existing, detail.noteDraft)
                    else -> repository.updateCategory(existing, detail.categoryDraft)
                }
            },
            onSuccess = { saved ->
                hydrated[detail.line.lineItemId] = saved
                setState {
                    copy(
                        detail = currentState.detail?.takeIf {
                            it.line.lineItemId == detail.line.lineItemId
                        }?.copy(
                            isSaving = false,
                            record = saved,
                            categoryDraft = saved.category,
                            noteDraft = saved.comments,
                        ),
                        lines = lines.map { row ->
                            if (row.lineItemId == detail.line.lineItemId) {
                                row.copy(assetId = saved.id.ifBlank { row.assetId }, category = saved.category)
                            } else {
                                row
                            }
                        },
                    )
                }
                sendEffect(AssetEffect.Notice("Saved."))
            },
            onError = { error ->
                setState {
                    copy(detail = currentState.detail?.copy(isSaving = false), error = error.localised())
                }
            },
        )
    }

    private fun runExport(format: String) {
        if (!currentState.viewer.mayExport) {
            sendEffect(AssetEffect.Notice("You don't have export access."))
            return
        }
        if (currentState.isExporting) return
        setState { copy(isExporting = true) }
        launchResult(
            block = { export.export(format) },
            onSuccess = {
                setState { copy(isExporting = false) }
                sendEffect(AssetEffect.Notice("Export saved to Downloads."))
            },
            onError = { error -> setState { copy(isExporting = false, error = error.localised()) } },
        )
    }
}
