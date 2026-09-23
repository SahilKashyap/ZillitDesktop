package com.zillit.desktop.feature.sides.ui.flows

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.permissions.RightsKind
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.sides.domain.ScenePage
import com.zillit.desktop.feature.sides.domain.Script
import com.zillit.desktop.feature.sides.domain.ScriptVersion
import com.zillit.desktop.feature.sides.domain.SidesRules
import com.zillit.desktop.feature.sides.ui.ConfirmKind
import com.zillit.desktop.feature.sides.ui.PickPurpose
import com.zillit.desktop.feature.sides.ui.PickedDoc
import com.zillit.desktop.feature.sides.ui.SidesDialog
import com.zillit.desktop.feature.sides.ui.SidesEffect
import com.zillit.desktop.feature.sides.ui.SidesStore
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * The scripts manager — the web's `ScriptsManagerPage` with its per-script
 * `VersionDropdown` and `PagesSection`.
 *
 * The web mounts a pages section and a version list under every script
 * card at once, so both are fetched for every script as soon as the list
 * lands: the cards then render whole, with no per-card spinner cascade.
 */
@Suppress("TooManyFunctions") // One handler per script/page act.
internal class ScriptsFlow(
    private val store: SidesStore,
    private val pdf: PdfFlow,
) {

    fun load() {
        store.update { copy(scripts = scripts.copy(loading = true)) }
        store.runTask {
            when (val list = store.repository.scripts()) {
                is ZillitResult.Success -> {
                    store.update { copy(scripts = scripts.copy(loading = false, scripts = list.data)) }
                    loadDetails(list.data)
                }
                is ZillitResult.Failure -> {
                    store.update { copy(scripts = scripts.copy(loading = false)) }
                    store.failed(list.error)
                }
            }
        }
    }

    private suspend fun loadDetails(scripts: List<Script>) {
        store.update { copy(scripts = this.scripts.copy(pagesLoading = scripts.map { it.id }.toSet())) }
        val answers = coroutineScope {
            scripts.map { script ->
                async {
                    val versions = async { store.repository.versions(script.id) }
                    val pages = async { store.repository.scenePages(script.id) }
                    Triple(script.id, versions.await(), pages.await())
                }
            }.awaitAll()
        }
        store.update {
            val versions = this.scripts.versions.toMutableMap()
            val pages = this.scripts.pages.toMutableMap()
            answers.forEach { (id, versionAnswer, pageAnswer) ->
                (versionAnswer as? ZillitResult.Success)?.let { versions[id] = it.data }
                (pageAnswer as? ZillitResult.Success)?.let { pages[id] = it.data }
            }
            copy(scripts = this.scripts.copy(versions = versions, pages = pages, pagesLoading = emptySet()))
        }
    }

    /** One script's pages after an edit — the web's per-section refetch. */
    fun reloadPages(scriptId: String) {
        store.update { copy(scripts = scripts.copy(pagesLoading = scripts.pagesLoading + scriptId)) }
        store.runTask {
            val pages = store.repository.scenePages(scriptId)
            store.update {
                copy(
                    scripts = scripts.copy(
                        pages = (pages as? ZillitResult.Success)?.let { scripts.pages + (scriptId to it.data) }
                            ?: scripts.pages,
                        pagesLoading = scripts.pagesLoading - scriptId,
                    ),
                )
            }
            if (pages is ZillitResult.Failure) store.failed(pages.error)
        }
    }

    // ── Scripts ──

    fun askAdd() {
        if (store.refuses(RightsKind.Post)) return
        store.update { copy(dialog = SidesDialog.AddScript()) }
    }

    /** Replace (or first-upload) a script's file: the picker, then [replaceWith]. */
    fun replace(script: Script) {
        if (store.refuses(RightsKind.Post)) return
        store.effect(SidesEffect.PickFile(PickPurpose.ReplaceScript(script), pdfOnly = false))
    }

    fun replaceWith(script: Script, file: PickedDoc) {
        if (!SidesRules.isPdfOrFdx(file.name)) {
            store.failed(str(S.desktop_only_pdf_fdx_allowed))
            return
        }
        store.update { copy(scripts = scripts.copy(replacing = scripts.replacing + script.id)) }
        store.runTask {
            val outcome = store.transfer.upload(file.name, file.bytes).let { stored ->
                when (stored) {
                    is ZillitResult.Failure -> stored
                    is ZillitResult.Success -> {
                        val label = "v${(script.currentVersion?.versionNumber ?: 0) + 1}"
                        store.repository.addVersion(script.id, stored.data, label)
                    }
                }
            }
            store.update { copy(scripts = scripts.copy(replacing = scripts.replacing - script.id)) }
            when (outcome) {
                is ZillitResult.Success -> {
                    store.notice(str(S.desktop_sides_script_file_updated))
                    load()
                }
                is ZillitResult.Failure -> store.failed(outcome.error)
            }
        }
    }

    fun view(script: Script, version: ScriptVersion) {
        val isCurrent = version.id == script.currentVersion?.id
        pdf.open(
            title = if (isCurrent) script.title else version.fileName.ifBlank { version.label },
            subtitle = if (isCurrent) version.label else str(S.desktop_version_number, version.versionNumber),
            fileName = SidesRules.downloadName(script.title, "script"),
        ) { store.repository.versionDownloadUrl(version.id) }
    }

    fun download(script: Script, version: ScriptVersion) {
        if (store.refuses(RightsKind.Download)) return
        val name = if (version.id == script.currentVersion?.id) script.title else version.label
        pdf.save(SidesRules.downloadName(name, "script")) { store.repository.versionDownloadUrl(version.id) }
    }

    fun askDelete(script: Script) {
        if (store.refuses(RightsKind.Post)) return
        store.update {
            copy(
                dialog = SidesDialog.Confirm(
                    kind = ConfirmKind.Script,
                    id = script.id,
                    title = str(S.desktop_sides_delete_script_title),
                    message = str(S.desktop_sides_delete_script_message, script.title),
                ),
            )
        }
    }

    suspend fun delete(id: String): Boolean = when (val deleted = store.repository.deleteScript(id)) {
        is ZillitResult.Success -> {
            store.notice(str(S.desktop_sides_script_deleted))
            load()
            true
        }
        is ZillitResult.Failure -> {
            store.failed(deleted.error)
            false
        }
    }

    fun versionMenu(scriptId: String?) = store.update { copy(scripts = scripts.copy(versionMenu = scriptId)) }

    // ── Pages ──

    fun togglePages(scriptId: String) = store.update {
        val collapsed = scripts.collapsedPages
        copy(
            scripts = scripts.copy(
                collapsedPages = if (scriptId in collapsed) collapsed - scriptId else collapsed + scriptId,
            ),
        )
    }

    fun searchPages(scriptId: String, query: String) = store.update {
        copy(scripts = scripts.copy(pageSearch = scripts.pageSearch + (scriptId to query)))
    }

    fun askAddPage(scriptId: String) {
        if (store.refuses(RightsKind.Post)) return
        store.update { copy(dialog = SidesDialog.PageEditor(scriptId = scriptId)) }
    }

    fun askEditPage(scriptId: String, page: ScenePage) {
        if (store.refuses(RightsKind.Post)) return
        store.update {
            copy(
                dialog = SidesDialog.PageEditor(
                    scriptId = scriptId,
                    pageId = page.id,
                    sceneNumber = page.sceneNumber,
                    color = page.color.ifBlank { SidesRules.PAGE_COLORS.first() },
                    description = page.description,
                    currentFileName = page.fileName,
                ),
            )
        }
    }

    fun viewPage(page: ScenePage) = pdf.open(
        title = page.sceneNumber,
        subtitle = page.description.ifBlank { str(S.page) },
        fileName = SidesRules.downloadName(page.fileName.ifBlank { page.sceneNumber }, "page"),
    ) { store.repository.scenePageDownloadUrl(page.id) }

    fun askDeletePage(page: ScenePage) {
        if (store.refuses(RightsKind.Post)) return
        store.update {
            copy(
                dialog = SidesDialog.Confirm(
                    kind = ConfirmKind.Page,
                    id = page.id,
                    title = str(S.desktop_sides_delete_page_title),
                    message = str(S.desktop_delete_permanently_named, page.sceneNumber),
                ),
            )
        }
    }

    suspend fun deletePage(id: String): Boolean = when (val deleted = store.repository.deleteScenePage(id)) {
        is ZillitResult.Success -> {
            store.notice(str(S.desktop_sides_page_deleted))
            store.current.scripts.pages.entries.firstOrNull { (_, pages) -> pages.any { it.id == id } }
                ?.let { reloadPages(it.key) }
            true
        }
        is ZillitResult.Failure -> {
            store.failed(deleted.error)
            false
        }
    }
}
