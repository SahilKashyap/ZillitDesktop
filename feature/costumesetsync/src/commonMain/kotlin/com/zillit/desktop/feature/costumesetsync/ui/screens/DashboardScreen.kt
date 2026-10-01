package com.zillit.desktop.feature.costumesetsync.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDivider
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.costumesetsync.data.SyncEvents
import com.zillit.desktop.feature.costumesetsync.domain.Rec
import com.zillit.desktop.feature.costumesetsync.domain.Tone
import com.zillit.desktop.feature.costumesetsync.domain.todayParam
import com.zillit.desktop.feature.costumesetsync.ui.Await
import com.zillit.desktop.feature.costumesetsync.ui.EmptyState
import com.zillit.desktop.feature.costumesetsync.ui.FieldGrid
import com.zillit.desktop.feature.costumesetsync.ui.FieldRow
import com.zillit.desktop.feature.costumesetsync.ui.LocalSync
import com.zillit.desktop.feature.costumesetsync.ui.MutedText
import com.zillit.desktop.feature.costumesetsync.ui.PageHead
import com.zillit.desktop.feature.costumesetsync.ui.ReadinessDot
import com.zillit.desktop.feature.costumesetsync.ui.RowTitle
import com.zillit.desktop.feature.costumesetsync.ui.SectionCard
import com.zillit.desktop.feature.costumesetsync.ui.SocketRefresh
import com.zillit.desktop.feature.costumesetsync.ui.StatCard
import com.zillit.desktop.feature.costumesetsync.ui.StatusBadge
import com.zillit.desktop.feature.costumesetsync.ui.rememberResource
import com.zillit.desktop.feature.costumesetsync.ui.t
import com.zillit.desktop.feature.costumesetsync.ui.tEnum
import kotlinx.coroutines.delay

private const val POLL_MS = 30_000L

/**
 * The department's day at a glance: counts, today's scenes with per-character
 * readiness, and whatever the service flagged as a priority. Re-read every 30 s
 * (rentals falling due and the day turning over send no socket event).
 */
