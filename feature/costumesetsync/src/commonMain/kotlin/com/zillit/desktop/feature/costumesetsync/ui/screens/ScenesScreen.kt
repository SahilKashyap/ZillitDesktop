package com.zillit.desktop.feature.costumesetsync.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.costumesetsync.data.SyncEvents
import com.zillit.desktop.feature.costumesetsync.domain.INT_EXT_FALLBACK
import com.zillit.desktop.feature.costumesetsync.domain.NEW_KEY
import com.zillit.desktop.feature.costumesetsync.domain.Rec
import com.zillit.desktop.feature.costumesetsync.domain.filterScenes
import com.zillit.desktop.feature.costumesetsync.domain.sceneLines
import com.zillit.desktop.feature.costumesetsync.domain.todayKey
import com.zillit.desktop.feature.costumesetsync.ui.Await
import com.zillit.desktop.feature.costumesetsync.ui.LocalSync
import com.zillit.desktop.feature.costumesetsync.ui.Pager
import com.zillit.desktop.feature.costumesetsync.ui.SectionCard
import com.zillit.desktop.feature.costumesetsync.ui.SocketRefresh
import com.zillit.desktop.feature.costumesetsync.ui.SyncCtx
import com.zillit.desktop.feature.costumesetsync.ui.mapRows
import com.zillit.desktop.feature.costumesetsync.ui.rememberResource
import com.zillit.desktop.feature.costumesetsync.ui.t
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/** The three lists the breakdown reads together (`GET /scenes`, `/characters`, `/actors`). */
internal class ScenesData(val scenes: List<Rec>, val characters: List<Rec>, val actors: List<Rec>)

/** Scenes shown per page outside Edit All — a long script is hundreds of rows. */
private const val PAGE_SCENES = 40

/**
 * Scenes and the breakdown are one screen: the same header, filters and editing,
 * read either one row per scene ("scenes", collapsed) or one row per character in
 * each scene ("breakdown", expanded) — the web's `ScenesScreen`.
 *
 * The view (`?view=`, else the route's [initialView]) and the open breakdown row
 * (`?row=<scene>.<character>`) live in the route's query so Back returns to them;
 * the filters are component state.
 */
@Composable
fun ScenesScreen(initialView: String) {
    val ctx = LocalSync.current
    val view = ctx.nav.current.arg("view").takeIf { it == "scenes" || it == "breakdown" } ?: initialView
    val data = rememberResource {
        coroutineScope {
            val scenes = async { api.get("/scenes").mapRows() }
            val characters = async { api.get("/characters").mapRows() }
            val actors = async { api.get("/actors").mapRows() }
            when (val s = scenes.await()) {
                is ZillitResult.Failure -> s
                is ZillitResult.Success -> ZillitResult.Success(
                    ScenesData(
                        s.data,
                        (characters.await() as? ZillitResult.Success)?.data.orEmpty(),
                        (actors.await() as? ZillitResult.Success)?.data.orEmpty(),
                    ),
                )
            }
        }
    }
    val scriptDocs = rememberProjectDocuments("SCRIPT")
    val scheduleDocs = rememberProjectDocuments("SCHEDULE")
    SocketRefresh(SyncEvents.Scene) { data.reload(silent = true) }
    SocketRefresh(SyncEvents.Document) {
        scriptDocs.reload()
        scheduleDocs.reload()
    }
    Await(data) { loaded ->
        ScenesBody(loaded, view, DocLists(scriptDocs, scheduleDocs)) { data.reload(silent = true) }
    }
}

