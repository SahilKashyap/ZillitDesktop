package com.zillit.desktop.feature.costumesetsync.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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

/** Height of one stacked per-character line, so the Nth cast number sits beside the Nth actor. */
private val ITEM_H = 40.dp

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

    Row(verticalAlignment = Alignment.Top) {
        if (input.expanded) TCell(Col.actions) { SaveCancel(row, canSave) }
        TCell(Col.dot) {}
        if (input.episodes) TCell(Col.episode) { CellField(d.episode, { set(d.copy(episode = it)) }, Col.episode - CELL_INSET, !row.busy, onEnter = save) }
        TCell(Col.scene) { NumberCell(row, save) }
        TCell(Col.day) { DayCell(row) }
        TCell(Col.location) { LocationCell(row, save) }
        TCell(Col.description) { CellField(d.synopsis, { set(d.copy(synopsis = it)) }, Col.description - CELL_INSET, !row.busy, onEnter = save) }
        PeopleCells(row, castProblem)
        TCell(Col.shootDate) { CellDate(d.shootDate, { set(d.copy(shootDate = it)) }, Col.shootDate - CELL_INSET, !row.busy) }
        TCell(Col.tail) { if (!input.expanded) SaveCancel(row, canSave) }
    }
}

private val CELL_INSET = 12.dp

private fun castNumberFor(d: SceneDraft, c: Rec): String = d.cast[c.id]?.castNumber ?: castNumberText(c)

private fun actorFor(d: SceneDraft, c: Rec): String = d.cast[c.id]?.actorId ?: actorIdOf(c)

@Composable
private fun SaveCancel(row: EditRowInput, canSave: Boolean) {
    if (!row.showButtons) return
    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
        ZillitButton(
            if (row.busy) t("csync_saving") else t("csync_save"),
            onClick = { row.actions.save(row.key) },
            size = ButtonSize.Small,
            enabled = canSave,
        )
        ZillitButton(t("csync_cancel"), onClick = { row.actions.cancel(row.key) }, variant = ButtonVariant.Tertiary, size = ButtonSize.Small, enabled = !row.busy)
    }
}

/** A scene keeps its number: it is what the script, schedule and call sheets match on. Only a new scene takes one here. */
@Composable
private fun NumberCell(row: EditRowInput, save: () -> Unit) {
    val d = row.draft
    if (row.isNew) {
        CellField(d.number, { row.onChange(d.copy(number = it)) }, Col.scene - CELL_INSET, !row.busy, row.error, save)
    } else {
        ZillitText(d.number, style = ZillitTheme.typography.titleSmall, maxLines = 1)
        row.error?.let { ZillitText(it, style = ZillitTheme.typography.bodySmall, color = ZillitTheme.colors.danger) }
    }
}

@Composable
private fun DayCell(row: EditRowInput) {
    val d = row.draft
    Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs), verticalAlignment = Alignment.Top) {
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

private val DAY_SELECT = 96.dp
private val DAY_FIELD = 72.dp

@Composable
private fun LocationCell(row: EditRowInput, save: () -> Unit) {
    val d = row.draft
    // Keep this row's INT/EXT selectable even when it is not in the configured list,
    // so an imported scene is never silently re-labelled on save.
    val options = (row.input.intExtOptions + listOfNotNull(d.intExt.takeIf { it.isNotEmpty() })).distinct().map { it to it }
    Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs), verticalAlignment = Alignment.Top) {
        CellPick(d.intExt, listOf("" to "—") + options, { row.onChange(d.copy(intExt = it)) }, INT_EXT_W, enabled = !row.busy)
        CellField(d.location, { row.onChange(d.copy(location = it)) }, LOCATION_W, !row.busy, onEnter = save)
    }
}

private val INT_EXT_W = 100.dp
private val LOCATION_W = 150.dp

/** Character, Cast # and Cast name: one stacked line per person, plus the Change they wear. */
@Composable
private fun PeopleCells(row: EditRowInput, castProblem: CastProblem?) {
    val d = row.draft
    val people = row.people
    val actorOptions = actorOptions(row)
    TCell(Col.character) {
        people.forEach { c -> Item { ZillitText(c.str("name"), maxLines = 1) } }
        if (people.isEmpty()) MutedText(t("csync_none"))
        ZillitButton(
            t("csync_add_remove"),
            onClick = { row.actions.principals(row.key) },
            variant = ButtonVariant.Secondary,
            size = ButtonSize.Small,
            enabled = !row.busy,
        )
    }
    TCell(Col.castNumber) {
        if (people.isEmpty()) MutedText("—")
        people.forEach { c ->
            Item {
                CellField(castNumberFor(d, c), { row.onChange(d.withCastNumber(c, it)) }, Col.castNumber - CELL_INSET, !row.busy, error = null)
            }
        }
        castProblem?.let { ZillitText(castProblemText(it), style = ZillitTheme.typography.bodySmall, color = ZillitTheme.colors.danger) }
    }
    TCell(Col.castName) {
        if (people.isEmpty()) MutedText("—")
        people.forEach { c ->
            Item {
                CellPick(actorFor(d, c), actorOptions, { row.onChange(d.withCastActor(c, it)) }, Col.castName - CELL_INSET, enabled = !row.busy)
            }
        }
    }
    TCell(Col.change) {
        // The look each character wears is set per row in the breakdown view, not in the scene editor.
        if (row.input.expanded) {
            people.forEach { c -> Item { MutedText(row.changeOf(c.id).ifEmpty { "—" }) } }
        } else {
            MutedText(row.changes.text.ifEmpty { "—" }, maxLines = 2)
        }
    }
}

@Composable
private fun Item(content: @Composable () -> Unit) {
    Row(Modifier.height(ITEM_H).padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) { content() }
}

/** The full actor list, plus the actors already attached to these people so the picker is never empty. */
private fun actorOptions(row: EditRowInput): List<Pair<String, String>> {
    val byId = LinkedHashMap<String, String>()
    row.input.actors.forEach { byId[it.id] = it.str("name") }
    row.people.forEach { c -> c.rec("actor")?.let { a -> if (a.id.isNotEmpty() && a.id !in byId) byId[a.id] = a.str("name") } }
    return listOf("" to "—") + byId.entries.map { it.key to it.value }
}
