package com.zillit.desktop.feature.scriptsupervision.ui.screens.characters

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitEmptyState
import com.zillit.desktop.core.designsystem.component.ZillitSectionCard
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.scriptsupervision.data.ContinuityCrud
import com.zillit.desktop.feature.scriptsupervision.domain.Characters
import com.zillit.desktop.feature.scriptsupervision.domain.Rec
import com.zillit.desktop.feature.scriptsupervision.ui.Await
import com.zillit.desktop.feature.scriptsupervision.ui.DeleteDialog
import com.zillit.desktop.feature.scriptsupervision.ui.Field
import com.zillit.desktop.feature.scriptsupervision.ui.FormDialog
import com.zillit.desktop.feature.scriptsupervision.ui.FormGrid
import com.zillit.desktop.feature.scriptsupervision.ui.FormHalf
import com.zillit.desktop.feature.scriptsupervision.ui.FormWide
import com.zillit.desktop.feature.scriptsupervision.ui.LocalSs
import com.zillit.desktop.feature.scriptsupervision.ui.MutedText
import com.zillit.desktop.feature.scriptsupervision.ui.NumberChip
import com.zillit.desktop.feature.scriptsupervision.ui.PickInput
import com.zillit.desktop.feature.scriptsupervision.ui.RowActions
import com.zillit.desktop.feature.scriptsupervision.ui.SsPageHeader
import com.zillit.desktop.feature.scriptsupervision.ui.SsTag
import com.zillit.desktop.feature.scriptsupervision.ui.TagInput
import com.zillit.desktop.feature.scriptsupervision.ui.TextInput
import com.zillit.desktop.feature.scriptsupervision.ui.Resource
import com.zillit.desktop.feature.scriptsupervision.ui.body
import com.zillit.desktop.feature.scriptsupervision.ui.cast
import com.zillit.desktop.feature.scriptsupervision.ui.count
import com.zillit.desktop.feature.scriptsupervision.ui.rememberResource
import com.zillit.desktop.feature.scriptsupervision.ui.scenes
import com.zillit.desktop.feature.scriptsupervision.ui.t
import kotlinx.coroutines.launch
import com.zillit.desktop.feature.scriptsupervision.ui.dropEmpty

/**
 * The story's chronology — the web's `StoryDaysScreen`.
 *
 * The story days in NARRATIVE order (what day 3 of the story is), which is not
 * the shooting order and not the scene numbering: a flashback is day 2 of the
 * story and may be shot last. Each day carries its kind, its date, how long it
 * spans, and the scenes that happen on it.
 */
@Suppress("LongMethod") // Header, the count, the timeline and two dialogs.
@Composable
internal fun StoryDaysScreen() {
    val ctx = LocalSs.current
    val castState = cast()
    var editing by remember { mutableStateOf<Rec?>(null) }
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Rec?>(null) }
    val crud = remember(ctx) { ContinuityCrud(ctx.api, "story-days") }
    val rows = rememberOrderedDays(crud)
    val changed = {
        rows.reload(silent = true)
        // Three other screens file a scene under its story day.
        castState.reload()
    }

    SsPageHeader(
        title = t("story_timeline"),
        sub = t("story_timeline_subtitle"),
        actions = {
            ZillitButton(
                text = t("add_story_day"),
                onClick = { ctx.whenPost { creating = true } },
                leadingIcon = ZillitIcons.Add,
            )
        },
    )
    Await(rows) { records ->
        MutedText(count("story_days_count", records.size))
        if (records.isEmpty()) {
            ZillitEmptyState(
                title = t("no_story_days"),
                message = t("story_timeline_subtitle"),
                action = {
                    ZillitButton(
                        text = t("add_story_day"),
                        onClick = { ctx.whenPost { creating = true } },
                        leadingIcon = ZillitIcons.Add,
                    )
                },
            )
        } else {
            records.forEach { day ->
                StoryDayCard(day, onEdit = { editing = day }, onDelete = { deleting = day })
            }
        }
    }

    if (creating || editing != null) {
        StoryDayForm(
            day = editing,
            existing = rows.value.orEmpty(),
            onClose = { creating = false; editing = null },
            onSaved = changed,
        )
    }
    deleting?.let { day ->
        DeleteDialog(
            title = t("delete_story_day_title"),
            name = day.str("label"),
            onClose = { deleting = null },
            onDone = changed,
            run = { crud.remove(day.id) },
        )
    }
}

/**
 * The story days in the order the writer set.
 *
 * The service answers them in its own order, and the screen's whole point is
 * the narrative one, so they are sorted here rather than trusted — a day with
 * no `order` at all still has to appear, and it appears last.
 */
@Composable
private fun rememberOrderedDays(crud: ContinuityCrud): Resource<List<Rec>> = rememberResource(crud) {
    when (val answer = crud.list()) {
        is ZillitResult.Failure -> answer
        is ZillitResult.Success -> ZillitResult.Success(
            answer.data.rows.sortedBy { if (it.has("order")) it.int("order") else Int.MAX_VALUE },
        )
    }
}