@Composable
private fun ScenesBody(data: ScenesData, view: String, docs: DocLists, reload: () -> Unit) {
    val ctx = LocalSync.current
    val writes = rememberSceneWrites()
    val filters = remember { SceneFilterState() }
    val editor = remember(ctx, writes) { SceneEditor(ctx, writes) }
    var confirm by remember { mutableStateOf<Confirm?>(null) }
    var addOpen by remember { mutableStateOf(false) }
    var principalsFor by remember { mutableStateOf<String?>(null) }
    var uploads by remember { mutableStateOf(UploadsState()) }

    val charById = remember(data.characters) { data.characters.associateBy { it.id } }
    val sceneById = remember(data.scenes) { data.scenes.associateBy { it.id } }
    val episodes = showsEpisodes(ctx, data.scenes)
    val intExt = ctx.metaList("int_ext").ifEmpty { INT_EXT_FALLBACK }

    val list = filterScenes(data.scenes, filters.toFilters(), charById, todayKey(ctx.now())) { it.id in editor.drafts }
    val draftKeys = draftKeysOf(list, editor)
    val problems = draftProblems(data.scenes, editor.drafts, draftKeys)

    UnsavedGuard(editor, sceneById)

    val pages = maxOf(1, (list.size + PAGE_SCENES - 1) / PAGE_SCENES)
    val page = filters.page.coerceIn(1, pages)
    val shown = if (editor.editAll) list else list.drop((page - 1) * PAGE_SCENES).take(PAGE_SCENES)
    val input = TableInput(
        view, episodes, shown, shown.associate { it.id to sceneLines(it, charById, filters.characterId) }, charById,
        data.characters, data.actors, intExt, problems, ctx.canPost,
    )
    val actions = tableActions(
        ctx,
        editor,
        sceneById,
        filters.revision,
        ask = { confirm = it },
        principals = { principalsFor = it },
        reload = reload,
    )

    ScenesHeader(
        data.scenes,
        filters,
        HeaderState(
            view,
            ctx.canPost,
            editor.editAll || editor.picking,
            docs.script.docs.isNotEmpty(),
            docs.schedule.docs.isNotEmpty(),
        ),
        headerActions(ctx, { uploads }, { uploads = it }) { addOpen = true },
    )
    ScenesFilterBar(data.scenes, episodes, filters, editor.editAll || editor.picking) {
        if (ctx.canPost) EditEnd(
            EditEndState(editor, view, filters, list, draftKeys, problems, sceneById),
            reload,
        ) { confirm = it }
    }

    ScenesListCard(list, filters, editor, input, actions, page, pages)

    SceneDialogsHost(
        RowDialogData(data.scenes, data.characters, data.actors, episodes, intExt), editor, principalsFor, addOpen,
        onClosePrincipals = { principalsFor = null }, onCloseAdd = { addOpen = false }, onSaved = reload,
    )
    ConfirmDialog(confirm) { confirm = null }
    UploadsHost(uploads, data.scenes, docs, { uploads = it }, filters, reload)
}

/**
 * A series shows its Episode column from the start, so the first episode can be typed in;
 * any other production shows it once a scene carries one.
 */
private fun showsEpisodes(ctx: SyncCtx, scenes: List<Rec>): Boolean =
    ctx.project.rec?.str("type") == "EPISODIC" || scenes.any { it.str("episode").trim().isNotEmpty() }

/** The scenes table with its pager, or the empty state when nothing matches. */
@Composable
private fun ScenesListCard(
    list: List<Rec>,
    filters: SceneFilterState,
    editor: SceneEditor,
    input: TableInput,
    actions: TableActions,
    page: Int,
    pages: Int,
) {
    val ctx = LocalSync.current
    SectionCard(flush = true, modifier = Modifier.fillMaxWidth()) {
        if (list.isEmpty() && NEW_KEY !in editor.drafts) {
            SceneEmpty(filters, ctx.canPost) { editor.startNew() }
        } else {
            ScenesTable(input, editor, actions)
            if (!editor.editAll) Pager(page, pages, onPage = { filters.page = it })
        }
    }
}

