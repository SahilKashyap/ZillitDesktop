package com.zillit.desktop.feature.costumesetsync.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.feature.costumesetsync.domain.CastProblem
import com.zillit.desktop.feature.costumesetsync.domain.CellText
import com.zillit.desktop.feature.costumesetsync.domain.DAY_PREFIXES
import com.zillit.desktop.feature.costumesetsync.domain.Rec
import com.zillit.desktop.feature.costumesetsync.domain.SceneDraft
import com.zillit.desktop.feature.costumesetsync.domain.actorIdOf
import com.zillit.desktop.feature.costumesetsync.domain.castNumberProblem
import com.zillit.desktop.feature.costumesetsync.domain.castNumberText
import com.zillit.desktop.feature.costumesetsync.domain.withCastActor
import com.zillit.desktop.feature.costumesetsync.domain.withCastNumber
import com.zillit.desktop.feature.costumesetsync.ui.MutedText
import com.zillit.desktop.feature.costumesetsync.ui.t

/** What one editor row needs: the draft, who is in it, and how to save it. */
@Suppress("LongParameterList")
internal class EditRowInput(
    val key: String,
    val draft: SceneDraft,
    val isNew: Boolean,
    /** The characters in the draft, in cast order. */
    val people: List<Rec>,
    val changeOf: (characterId: String) -> String,
    val changes: CellText,
    val input: TableInput,
    val busy: Boolean,
    val error: String?,
    /** Edit All shows no per-row Save/Cancel: one Save all covers every row. */
    val showButtons: Boolean,
    val onChange: (SceneDraft) -> Unit,
    val actions: TableActions,
)

/**
 * One stacked per-character line: the controls are 24dp (antd small); lines sit 16dp apart, as table rows with 8dp
 * padding do.
 */
private val ITEM_H = 24.dp
private val ITEM_GAP = 16.dp

/**
 * The inline editor for one scene — used by both Add and Edit, in both views.
 *
 * The scene's own fields are single cells; the people in it are stacked in the
 * Character / Cast # / Cast name cells, one line each, so cast numbers and actors
 * line up with their character. A cast number and the actor belong to the
 * CHARACTER, so a change here follows them into every scene.
 */
@Composable
internal fun SceneEditRow(row: EditRowInput) {
    val d = row.draft
    val input = row.input
    val castProblem = row.people.firstNotNullOfOrNull { castNumberProblem(castNumberFor(d, it)) }
    val canSave = d.number.trim().isNotEmpty() && row.error == null && castProblem == null && !row.busy
    val save = { if (canSave) row.actions.save(row.key) }
    val set = row.onChange

    Row(Modifier.background(ZillitTheme.colors.surfaceSunken), verticalAlignment = Alignment.Top) {
        if (input.expanded) TCell(actionsWidth(true)) { SaveCancel(row, canSave) }
        TCell(Col.dot) {}
        if (input.episodes) TCell(Col.episode) {
            CellField(d.episode, { set(d.copy(episode = it)) }, EPISODE_W, !row.busy, onEnter = save)
        }
        TCell(Col.scene) { NumberCell(row, save) }
        TCell(Col.day) { DayCell(row) }
        TCell(Col.location) { LocationCell(row, save) }
        TCell(Col.description) {
            CellField(d.synopsis, { set(d.copy(synopsis = it)) }, Dp.Unspecified, !row.busy, onEnter = save)
        }
        PeopleCells(row, castProblem)
        TCell(Col.shootDate) { CellDate(d.shootDate, { set(d.copy(shootDate = it)) }, Dp.Unspecified, !row.busy) }
        TCell(tailWidth(input.expanded)) { if (!input.expanded) SaveCancel(row, canSave) }
    }
}

private val EPISODE_W = 76.dp
private val NUMBER_W = 96.dp
private val CAST_NO_W = 76.dp