/** One story day on the timeline: its short name, what kind of day it is, and its scenes. */
@Composable
private fun StoryDayCard(day: Rec, onEdit: () -> Unit, onDelete: () -> Unit) {
    val sceneState = scenes()
    val kind = day.str("type")
    ZillitSectionCard(
        modifier = Modifier.fillMaxWidth(),
        action = { RowActions(onEdit = onEdit, onDelete = onDelete) },
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            verticalAlignment = Alignment.Top,
        ) {
            NumberChip(caption = t("day"), value = shortLabel(day))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ZillitText(
                        text = day.str("label"),
                        style = ZillitTheme.typography.titleSmall,
                    )
                    // "normal" is the default and says nothing worth a tag.
                    if (kind.isNotBlank() && kind != "normal") {
                        SsTag(t("story_type_$kind"), StatusTone.Progress)
                    }
                    day.str("story_date").takeIf { it.isNotBlank() }?.let { MutedText(it) }
                    day.str("time_span").takeIf { it.isNotBlank() }?.let { SsTag(it) }
                }
                Field(t("description"), day.str("description"))
                val numbers = day.strings("scene_numbers")
                if (numbers.isNotEmpty()) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
                        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
                    ) {
                        numbers.forEach { number ->
                            // Gold when the production HAS that scene; plain
                            // when the day names a number nothing matches,
                            // which is the web's way of showing a typo.
                            val known = sceneState.scenes.any { it.str("scene_number") == number }
                            SsTag(
                                "${t("scene_short")} $number",
                                if (known) StatusTone.Progress else StatusTone.Neutral,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * The day's short name, as it goes on a slate.
 *
 * A label with no number in it falls back to the day's own `order`, which is
 * what the web does: "Prologue" with order 0 still has to read as something.
 */
private fun shortLabel(day: Rec): String {
    val digits = day.str("label").filter { it.isDigit() }
    return if (digits.isNotEmpty()) "D$digits" else "D${day.int("order")}"
}

/**
 * The story-day form.
 *
 * ⚠ Two traps the web encodes and this keeps. The story DATE is stored as the
 * text "Oct 5, 2026", not as a date — so it is typed, and a date picker here
 * would write a different format than the web reads. And the time SPAN stores
 * the translated LABEL, not a key, because the web's picker is built
 * `{ value: label, label }` — so the option values are the words, and a record
 * written in one language reads in that language everywhere.
 */
@Suppress("LongMethod") // The day's seven fields, one after another.
@Composable
private fun StoryDayForm(day: Rec?, existing: List<Rec>, onClose: () -> Unit, onSaved: () -> Unit) {
    val ctx = LocalSs.current
    val sceneState = scenes()
    // A new day is suggested as the next one in the story, as the web does.
    val nextOrder = remember(existing) { existing.maxOfOrNull { it.int("order") }?.plus(1) ?: 1 }
    var order by remember(day) { mutableStateOf((day?.int("order") ?: nextOrder).toString()) }
    var label by remember(day) {
        mutableStateOf(day?.str("label") ?: "${t("story_day")} $nextOrder")
    }
    var kind by remember(day) { mutableStateOf(day?.str("type").orEmpty().ifBlank { "normal" }) }
    var storyDate by remember(day) { mutableStateOf(day?.str("story_date").orEmpty()) }
    var span by remember(day) { mutableStateOf(day?.str("time_span").orEmpty()) }
    var numbers by remember(day) { mutableStateOf(day?.strings("scene_numbers").orEmpty()) }
    var description by remember(day) { mutableStateOf(day?.str("description").orEmpty()) }
    var saving by remember { mutableStateOf(false) }

    FormDialog(
        open = true,
        title = t(if (day == null) "add_story_day" else "edit_story_day"),
        onDismiss = onClose,
        confirmLabel = t("save"),
        onConfirm = {
            saving = true
            ctx.scope.launch {
                val payload = body(
                    "order" to (order.trim().toIntOrNull() ?: 0),
                    "label" to label.trim(),
                    "type" to kind.ifBlank { "normal" },
                    "story_date" to storyDate.trim(),
                    "time_span" to span,
                    "description" to description,
                    "scene_numbers" to numbers,
                )
                val crud = ContinuityCrud(ctx.api, "story-days")
                val done = ctx.write {
                    if (day == null) crud.create(payload.dropEmpty()) else crud.update(day.id, payload)
                }
                saving = false
                if (done != null) {
                    onClose()
                    onSaved()
                }
            }
        },
        confirmEnabled = label.isNotBlank() && order.trim().toIntOrNull() != null,
        busy = saving,
    ) {
        FormGrid {
            TextInput(order, { order = it }, t("order"), FormHalf, number = true)
            TextInput(label, { label = it }, t("field_label"), placeholder = t("label_placeholder"))
            PickInput(
                value = kind,
                options = Characters.STORY_TYPES.map { it to t("story_type_$it") },
                onChange = { kind = it },
                label = t("type"),
            )
            // Typed, not picked: the service stores this as text, and a picker
            // would write a format the web does not read back.
            TextInput(storyDate, { storyDate = it }, t("story_date"), placeholder = DATE_HINT)
            PickInput(
                value = span,
                // The stored value IS the label, so both halves are the words.
                options = Characters.TIME_SPANS.map { t("time_span_$it") to t("time_span_$it") },
                onChange = { span = it },
                label = t("time_span"),
                placeholder = t("time_span_placeholder"),
                clearable = true,
            )
            TagInput(
                tokens = numbers,
                onChange = { numbers = it },
                label = t("scenes"),
                modifier = FormWide,
                placeholder = sceneState.scenes.take(HINT_SCENES).joinToString(", ") { it.str("scene_number") },
                help = t("story_day_scenes_help"),
            )
            TextInput(description, { description = it }, t("description"), FormWide, multiline = true)
        }
    }
}

/** The format the service stores a story date in, shown as the field's hint. */
private const val DATE_HINT = "Oct 5, 2026"

/** How many of the production's scene numbers the picker hints at. */
private const val HINT_SCENES = 4