/** The header's buttons: the view switch, the two uploads, the two viewers and "Add to breakdown". */
private fun headerActions(
    ctx: SyncCtx,
    uploads: () -> UploadsState,
    setUploads: (UploadsState) -> Unit,
    onAdd: () -> Unit,
) = HeaderActions(
    setView = { ctx.nav.setQuery("view", it) },
    uploadScript = { setUploads(uploads().copy(script = true)) },
    uploadSchedule = { setUploads(uploads().copy(schedule = true)) },
    viewScript = { setUploads(uploads().copy(viewing = "SCRIPT")) },
    viewSchedule = { setUploads(uploads().copy(viewing = "SCHEDULE")) },
    addToBreakdown = onAdd,
)

/** The rows being edited: a new one first, then each listed scene that has a draft. */
private fun draftKeysOf(list: List<Rec>, editor: SceneEditor): List<String> =
    (if (NEW_KEY in editor.drafts) listOf(NEW_KEY) else emptyList()) +
        list.filter { it.id in editor.drafts }.map { it.id }

/** The two document lists (script, schedule) the header's View buttons and the upload dialogs share. */
internal class DocLists(val script: ProjectDocuments, val schedule: ProjectDocuments)

/** Everything the toolbar's end needs, bundled. */
private class EditEndState(
    val editor: SceneEditor,
    val view: String,
    val filters: SceneFilterState,
    val list: List<Rec>,
    val draftKeys: List<String>,
    val problems: Map<String, String>,
    val sceneById: Map<String, Rec>,
)

/** Edit All / Edit Single / Add — or Save all / Cancel while editing. */
@Composable
private fun EditEnd(s: EditEndState, reload: () -> Unit, ask: (Confirm?) -> Unit) {
    val editor = s.editor
    when {
        editor.editAll -> EditAllToolbar(
            s.draftKeys.size, s.problems.values.firstOrNull(), editor.busy, editor.savingAll,
            onSaveAll = { editor.saveAll(s.draftKeys, s.sceneById, s.filters.revision, reload) },
            onCancel = { ask(cancelAllConfirm(editor, s.sceneById)) },
        )
        editor.picking -> SingleToolbar(
            picked = editor.single?.let { s.sceneById[it] },
            onEdit = {
                s.sceneById[editor.single]?.let { editor.startEdit(it) }
                editor.single = null
            },
            onDelete = {
                ask(
                    Confirm(
                        t("csync_delete_scene_confirm"),
                        "",
                        t("csync_delete"),
                        danger = true,
                    ) { editor.single?.let { editor.deleteScene(it, reload) } },
                )
            },
            onCancel = { editor.single = null },
        )
        else -> Row(
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The expanded view is one row per character, edited row by row with its pencil, so Edit All lives only in
            // the collapsed view.
            if (s.view == "scenes") {
                ZillitButton(
                    t("csync_edit_all"),
                    onClick = { editor.startEditAll(s.list) },
                    variant = ButtonVariant.Secondary,
                    enabled = s.list.isNotEmpty() && !editor.busy,
                )
                ZillitButton(
                    t("csync_edit_single"),
                    onClick = { editor.single = "" },
                    variant = ButtonVariant.Secondary,
                    enabled = s.list.isNotEmpty() && !editor.busy,
                )
            }
            BlueButton(
                t("csync_add"),
                onClick = { editor.startNew() },
                enabled = NEW_KEY !in editor.drafts && !editor.busy,
            )
        }
    }
}

private fun cancelAllConfirm(editor: SceneEditor, sceneById: Map<String, Rec>): Confirm {
    val edited = editor.drafts.keys.count { editor.changed(it, sceneById) }
    return if (edited > 0) {
        Confirm(
            t("csync_discard_changes_title"),
            pluralMany("csync_discard_scenes", edited, "n" to edited),
            t("csync_discard"),
            danger = true,
        ) { editor.leaveEditAll() }
    } else {
        Confirm(
            t("csync_stop_editing_title"),
            t("csync_stop_editing_body"),
            t("csync_stop_editing"),
        ) { editor.leaveEditAll() }
    }
}

