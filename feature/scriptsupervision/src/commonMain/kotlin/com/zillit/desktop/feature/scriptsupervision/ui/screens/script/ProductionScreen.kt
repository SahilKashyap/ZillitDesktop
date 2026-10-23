package com.zillit.desktop.feature.scriptsupervision.ui.screens.script

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitAvatar
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitSectionCard
import com.zillit.desktop.core.designsystem.component.ZillitSectionLabel
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.AhIcons
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.scriptsupervision.domain.Rec
import com.zillit.desktop.feature.scriptsupervision.ui.Await
import com.zillit.desktop.feature.scriptsupervision.ui.CardGrid
import com.zillit.desktop.feature.scriptsupervision.ui.LocalSs
import com.zillit.desktop.feature.scriptsupervision.ui.MutedText
import com.zillit.desktop.feature.scriptsupervision.ui.SsNav
import com.zillit.desktop.feature.scriptsupervision.ui.SsPage
import com.zillit.desktop.feature.scriptsupervision.ui.SsPageHeader
import com.zillit.desktop.feature.scriptsupervision.ui.SsTag
import com.zillit.desktop.feature.scriptsupervision.ui.SsView
import com.zillit.desktop.feature.scriptsupervision.ui.nav
import com.zillit.desktop.feature.scriptsupervision.ui.rememberResource
import com.zillit.desktop.feature.scriptsupervision.ui.t

/**
 * The production at a glance — the web's `ProductionScreen`.
 *
 * `GET /dashboard/summary/{projectId}` in one call: the counts behind every
 * tile, how much of the script has been filmed, and the recent changes. Each
 * tile opens the screen it counts, which is what makes this the tool's front
 * door rather than a report.
 *
 * The web shows two tiles for things the SERVICE does not have yet (sets,
 * members, billing) marked "soon", and one that is simply a different product
 * (billing). They are kept, marked the same way: a reader who has used the
 * standalone looks for them, and a missing tile reads as a broken port.
 */
@Composable
internal fun ProductionScreen(onOpen: (SsPage) -> Unit) {
    val ctx = LocalSs.current
    val navState = nav()
    val project = ctx.api.project().orEmpty()
    val summary = rememberResource(project) { api.get("/dashboard/summary/$project") }

    SsPageHeader(
        title = t("production"),
        actions = {
            ZillitButton(
                text = t("refresh"),
                onClick = { summary.reload(silent = true) },
                variant = ButtonVariant.Secondary,
                leadingIcon = ZillitIcons.Reload,
            )
        },
    )
    Await(summary) { answer ->
        val record = answer.rec
        val counts = record?.rec("counts")
        val filmed = record?.doubleOrNull("filmed_pct") ?: 0.0
        if (filmed > 0) MutedText(t("filming_progress") + ": " + filmed.toInt() + "%")

        ZillitSectionLabel(t("production"))
        CardGrid(STRUCTURE, minWidth = TILE_MIN) { tile ->
            ProductionTile(tile, counts) { open(tile, navState, onOpen) }
        }
        ZillitSectionLabel(t("manage"))
        CardGrid(MANAGE, minWidth = TILE_MIN) { tile ->
            ProductionTile(tile, counts) { open(tile, navState, onOpen) }
        }

        ZillitSectionLabel(t("recent_activity"))
        val activity = record?.recs("recent_activity").orEmpty()
        if (activity.isEmpty()) {
            MutedText(t("no_activity"))
        } else {
            activity.forEach { ActivityRow(it) }
        }
    }
}

/** Opens the screen a tile counts; a "soon" tile opens nothing, as on the web. */
private fun open(tile: ProductionTile, navState: SsNav, onOpen: (SsPage) -> Unit) {
    tile.page?.let { navState.goOn(it, SsView.Root, onOpen) }
}

