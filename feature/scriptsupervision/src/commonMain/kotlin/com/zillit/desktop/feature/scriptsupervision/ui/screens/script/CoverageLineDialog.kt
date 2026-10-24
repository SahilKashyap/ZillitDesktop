package com.zillit.desktop.feature.scriptsupervision.ui.screens.script

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import com.zillit.desktop.feature.scriptsupervision.data.ContinuityCrud
import com.zillit.desktop.feature.scriptsupervision.domain.LinedScript
import com.zillit.desktop.feature.scriptsupervision.domain.Rec
import com.zillit.desktop.feature.scriptsupervision.ui.FormDialog
import com.zillit.desktop.feature.scriptsupervision.ui.FormGrid
import com.zillit.desktop.feature.scriptsupervision.ui.FormHalf
import com.zillit.desktop.feature.scriptsupervision.ui.FormWide
import com.zillit.desktop.feature.scriptsupervision.ui.LocalSs
import com.zillit.desktop.feature.scriptsupervision.ui.PickInput
import com.zillit.desktop.feature.scriptsupervision.ui.TagInput
import com.zillit.desktop.feature.scriptsupervision.ui.TextInput
import com.zillit.desktop.feature.scriptsupervision.ui.body
import com.zillit.desktop.feature.scriptsupervision.ui.dropEmpty
import com.zillit.desktop.feature.scriptsupervision.ui.t
import kotlinx.coroutines.launch

/**
 * Adding or editing one coverage line — the web's modal on `LinedScriptScreen`.
 *
 * ## The two traps this form sits on
 *
 * 1. **A create must DROP what nobody filled; an edit must SEND it.** The
 *    service's create validator refuses `""` for an optional field, and an
 *    edit that leaves a cleared field out keeps the old value instead of
 *    clearing it. [dropEmpty] is applied on create only, which is the rule the
 *    whole module follows.
 * 2. **Numbers nobody entered are 0, not absent.** `total_takes` and
 *    `preferred_take` go as 0 rather than omitted — the web is explicit that a
 *    cleared number "comes back undefined, which JSON drops", and sends `?? 0`
 *    for exactly that reason. A preferred take of 0 means "none chosen", which
 *    is why the detail panel shows a dash for it.
 *
 * `line_end` is checked here rather than left to the service, because the
 * service accepts a backwards span and then draws nothing — a silent no-op is
 * the worst answer to a typo.
 */
@Composable
internal fun CoverageLineDialog(line: Rec, sceneId: String, onClose: () -> Unit, onSaved: () -> Unit) {
    val ctx = LocalSs.current
    val editing = line.id.isNotBlank()
    val form = remember(line.id) { LineForm(line) }
    var busy by remember { mutableStateOf(false) }

    FormDialog(
        open = true,
        title = t(if (editing) "edit_coverage_line" else "add_coverage_line"),
        onDismiss = onClose,
        confirmLabel = t("save"),
        confirmEnabled = form.complete && !form.backwards,
        onConfirm = {
            busy = true
            ctx.scope.launch {
                val crud = ContinuityCrud(ctx.api, "lined-script")
                val done = ctx.write {
                    if (editing) {
                        crud.update(line.id, body(form.fields()))
                    } else {
                        crud.create(body(form.fields() + ("scene_id" to sceneId)).dropEmpty())
                    }
                }
                busy = false
                if (done != null) {
                    onClose()
                    onSaved()
                }
            }
        },
        busy = busy,
        width = DIALOG_WIDTH,
    ) {
        CoverageFields(form)
    }
}

