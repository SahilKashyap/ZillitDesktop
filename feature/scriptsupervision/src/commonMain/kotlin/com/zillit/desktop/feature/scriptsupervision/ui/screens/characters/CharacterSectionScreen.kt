package com.zillit.desktop.feature.scriptsupervision.ui.screens.characters

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitEmptyState
import com.zillit.desktop.core.designsystem.component.ZillitSectionCard
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.scriptsupervision.data.ContinuityCrud
import com.zillit.desktop.feature.scriptsupervision.domain.CharacterSection
import com.zillit.desktop.feature.scriptsupervision.domain.Characters
import com.zillit.desktop.feature.scriptsupervision.domain.Rec
import com.zillit.desktop.feature.scriptsupervision.domain.ScriptDayGroup
import com.zillit.desktop.feature.scriptsupervision.domain.ShootingDayGroup
import com.zillit.desktop.feature.scriptsupervision.ui.Await
import com.zillit.desktop.feature.scriptsupervision.ui.CardGrid
import com.zillit.desktop.feature.scriptsupervision.ui.Field
import com.zillit.desktop.feature.scriptsupervision.ui.LocalSs
import com.zillit.desktop.feature.scriptsupervision.ui.MutedText
import com.zillit.desktop.feature.scriptsupervision.ui.NumberChip
import com.zillit.desktop.feature.scriptsupervision.ui.SsPage
import com.zillit.desktop.feature.scriptsupervision.ui.SsPageHeader
import com.zillit.desktop.feature.scriptsupervision.ui.SsTag
import com.zillit.desktop.feature.scriptsupervision.ui.SsView
import com.zillit.desktop.feature.scriptsupervision.ui.cast
import com.zillit.desktop.feature.scriptsupervision.ui.count
import com.zillit.desktop.feature.scriptsupervision.domain.lookStatusTone
import com.zillit.desktop.feature.scriptsupervision.ui.nav
import com.zillit.desktop.feature.scriptsupervision.ui.rememberRows
import com.zillit.desktop.feature.scriptsupervision.ui.scenes
import com.zillit.desktop.feature.scriptsupervision.ui.t

/**
 * One of a character's sections — the web's `CharacterSectionScreen`.
 *
 * Five shapes behind one screen, as the web has them: the two day sections
 * group the character's own scenes (nothing is fetched for shooting days at
 * all — they come out of the scene list the shell already holds), the three
 * look departments list that department's looks for this character, and
 * wardrobe and props list the items assigned to them.
 *
 * `scenes` is NOT here: it has its own screen, because the hand-made scene
 * links are managed there.
 */
@Suppress("LongMethod") // One header, then the five shapes a section takes.
@Composable
internal fun CharacterSectionScreen(id: String, section: CharacterSection, onOpen: (SsPage) -> Unit) {
    val navState = nav()
    val castState = cast()
    val sceneState = scenes()
    val character = castState.character(id)
    val name = character?.str("name").orEmpty().ifBlank { t("this_character") }
    val label = t(section.labelKey)
    val mine = Characters.scenesFor(character, sceneState.scenes)

    SsPageHeader(
        title = t("section_for", "section" to label, "name" to name),
        sub = t("characters"),
        actions = {
            ZillitButton(
                text = name,
                onClick = { navState.back() },
                variant = ButtonVariant.Secondary,
                leadingIcon = ZillitIcons.ArrowLeft,
            )
            // Where this section's records are managed for the whole
            // production — the web's `MANAGE` map.
            section.managedOn()?.let { page ->
                ZillitButton(
                    text = t(if (section.dept != null) "manage_looks" else page.labelKey),
                    onClick = { navState.goOn(page, SsView.Root, onOpen) },
                    variant = ButtonVariant.Secondary,
                    leadingIcon = ZillitIcons.Edit,
                )
            }
        },
    )

    when (section) {
        CharacterSection.ScriptDays -> {
            val groups = Characters.groupScriptDays(mine, castState.storyDays)
            // Labelled days only, as the character page's tile counts them.
            MutedText(count("count_script_days", groups.count { it.label != Characters.UNASSIGNED }, name))
            if (groups.isEmpty()) {
                SectionEmpty(t("no_script_days_help"), t("open_story_timeline")) {
                    navState.goOn(SsPage.StoryDays, SsView.Root, onOpen)
                }
            } else {
                CardGrid(groups) { group ->
                    ScriptDayCard(group, name) {
                        navState.go(SsView.SectionDay(id, section, group.label))
                    }
                }
            }
        }

        CharacterSection.ShootingDays -> {
            val groups = Characters.groupShootingDays(mine)
            MutedText(count("count_shooting_days", groups.count { it.day > 0 }, name))
            if (groups.isEmpty()) {
                SectionEmpty(t("no_shooting_days_help"), t("open_scenes")) {
                    navState.goOn(SsPage.Scenes, SsView.Root, onOpen)
                }
            } else {
                CardGrid(groups) { group ->
                    ShootingDayCard(group, name) {
                        navState.go(SsView.SectionDay(id, section, group.day.toString()))
                    }
                }
            }
        }

        else -> SectionRecords(id, section, label, name, onOpen)
    }
}