@Composable
private fun ProductionTile(tile: ProductionTile, counts: Rec?, onClick: () -> Unit) {
    val count = tile.count?.let { counts?.int(it) ?: 0 }
    ZillitSectionCard(
        modifier = Modifier
            .fillMaxWidth()
            .let { if (tile.page != null) it.clickable(onClick = onClick) else it },
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ZillitIcon(tile.icon, size = TILE_ICON)
            Column(Modifier.weight(1f)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ZillitText(
                        text = listOfNotNull(count?.toString(), t(tile.labelKey)).joinToString(" "),
                        style = ZillitTheme.typography.titleSmall,
                    )
                    // The service has no such records yet; the web says so
                    // rather than hiding the tile.
                    if (tile.page == null) SsTag(t("soon"))
                }
                MutedText(t(tile.hintKey), maxLines = HINT_LINES)
            }
        }
    }
}

/** One recent change: who, how long ago, and what they did. */
@Composable
private fun ActivityRow(entry: Rec) {
    val who = entry.str("user_name").ifBlank { t("system") }
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.Top,
    ) {
        ZillitAvatar(name = who, size = ACTIVITY_AVATAR)
        Column(Modifier.weight(1f)) {
            ZillitText(who, style = ZillitTheme.typography.titleSmall)
            ZillitText(
                // The service writes a sentence; where it did not, the action
                // and the module it touched are the sentence.
                text = entry.str("description").ifBlank {
                    "${entry.str("action")} ${entry.str("module")}"
                },
                style = ZillitTheme.typography.bodySmall,
            )
            entry.str("module").takeIf { it.isNotBlank() }?.let { module ->
                SsTag(module.replace('_', ' '), actionTone(entry.str("action")))
            }
        }
    }
}

/** How a change reads: made, changed, gone, or brought back. */
private fun actionTone(action: String): StatusTone = when (action) {
    "create" -> StatusTone.Done
    "update" -> StatusTone.Progress
    "delete" -> StatusTone.Escalated
    else -> StatusTone.Neutral
}

/**
 * One tile: its words, its icon, the count it shows, and the page it opens.
 *
 * [page] null is the web's "soon": a tile the standalone had and this service
 * does not carry records for yet.
 */
private class ProductionTile(
    val labelKey: String,
    val hintKey: String,
    val icon: ImageVector,
    val count: String? = null,
    val page: SsPage? = null,
)

/** The structure half, in the web's order. */
private val STRUCTURE = listOf(
    ProductionTile("tile_characters", "tile_characters_hint", ZillitIcons.User, "characters", SsPage.Characters),
    ProductionTile("scenes", "tile_scenes_hint", ZillitIcons.File, "scenes", SsPage.Scenes),
    ProductionTile("tile_story_days", "tile_story_days_hint", ZillitIcons.Clock, "story_days", SsPage.StoryDays),
    ProductionTile("tile_sets", "tile_sets_hint", ZillitIcons.Pin),
    ProductionTile("tile_boxes", "tile_boxes_hint", AhIcons.Archive, "boxes", SsPage.Boxes),
    ProductionTile("tile_script", "tile_script_hint", AhIcons.Document, page = SsPage.ScriptImport),
)

/** The managing half. */
private val MANAGE = listOf(
    ProductionTile(
        "tile_shooting_days",
        "tile_shooting_days_hint",
        ZillitIcons.Clock,
        "shooting_days",
        SsPage.Coverage,
    ),
    ProductionTile("tile_actors", "tile_actors_hint", ZillitIcons.StarOutline, "actors", SsPage.Actors),
    ProductionTile(
        "tile_documents",
        "tile_documents_hint",
        ZillitIcons.Photo,
        "documents",
        SsPage.ContinuityPhotos,
    ),
    ProductionTile("tile_members", "tile_members_hint", ZillitIcons.User),
    ProductionTile("tile_backup", "tile_backup_hint", ZillitIcons.Download, page = SsPage.Export),
    ProductionTile("tile_billing", "tile_billing_hint", AhIcons.CostReport),
)

private const val HINT_LINES = 2
private val TILE_MIN = 300.dp
private val TILE_ICON = 28.dp
private val ACTIVITY_AVATAR = 28.dp
