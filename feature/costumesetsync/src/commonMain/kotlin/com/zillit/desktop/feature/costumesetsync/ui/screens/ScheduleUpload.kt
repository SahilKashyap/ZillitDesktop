package com.zillit.desktop.feature.costumesetsync.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.feature.costumesetsync.domain.DOC_GONE
import com.zillit.desktop.feature.costumesetsync.domain.PickedFile
import com.zillit.desktop.feature.costumesetsync.domain.Rec
import com.zillit.desktop.feature.costumesetsync.domain.dateKey
import com.zillit.desktop.feature.costumesetsync.domain.docName
import com.zillit.desktop.feature.costumesetsync.ui.LocalSync
import com.zillit.desktop.feature.costumesetsync.ui.SyncCtx
import com.zillit.desktop.feature.costumesetsync.ui.body
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** One scene in a schedule review: what the file says about it and what the breakdown holds. */
internal data class SchedRow(
    val number: String,
    val sceneId: String?,
    val exists: Boolean,
    val currentShootDate: Long,
    val current: Rec?,
    val read: Rec?,
    val fills: Int,
    val cast: List<Rec>,
    val manual: Boolean,
    val parsedDate: String,
)

private fun schedRowOf(r: Rec) = SchedRow(
    number = r.str("number"),
    sceneId = r.str("_id").ifEmpty { null },
    exists = r.bool("exists"),
    currentShootDate = r.long("current_shoot_date"),
    current = r.rec("current"),
    read = r.rec("read"),
    fills = r.strings("fills").size.takeIf { it > 0 } ?: r.recs("fills").size,
    cast = r.recs("cast"),
    manual = false,
    parsedDate = r.str("date"),
)

/**
 * Upload a schedule or a call sheet, review what it says about each scene, apply —
 * the web's `ScheduleUploadModal` logic. A schedule does not rebuild the breakdown,
 * it SCHEDULES it: scenes already there keep everything they have and only their
 * blanks are filled; scenes the file names but the breakdown lacks can be added.
 * Nothing is deleted, and nothing is written until Apply.
 *
 * [kind] is `SCHEDULE` or `CALLSHEET` — the same flow, different wording and file.
 */
