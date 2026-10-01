package com.zillit.desktop.feature.costumesetsync.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.costumesetsync.domain.CharacterImport
import com.zillit.desktop.feature.costumesetsync.domain.DOC_GONE
import com.zillit.desktop.feature.costumesetsync.domain.ImportRow
import com.zillit.desktop.feature.costumesetsync.domain.PickedFile
import com.zillit.desktop.feature.costumesetsync.domain.Rec
import com.zillit.desktop.feature.costumesetsync.domain.aliveRows
import com.zillit.desktop.feature.costumesetsync.domain.buildCharacterImport
import com.zillit.desktop.feature.costumesetsync.domain.dateKey
import com.zillit.desktop.feature.costumesetsync.domain.docName
import com.zillit.desktop.feature.costumesetsync.domain.editedName
import com.zillit.desktop.feature.costumesetsync.domain.forceImport
import com.zillit.desktop.feature.costumesetsync.domain.initialRows
import com.zillit.desktop.feature.costumesetsync.domain.manualCharacters
import com.zillit.desktop.feature.costumesetsync.ui.LocalSync
import com.zillit.desktop.feature.costumesetsync.ui.SyncCtx
import com.zillit.desktop.feature.costumesetsync.ui.body
import com.zillit.desktop.core.localization.localised
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

internal val SCRIPT_EXTENSIONS = setOf("fdx", "txt", "pdf")
internal val SCHEDULE_EXTENSIONS = setOf("pdf", "csv", "tsv", "txt")

/** The editable fields of a scene in the review's hand-correction row. */
internal val EDIT_FIELDS = listOf("location", "int_ext", "time_of_day", "script_day", "pages", "synopsis")

/**
 * Upload a screenplay, review the breakdown it produces, then import it — the web's
 * `ScriptUploadModal` logic. Three phases: pick a file (or a listed document) → (if
 * the production already has scenes, confirm a replace BEFORE the file is even read)
 * → review and import. Nothing is written until the last button.
 *
 * Per scene the reviewer chooses Replace / Keep / Edit; the table shows what each
 * field would become with what it says now beside it, so a revised draft can be
 * taken field by field rather than all-or-nothing.
 */
