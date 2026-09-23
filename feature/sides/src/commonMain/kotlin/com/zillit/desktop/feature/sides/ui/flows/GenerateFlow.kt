package com.zillit.desktop.feature.sides.ui.flows

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.permissions.RightsKind
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.sides.domain.GeneratePoller
import com.zillit.desktop.feature.sides.domain.SidesRules
import com.zillit.desktop.feature.sides.ui.GenerateState
import com.zillit.desktop.feature.sides.ui.SidesStore
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * The manual generate page — the web's `GenerateSidesForm`.
 *
 * Scene picks live in one map keyed by version id (unique across scripts),
 * so added scripts need no extra wiring in the payload; page picks are the
 * primary script's alone. Scenes load lazily on first expand and are held
 * thereafter. Switching the primary script clears every pick, since they
 * were keyed to the previous source set.
 */
@Suppress("TooManyFunctions") // One handler per form act.
internal class GenerateFlow(
    private val store: SidesStore,
    private val pdf: PdfFlow,
    private val onPublished: () -> Unit,
) {

    private fun form(change: GenerateState.() -> GenerateState) =
        store.update { copy(generate = generate?.change()) }

    fun open() {
        store.update { copy(generate = GenerateState()) }
        store.runTask {
            val (scripts, active) = coroutineScope {
                val scripts = async { store.repository.scripts() }
                val active = async { store.repository.activeScript() }
                scripts.await() to active.await()
            }
            val all = (scripts as? ZillitResult.Success)?.data.orEmpty()
            val activeScript = (active as? ZillitResult.Success)?.data
            form {
                copy(
                    loading = false,
                    scripts = all,
                    activeScriptId = activeScript?.id.orEmpty(),
                    activeVersionId = activeScript?.currentVersion?.id.orEmpty(),
                )
            }
            if (scripts is ZillitResult.Failure) store.failed(scripts.error)
            // Default to the active script, else the first available.
            (all.firstOrNull { it.id == activeScript?.id } ?: all.firstOrNull())?.let { pickScript(it.id) }
        }
    }

    fun close() = store.update { copy(generate = null) }

    // ── Sources ──

    fun pickScript(scriptId: String) {
        form {
            copy(
                scriptId = scriptId,
                versionPicks = emptyMap(),
                pagePicks = emptyMap(),
                wholePages = emptySet(),
                extraScriptIds = emptyList(),
                order = emptyList(),
                pages = emptyList(),
                openVersions = emptySet(),
                openPages = emptySet(),
            )
        }
        store.runTask {
            val (versions, pages) = coroutineScope {
                val versions = async { store.repository.versions(scriptId) }
                val pages = async { store.repository.scenePages(scriptId) }
                versions.await() to pages.await()
            }
            if (store.current.generate?.scriptId != scriptId) return@runTask
            val loaded = (versions as? ZillitResult.Success)?.data.orEmpty()
            form {
                copy(
                    versions = this.versions + (scriptId to loaded),
                    pages = (pages as? ZillitResult.Success)?.data.orEmpty(),
                )
            }
            if (versions is ZillitResult.Failure) store.failed(versions.error)
            // The current version of the active script opens expanded.
            val current = store.current.generate ?: return@runTask
            loaded.firstOrNull { current.isActiveScript && it.id == current.activeVersionId }
                ?.let { toggleVersionOpen(it.id) }
        }
    }

    fun addScript(scriptId: String) {
        if (scriptId.isBlank()) return
        form { copy(extraScriptIds = if (scriptId in extraScriptIds) extraScriptIds else extraScriptIds + scriptId) }
        if (store.current.generate?.versions?.containsKey(scriptId) == true) return
        store.runTask {
            when (val versions = store.repository.versions(scriptId)) {
                is ZillitResult.Success -> form { copy(versions = this.versions + (scriptId to versions.data)) }
                is ZillitResult.Failure -> store.failed(versions.error)
            }
        }
    }

    /** Removing an added script drops its picks with it. */
    fun removeScript(scriptId: String) = form {
        val versionIds = versions[scriptId].orEmpty().map { it.id }.toSet()
        copy(
            extraScriptIds = extraScriptIds - scriptId,
            versionPicks = versionPicks.filterKeys { it !in versionIds },
        ).synced()
    }

    fun toggleVersionOpen(versionId: String) {
        val open = store.current.generate?.openVersions ?: return
        form { copy(openVersions = if (versionId in open) open - versionId else open + versionId) }
        if (versionId !in open) loadVersionScenes(versionId)
    }

    private fun loadVersionScenes(versionId: String) {
        val current = store.current.generate ?: return
        if (versionId in current.scenesByVersion || versionId in current.scenesLoading) return
        form { copy(scenesLoading = scenesLoading + versionId) }
        store.runTask {
            val scenes = store.repository.scenes(versionId)
            form {
                copy(
                    scenesLoading = scenesLoading - versionId,
                    scenesByVersion = scenesByVersion +
                        (versionId to (scenes as? ZillitResult.Success)?.data.orEmpty()),
                )
            }
            if (scenes is ZillitResult.Failure) store.failed(scenes.error)
        }
    }

    fun togglePageOpen(pageId: String) {
        val open = store.current.generate?.openPages ?: return
        form { copy(openPages = if (pageId in open) open - pageId else open + pageId) }
        if (pageId !in open) loadPageScenes(pageId)
    }

    private fun loadPageScenes(pageId: String) {
        val current = store.current.generate ?: return
        if (pageId in current.scenesByPage || pageId in current.scenesLoading) return
        form { copy(scenesLoading = scenesLoading + pageId) }
        store.runTask {
            val scenes = store.repository.scenePageScenes(pageId)
            form {
                copy(
                    scenesLoading = scenesLoading - pageId,
                    scenesByPage = scenesByPage + (pageId to (scenes as? ZillitResult.Success)?.data.orEmpty()),
                )
            }
            if (scenes is ZillitResult.Failure) store.failed(scenes.error)
        }
    }

    // ── Picks ──

    private fun List<String>.toggled(scene: String): List<String> =
        if (scene in this) this - scene else this + scene

    fun toggleScene(versionId: String, scene: String) = form {
        copy(versionPicks = versionPicks + (versionId to versionPicks[versionId].orEmpty().toggled(scene))).synced()
    }

    fun setScenes(versionId: String, scenes: List<String>) = form {
        copy(versionPicks = versionPicks + (versionId to scenes)).synced()
    }

    fun togglePageScene(pageId: String, scene: String) = form {
        copy(pagePicks = pagePicks + (pageId to pagePicks[pageId].orEmpty().toggled(scene))).synced()
    }

    fun setPageScenes(pageId: String, scenes: List<String>) = form {
        copy(pagePicks = pagePicks + (pageId to scenes)).synced()
    }

    fun toggleWholePage(pageId: String) = form {
        copy(wholePages = if (pageId in wholePages) wholePages - pageId else wholePages + pageId)
    }

    /** Keeps a custom order in step with the selection while rearranging. */
    private fun GenerateState.synced(): GenerateState =
        if (rearrange) copy(order = SidesRules.syncOrder(order, allSelectedScenes)) else this

    fun rearrange(on: Boolean) = form {
        copy(rearrange = on, order = if (on && order.isEmpty()) allSelectedScenes else order)
    }

    fun order(order: List<String>) = form { copy(order = order) }
    fun displayMode(mode: String) = form { copy(displayMode = mode) }
    fun title(title: String) = form { copy(title = title) }

    // ── Run and review ──

    fun submit() {
        val current = store.current.generate ?: return
        if (current.running || store.refuses(RightsKind.Post)) return
        val refusal = when {
            current.scriptId.isBlank() -> str(S.desktop_no_script_selected)
            !current.readyToSubmit -> str(S.desktop_sides_select_scene_or_page)
            else -> null
        }
        if (refusal != null) return store.failed(refusal)
        val plan = current.plan
        form { copy(running = true, result = null, viewed = false) }
        store.runTask {
            val poller = GeneratePoller(
                generate = { store.repository.generate(plan) },
                get = store.repository::sidesById,
            )
            when (val outcome = poller.run(onTick = { tick -> form { copy(result = tick) } })) {
                is ZillitResult.Success -> {
                    form { copy(running = false, result = outcome.data.sides) }
                    if (outcome.data.timedOut) store.failed(str(S.desktop_sides_still_rendering))
                }
                is ZillitResult.Failure -> {
                    form { copy(running = false, result = null) }
                    store.failed(outcome.error)
                }
            }
        }
    }

    /** Back from the review stage to the form; picks survive, the draft does not. */
    fun backToForm() = form { copy(result = null, viewed = false) }

    fun view() {
        val current = store.current.generate ?: return
        val result = current.result ?: return
        form { copy(viewed = true) }
        pdf.open(
            title = result.title.ifBlank { str(S.txt_sides) },
            subtitle = str(S.desktop_sides_generated_subtitle),
            fileName = SidesRules.downloadName(result.title),
            info = current.selectionInfo,
        ) { store.repository.downloadUrl(result.id, countDownload = false) }
    }

    fun download() {
        val result = store.current.generate?.result ?: return
        if (store.refuses(RightsKind.Download)) return
        pdf.save(SidesRules.downloadName(result.title)) {
            store.repository.downloadUrl(result.id, countDownload = true)
        }
    }

    fun publish() {
        val result = store.current.generate?.result ?: return
        if (store.current.generate?.publishing == true) return
        form { copy(publishing = true) }
        store.runTask {
            when (val published = store.repository.publish(result.id)) {
                is ZillitResult.Success -> {
                    store.update { copy(generate = null) }
                    store.notice(str(S.desktop_sides_published))
                    onPublished()
                }
                is ZillitResult.Failure -> {
                    form { copy(publishing = false) }
                    store.failed(published.error)
                }
            }
        }
    }
}