@Stable
internal class ScheduleUpload(
    private val ctx: SyncCtx,
    private val docs: ProjectDocuments,
    val kind: String,
    private val breakdown: List<Rec>,
    private val onApplied: (String?) -> Unit,
) {
    var file by mutableStateOf<PickedFile?>(null)
    var doc by mutableStateOf<Rec?>(null)
    var parsing by mutableStateOf(false)
    var applying by mutableStateOf(false)
    var result by mutableStateOf<Rec?>(null)
    var rows by mutableStateOf<List<SchedRow>>(emptyList())
    var dates by mutableStateOf<Map<String, String>>(emptyMap())
    var excluded by mutableStateOf<Set<String>>(emptySet())
    var createMissing by mutableStateOf(true)
    var fillBlanks by mutableStateOf(true)
    var manualOpen by mutableStateOf(false)
    var manualNo by mutableStateOf("")
    var manualDate by mutableStateOf("")
    var done: () -> Unit = {}

    val lower: String get() = if (kind == "CALLSHEET") "callsheet" else "schedule"
    val pickedName: String get() = file?.name ?: docName(doc)

    fun reset() {
        file = null
        doc = null
        result = null
        rows = emptyList()
        dates = emptyMap()
        excluded = emptySet()
        manualOpen = false
        manualNo = ""
        manualDate = ""
    }

    fun chooseFile() {
        ctx.scope.launch {
            val picked = ctx.host.pick(SCHEDULE_EXTENSIONS, multiple = false).firstOrNull() ?: return@launch
            file = picked
            doc = null
            read()
        }
    }

    fun pickDoc(d: Rec) {
        doc = d
        file = null
        read()
    }

    /** Read a schedule / call sheet — an uploaded file, or a listed document. */
    private fun read() {
        val f = file
        val d = doc
        parsing = true
        ctx.scope.launch {
            val res = when {
                d != null -> ctx.api.post("/schedule/parse", body("document_id" to d.id, "kind" to kind))
                f != null -> ctx.api.upload("/schedule/parse", f.name, f.bytes, f.mime, mapOf("kind" to kind))
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
                is ZillitResult.Success -> {
                    val r = res.data.rec ?: Rec.Empty
                    val parsed = r.recs("scenes").map(::schedRowOf)
                    result = r
                    rows = parsed
                    dates = parsed.associate { it.number to it.parsedDate }
                    excluded = emptySet()
                    createMissing = parsed.any { !it.exists }
                }
            }
        }
    }

    /** A scene added by hand is always meant, whatever the tick box says. */
    fun isOn(s: SchedRow): Boolean = s.number !in excluded && (s.exists || createMissing || s.manual)

    val included: List<SchedRow> get() = rows.filter(::isOn)

    fun toggle(number: String) {
        excluded = if (number in excluded) excluded - number else excluded + number
    }

    private val manualKey: String get() = manualNo.trim().uppercase().replace(Regex("^0+(?=\\d)"), "")
    val manualDuplicate: Boolean get() = manualKey.isNotEmpty() && rows.any { it.number.uppercase() == manualKey }
    val canAddManual: Boolean get() = result != null && manualKey.isNotEmpty() && !manualDuplicate

    /** The date a hand-added scene starts on: what was typed, else the earliest in the file, else the file's own, else today. */
    val defaultManualDate: String
        get() = manualDate.ifEmpty { dates.values.filter { it.isNotEmpty() }.minOrNull() ?: result?.str("date")?.ifEmpty { null } ?: dateKey(ctx.now()) }

    /** A scene the file missed: matched to the breakdown by number if it is there. */
    fun addManual() {
        if (!canAddManual) return
        val found = breakdown.firstOrNull { it.str("number").uppercase() == manualKey }
        val row = SchedRow(
            number = found?.str("number") ?: manualNo.trim(),
            sceneId = found?.id,
            exists = found != null,
            currentShootDate = found?.long("shoot_date") ?: 0L,
            current = found,
            read = null,
            fills = 0,
            cast = emptyList(),
            manual = true,
            parsedDate = defaultManualDate,
        )
        rows = rows + row
        dates = dates + (row.number to defaultManualDate)
        manualNo = ""
    }

    fun removeManual(number: String) {
        rows = rows.filterNot { it.manual && it.number == number }
        dates = dates - number
    }

    fun setDate(number: String, date: String) {
        dates = dates + (number to date)
    }

    val summaryCounts: SchedCounts
        get() {
            val inc = included
            return SchedCounts(
                added = inc.count { !it.exists },
                scheduled = inc.count { it.exists && !dates[it.number].isNullOrEmpty() },
                filled = if (fillBlanks) inc.filter { it.exists }.sumOf { it.fills } else 0,
                links = inc.sumOf { s -> s.cast.count { it.str("_id").isNotEmpty() } },
                skipped = rows.count { !it.exists && !createMissing && !it.manual },
            )
        }

    fun apply() {
        applying = true
        val inc = included
        val sheetDay = inc.mapNotNull { dates[it.number]?.takeIf(String::isNotEmpty) }.minOrNull() ?: result?.str("date")?.ifEmpty { null }
        ctx.scope.launch {
            val res = ctx.api.post("/schedule/apply", applyBody(inc, sheetDay))
            applying = false
            ctx.report(res)
            if (res is ZillitResult.Success) {
                onApplied(sheetDay)
                done()
            }
        }
    }

    private fun applyBody(inc: List<SchedRow>, sheetDay: String?): JsonObject = buildJsonObject {
        put("kind", JsonPrimitive(kind))
        put("file", result?.str("file")?.ifEmpty { null }?.let { JsonPrimitive(it) } ?: JsonNull)
        // The parse's token keeps this file in the documents list.
        result?.str("file_token")?.takeIf { it.isNotEmpty() }?.let { put("file_token", JsonPrimitive(it)) }
        // Only a call sheet is for one day; a schedule's dates are per scene.
        if (kind == "CALLSHEET") put("sheet_date", sheetDay?.let { JsonPrimitive(it) } ?: JsonNull)
        put("assignments", JsonArray(inc.map { assignment(it) }))
    }

    private fun assignment(s: SchedRow): JsonElement = buildJsonObject {
        put("scene_id", s.sceneId?.let { JsonPrimitive(it) } ?: JsonNull)
        put("number", JsonPrimitive(s.number))
        put("date", dates[s.number]?.takeIf { it.isNotEmpty() }?.let { JsonPrimitive(it) } ?: JsonNull)
        put("create", JsonPrimitive(!s.exists && (createMissing || s.manual)))
        // A new scene takes what the file says; an existing one only where "fill in blanks" is ticked.
        if (fillBlanks || !s.exists) {
            put(
                "fields",
                buildJsonObject {
                    listOf("name", "location", "int_ext", "time_of_day", "pages", "script_day").forEach { k ->
                        put(k, s.read?.json?.get(k) ?: JsonNull)
                    }
                },
            )
        }
        put("cast", JsonArray(s.cast.filter { it.str("_id").isNotEmpty() }.map { it.json["cast_number"] ?: JsonNull }))
    }
}

internal data class SchedCounts(val added: Int, val scheduled: Int, val filled: Int, val links: Int, val skipped: Int)

@Composable
internal fun rememberScheduleUpload(
    open: Boolean,
    kind: String,
    docs: ProjectDocuments,
    breakdown: List<Rec>,
    onApplied: (String?) -> Unit,
    onClose: () -> Unit,
): ScheduleUpload {
    val ctx = LocalSync.current
    val upload = remember(open, kind, ctx) { ScheduleUpload(ctx, docs, kind, breakdown, onApplied) }
    upload.done = onClose
    return upload
}