private fun tableActions(
    ctx: SyncCtx,
    editor: SceneEditor,
    sceneById: Map<String, Rec>,
    revision: String,
    ask: (Confirm) -> Unit,
    principals: (String) -> Unit,
    reload: () -> Unit,
): TableActions = TableActions(
    // Via `scenes/` from either route (the breakdown route has no child routes).
    openScene = { ctx.nav.go("scenes/${it.id}") },
    editLine = { sceneId, characterId -> ctx.nav.setQuery("row", "$sceneId.$characterId") },
    removeLine = { sceneId, characterId ->
        ask(
            Confirm(t("csync_remove_from_scene_confirm"), "", t("csync_remove"), danger = true) {
                ctx.launchWrite({ ctx.api.delete("/scenes/$sceneId/characters/$characterId") }) { reload() }
            },
        )
    },
    principals = principals,
    save = { editor.saveOne(it, sceneById, revision, reload) },
    cancel = { key ->
        if (!editor.changed(key, sceneById)) {
            editor.drop(key)
        } else {
            ask(
                Confirm(
                    t("csync_discard_changes_title"),
                    t("csync_discard_changes_body"),
                    t("csync_discard"),
                    danger = true,
                ) { editor.drop(key) },
            )
        }
    },
)

/**
 * Leaving the page with an edited row holds the navigation and asks first (the
 * shell's leave guard). Discarding drops the drafts, so the next click goes through.
 */
@Composable
private fun UnsavedGuard(editor: SceneEditor, sceneById: Map<String, Rec>) {
    val ctx = LocalSync.current
    val dirty = editor.anyChanged(sceneById)
    var ask by remember { mutableStateOf(false) }
    DisposableEffect(dirty) {
        if (dirty) {
            ctx.nav.leaveGuard = {
                ask = true
                false
            }
        }
        onDispose { ctx.nav.leaveGuard = null }
    }
    LaunchedEffect(dirty) { if (!dirty) ask = false }
    ConfirmDialog(
        if (ask) Confirm(
            t("csync_discard_changes_title"),
            t("csync_leave_unsaved_body"),
            t("csync_discard"),
            danger = true,
        ) { editor.leaveEditAll() } else null,
    ) { ask = false }
}

/** The principals picker and the row dialog, wired to the editor's drafts and the route's `?row=`. */
@Composable
private fun SceneDialogsHost(
    data: RowDialogData,
    editor: SceneEditor,
    principalsFor: String?,
    addOpen: Boolean,
    onClosePrincipals: () -> Unit,
    onCloseAdd: () -> Unit,
    onSaved: () -> Unit,
) {
    val ctx = LocalSync.current
    val rowParam = ctx.nav.current.arg("row")
    // The open row lives in the route as well, so following its Character link and pressing Back
    // lands on the row's dialog again rather than on a closed table.
    val target = remember(rowParam, data.scenes) {
        val (sceneId, characterId) = rowParam.substringBefore('.') to rowParam.substringAfter('.', "")
        val found = data.scenes.firstOrNull { it.id == sceneId }
            ?.recs("characters")
            ?.any { it.str("character_id") == characterId } == true
        if (rowParam.isNotEmpty() && found) RowTarget(sceneId, characterId) else null
    }
    LaunchedEffect(rowParam, target) { if (rowParam.isNotEmpty() && target == null) ctx.nav.setQuery("row", null) }
    BreakdownRowDialog(
        open = addOpen || target != null,
        target = target,
        data = data,
        onClose = {
            if (target != null) ctx.nav.setQuery("row", null)
            onCloseAdd()
        },
        onSaved = onSaved,
    )
    PrincipalsDialog(
        open = principalsFor != null && editor.drafts.containsKey(principalsFor),
        onClose = onClosePrincipals,
        characters = data.characters,
        value = principalsFor?.let { editor.drafts[it]?.principals }.orEmpty(),
        onChange = { ids ->
            principalsFor?.let { key -> editor.drafts[key]?.let { editor.set(key, it.copy(principals = ids)) } }
        },
    )
}