private fun castNumberFor(d: SceneDraft, c: Rec): String = d.cast[c.id]?.castNumber ?: castNumberText(c)

private fun actorFor(d: SceneDraft, c: Rec): String = d.cast[c.id]?.actorId ?: actorIdOf(c)

@Composable
private fun SaveCancel(row: EditRowInput, canSave: Boolean) {
    if (!row.showButtons) return
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        ZillitButton(
            if (row.busy) t("csync_saving") else t("csync_save"),
            onClick = { row.actions.save(row.key) },
            size = ButtonSize.Small,
            enabled = canSave,
        )
        ZillitButton(
            t("csync_cancel"),
            onClick = { row.actions.cancel(row.key) },
            variant = ButtonVariant.Tertiary,
            size = ButtonSize.Small,
            enabled = !row.busy
        )
    }
}

/**
 * A scene keeps its number: it is what the script, schedule and call sheets match on. Only a new scene takes one here.
 */
@Composable
private fun NumberCell(row: EditRowInput, save: () -> Unit) {
    val d = row.draft
    if (row.isNew) {
        CellField(
            d.number,
            { row.onChange(d.copy(number = it)) },
            NUMBER_W,
            !row.busy,
            row.error,
            save,
            autoFocus = true
        )
    } else {
        ZillitText(d.number, style = ZillitTheme.typography.titleSmall, maxLines = 1)
    }
    row.error?.let { ZillitText(it, style = ZillitTheme.typography.labelSmall, color = ZillitTheme.colors.danger) }
}

@Composable
private fun DayCell(row: EditRowInput) {
    val d = row.draft
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Top) {
        CellSelect(
            d.dayPrefix,
            listOf("" to t("csync_none")) + DAY_PREFIXES.map { it to it },
            { row.onChange(d.copy(dayPrefix = it)) },
            DAY_SELECT,
            !row.busy,
        )
        CellField(d.dayN, { row.onChange(d.copy(dayN = it)) }, DAY_FIELD, !row.busy)
    }
}

private val DAY_SELECT = 92.dp
private val DAY_FIELD = 66.dp

@Composable
private fun LocationCell(row: EditRowInput, save: () -> Unit) {
    val d = row.draft
    // Keep this row's INT/EXT selectable even when it is not in the configured list,
    // so an imported scene is never silently re-labelled on save.
    val options = (row.input.intExtOptions + listOfNotNull(d.intExt.takeIf { it.isNotEmpty() })).distinct().map {
        it to it
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Top) {
        CellPick(
            d.intExt,
            listOf("" to "—") + options,
            { row.onChange(d.copy(intExt = it)) },
            INT_EXT_W,
            placeholder = "—",
            enabled = !row.busy,
            searchable = false
        )
        CellField(
            d.location,
            { row.onChange(d.copy(location = it)) },
            Dp.Unspecified,
            !row.busy,
            onEnter = save,
            modifier = Modifier.weight(1f)
        )
    }
}

private val INT_EXT_W = 100.dp

/**
 * Character, Cast # and Cast name. In the breakdown (a row per character) the people stack one line each,
 * the first carrying Add/Remove; collapsed, the cell reads "A, B" with Add/Remove beside it and the Cast
 * cells stack a tiny name over each input.
 */
@Composable
private fun PeopleCells(row: EditRowInput, castProblem: CastProblem?) {
    val d = row.draft
    val people = row.people
    val actorOptions = actorOptions(row)
    val split = row.input.expanded && people.size > 1
    TCell(Col.character) { CharacterCell(row, split) }
    TCell(Col.castNumber) {
        if (people.isEmpty()) {
            MutedText("—")
        } else {
            PersonStack(people, split) { c ->
                CellField(
                    castNumberFor(d, c), { row.onChange(d.withCastNumber(c, it)) }, CAST_NO_W, !row.busy,
                    error = castNumberProblem(castNumberFor(d, c))?.let { castProblemText(it) }, numeric = true,
                )
            }
        }
        castProblem?.let {
            ZillitText(
                castProblemText(it),
                style = ZillitTheme.typography.labelSmall,
                color = ZillitTheme.colors.danger
            )
        }
    }
    TCell(Col.castName) {
        if (people.isEmpty()) {
            MutedText("—")
        } else {
            PersonStack(people, split) { c ->
                CellActorPick(actorFor(d, c), actorOptions, { row.onChange(d.withCastActor(c, it)) }, !row.busy)
            }
        }
    }
    TCell(Col.change) { ChangeCell(row, split) }
}

