package com.zillit.desktop.feature.costumesetsync.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.costumesetsync.data.SyncEvents
import com.zillit.desktop.feature.costumesetsync.domain.Rec
import com.zillit.desktop.feature.costumesetsync.domain.Tone
import com.zillit.desktop.feature.costumesetsync.domain.longDay
import com.zillit.desktop.feature.costumesetsync.domain.todayParam
import com.zillit.desktop.feature.costumesetsync.ui.AutoFillGrid
import com.zillit.desktop.feature.costumesetsync.ui.Await
import com.zillit.desktop.feature.costumesetsync.ui.EmptyState
import com.zillit.desktop.feature.costumesetsync.ui.LocalSync
import com.zillit.desktop.feature.costumesetsync.ui.MutedText
import com.zillit.desktop.feature.costumesetsync.ui.Page
import com.zillit.desktop.feature.costumesetsync.ui.PageHead
import com.zillit.desktop.feature.costumesetsync.ui.ReadinessDot
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
@Suppress("LongMethod")
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
        Page {
            PageHead(
                title = ctx.project.name.ifBlank { t("csync_production") },
                sub = listOf(
                    "${t("csync_shooting_day")} ${project?.long("shooting_day") ?: 0}",
                    phase,
                    longDay(root.str("date"))
                )
                    .filter { it.isNotBlank() }.joinToString(" · "),
                actions = {
                    if (ctx.canPost) {
                        ZillitButton(
                            t("csync_emergency"),
                            onClick = { emergency = true },
                            variant = ButtonVariant.Danger,
                            leadingIcon = ZillitIcons.Siren
                        )
                        ZillitButton(
                            t("csync_costume"),
                            onClick = { ctx.nav.go("costumes?new=1") },
                            variant = ButtonVariant.Secondary,
                            leadingIcon = ZillitIcons.Add
                        )
                    }
                },
                bottomPadding = 0.dp,
            )

            // `.csync-stats--compact`: auto-fill columns of at least 120, 10 apart, every tile as tall as its row.
            val tiles = listOf<Tile>(
                Tile(t("csync_dash_characters"), counts.long("characters"), go = "characters"),
                Tile(t("csync_dash_costumes"), counts.long("costumes"), go = "costumes"),
                Tile(t("csync_dash_todays_scenes"), counts.long("todays_scenes"), go = "scenes"),
                Tile(
                    t("csync_dash_todays_costumes"),
                    counts.long("todays_costumes"),
                    hint = t("csync_dash_across_changes")
                ),
                Tile(t("csync_dash_issued_today"), counts.long("issued_today"), tone = Tone.Info),
                Tile(t("csync_dash_returned_today"), counts.long("returned_today"), tone = Tone.Ok),
                Tile(
                    t("csync_dash_cleaning"),
                    counts.long("cleaning"),
                    tone = Tone.Info.takeIf { counts.long("cleaning") > 0 },
                    go = "cleaning"
                ),
                Tile(
                    t("csync_dash_alteration"),
                    counts.long("alteration"),
                    tone = Tone.Warn.takeIf { counts.long("alteration") > 0 },
                    go = "alterations"
                ),
                Tile(
                    t("csync_dash_missing"),
                    counts.long("missing"),
                    tone = Tone.Danger.takeIf { counts.long("missing") > 0 },
                    go = "missing"
                ),
                Tile(
                    t("csync_dash_damaged"),
                    counts.long("damaged"),
                    tone = Tone.Danger.takeIf { counts.long("damaged") > 0 },
                    go = "damages"
                ),
            )
            AutoFillGrid(tiles.size, TILE_MIN, TILE_GAP) { i, cell ->
                val tile = tiles[i]
                StatCard(
                    tile.label,
                    tile.value,
                    cell,
                    tone = tile.tone,
                    hint = tile.hint,
                    compact = true,
                    onClick = tile.go?.let { target -> { ctx.nav.go(target) } }
                )
            }

            // `.csync-columns`: 1.25fr beside 1fr, 16 apart, each column as tall as its own content.
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.Top
            ) {
                SectionCard(
                    title = t("csync_dash_todays_scenes"),
                    modifier = Modifier.weight(1.25f),
                    actions = { TextLink(t("csync_all_scenes")) { ctx.nav.go("scenes") } },
                ) {
                    val scenes = root.recs("todays_scenes")
                    if (scenes.isEmpty()) {
                        EmptyState(t("csync_dash_scenes_empty_title"), t("csync_dash_scenes_empty_hint"))
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) { scenes.forEach { SceneCard(it) } }
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    SectionCard(title = t("csync_dash_priorities")) {
                        val priorities = root.recs("priorities")
                        if (priorities.isEmpty()) {
                            MutedText(t("csync_dash_priorities_clear"))
                        } else {
                            priorities.forEachIndexed { index, priority -> PriorityRow(priority, divided = index > 0) }
                        }
                    }
                    SectionCard(title = t("csync_dash_by_status")) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            byStatus?.keys?.map { it to byStatus.long(it) }
                                ?.sortedByDescending { it.second }
                                ?.forEach { (status, n) ->
                                    StatusBadge(
                                        status,
                                        "${tEnum(status)} · $n",
                                        Modifier.clickable { ctx.nav.go("costumes?status=$status") },
                                        large = true
                                    )
                                }
                        }
                    }
                    SectionCard(title = t("csync_dash_at_a_glance")) {
                        // `.csync-fields`: auto-fill columns of at least 180, 16 apart and 12 between rows.
                        val glance = listOf(
                            t("csync_dash_emergencies") to counts.long("emergencies_today").toString(),
                            t("csync_dash_fittings") to counts.long("fittings_today").toString(),
                            t("csync_dash_rentals_due") to counts.long("rentals_due").toString(),
                        )
                        AutoFillGrid(glance.size, 180.dp, 16.dp, rowGap = 12.dp) { i, cell ->
                            GlanceField(glance[i].first, glance[i].second, cell)
                        }
                    }
                }
            }
        }
    }
    EmergencyCleanDialog(open = emergency, onClose = { emergency = false }, onChanged = { data.reload(silent = true) })
}

