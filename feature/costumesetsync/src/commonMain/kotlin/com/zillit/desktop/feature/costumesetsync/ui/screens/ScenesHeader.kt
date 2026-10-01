package com.zillit.desktop.feature.costumesetsync.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitChoiceChip
import com.zillit.desktop.core.designsystem.component.ZillitSearchField
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.costumesetsync.domain.Rec
import com.zillit.desktop.feature.costumesetsync.domain.SceneFilters
import com.zillit.desktop.feature.costumesetsync.domain.WHEN_KEYS
import com.zillit.desktop.feature.costumesetsync.domain.castOptions
import com.zillit.desktop.feature.costumesetsync.domain.episodesOf
import com.zillit.desktop.feature.costumesetsync.domain.latestRevisionOf
import com.zillit.desktop.feature.costumesetsync.domain.revisionsOf
import com.zillit.desktop.feature.costumesetsync.ui.FilterSelect
import com.zillit.desktop.feature.costumesetsync.ui.MutedText
import com.zillit.desktop.feature.costumesetsync.ui.PageHead
import com.zillit.desktop.feature.costumesetsync.ui.t

/** The Scene Breakdown's filter bar state: component state, not the URL, as the web (only the view is in the URL). */
@Stable
internal class SceneFilterState {
    var shoot by mutableStateOf("all")
    var revision by mutableStateOf("")
    var episode by mutableStateOf("")
    var query by mutableStateOf("")
    var characterId by mutableStateOf("")
    var page by mutableStateOf(1)

    fun toFilters() = SceneFilters(shoot, revision, episode, query, characterId)
}

/** The header's buttons are one bundle so the screen passes one thing. */
internal class HeaderActions(
    val setView: (String) -> Unit,
    val uploadScript: () -> Unit,
    val uploadSchedule: () -> Unit,
    val viewScript: () -> Unit,
    val viewSchedule: () -> Unit,
    val addToBreakdown: () -> Unit,
)

/** What the header needs to know besides the scenes. */
internal class HeaderState(val view: String, val canPost: Boolean, val busyEditing: Boolean, val hasScript: Boolean, val hasSchedule: Boolean)

/** The draft heading and sub-line, as the reference reads them. */
@Composable
internal fun ScenesHeader(scenes: List<Rec>, filters: SceneFilterState, state: HeaderState, actions: HeaderActions) {
    val revisions = revisionsOf(scenes)
    val draftTitle = filters.revision.ifEmpty {
        when {
            revisions.size == 1 -> revisions[0]
            revisions.isNotEmpty() -> t("csync_all_drafts")
            else -> t("csync_scenes_title")
        }
    }
    val drafts = if (revisions.isNotEmpty()) pluralMany("csync_n_drafts", revisions.size, "n" to revisions.size) else ""
    val sub = when {
        revisions.isNotEmpty() && filters.revision.isNotEmpty() -> "${t("csync_script_draft")} · $drafts"
        revisions.isNotEmpty() -> "$drafts · ${t("csync_latest")} ${latestRevisionOf(scenes)}"
        state.view == "breakdown" -> t("csync_breakdown_sub")
        else -> t("csync_scenes_sub")
    }
    PageHead(title = draftTitle, sub = sub, actions = { HeaderButtons(revisions, filters, state, actions) })
}

@Composable
private fun HeaderButtons(revisions: List<String>, filters: SceneFilterState, state: HeaderState, actions: HeaderActions) {
    if (revisions.isNotEmpty()) {
        FilterSelect(filters.revision, revisions.map { it to it }, t("csync_all_drafts"), { filters.revision = it; filters.page = 1 }, Modifier.width(REV_W))
    }
    // Editing keeps the bar to the job in hand: no uploads until it is put down.
    if (!state.canPost || state.busyEditing) return
    if (state.view == "breakdown") {
        ZillitButton(t("csync_collapse_scene_number"), onClick = { actions.setView("scenes") }, variant = ButtonVariant.Secondary, leadingIcon = ZillitIcons.Collapse)
    } else {
        ZillitButton(t("csync_expand_scene_number"), onClick = { actions.setView("breakdown") }, variant = ButtonVariant.Secondary, leadingIcon = ZillitIcons.Expand)
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        ZillitButton(t("csync_upload_script"), onClick = actions.uploadScript, leadingIcon = ZillitIcons.Upload)
        if (state.hasScript) ZillitButton(t("csync_view_uploaded_script"), onClick = actions.viewScript, variant = ButtonVariant.Tertiary, size = ButtonSize.Small, leadingIcon = ZillitIcons.Eye)
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        ZillitButton(t("csync_upload_schedule"), onClick = actions.uploadSchedule, variant = ButtonVariant.Secondary, leadingIcon = ZillitIcons.Calendar)
        ZillitText(t("csync_upload_schedule_hint"), style = ZillitTheme.typography.bodySmall, color = ZillitTheme.colors.danger)
        if (state.hasSchedule) ZillitButton(t("csync_view_uploaded_schedule"), onClick = actions.viewSchedule, variant = ButtonVariant.Tertiary, size = ButtonSize.Small, leadingIcon = ZillitIcons.Eye)
    }
    ZillitButton(t("csync_add_to_breakdown"), onClick = actions.addToBreakdown, variant = ButtonVariant.Secondary, leadingIcon = ZillitIcons.Add)
}