/** The form's fields, in the web's own order and grouping. */
@Composable
private fun CoverageFields(form: LineForm) {
    FormGrid {
        TextInput(form.shot, { form.shot = it }, t("shot"), placeholder = "1A")
        PickInput(
            value = form.size,
            options = LinedScript.SHOT_SIZES.map { it to it },
            onChange = { form.size = it },
            label = t("shot_size"),
            clearable = true,
        )
        TextInput(form.character, { form.character = it }, t("character"))
        TextInput(form.description, { form.description = it }, t("description"), modifier = FormWide)
        TextInput(form.page, { form.page = it }, t("page"))
        TextInput(form.start, { form.start = it }, t("line_start"), modifier = FormHalf, number = true)
        TextInput(
            value = form.end,
            onChange = { form.end = it },
            label = t("line_end"),
            modifier = FormHalf,
            number = true,
            error = t("line_end_after_start").takeIf { form.backwards },
        )
        PickInput(
            value = form.type,
            options = listOf(
                "straight" to t("on_screen_solid"),
                "squiggly" to t("off_screen_dashed"),
            ),
            onChange = { form.type = it },
            label = t("line_type"),
        )
        TextInput(form.takes, { form.takes = it }, t("total_takes"), modifier = FormHalf, number = true)
        TextInput(form.preferred, { form.preferred = it }, t("preferred_take"), modifier = FormHalf, number = true)
        TagInput(
            tokens = form.circled,
            onChange = { form.circled = it },
            label = t("circled_takes"),
            help = t("circled_takes_help"),
            modifier = FormWide,
        )
        TextInput(form.notes, { form.notes = it }, t("notes"), modifier = FormWide, multiline = true)
    }
}

/**
 * What the form holds, as one object.
 *
 * A holder rather than a dozen `remember { mutableStateOf(…) }` at the call
 * site: it lets the fields and the save be separate composables that each take
 * one parameter, and it keeps the wire body ([fields]) next to the state it is
 * built from, where the two cannot drift apart.
 *
 * A number is kept as the TYPED TEXT, not as an Int, because "" and 0 are
 * different things to a reader — an empty box is not a zero — and only the
 * wire body flattens the two.
 */
private class LineForm(line: Rec) {
    var shot by mutableStateOf(line.str("shot_number"))
    var size by mutableStateOf(line.str("shot_size"))
    var character by mutableStateOf(line.str("character_focus"))
    var description by mutableStateOf(line.str("shot_description"))
    var page by mutableStateOf(line.str("page_number"))
    var start by mutableStateOf(line.int("line_start").takeIf { it > 0 }?.toString().orEmpty())
    var end by mutableStateOf(line.int("line_end").takeIf { it > 0 }?.toString().orEmpty())
    var type by mutableStateOf(line.str("line_type").ifBlank { "straight" })
    var takes by mutableStateOf(line.int("total_takes").takeIf { it > 0 }?.toString().orEmpty())
    var preferred by mutableStateOf(line.int("preferred_take").takeIf { it > 0 }?.toString().orEmpty())
    var circled by mutableStateOf(line.strings("circled_takes"))
    var notes by mutableStateOf(line.str("notes"))

    private val startNumber: Int? get() = start.toIntOrNull()
    private val endNumber: Int? get() = end.toIntOrNull()

    /** A span that ends before it starts — refused here, see the class note. */
    val backwards: Boolean
        get() = startNumber?.let { from -> endNumber?.let { to -> to < from } } ?: false

    /** The four the service requires; everything else is optional. */
    val complete: Boolean
        get() = shot.isNotBlank() && page.isNotBlank() && startNumber != null && endNumber != null

    /** The record as the service takes it. */
    fun fields(): List<Pair<String, Any?>> = listOf(
        "shot_number" to shot,
        "shot_size" to size,
        "character_focus" to character,
        "shot_description" to description,
        "page_number" to page,
        "line_start" to (startNumber ?: 0),
        "line_end" to (endNumber ?: 0),
        "line_type" to type,
        // Sent as 0, never omitted: see the file's note.
        "total_takes" to (takes.toIntOrNull() ?: 0),
        "preferred_take" to (preferred.toIntOrNull() ?: 0),
        // Numbers on the wire, whatever was typed: the service stores an array
        // of ints and "3" would be stored as a string beside them.
        "circled_takes" to circled.mapNotNull { it.trim().toIntOrNull() },
        "notes" to notes,
    )
}

private val DIALOG_WIDTH = 640.dp
