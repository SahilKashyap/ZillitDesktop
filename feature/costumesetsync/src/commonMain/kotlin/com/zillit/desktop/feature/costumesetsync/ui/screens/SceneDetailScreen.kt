package com.zillit.desktop.feature.costumesetsync.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import com.zillit.desktop.core.designsystem.component.ZillitText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitChoiceChip
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.costumesetsync.data.SyncEvents
import com.zillit.desktop.feature.costumesetsync.domain.Rec
import com.zillit.desktop.feature.costumesetsync.domain.fmtDate
import com.zillit.desktop.feature.costumesetsync.ui.Await
import com.zillit.desktop.feature.costumesetsync.ui.ChipRow
import com.zillit.desktop.feature.costumesetsync.ui.EmptyState
import com.zillit.desktop.feature.costumesetsync.ui.LocalSync
import com.zillit.desktop.feature.costumesetsync.ui.MonoText
import com.zillit.desktop.feature.costumesetsync.ui.MutedText
import com.zillit.desktop.feature.costumesetsync.ui.PageHead
import com.zillit.desktop.feature.costumesetsync.ui.ReadinessDot
import com.zillit.desktop.feature.costumesetsync.ui.RowTitle
import com.zillit.desktop.feature.costumesetsync.ui.SectionCard
import com.zillit.desktop.feature.costumesetsync.ui.SocketRefresh
import com.zillit.desktop.feature.costumesetsync.ui.StatusBadge
import com.zillit.desktop.feature.costumesetsync.ui.SyncCtx
import com.zillit.desktop.feature.costumesetsync.ui.rememberResource
import com.zillit.desktop.feature.costumesetsync.ui.t
import com.zillit.desktop.feature.costumesetsync.ui.tEnum
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay

/** The reference re-reads a scene's readiness this often (a piece's status changes on other screens and devices). */
private const val READINESS_POLL_MS = 30_000L

/**
 * One scene: costume readiness character by character — with the look each one
 * wears assigned right here — the takes recorded on it, the costume cues read out
 * of its script text, and its open cleaning tickets (the web's `SceneDetailScreen`).
 */
@Composable
fun SceneDetailScreen(id: String) {
    val ctx = LocalSync.current
    val data = rememberResource(id) {
        coroutineScope {
            val scene = async { api.get("/scenes/$id") }
            val readiness = async { api.get("/scenes/$id/readiness") }
            val characters = async { api.get("/characters") }
            val s = (scene.await() as? ZillitResult.Success)?.data?.rec
            ZillitResult.Success(
                SceneDetailData(
                    s,
                    (readiness.await() as? ZillitResult.Success)?.data?.rec,
                    (characters.await() as? ZillitResult.Success)?.data?.rows.orEmpty(),
                ),
            )
        }
    }
    LaunchedEffect(data) {
        while (true) {
            delay(READINESS_POLL_MS)
            data.reload(silent = true)
        }
    }
    // The scene and its cues narrow to this scene; pieces, looks, cleaning and takes move readiness from anywhere.
    SocketRefresh(
        SyncEvents.Scene + SyncEvents.Cue,
        predicate = { f -> f.str("entity_id") == id || f.rec("data")?.str("scene_id") == id },
    ) {
        data.reload(silent = true)
    }
    SocketRefresh(
        SyncEvents.Costume + SyncEvents.Change + SyncEvents.Cleaning + SyncEvents.Continuity,
    ) { data.reload(silent = true) }
    Await(data) { loaded ->
        val scene = loaded.scene
        if (scene == null) EmptyState(t("csync_scene_not_found")) else SceneDetailBody(
            ctx,
            scene,
            loaded,
        ) { data.reload(silent = true) }
    }
}

@Composable
private fun SceneDetailBody(ctx: SyncCtx, scene: Rec, data: SceneDetailData, reload: () -> Unit) {
    var editOpen by remember { mutableStateOf(false) }
    val readiness = data.readiness
    val episodes = ctx.project.rec?.str("type") == "EPISODIC" || scene.str("episode").trim().isNotEmpty()
    val overall = readiness?.str("overall").orEmpty()
    val sceneTitle = "${t("csync_sc")} ${scene.str("number")}" +
        scene.str("name").takeIf { it.isNotEmpty() }?.let { " · $it" }.orEmpty()
    PageHead(
        title = sceneTitle,
        crumbs = "${t("csync_scenes_title")} / ${t("csync_sc")} ${scene.str("number")}",
        titleContent = { SceneTitle(sceneTitle, overall) },
        sub = sceneSub(scene, episodes),
        actions = {
            ZillitButton(
                t("csync_nav_continuity"), onClick = { ctx.nav.go("continuity?sceneId=${scene.id}") },
                variant = ButtonVariant.Secondary, leadingIcon = ZillitIcons.Draft,
            )
            if (ctx.canPost) ZillitButton(
                t("csync_edit"),
                onClick = { editOpen = true },
                variant = ButtonVariant.Secondary,
                leadingIcon = ZillitIcons.Edit,
            )
        },
    )
    scene.str("synopsis")
        .takeIf { it.isNotEmpty() }
        ?.let { ZillitNotice(it, Modifier.padding(bottom = ZillitTheme.spacing.md), tone = StatusTone.Progress) }

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.weight(1.4f)) { ReadinessCard(scene, readiness, data.characters, reload) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TakesCard(scene)
            CuesCard(scene, reload)
            CleaningCard(scene)
        }
    }
    SceneEditDialog(editOpen, scene, episodes, { editOpen = false }, reload)
}

