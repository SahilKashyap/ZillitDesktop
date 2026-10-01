package com.zillit.desktop.feature.costumesetsync.ui.screens

import androidx.compose.foundation.layout.Arrangement
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
import com.zillit.desktop.feature.costumesetsync.ui.MutedText
import com.zillit.desktop.feature.costumesetsync.ui.SectionCard
import com.zillit.desktop.feature.costumesetsync.ui.StatusBadge
import com.zillit.desktop.feature.costumesetsync.ui.t

private val CARD_WIDTH = 720.dp
private val STEPS = listOf("dates", "script", "breakdown")
private val GETS = listOf("breakdown", "costumes", "continuity")

/**
 * The tool's landing page for a Zillit project with nothing in Costumes & Set Sync yet (the web's `FirstRun`):
 * what the tool is, what setup will ask (dates → script → breakdown), what you get, and one "+ Create".
 * Someone who may not set it up sees the same page with who can, instead of the button. No tabs, no search.
 * [onChanged] re-reads the production record after a write; [onDone] opens the tool.
 */
@Composable
fun FirstRun(onChanged: () -> Unit, onDone: () -> Unit) {
    val ctx = LocalSync.current
    var open by remember { mutableStateOf(false) }
    val project = ctx.project.rec
    val allowed = ctx.project.canSetUp(ctx.canPost)
    val name = ctx.project.name
    val typeKey = when (project?.str("type")) {
        "EPISODIC" -> "csync_setup_series"
        "FEATURE" -> "csync_setup_feature"
        else -> null
    }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        SectionCard(modifier = Modifier.widthIn(max = CARD_WIDTH)) {
            Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg)) {
                ZillitText(t("csync_first_run_title"), style = ZillitTheme.typography.titleLarge)
                if (name.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm), verticalAlignment = Alignment.CenterVertically) {
                        ZillitText(name, style = ZillitTheme.typography.titleSmall)
                        typeKey?.let { StatusBadge("MUTED", t(it)) }
                    }
                }
                MutedText(t("csync_first_run_sub", "project" to name.ifEmpty { t("csync_production") }), maxLines = 3)
                ZillitText(t("csync_first_run_how").uppercase(), style = ZillitTheme.typography.labelSmall, color = ZillitTheme.colors.textMuted)
                STEPS.forEachIndexed { i, key ->
                    Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md), verticalAlignment = Alignment.CenterVertically) {
                        ZillitText("${i + 1}", style = ZillitTheme.typography.titleSmall, color = ZillitTheme.colors.accent)
                        Column {
                            ZillitText(t("csync_first_run_step_$key"), style = ZillitTheme.typography.titleSmall)
                            MutedText(t("csync_first_run_step_${key}_hint"), maxLines = 2)
                        }
                    }
                }
                ZillitText(t("csync_first_run_get").uppercase(), style = ZillitTheme.typography.labelSmall, color = ZillitTheme.colors.textMuted)
                GETS.forEach { key -> ZillitText("•  " + t("csync_first_run_get_$key")) }
                if (allowed) {
                    ZillitButton(t("csync_first_run_create"), onClick = { open = true }, leadingIcon = ZillitIcons.Add, modifier = Modifier.padding(top = ZillitTheme.spacing.sm))
                } else {
                    ZillitNotice(t("csync_first_run_no_rights"), tone = StatusTone.Progress)
                }
            }
        }
    }
    ProductionSetupWizard(open, project, edit = false, onClose = { open = false }, onChanged = onChanged, onDone = { open = false; onDone() })
}
