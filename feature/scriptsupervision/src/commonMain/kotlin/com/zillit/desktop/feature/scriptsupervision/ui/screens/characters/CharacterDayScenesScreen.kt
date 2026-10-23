package com.zillit.desktop.feature.scriptsupervision.ui.screens.characters

import androidx.compose.runtime.Composable
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitEmptyState
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.scriptsupervision.domain.CharacterSection
import com.zillit.desktop.feature.scriptsupervision.domain.Characters
import com.zillit.desktop.feature.scriptsupervision.ui.CardGrid
import com.zillit.desktop.feature.scriptsupervision.ui.MutedText
import com.zillit.desktop.feature.scriptsupervision.ui.SsPageHeader
import com.zillit.desktop.feature.scriptsupervision.ui.SsView
import com.zillit.desktop.feature.scriptsupervision.ui.cast
import com.zillit.desktop.feature.scriptsupervision.ui.count
import com.zillit.desktop.feature.scriptsupervision.ui.nav
import com.zillit.desktop.feature.scriptsupervision.ui.scenes
import com.zillit.desktop.feature.scriptsupervision.ui.t

/**
 * One day's scenes for one character — the web's `CharacterDayScenesScreen`.
 *
 * The day is a STORY day or a SHOOTING day depending on which section opened
 * it, and the two are filtered differently: a story day matches the label
 * ([Characters.scriptDayOf], which falls back to the story-day record that
 * lists the scene's number), while a shooting day matches the number on the
 * scene itself.
 */
@Composable
internal fun CharacterDayScenesScreen(id: String, section: CharacterSection, day: String) {
    val navState = nav()
    val castState = cast()
    val sceneState = scenes()
    val character = castState.character(id)
    val name = character?.str("name").orEmpty().ifBlank { t("this_character") }
    val script = section == CharacterSection.ScriptDays
    val mine = Characters.scenesFor(character, sceneState.scenes)
    val rows = if (script) {
        mine.filter { Characters.scriptDayOf(it, castState.storyDays) == day }
    } else {
        mine.filter { Characters.shootingDayOf(it) == day.toIntOrNull() }
    }
    val dayLabel = dayLabel(script, day)

    SsPageHeader(
        title = t("day_scenes_for", "day" to dayLabel, "name" to name),
        sub = count("day_scenes_count", rows.size),
        actions = {
            ZillitButton(
                text = t(section.labelKey),
                onClick = { navState.back() },
                variant = ButtonVariant.Secondary,
                leadingIcon = ZillitIcons.ArrowLeft,
            )
        },
    )
    if (rows.isEmpty()) {
        ZillitEmptyState(
            title = t("no_day_scenes_for", "name" to name, "day" to dayLabel),
            message = "",
            action = {
                ZillitButton(
                    text = t("back_to", "section" to t(section.labelKey)),
                    onClick = { navState.back() },
                    variant = ButtonVariant.Secondary,
                )
            },
        )
    } else {
        MutedText(count("day_scenes_count", rows.size))
        CardGrid(rows) { scene ->
            SceneCard(scene = scene, onOpen = { navState.go(SsView.CharacterScene(id, scene.id)) })
        }
    }
}

/**
 * What the day is called on this page.
 *
 * A story day shortens to "D3" — except [Characters.UNASSIGNED], which is not
 * a day at all. A shooting day reads "Day 4", and day 0 is "unscheduled",
 * which is the service's way of saying nothing has been scheduled yet.
 */
@Composable
private fun dayLabel(script: Boolean, day: String): String = when {
    script && day == Characters.UNASSIGNED -> t("unassigned")
    script -> Characters.shortDay(day)
    (day.toIntOrNull() ?: 0) > 0 -> "${t("day")} $day"
    else -> t("unscheduled")
}