@Stable
internal class ScriptUpload(
    private val ctx: SyncCtx,
    private val docs: ProjectDocuments,
    val existingScenes: Int,
    private val onImported: () -> Unit,
    val cues: CueExtraction,
) {
    var file by mutableStateOf<PickedFile?>(null)
    var doc by mutableStateOf<Rec?>(null)
    var parsing by mutableStateOf(false)
    var importing by mutableStateOf(false)
    var result by mutableStateOf<Rec?>(null)
    var revision by mutableStateOf("")
    var rows by mutableStateOf<List<ImportRow>>(emptyList())
    var tab by mutableStateOf("scenes")
    var actions by mutableStateOf<Map<String, String>>(emptyMap())
    var edits by mutableStateOf<Map<String, Map<String, String>>>(emptyMap())

    /** A production that already has a script is asked the moment a file is chosen — before it is read. */
    var confirmReplace by mutableStateOf(false)
    var replacing by mutableStateOf(false)

    /** "Also extract costume cues after import". */
    var withCues by mutableStateOf(false)
    var phase by mutableStateOf("review")

    val pickedName: String get() = file?.name ?: docName(doc)
    val scenes: List<Rec> get() = result?.recs("scenes").orEmpty()
    val detected: List<Rec> get() = result?.recs("characters").orEmpty()
    val existing: List<Rec> get() = result?.recs("existing_characters").orEmpty()

    fun actionOf(s: Rec): String = actions[s.str("number")] ?: "replace"
    val included: List<Rec> get() = scenes.filter { actionOf(it) != "keep" }
    val kept: Int get() = scenes.size - included.size

    /** Wiping would take the kept scenes with it, so keeping any turns it into a merge. */
    val wipes: Boolean get() = replacing && kept == 0
    val editedCount: Int get() = included.count { actionOf(it) == "edit" }

    fun reset() {
        file = null
        doc = null
        result = null
        rows = emptyList()
        actions = emptyMap()
        edits = emptyMap()
        tab = "scenes"
        confirmReplace = false
        replacing = false
        revision = ""
        withCues = false
        phase = "review"
    }

    /** The file chooser, then the same path a listed document takes. */
    fun chooseFile() {
        ctx.scope.launch {
            val picked = ctx.host.pick(SCRIPT_EXTENSIONS, multiple = false).firstOrNull() ?: return@launch
            file = picked
            doc = null
            if (existingScenes > 0 && !replacing) confirmReplace = true else parse()
        }
    }

    fun pickDoc(d: Rec) {
        doc = d
        file = null
        if (existingScenes > 0 && !replacing) confirmReplace = true else parse()
    }

    fun keepCurrent() {
        confirmReplace = false
        file = null
        doc = null
    }

    fun replaceGo() {
        confirmReplace = false
        replacing = true
        if (file != null || doc != null) parse()
    }

    /** Read the script — an uploaded file, or a listed document by its id. */
    fun parse() {
        val f = file
        val d = doc
        parsing = true
        ctx.scope.launch {
            val res = when {
                d != null -> ctx.api.post("/scenes/parse-script", body("document_id" to d.id))
                f != null -> ctx.api.upload("/scenes/parse-script", f.name, f.bytes, f.mime)
                else -> return@launch
            }
            parsing = false
            when (res) {
                is ZillitResult.Failure -> {
                    ctx.toast(res.error.localised(), false)
                    // Deleted or replaced in Zillit since the list was read: show the list as it is now.
                    if (d != null && (res.error as? ZillitError.Http)?.serverMessage == DOC_GONE) {
                        doc = null
                        docs.reload()
                    }
                }
                is ZillitResult.Success -> received(res.data.rec ?: Rec.Empty)
            }
        }
    }

    private fun received(r: Rec) {
        result = r
        actions = emptyMap()
        edits = emptyMap()
        revision = if (r.bool("first_upload")) "White" else "Revision ${ctx.now().let(::isoDay)}"
        rows = initialRows(r.recs("characters"), r.recs("existing_characters"))
        // Land on whichever tab needs a decision.
        tab = if (r.recs("characters").any { !it.bool("exists") }) "characters" else "scenes"
    }

    fun setAction(s: Rec, action: String) {
        val number = s.str("number")
        actions = actions + (number to action)
        // Editing starts from what the script says, so it is a tweak not a retype.
        if (action == "edit" && number !in edits) edits = edits + (number to EDIT_FIELDS.associateWith { s.str(it) })
    }

    fun setEdit(number: String, field: String, value: String) {
        edits = edits + (number to (edits[number].orEmpty() + (field to value)))
    }

    fun setRow(index: Int, row: ImportRow) {
        rows = rows.mapIndexed { i, r -> if (i == index) row else r }
    }

    val newCharacters: Int
        get() {
            val known = existing.map { it.str("name").lowercase() }.toSet()
            return aliveRows(rows).count { it.name.trim().lowercase() !in known }
        }

    /** The scene as it will be imported: its fields, with any hand correction laid over them. */
    fun importedScene(s: Rec): Rec {
        val edit = edits[s.str("number")]
        return if (actionOf(s) == "edit" && edit != null) Rec(JsonObject(s.json + edit.mapValues { JsonPrimitive(it.value) })) else s
    }

    fun runImport(meta: Rec?) {
        importing = true
        val plan = buildCharacterImport(rows, existing)
        val sent = included
        ctx.scope.launch {
            val res = ctx.api.post("/scenes/import", importBody(plan, sent))
            // The breakdown import only creates names it finds IN scenes, so anyone typed in by
            // hand who never speaks is created on their own afterwards.
            if (res is ZillitResult.Success) {
                manualCharacters(rows, existing, detected).forEach { c ->
                    ctx.api.post("/characters", body("name" to c.name, "cast_number" to c.castNumber))
                }
            }
            importing = false
            ctx.report(res)
            if (res !is ZillitResult.Success) return@launch
            onImported()
            if (!withCues) {
                done()
                return@launch
            }
            // Read the scenes just imported that have script text, by number.
            val numbers = sent.map { it.str("number") }.toSet()
            val all = (ctx.api.get("/scenes") as? ZillitResult.Success)?.data?.rows.orEmpty()
            val ids = all.filter { it.str("number") in numbers && it.bool("has_script") }.map { it.id }
            phase = "cues"
            val failure = cues.run(ids, cueEngineOf(meta))
            onImported()
            if (failure != null) ctx.toast(failure.localised(), false)
        }
    }

    /** Closes the dialog (set by the host). */
    var done: () -> Unit = {}

    private fun importBody(plan: CharacterImport, sent: List<Rec>): JsonObject = buildJsonObject {
        put("scenes", JsonArray(sent.map(::sceneBody)))
        put("revision", revision.ifEmpty { null }?.let { JsonPrimitive(it) } ?: JsonNull)
        put("character_map", JsonObject(plan.characterMap.mapValues { (_, v) -> v?.let { JsonPrimitive(it) } ?: JsonNull }))
        put("cast_numbers", JsonObject(plan.castNumbers.mapValues { JsonPrimitive(it.value) }))
        put("replace", JsonPrimitive(wipes))
        // Handing back the parse's token keeps this script in the documents list, so "View script" shows what was imported.
        result?.str("file_token")?.takeIf { it.isNotEmpty() }?.let {
            put("file_token", JsonPrimitive(it))
            put("file_name", JsonPrimitive(result?.str("file")?.ifEmpty { null } ?: pickedName))
        }
    }

    private fun sceneBody(s: Rec): JsonElement {
        val edited = actionOf(s) == "edit"
        val v = importedScene(s)
        fun field(key: String): JsonElement = v.json[key] ?: JsonNull
        return buildJsonObject {
            put("number", JsonPrimitive(s.str("number")))
            put("name", JsonPrimitive(if (edited) editedName(s, v) else s.str("name")))
            put("location", field("location"))
            put("int_ext", field("int_ext").blankToNull())
            put("time_of_day", field("time_of_day").blankToNull())
            put("script_day", field("script_day"))
            put("synopsis", field("synopsis"))
            put("pages", field("pages").blankToNull())
            // An omitted scene comes back from the reader as OMITTED; saying so marks it.
            s.str("status").takeIf { it.isNotEmpty() }?.let { put("status", JsonPrimitive(it)) }
            put("characters", s.json["characters"] ?: JsonArray(emptyList()))
            put("script_text", s.str("text").ifEmpty { null }?.let { JsonPrimitive(it) } ?: JsonNull)
            // A scene whose text has not moved is left alone by the server, which is right for a
            // re-upload and wrong for a row the review promised to change.
            if (forceImport(s, edited)) put("force", JsonPrimitive(true))
        }
    }
}

private fun JsonElement.blankToNull(): JsonElement = if (this is JsonPrimitive && content.isEmpty()) JsonNull else this

private fun isoDay(now: Long): String = dateKey(now)

@Composable
internal fun rememberScriptUpload(
    open: Boolean,
    docs: ProjectDocuments,
    existingScenes: Int,
    onImported: () -> Unit,
    onClose: () -> Unit,
): ScriptUpload {
    val ctx = LocalSync.current
    val cues = remember(open, ctx) { CueExtraction(ctx) }
    val upload = remember(open, ctx, existingScenes) { ScriptUpload(ctx, docs, existingScenes, onImported, cues) }
    upload.done = onClose
    return upload
}