/**
 * The Character cell: one name per row (Add/Remove on the first) in the breakdown, "A, B" with Add/Remove beside it
 * collapsed.
 */
@Composable
private fun CharacterCell(row: EditRowInput, split: Boolean) {
    val people = row.people
    if (split) {
        Column(verticalArrangement = Arrangement.spacedBy(ITEM_GAP)) {
            people.forEachIndexed { i, c ->
                Item {
                    ZillitText(c.str("name"), Modifier.weight(1f, fill = false), style = CELL_TEXT, maxLines = 1)
                    // Adding or removing people is for the scene as a whole, so it sits on its first row only.
                    if (i == 0) AddRemove(row)
                }
            }
        }
        return
    }
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        itemVerticalAlignment = Alignment.CenterVertically
    ) {
        val names = people.joinToString(", ") { it.str("name") }
        if (names.isEmpty()) MutedText(t("csync_none")) else ZillitText(names, style = CELL_TEXT, maxLines = 2)
        AddRemove(row)
    }
}

/** The look each character wears is set per row in the breakdown view, not in the scene editor. */
@Composable
private fun ChangeCell(row: EditRowInput, split: Boolean) {
    val people = row.people
    if (split) {
        Column(verticalArrangement = Arrangement.spacedBy(ITEM_GAP)) {
            people.forEach { c -> Item { MutedText(row.changeOf(c.id).ifEmpty { "—" }) } }
        }
    } else {
        val text = if (row.input.expanded && people.size == 1) row.changeOf(people[0].id) else row.changes.text
        MutedText(text.ifEmpty { "—" }, maxLines = 2)
    }
}

private val CELL_TEXT @Composable get() = ZillitTheme.typography.bodyMedium

@Composable
private fun AddRemove(row: EditRowInput) {
    ZillitButton(
        t("csync_add_remove"),
        onClick = { row.actions.principals(row.key) },
        variant = ButtonVariant.Secondary,
        size = ButtonSize.Small,
        enabled = !row.busy,
    )
}

/**
 * Per person: a tiny name over the control when the cell holds several (the web's `csync-stack`), or lines in line with
 * the rows.
 */
@Composable
private fun PersonStack(people: List<Rec>, aligned: Boolean, control: @Composable (Rec) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(if (aligned) ITEM_GAP else 6.dp)) {
        people.forEach { c ->
            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                if (people.size > 1 && !aligned) {
                    ZillitText(
                        c.str("name"),
                        style = ZillitTheme.typography.labelSmall,
                        color = ZillitTheme.colors.textMuted,
                        maxLines = 1
                    )
                }
                control(c)
            }
        }
    }
}

@Composable
private fun Item(content: @Composable () -> Unit) {
    Row(
        Modifier.height(ITEM_H),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        content()
    }
}

/** The full actor list, plus the actors already attached to these people so the picker is never empty. */
private fun actorOptions(row: EditRowInput): List<Pair<String, String>> {
    val byId = LinkedHashMap<String, String>()
    row.input.actors.forEach { byId[it.id] = it.str("name") }
    row.people.forEach { c ->
        c.rec("actor")?.let { a -> if (a.id.isNotEmpty() && a.id !in byId) byId[a.id] = a.str("name") }
    }
    return byId.entries.map { it.key to it.value }
}