/**
 * A section whose records come from the service: a look department, wardrobe
 * or props.
 *
 * All three are the same fetch with a different folder and a different filter,
 * which is how the web writes them (`fetchers`).
 */
@Composable
private fun SectionRecords(
    id: String,
    section: CharacterSection,
    label: String,
    name: String,
    onOpen: (SsPage) -> Unit,
) {
    val ctx = LocalSs.current
    val navState = nav()
    val sceneState = scenes()
    val folder = section.folder()
    val rows = rememberRows(id, section) {
        ContinuityCrud(ctx.api, folder).list(
            buildMap {
                put("character_id", id)
                section.dept?.let { put("department", it) }
            },
        )
    }

    Await(rows) { records ->
        MutedText(count("count_${section.route.replace('-', '_')}", records.size, name))
        if (records.isEmpty()) {
            val help = when {
                section.dept != null -> t("no_looks_help", "label" to label, "name" to name)
                else -> count("count_${section.route}", 0, name)
            }
            SectionEmpty(help, t(section.addLabelKey())) {
                section.managedOn()?.let { page -> navState.goOn(page, SsView.Root, onOpen) }
            }
        } else if (section.dept != null) {
            CardGrid(records) { look ->
                LookCard(look, sceneState.scenes) {
                    navState.go(
                        SsView.Look(look.id, characterId = id, department = section.dept.orEmpty()),
                    )
                }
            }
        } else {
            CardGrid(records) { item ->
                ItemCard(item, section) {
                    navState.go(
                        if (section == CharacterSection.Props) SsView.Prop(item.id) else SsView.WardrobeItem(item.id),
                    )
                }
            }
        }
    }
}

/** The service folder a section's records live in. */
private fun CharacterSection.folder(): String = when (this) {
    CharacterSection.Wardrobe -> "garments"
    CharacterSection.Props -> "props-items"
    // The three look departments are one folder, told apart by `department`.
    else -> "looks"
}

/** Where the whole production's copy of this section is managed. */
private fun CharacterSection.managedOn(): SsPage? = when (this) {
    CharacterSection.ScriptDays -> SsPage.StoryDays
    CharacterSection.ShootingDays -> SsPage.Scenes
    CharacterSection.Costume -> SsPage.CostumeLooks
    CharacterSection.Hair -> SsPage.HairLooks
    CharacterSection.Makeup -> SsPage.MakeupLooks
    CharacterSection.Wardrobe, CharacterSection.Props -> SsPage.PropsWardrobe
    CharacterSection.Scenes -> null
}

/** What the empty state's button offers to add. */
private fun CharacterSection.addLabelKey(): String = when (this) {
    CharacterSection.Costume -> "add_costume_look"
    CharacterSection.Hair -> "add_hair_look"
    CharacterSection.Makeup -> "add_makeup_look"
    else -> "new_item"
}

