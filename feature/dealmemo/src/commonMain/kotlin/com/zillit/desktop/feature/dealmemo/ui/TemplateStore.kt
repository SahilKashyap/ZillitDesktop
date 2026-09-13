package com.zillit.desktop.feature.dealmemo.ui

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.dealmemo.domain.DealTemplate
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonObject

/** What a writer knows about a setup it just saved; a null field is left as the row has it. */
data class TemplatePatch(
    val name: String? = null,
    val form: JsonObject? = null,
    val createdBy: String? = null,
    val createdAt: Long? = null,
)

/**
 * The project's setups (`templatesStore.jsx`): fetched once per entry — the
 * slim list, then every row's payload — and kept coherent afterwards by the
 * pages that write them, never by refetching.
 */
internal class TemplateStore(private val vm: DealMemoViewModel) {

    private var job: Job? = null

    /** Writes made while the first load is in flight, replayed over its snapshot; null once it settles. */
    private var pending: MutableList<(List<DealTemplate>) -> List<DealTemplate>>? = mutableListOf()

    val loading: Boolean get() = job?.isActive == true

    fun load() {
        if (job != null) return
        vm.update { copy(templates = templates.copy(loading = true)) }
        job = vm.work {
            when (val list = vm.repository.templates()) {
                is ZillitResult.Success -> {
                    val rows = coroutineScope {
                        list.data.map { summary ->
                            async {
                                DealTemplate(
                                    id = summary.id,
                                    name = summary.name.ifBlank { UNTITLED },
                                    form = vm.repository.template(summary.id).getOrNull()?.form,
                                    createdBy = summary.createdBy,
                                    createdAt = summary.createdAt,
                                )
                            }
                        }.awaitAll()
                    }
                    val ops = pending.orEmpty()
                    pending = null
                    vm.update { copy(templates = TemplatesState(rows = ops.fold(rows) { acc, op -> op(acc) })) }
                }
                // Fails open: whatever a local write already produced stays.
                is ZillitResult.Failure -> {
                    val ops = pending.orEmpty()
                    pending = null
                    vm.update {
                        val kept = templates.rows ?: ops.fold(emptyList()) { acc, op -> op(acc) }
                        copy(templates = TemplatesState(rows = kept, failed = true))
                    }
                }
            }
        }
    }

    /** Waits for the load in flight rather than starting another. */
    suspend fun await() {
        job?.join()
    }

    /** A setup was created or written: an unknown id is prepended, a known one takes the patch. */
    fun upsert(id: String, patch: TemplatePatch) {
        if (id.isEmpty()) return
        val op: (List<DealTemplate>) -> List<DealTemplate> = { rows -> applyUpsert(rows, id, patch) }
        pending?.add(op)
        vm.update { copy(templates = templates.copy(rows = op(templates.rows.orEmpty()))) }
        vm.onTemplatesChanged()
    }

    /**
     * One read of the saved row so the lists show the server's name and
     * stamp — except a `TPL-DRFT-` name that raced a rename, which loses to
     * the name just typed. A failed read keeps [fallback].
     */
    fun refresh(id: String, fallback: TemplatePatch) {
        if (id.isEmpty()) return
        vm.work {
            when (val result = vm.repository.template(id)) {
                is ZillitResult.Success -> {
                    val row = result.data
                    val serverName = row.name.ifEmpty { null }
                    val stale = fallback.name != null && serverName?.startsWith(DRAFT_PREFIX) == true
                    upsert(
                        id,
                        TemplatePatch(
                            name = (if (stale) fallback.name else serverName) ?: fallback.name,
                            createdBy = row.createdBy ?: fallback.createdBy,
                            createdAt = row.createdAt,
                            form = row.form ?: fallback.form,
                        ),
                    )
                }
                is ZillitResult.Failure -> upsert(id, fallback)
            }
        }
    }

    fun remove(id: String) {
        if (id.isEmpty()) return
        val op: (List<DealTemplate>) -> List<DealTemplate> = { rows -> rows.filterNot { it.id == id } }
        pending?.add(op)
        vm.update { copy(templates = templates.copy(rows = templates.rows?.let(op))) }
        vm.onTemplatesChanged()
    }

    fun reset() {
        job?.cancel()
        job = null
        pending = mutableListOf()
    }

    private fun applyUpsert(rows: List<DealTemplate>, id: String, patch: TemplatePatch): List<DealTemplate> {
        val at = rows.indexOfFirst { it.id == id }
        if (at < 0) {
            return listOf(
                DealTemplate(
                    id = id,
                    name = patch.name?.ifEmpty { null } ?: UNTITLED,
                    form = patch.form,
                    createdBy = patch.createdBy,
                    createdAt = patch.createdAt ?: vm.clock(),
                ),
            ) + rows
        }
        val row = rows[at]
        val merged = row.copy(
            name = patch.name ?: row.name,
            form = patch.form ?: row.form,
            createdBy = patch.createdBy ?: row.createdBy,
            createdAt = patch.createdAt ?: row.createdAt,
        )
        return rows.toMutableList().also { it[at] = merged }
    }

    companion object {
        const val UNTITLED = "Untitled setup"
        const val DRAFT_PREFIX = "TPL-DRFT-"
    }
}