@Composable
private fun SceneTitle(title: String, overall: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        ZillitText(
            title,
            Modifier.weight(1f, fill = false),
            style = ZillitTheme.typography.titleLarge.copy(
                fontSize = 24.sp,
                lineHeight = 30.sp,
                fontWeight = FontWeight.Bold,
            ),
            maxLines = 2,
        )
        if (overall.isNotEmpty()) {
            StatusBadge(overall, if (overall == "READY") t("csync_costume_ready") else tEnum(overall))
        }
    }
}

/** "Episode · INT/EXT · location · time · day · pages · revision · shoot date". */
private fun sceneSub(scene: Rec, episodes: Boolean): String = listOfNotNull(
    scene.str("episode").takeIf { episodes && it.isNotEmpty() }?.let { "${t("csync_field_episode")} $it" },
    scene.str("int_ext").ifEmpty { null },
    scene.str("location").ifEmpty { null },
    scene.str("time_of_day").takeIf { it.isNotEmpty() }?.let { tEnum(it) },
    scene.str("script_day").ifEmpty { null },
    scene.str("pages").takeIf { it.isNotEmpty() }?.let { "$it ${t("csync_pgs")}" },
    scene.str("revision").takeIf { it.isNotEmpty() }?.let { "${t("csync_rev")} $it" },
    if (scene.long("shoot_date") != 0L) fmtDate(scene.long("shoot_date")) else t("csync_unscheduled"),
).joinToString(" · ")

@Composable
private fun TakesCard(scene: Rec) {
    val ctx = LocalSync.current
    val byChar = scene.recs("continuity").groupBy { it.str("character_id") }
    SectionCard(
        title = t("csync_continuity_takes"),
        actions = {
            ZillitButton(
                t("csync_record_take"), onClick = { ctx.nav.go("continuity?sceneId=${scene.id}") },
                size = ButtonSize.Small, variant = ButtonVariant.Secondary,
            )
        },
    ) {
        if (byChar.isEmpty()) MutedText(t("csync_no_takes_recorded_yet"))
        byChar.forEach { (cid, recs) ->
            RowTitle(recs.first().rec("character")?.str("name").orEmpty())
            ChipRow {
                recs.forEach { r ->
                    ZillitChoiceChip(
                        "${t("csync_take")} ${r.str("take_number")}${if (r.str("notes").isNotEmpty()) " ✎" else ""}",
                        selected = false,
                        onClick = { ctx.nav.go("continuity?sceneId=${scene.id}&characterId=$cid") },
                    )
                }
            }
        }
    }
}

@Composable
private fun CleaningCard(scene: Rec) {
    val ctx = LocalSync.current
    val tickets = scene.recs("cleaning")
    SectionCard(title = t("csync_open_cleaning_tickets"), flush = tickets.isNotEmpty()) {
        if (tickets.isEmpty()) {
            MutedText(t("csync_none_period"))
            return@SectionCard
        }
        tickets.forEach { c ->
            Row(
                Modifier.fillMaxWidth().clickable { ctx.nav.go("cleaning/${c.id}") }.padding(
                    horizontal = ZillitTheme.spacing.lg,
                    vertical = ZillitTheme.spacing.md,
                ),
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ReadinessDot(
                    when {
                        c.bool("is_emergency") || c.str("priority") == "URGENT" -> "CRITICAL"
                        c.str("priority") == "HIGH" -> "WARNING"
                        else -> "INFO"
                    },
                )
                Column(Modifier.weight(1f)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
                        MonoText(c.rec("costume")?.str("asset_number").orEmpty())
                        RowTitle(c.rec("costume")?.str("name").orEmpty(), Modifier.weight(1f, fill = false))
                    }
                    MutedText(c.str("problem"))
                }
                StatusBadge(c.str("status"), tEnum(c.str("status")))
            }
        }
    }
}