@Composable
private fun SectionEmpty(message: String, action: String, onAction: () -> Unit) {
    ZillitEmptyState(
        title = message,
        message = "",
        action = {
            ZillitButton(text = action, onClick = onAction, variant = ButtonVariant.Secondary)
        },
    )
}

/** A story day of this character's, with the scenes they are in on it. */
@Composable
private fun ScriptDayCard(
    group: ScriptDayGroup,
    name: String,
    onOpen: () -> Unit,
) {
    val kind = group.record?.str("type").orEmpty()
    ZillitSectionCard(modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            verticalAlignment = Alignment.Top,
        ) {
            NumberChip(
                caption = t("script_day"),
                value = if (group.label == Characters.UNASSIGNED) {
                    t("unassigned")
                } else {
                    Characters.shortDay(group.label)
                },
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
                if (kind.isNotBlank() && kind != "normal") SsTag(t("story_type_$kind"), StatusTone.Progress)
                listOf(group.record?.str("story_date"), group.record?.str("time_span"))
                    .filter { !it.isNullOrBlank() }
                    .joinToString(" · ")
                    .takeIf { it.isNotBlank() }
                    ?.let { MutedText(it) }
                Field(t("description"), group.record?.str("description").orEmpty())
                MutedText(count("scenes_with", group.scenes.size, name))
                ScenePills(group.scenes)
            }
        }
    }
}

/** A shooting day of this character's. */
@Composable
private fun ShootingDayCard(
    group: ShootingDayGroup,
    name: String,
    onOpen: () -> Unit,
) {
    ZillitSectionCard(modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            verticalAlignment = Alignment.Top,
        ) {
            NumberChip(
                caption = t("shooting"),
                value = if (group.day > 0) "${t("day")} ${group.day}" else "—",
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
                if (group.day == 0) SsTag(t("unscheduled"))
                MutedText(count("scenes_with", group.scenes.size, name))
                ScenePills(group.scenes)
            }
        }
    }
}

/** "Sc 12" for each scene — the web's scene pills. */
@Composable
internal fun ScenePills(sceneList: List<Rec>) {
    if (sceneList.isEmpty()) return
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        sceneList.forEach { scene -> SsTag("${t("scene_short")} ${scene.str("scene_number")}") }
    }
}

/** One look of this character's, in one department. */
@Composable
private fun LookCard(look: Rec, allScenes: List<Rec>, onOpen: () -> Unit) {
    val status = look.str("status").ifBlank { "planned" }
    val lookScenes = look.strings("scene_ids").mapNotNull { id -> allScenes.firstOrNull { it.id == id } }
    ZillitSectionCard(modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NumberChip(
                caption = t("dept_${look.str("department")}"),
                value = look.str("look_number").takeIf { it.isNotBlank() }?.let { "#$it" } ?: "—",
            )
            ZillitText(
                text = look.str("name").ifBlank { t("unnamed") },
                style = ZillitTheme.typography.titleSmall,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            SsTag(t("look_status_$status"), lookStatusTone(status))
        }
        Field(t("description"), look.str("description"))
        ScenePills(lookScenes)
    }
}

/** One wardrobe item or prop of this character's. */
@Composable
private fun ItemCard(item: Rec, section: CharacterSection, onOpen: () -> Unit) {
    ZillitSectionCard(modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ZillitText(
                text = item.str("name").ifBlank { t("unnamed") },
                style = ZillitTheme.typography.titleSmall,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            item.str("category").takeIf { it.isNotBlank() }?.let { category ->
                SsTag(t("${if (section == CharacterSection.Props) "asset_noun" else "garment"}_$category"))
            }
            item.int("quantity").takeIf { it > 1 }?.let { SsTag("×$it") }
        }
        Field(t("description"), item.str("description"))
        Field(t("size"), item.str("size"))
    }
}
