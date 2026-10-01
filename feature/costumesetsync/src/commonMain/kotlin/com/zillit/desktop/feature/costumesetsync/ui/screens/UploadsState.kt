package com.zillit.desktop.feature.costumesetsync.ui.screens

import androidx.compose.runtime.Composable
import com.zillit.desktop.feature.costumesetsync.domain.Rec

/**
 * Which upload / viewer dialogs the Scene Breakdown has open. [readDoc] is a version picked in the viewer to read into
 * its importer.
 */
internal data class UploadsState(
    val script: Boolean = false,
    val schedule: Boolean = false,
    /** `SCRIPT` | `SCHEDULE` while the viewer is open. */
    val viewing: String? = null,
    val readDoc: Rec? = null,
)

/**
 * The script upload, schedule upload and document viewer, with the hand-offs between
 * them (the viewer's "Read this" opens the importer on the version on screen) and
 * what each does to the screen when it finishes.
 */
@Composable
internal fun UploadsHost(
    state: UploadsState,
    scenes: List<Rec>,
    docs: DocLists,
    onState: (UploadsState) -> Unit,
    filters: SceneFilterState,
    reload: () -> Unit,
) {
    val scriptUpload = rememberScriptUpload(
        open = state.script,
        docs = docs.script,
        existingScenes = scenes.size,
        onImported = {
            filters.shoot = "all"
            filters.revision = ""
            reload()
            docs.script.reload()
        },
        onClose = { onState(state.copy(script = false, readDoc = null)) },
    )
    val scheduleUpload = rememberScheduleUpload(
        open = state.schedule,
        kind = "SCHEDULE",
        docs = docs.schedule,
        breakdown = scenes,
        onApplied = {
            filters.shoot = "upcoming"
            filters.revision = ""
            reload()
            docs.schedule.reload()
        },
        onClose = { onState(state.copy(schedule = false, readDoc = null)) },
    )
    // Opened from the viewer's "Read this": start straight on that document, once per opening.
    SeedFromDocument(state.script, state.readDoc?.takeIf { it.str("kind") == "SCRIPT" }) { scriptUpload.pickDoc(it) }
    SeedFromDocument(state.schedule, state.readDoc?.takeIf { it.str("kind") == "SCHEDULE" }) {
        scheduleUpload.pickDoc(it)
    }

    ScriptUploadDialog(state.script, scriptUpload, docs.script) { onState(state.copy(script = false, readDoc = null)) }
    ScheduleUploadDialog(state.schedule, scheduleUpload, docs.schedule) {
        onState(state.copy(schedule = false, readDoc = null))
    }
    state.viewing?.let { kind ->
        DocumentViewerDialog(
            open = true,
            kind = kind,
            docs = if (kind == "SCRIPT") docs.script else docs.schedule,
            scenes = scenes,
            onClose = { onState(state.copy(viewing = null)) },
            onRead = { d ->
                onState(
                    state.copy(viewing = null, readDoc = d, script = kind == "SCRIPT", schedule = kind == "SCHEDULE"),
                )
            },
        )
    }
}

@Composable
private fun SeedFromDocument(open: Boolean, doc: Rec?, start: (Rec) -> Unit) {
    androidx.compose.runtime.LaunchedEffect(open, doc?.id) { if (open && doc != null) start(doc) }
}