private val REV_W = 170.dp
private val EPISODE_W = 150.dp
private val CHARACTER_W = 190.dp

/** Search, episode and character filters, and the shoot-date chips (hidden while editing). */
@Composable
internal fun ScenesFilterBar(scenes: List<Rec>, episodes: Boolean, filters: SceneFilterState, editingMode: Boolean, end: @Composable () -> Unit) {
    val episodeList = episodesOf(scenes)
    val cast = castOptions(scenes)
    FlowRow(
        Modifier.fillMaxWidth().padding(bottom = ZillitTheme.spacing.md),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        ZillitSearchField(
            value = filters.query,
            onValueChange = { filters.query = it; filters.page = 1 },
            placeholder = t("csync_scenes_search"),
            modifier = Modifier.width(SEARCH_W),
        )
        if (episodes && episodeList.isNotEmpty()) {
            FilterSelect(
                filters.episode, episodeList.map { it to "${t("csync_field_episode")} $it" }, t("csync_all_episodes"),
                { filters.episode = it; filters.page = 1 }, Modifier.width(EPISODE_W),
            )
        }
        if (cast.isNotEmpty()) FilterSelect(filters.characterId, cast, t("csync_all_characters"), { filters.characterId = it; filters.page = 1 }, Modifier.width(CHARACTER_W))
        if (!editingMode) {
            Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
                WHEN_KEYS.forEach { key -> ZillitChoiceChip(t("csync_when_$key"), filters.shoot == key, { filters.shoot = key; filters.page = 1 }) }
            }
        }
        end()
    }
}

private val SEARCH_W = 260.dp

@Composable
internal fun SceneEmpty(filters: SceneFilterState, canPost: Boolean, onAdd: () -> Unit) {
    val title = when {
        filters.shoot == "today" -> t("csync_scenes_empty_today")
        filters.shoot == "upcoming" -> t("csync_scenes_empty_upcoming")
        filters.query.isNotEmpty() || filters.revision.isNotEmpty() -> t("csync_scenes_no_match")
        else -> t("csync_scenes_empty_title")
    }
    val hint = when (filters.shoot) {
        "today" -> t("csync_scenes_empty_today_hint")
        "upcoming" -> t("csync_scenes_empty_upcoming_hint")
        else -> t("csync_scenes_empty_hint")
    }
    com.zillit.desktop.feature.costumesetsync.ui.EmptyState(title, hint) {
        if (canPost && filters.shoot == "all" && filters.query.isEmpty() && filters.revision.isEmpty()) {
            ZillitButton(t("csync_add_scene"), onClick = onAdd, leadingIcon = ZillitIcons.Add)
        }
    }
}

@Composable
internal fun SingleToolbar(picked: Rec?, onEdit: () -> Unit, onDelete: () -> Unit, onCancel: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm), verticalAlignment = Alignment.CenterVertically) {
        if (picked != null) {
            MutedText("${t("csync_field_scene")} ${picked.str("number")}")
            ZillitButton(t("csync_edit"), onClick = onEdit)
            ZillitButton(t("csync_delete"), onClick = onDelete, variant = ButtonVariant.Danger)
        } else {
            MutedText(t("csync_select_a_scene"))
        }
        ZillitButton(t("csync_cancel"), onClick = onCancel, variant = ButtonVariant.Secondary)
    }
}

@Composable
internal fun EditAllToolbar(count: Int, problem: String?, busy: Boolean, saving: Boolean, onSaveAll: () -> Unit, onCancel: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm), verticalAlignment = Alignment.CenterVertically) {
        problem?.let { ZillitText(it, style = ZillitTheme.typography.bodySmall, color = ZillitTheme.colors.danger, modifier = Modifier.widthIn(max = PROBLEM_W)) }
        ZillitButton("${t("csync_save_all")} ($count)", onClick = onSaveAll, enabled = !busy && count > 0 && problem == null, loading = saving)
        ZillitButton(t("csync_cancel"), onClick = onCancel, variant = ButtonVariant.Secondary, enabled = !busy)
    }
}

private val PROBLEM_W = 280.dp