private class Tile(
    val label: String,
    val value: Long,
    val tone: Tone? = null,
    val hint: String? = null,
    val go: String? = null
)

/** `.csync-textlink`: a quiet bold text button with a trailing caret. */
@Composable
private fun TextLink(text: String, onClick: () -> Unit) {
    val colors = ZillitTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        Modifier.clip(RoundedCornerShape(6.dp)).background(if (hovered) colors.surfaceHover else Color.Transparent)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick
            ).padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ZillitText(
            text,
            style = ZillitTheme.typography.bodyLarge.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
            maxLines = 1
        )
        ZillitIcon(ZillitIcons.ChevronRight, tint = colors.textPrimary, size = 14.dp)
    }
}

/** `.csync-priority--link`: dot, text, and a caret when the priority links somewhere; hairlines between rows. */
@Composable
private fun PriorityRow(priority: Rec, divided: Boolean) {
    val ctx = LocalSync.current
    val colors = ZillitTheme.colors
    val link = priority.str("link")
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    if (divided) Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))
    Row(
        Modifier.fillMaxWidth()
            .background(if (link.isNotBlank() && hovered) colors.surfaceHover else Color.Transparent)
            .then(if (link.isNotBlank()) Modifier.clickable(
                interactionSource = interaction,
                indication = null
            ) { ctx.nav.go(link) } else Modifier)
            .padding(horizontal = 4.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ReadinessDot(priority.str("severity"), pulse = priority.str("severity") == "CRITICAL")
        ZillitText(
            priority.str("text"),
            Modifier.weight(1f),
            style = ZillitTheme.typography.bodyLarge.copy(fontSize = 14.sp)
        )
        if (link.isNotBlank()) ZillitIcon(ZillitIcons.ChevronRight, tint = colors.textMuted, size = 15.dp)
    }
}

/** A label over a figure, as `.csync-field`: 11 upper-case muted label, 14 value. */
@Composable
private fun GlanceField(label: String, value: String, modifier: Modifier) {
    Column(modifier) {
        ZillitText(
            label.uppercase(),
            style = ZillitTheme.typography.labelSmall.copy(
                fontSize = 11.sp,
                letterSpacing = 0.04.em,
                fontWeight = FontWeight.Normal
            ),
            color = ZillitTheme.colors.textMuted,
            maxLines = 1
        )
        ZillitText(value, Modifier.padding(top = 2.dp), style = ZillitTheme.typography.bodyLarge.copy(fontSize = 14.sp))
    }
}

/** One of today's scenes (`.csync-scene`): a bordered card, its title and status, then each character's readiness. */
@Suppress("LongMethod")
@Composable
private fun SceneCard(scene: Rec) {
    val ctx = LocalSync.current
    val colors = ZillitTheme.colors
    val shape = RoundedCornerShape(12.dp)
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Column(
        Modifier.fillMaxWidth().clip(shape).background(colors.surface)
            .border(1.dp, if (hovered) colors.accent else colors.border, shape)
            .clickable(interactionSource = interaction, indication = null) { ctx.nav.go("scenes/${scene.id}") }
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Row(
            Modifier.padding(bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ReadinessDot(scene.str("level"), pulse = scene.str("level") == "MISSING")
            Column(Modifier.weight(1f)) {
                ZillitText(
                    listOf(
                        scene.str("number").takeIf { it.isNotBlank() }?.let { "${t("csync_sc")} $it" }.orEmpty(),
                        scene.str("name")
                    ).filter { it.isNotBlank() }.joinToString(" · "),
                    style = ZillitTheme.typography.bodyLarge.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                )
                ZillitText(
                    listOf(
                        scene.str("location"),
                        scene.str("time_of_day").takeIf { it.isNotBlank() }?.let(::tEnum).orEmpty()
                    ).filter { it.isNotBlank() }.joinToString(" · "),
                    style = ZillitTheme.typography.bodyLarge.copy(fontSize = 13.sp),
                    color = colors.textMuted,
                    maxLines = 1,
                )
            }
            StatusBadge(scene.str("status"))
        }
        scene.recs("characters").forEachIndexed { index, ch ->
            // `.csync-readinessline + .csync-readinessline`: a dashed rule between rows only.
            if (index > 0) DashedRule(colors.border)
            Row(
                Modifier.fillMaxWidth().padding(vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                ReadinessDot(ch.str("level"), pulse = ch.str("level") == "MISSING")
                Column(Modifier.weight(1f)) {
                    ZillitText(
                        ch.str("name"),
                        style = ZillitTheme.typography.bodyLarge.copy(
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold
                        ),
                        maxLines = 1
                    )
                    ZillitText(
                        ch.str("change").ifBlank { t("csync_dash_no_change") },
                        style = ZillitTheme.typography.bodyLarge.copy(fontSize = 13.sp),
                        color = colors.textMuted,
                        maxLines = 1
                    )
                }
                StatusBadge(ch.str("level"))
            }
        }
    }
}

@Composable
private fun DashedRule(color: Color) {
    Canvas(Modifier.fillMaxWidth().height(1.dp)) {
        drawLine(
            color,
            Offset(0f, 0f),
            Offset(size.width, 0f),
            strokeWidth = 1.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))
        )
    }
}

private val TILE_MIN = 120.dp
private val TILE_GAP = 10.dp
