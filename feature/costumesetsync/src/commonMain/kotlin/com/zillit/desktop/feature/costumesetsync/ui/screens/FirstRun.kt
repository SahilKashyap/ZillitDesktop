package com.zillit.desktop.feature.costumesetsync.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.component.ZillitDivider
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.costumesetsync.ui.LocalSync
import com.zillit.desktop.feature.costumesetsync.ui.StatusBadge
import com.zillit.desktop.feature.costumesetsync.ui.t

private val CARD_WIDTH = 760.dp
private val STEPS = listOf("dates" to ZillitIcons.Calendar, "script" to ZillitIcons.Upload, "breakdown" to ZillitIcons.Grid)
private val GETS = listOf("breakdown" to ZillitIcons.Grid, "costumes" to ZillitIcons.Tag, "continuity" to ZillitIcons.Camera)

/**
 * The tool's landing page for a Zillit project with nothing in Costumes & Set Sync yet (the web's `FirstRun`):
 * a centred card with the tool's mark, title, project and sub-line; what setup will ask (dates -> script ->
 * breakdown) as three tiles; what you get as pills; and one "+ Create" under a rule. Someone who may not set it
 * up sees the same page with who can, instead of the button. [onChanged] re-reads the production record after a
 * write; [onDone] opens the tool.
 */
@Composable
fun FirstRun(onChanged: () -> Unit, onDone: () -> Unit) {
    val ctx = LocalSync.current
    val colors = ZillitTheme.colors
    var open by remember { mutableStateOf(false) }
    val project = ctx.project.rec
    val allowed = ctx.project.canSetUp(ctx.canPost)
    val name = ctx.project.name
    val typeKey = when (project?.str("type")) {
        "EPISODIC" -> "csync_setup_series"
        "FEATURE" -> "csync_setup_feature"
        else -> null
    }
    val shape = RoundedCornerShape(18.dp)
    Column(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Column(
            Modifier.widthIn(max = CARD_WIDTH).fillMaxWidth().background(colors.surface, shape).border(1.dp, colors.border, shape).padding(horizontal = 40.dp, vertical = 34.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    Modifier.size(64.dp).background(colors.accentSoft, RoundedCornerShape(18.dp)).border(1.dp, colors.accent.copy(alpha = 0.35f), RoundedCornerShape(18.dp)),
                    contentAlignment = Alignment.Center,
                ) { ZillitIcon(ZillitIcons.Tag, tint = colors.accentText, size = 30.dp) }
                ZillitText(
                    t("csync_first_run_title"),
                    style = ZillitTheme.typography.titleLarge.copy(fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold),
                    textAlign = TextAlign.Center,
                )
                if (name.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        ZillitText(name, style = ZillitTheme.typography.bodyMedium.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold))
                        typeKey?.let { StatusBadge("MUTED", t(it)) }
                    }
                }
                ZillitText(
                    t("csync_first_run_sub", "project" to name.ifEmpty { t("csync_production") }),
                    modifier = Modifier.widthIn(max = 560.dp),
                    style = ZillitTheme.typography.bodyMedium.copy(fontSize = 15.sp, lineHeight = 22.sp),
                    color = colors.textSecondary,
                    textAlign = TextAlign.Center,
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionLabel(t("csync_first_run_how"))
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    STEPS.forEachIndexed { i, (key, icon) -> StepTile(i + 1, icon, t("csync_first_run_step_$key"), t("csync_first_run_step_${key}_hint"), Modifier.weight(1f).fillMaxHeight()) }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionLabel(t("csync_first_run_get"))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    GETS.forEach { (key, icon) ->
                        Row(
                            Modifier.border(1.dp, colors.border, CircleShape).padding(horizontal = 12.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            ZillitIcon(icon, tint = colors.accentText, size = 16.dp)
                            ZillitText(t("csync_first_run_get_$key"), style = ZillitTheme.typography.bodyMedium.copy(fontSize = 13.sp))
                        }
                    }
                }
            }
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
                ZillitDivider()
                if (allowed) {
                    ZillitButton(t("csync_first_run_create"), onClick = { open = true }, leadingIcon = ZillitIcons.Add, modifier = Modifier.widthIn(min = 200.dp))
                } else {
                    ZillitNotice(t("csync_first_run_no_rights"), tone = StatusTone.Progress, icon = ZillitIcons.Info, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
    ProductionSetupWizard(open, project, edit = false, onClose = { open = false }, onChanged = onChanged, onDone = { open = false; onDone() })
}

@Composable
private fun SectionLabel(text: String) {
    ZillitText(text.uppercase(), style = ZillitTheme.typography.labelSmall.copy(fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.72.sp), color = ZillitTheme.colors.textMuted)
}

/** One setup step: an icon tile, the step's title and hint, its number in a ring at the top right. */
@Composable
private fun StepTile(n: Int, icon: ImageVector, title: String, hint: String, modifier: Modifier) {
    val colors = ZillitTheme.colors
    val shape = RoundedCornerShape(12.dp)
    Box(modifier.background(colors.surfaceSunken, shape).border(1.dp, colors.border, shape).padding(16.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.size(36.dp).background(colors.surface, RoundedCornerShape(10.dp)).border(1.dp, colors.border, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                ZillitIcon(icon, tint = colors.accentText, size = 20.dp)
            }
            ZillitText(title, style = ZillitTheme.typography.bodyMedium.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold))
            ZillitText(hint, style = ZillitTheme.typography.bodyMedium.copy(fontSize = 13.sp, lineHeight = 19.sp), color = colors.textSecondary)
        }
        Box(
            Modifier.align(Alignment.TopEnd).offset(x = 4.dp, y = (-4).dp).size(22.dp).background(colors.surface, CircleShape).border(1.dp, colors.border, CircleShape),
            contentAlignment = Alignment.Center,
        ) { ZillitText(n.toString(), style = ZillitTheme.typography.labelSmall.copy(fontSize = 12.sp, fontWeight = FontWeight.Bold), color = colors.textMuted) }
    }
}