@Composable
fun DashboardScreen() {
    val ctx = LocalSync.current
    val data = rememberResource { api.get("/dashboard", mapOf("date" to todayParam(now()))) }
    var emergency by remember { mutableStateOf(false) }

    LaunchedEffect(data) {
        while (true) {
            delay(POLL_MS)
            data.reload(silent = true)
        }
    }
    // Every figure here counts something another screen changes, so it watches the lot.
    SocketRefresh(
        SyncEvents.Costume + SyncEvents.Cleaning + SyncEvents.Scene + SyncEvents.Character +
            SyncEvents.Fitting + SyncEvents.Alteration + SyncEvents.Damage + SyncEvents.Missing,
    ) { data.reload(silent = true) }

    Await(data) { page ->
        val root = page.rec ?: Rec.Empty
        val counts = root.rec("counts") ?: Rec.Empty
        val byStatus = counts.rec("by_status")
        val project = ctx.project.rec
        val phase = project?.str("current_location")?.ifBlank { null } ?: project?.str("status")?.let(::tEnum).orEmpty()
        PageHead(
            title = ctx.project.name.ifBlank { t("csync_production") },
            sub = listOf("${t("csync_shooting_day")} ${project?.long("shooting_day") ?: 0}", phase, root.str("date"))
                .filter { it.isNotBlank() }.joinToString(" · "),
            actions = {
                if (ctx.canPost) {
                    ZillitButton(t("csync_emergency"), onClick = { emergency = true }, variant = ButtonVariant.Danger, leadingIcon = ZillitIcons.Siren)
                    ZillitButton(t("csync_costume"), onClick = { ctx.nav.go("costumes?new=1") }, variant = ButtonVariant.Secondary, leadingIcon = ZillitIcons.Add)
                }
            },
        )

        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
            val tile = Modifier.width(TILE_WIDTH)
            StatCard(t("csync_dash_characters"), counts.long("characters"), tile) { ctx.nav.go("characters") }
            StatCard(t("csync_dash_costumes"), counts.long("costumes"), tile) { ctx.nav.go("costumes") }
            StatCard(t("csync_dash_todays_scenes"), counts.long("todays_scenes"), tile) { ctx.nav.go("scenes") }
            StatCard(t("csync_dash_todays_costumes"), counts.long("todays_costumes"), tile, hint = t("csync_dash_across_changes"))
            StatCard(t("csync_dash_issued_today"), counts.long("issued_today"), tile, tone = Tone.Info)
            StatCard(t("csync_dash_returned_today"), counts.long("returned_today"), tile, tone = Tone.Ok)
            StatCard(t("csync_dash_cleaning"), counts.long("cleaning"), tile, tone = Tone.Info.takeIf { counts.long("cleaning") > 0 }) { ctx.nav.go("cleaning") }
            StatCard(t("csync_dash_alteration"), counts.long("alteration"), tile, tone = Tone.Warn.takeIf { counts.long("alteration") > 0 }) { ctx.nav.go("alterations") }
            StatCard(t("csync_dash_missing"), counts.long("missing"), tile, tone = Tone.Danger.takeIf { counts.long("missing") > 0 }) { ctx.nav.go("missing") }
            StatCard(t("csync_dash_damaged"), counts.long("damaged"), tile, tone = Tone.Danger.takeIf { counts.long("damaged") > 0 }) { ctx.nav.go("damages") }
        }

        Row(Modifier.fillMaxWidth().padding(top = ZillitTheme.spacing.lg), horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg), verticalAlignment = Alignment.Top) {
            SectionCard(
                title = t("csync_dash_todays_scenes"),
                modifier = Modifier.weight(1.4f),
                actions = { ZillitButton(t("csync_all_scenes"), onClick = { ctx.nav.go("scenes") }, variant = ButtonVariant.Tertiary, trailingIcon = ZillitIcons.ChevronRight) },
            ) {
                val scenes = root.recs("todays_scenes")
                if (scenes.isEmpty()) {
                    EmptyState(t("csync_dash_scenes_empty_title"), t("csync_dash_scenes_empty_hint"))
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) { scenes.forEach { SceneCard(it) } }
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg)) {
                SectionCard(title = t("csync_dash_priorities")) {
                    val priorities = root.recs("priorities")
                    if (priorities.isEmpty()) {
                        MutedText(t("csync_dash_priorities_clear"))
                    } else {
                        priorities.forEachIndexed { index, priority ->
                            if (index > 0) ZillitDivider()
                            val link = priority.str("link")
                            Row(
                                Modifier.fillMaxWidth().then(if (link.isNotBlank()) Modifier.clickable { ctx.nav.go(link) } else Modifier).padding(vertical = ZillitTheme.spacing.sm),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                            ) {
                                ReadinessDot(priority.str("severity"))
                                ZillitText(priority.str("text"), Modifier.weight(1f))
                                if (link.isNotBlank()) ZillitText("›", color = ZillitTheme.colors.textMuted)
                            }
                        }
                    }
                }
                SectionCard(title = t("csync_dash_by_status")) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
                        byStatus?.keys?.map { it to byStatus.long(it) }
                            ?.sortedByDescending { it.second }
                            ?.forEach { (status, n) ->
                                StatusBadge(status, "${tEnum(status)} · $n", Modifier.clickable { ctx.nav.go("costumes?status=$status") })
                            }
                    }
                }
                SectionCard(title = t("csync_dash_at_a_glance")) {
                    FieldGrid {
                        FieldRow(t("csync_dash_emergencies"), counts.long("emergencies_today").toString())
                        FieldRow(t("csync_dash_fittings"), counts.long("fittings_today").toString())
                        FieldRow(t("csync_dash_rentals_due"), counts.long("rentals_due").toString())
                    }
                }
            }
        }
    }
    EmergencyCleanDialog(open = emergency, onClose = { emergency = false }, onChanged = { data.reload(silent = true) })
}

/** One of today's scenes: its title and status, then each character's readiness. */
@Composable
private fun SceneCard(scene: Rec) {
    val ctx = LocalSync.current
    Column(
        Modifier.fillMaxWidth().clickable { ctx.nav.go("scenes/${scene.id}") }.padding(ZillitTheme.spacing.sm),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
            ReadinessDot(scene.str("level"))
            Column(Modifier.weight(1f)) {
                RowTitle(listOf(scene.str("number").takeIf { it.isNotBlank() }?.let { "${t("csync_sc")} $it" }.orEmpty(), scene.str("name")).filter { it.isNotBlank() }.joinToString(" · "))
                MutedText(listOf(scene.str("location"), tEnum(scene.str("time_of_day"))).filter { it.isNotBlank() }.joinToString(" · "))
            }
            StatusBadge(scene.str("status"))
        }
        scene.recs("characters").forEach { ch ->
            Row(Modifier.padding(start = ZillitTheme.spacing.lg), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
                ReadinessDot(ch.str("level"))
                Column(Modifier.weight(1f)) {
                    RowTitle(ch.str("name"))
                    MutedText(ch.str("change").ifBlank { t("csync_dash_no_change") })
                }
                StatusBadge(ch.str("level"))
            }
        }
    }
}

private val TILE_WIDTH = 168.dp
