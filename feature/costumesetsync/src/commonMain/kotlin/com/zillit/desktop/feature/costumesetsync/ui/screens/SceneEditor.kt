package com.zillit.desktop.feature.costumesetsync.ui.screens

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.costumesetsync.data.Answer
import com.zillit.desktop.feature.costumesetsync.data.SceneWrites
import com.zillit.desktop.feature.costumesetsync.data.persistDraft
import com.zillit.desktop.feature.costumesetsync.data.toJsonBody
import com.zillit.desktop.feature.costumesetsync.domain.NEW_KEY
import com.zillit.desktop.feature.costumesetsync.domain.Rec
import com.zillit.desktop.feature.costumesetsync.domain.SceneDraft
import com.zillit.desktop.feature.costumesetsync.domain.emptyDraft
import com.zillit.desktop.feature.costumesetsync.domain.planSaveOrder
import com.zillit.desktop.feature.costumesetsync.domain.toDraft
import com.zillit.desktop.feature.costumesetsync.ui.SyncCtx
import com.zillit.desktop.feature.costumesetsync.ui.t
import kotlinx.coroutines.launch

/**
 * The Scene Breakdown's editing state — the drafts, the Edit All / Edit Single
 * modes, and saving one row or all of them (the web's `ScenesScreen` state).
 *
 * A draft is held per scene id; the not-yet-created row is held under
 * [NEW_KEY]. Saving goes through `persistDraft`, which reports rather than
 * throws; this class decides what the outcome means for the screen.
 */
@Stable
internal class SceneEditor(private val ctx: SyncCtx, private val writes: SceneWrites) {
    var drafts by mutableStateOf<Map<String, SceneDraft>>(emptyMap())
        private set
    var editAll by mutableStateOf(false)
        private set

    /** "Edit single": null = mode off, "" = on but nothing picked yet. */
    var single by mutableStateOf<String?>(null)
    var savingKey by mutableStateOf<String?>(null)
        private set
    var savingAll by mutableStateOf(false)
        private set

    val busy: Boolean get() = savingAll || savingKey != null
    val picking: Boolean get() = single != null

    fun set(key: String, draft: SceneDraft) {
        drafts = drafts + (key to draft)
    }

    fun drop(key: String) {
        drafts = drafts - key
    }

    fun startNew() = set(NEW_KEY, emptyDraft())

    fun startEdit(scene: Rec) = set(scene.id, toDraft(scene))

    /** Rows already being edited keep their in-progress values; only untouched rows get a fresh snapshot. */
    fun startEditAll(list: List<Rec>) {
        drafts = list.associate { it.id to toDraft(it) } + drafts
        editAll = true
    }

    fun leaveEditAll() {
        drafts = emptyMap()
        editAll = false
    }

    /** A draft only counts as unsaved once it differs from the scene it was opened on (or from a blank row). */
    fun changed(key: String, sceneById: Map<String, Rec>): Boolean {
        val d = drafts[key] ?: return false
        val base = if (key == NEW_KEY) emptyDraft() else sceneById[key]?.let(::toDraft) ?: return true
        return d != base
    }

    fun anyChanged(sceneById: Map<String, Rec>): Boolean = drafts.keys.any { changed(it, sceneById) }

    /**
     * Saves one row. Branches on the outcome, NOT on whether a toast was shown: a
     * save where nothing changed writes nothing, so there is no envelope — and
     * treating that silence as a failure would leave the row stuck in edit.
     */
    fun saveOne(key: String, sceneById: Map<String, Rec>, revision: String, onSaved: () -> Unit) {
        val draft = drafts[key] ?: return
        savingKey = key
        ctx.scope.launch {
            val outcome = persistDraft(
                writes, draft, if (key == NEW_KEY) null else key, sceneById[key], revision.ifEmpty { null }, ::castProblemText,
            )
            savingKey = null
            ctx.report(outcome.result)
            if (outcome.ok) {
                drop(key)
                onSaved()
            } else if (key == NEW_KEY && outcome.sceneId != null) {
                // The scene was created but a later step failed: re-key the draft to the
                // new id so a retry updates it rather than creating a second scene.
                drafts = drafts - NEW_KEY + (outcome.sceneId to draft)
                onSaved()
            }
        }
    }

    /**
     * Saves every draft, dependency-ordered so renumbering (5→6 while 6→7, or a 5↔6
     * swap) never hits the service's unique scene number. Keeps only the rows that
     * failed, so the user sees exactly what still needs saving; the first failure
     * is the news, even when a later row saved.
     */
    fun saveAll(keys: List<String>, sceneById: Map<String, Rec>, revision: String, onSaved: () -> Unit) {
        savingAll = true
        ctx.scope.launch {
            val failed = LinkedHashMap<String, String?>() // draft key -> id of a scene created before a later step failed
            var firstFailure: ZillitResult<Answer>? = null
            var lastOk: ZillitResult<Answer>? = null
            val numbers = sceneById.mapValues { it.value.str("number") }
            for (step in planSaveOrder(keys, drafts, numbers)) {
                if (step.key in failed) continue
                if (step.tempNumber != null) {
                    val res = writes.updateScene(step.key, mapOf<String, Any>("number" to step.tempNumber).toJsonBody())
                    if (res !is ZillitResult.Success) {
                        failed[step.key] = null
                        firstFailure = firstFailure ?: res
                    }
                    continue
                }
                val draft = drafts[step.key] ?: continue
                val outcome = persistDraft(
                    writes, draft, if (step.key == NEW_KEY) null else step.key, sceneById[step.key], revision.ifEmpty { null }, ::castProblemText,
                )
                if (outcome.ok) {
                    lastOk = outcome.result ?: lastOk
                } else {
                    failed[step.key] = outcome.sceneId
                    firstFailure = firstFailure ?: outcome.result
                }
            }
            val kept = failed.entries.associate { (key, createdId) -> (if (key == NEW_KEY && createdId != null) createdId else key) to drafts.getValue(key) }
            drafts = kept
            savingAll = false
            if (failed.isEmpty()) editAll = false
            ctx.report(firstFailure ?: lastOk)
            onSaved()
        }
    }

    /** `DELETE /scenes/:id` from Edit single's toolbar. */
    fun deleteScene(id: String, onDone: () -> Unit) {
        ctx.launchWrite({ ctx.api.delete("/scenes/$id") }) {
            single = null
            onDone()
        }
    }
}

/**
 * Per-draft problems that stop a save: a scene number is required, and must be
 * unique against other drafts and against the scenes that are not being edited.
 */
internal fun draftProblems(scenes: List<Rec>, drafts: Map<String, SceneDraft>, keys: List<String>): Map<String, String> {
    val taken = scenes.filter { it.id !in drafts }.map { it.str("number") }.toSet()
    val seen = HashSet<String>()
    val out = LinkedHashMap<String, String>()
    keys.forEach { key ->
        val n = drafts[key]?.number.orEmpty().trim()
        if (n.isEmpty()) {
            out[key] = t("csync_scene_number_required")
            return@forEach
        }
        if (n in taken || n in seen) out[key] = t("csync_scene_number_taken_n", "n" to n)
        seen.add(n)
    }
    return out
}
