package com.zillit.desktop.feature.costumesetsync.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontStyle
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.feature.costumesetsync.domain.Rec
import com.zillit.desktop.feature.costumesetsync.ui.ChipRow
import com.zillit.desktop.feature.costumesetsync.ui.LocalSync
import com.zillit.desktop.feature.costumesetsync.ui.MutedText
import com.zillit.desktop.feature.costumesetsync.ui.SectionCard
import com.zillit.desktop.feature.costumesetsync.ui.StatusBadge
import com.zillit.desktop.feature.costumesetsync.ui.body
import com.zillit.desktop.feature.costumesetsync.ui.t
import com.zillit.desktop.feature.costumesetsync.ui.tEnum
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

private const val DISMISSED_ALPHA = 0.55f

/** A cue's kind as a badge colour: conditions and notes warn, continuity and changes inform, the rest is quiet. */
private fun cueTone(kind: String): String = when (kind) {
    "CONDITION", "NOTE" -> "CLEANING"
    "CONTINUITY", "CHANGE" -> "SCHEDULED"
    else -> "RETIRED"
}

/**
 * Costume cues read out of the scene's script text: extract, then accept or dismiss
 * each one (or all the suggested ones at once). Dismissed cues stay behind a toggle.
 */
@Composable
internal fun CuesCard(scene: Rec, reload: () -> Unit) {
    val ctx = LocalSync.current
    val extraction = remember(ctx) { CueExtraction(ctx) }
    var showDismissed by remember { mutableStateOf(false) }
    val cues = scene.recs("cues")
    val suggested = cues.filter { it.str("status") == "SUGGESTED" }
    val dismissed = cues.filter { it.str("status") == "DISMISSED" }
    val visible = suggested + cues.filter { it.str("status") == "ACCEPTED" } + if (showDismissed) dismissed else emptyList()
    val extracting = extraction.progress.running
    val hasScript = scene.bool("has_script")
    val extract = {
        ctx.scope.launch {
            extraction.run(listOf(scene.id), cueEngineOf(ctx.meta))?.let { ctx.toast(it.localised(), false) }
            reload()
        }
        Unit
    }
    SectionCard(
        title = "✦ ${t("csync_cues_from_script")}",
        actions = {
            if (ctx.canPost && hasScript) {
                ZillitButton(
                    if (extracting) t("csync_reading") else if (cues.isNotEmpty()) t("csync_reextract") else t("csync_extract"),
                    onClick = extract, size = ButtonSize.Small, variant = ButtonVariant.Secondary, loading = extracting,
                )
            }
        },
    ) {
        CueProgressView(extraction.progress)
        if (cues.isEmpty() && !extracting) MutedText(t(if (hasScript) "csync_cues_press_extract" else "csync_cues_no_script"), maxLines = 3)
        if (suggested.isNotEmpty() && ctx.canPost) SuggestedBar(suggested, reload)
        visible.forEach { CueRow(it, reload) }
        if (dismissed.isNotEmpty()) {
            ZillitButton(
                t(if (showDismissed) "csync_hide_n_dismissed" else "csync_show_n_dismissed", "n" to dismissed.size),
                onClick = { showDismissed = !showDismissed }, variant = ButtonVariant.Tertiary, size = ButtonSize.Small,
            )
        }
        if (cues.isNotEmpty()) MutedText(t("csync_cues_note"), maxLines = 3)
    }
}

@Composable
private fun SuggestedBar(suggested: List<Rec>, reload: () -> Unit) {
    val ctx = LocalSync.current
    fun setAll(status: String) = ctx.launchWrite({ ctx.api.post("/cues/bulk", body("ids" to JsonArray(suggested.map { JsonPrimitive(it.id) }), "status" to status)) }) { reload() }
    Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm), verticalAlignment = Alignment.CenterVertically) {
        MutedText(plural("csync_cues_to_review", suggested.size, "n" to suggested.size), maxLines = 2)
        ZillitButton(t("csync_accept_all"), onClick = { setAll("ACCEPTED") }, size = ButtonSize.Small, variant = ButtonVariant.Secondary, leadingIcon = ZillitIcons.Check)
        ZillitButton(t("csync_dismiss_all"), onClick = { setAll("DISMISSED") }, size = ButtonSize.Small, variant = ButtonVariant.Tertiary)
    }
}

@Composable
private fun CueRow(c: Rec, reload: () -> Unit) {
    val ctx = LocalSync.current
    val status = c.str("status")
    fun set(next: String) = ctx.launchWrite({ ctx.api.patch("/cues/${c.id}", body("status" to next)) }) { reload() }
    Row(
        Modifier.fillMaxWidth().padding(vertical = ZillitTheme.spacing.xs).alpha(if (status == "DISMISSED") DISMISSED_ALPHA else 1f),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
            ChipRow {
                StatusBadge(cueTone(c.str("kind")), tEnum(c.str("kind")))
                val who = c.rec("character")
                when {
                    who != null && who.id.isNotEmpty() ->
                        ZillitText(who.str("name"), style = ZillitTheme.typography.labelSmall, color = ZillitTheme.colors.accent)
                    c.str("character_name").isNotEmpty() -> ZillitText(c.str("character_name"), style = ZillitTheme.typography.labelSmall)
                    else -> MutedText(t("csync_scene"))
                }
                if (status == "ACCEPTED") StatusBadge("READY", t("csync_accepted"))
                if (status == "DISMISSED") StatusBadge("RETIRED", t("csync_dismissed"))
                if (c.str("confidence") == "LOW") MutedText(t("csync_low_confidence"))
                StatusBadge("RETIRED", if (c.str("source") == "AI") "AI" else t("csync_reader"))
            }
            ZillitText(c.str("text"))
            c.str("quote").takeIf { it.isNotEmpty() }?.let {
                ZillitText("“$it”", style = ZillitTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic), color = ZillitTheme.colors.textSecondary, maxLines = 3)
            }
        }
        if (ctx.canPost) {
            Row {
                if (status != "ACCEPTED") ZillitButton("", onClick = { set("ACCEPTED") }, size = ButtonSize.Small, variant = ButtonVariant.Secondary, leadingIcon = ZillitIcons.Check)
                if (status != "DISMISSED") ZillitButton("", onClick = { set("DISMISSED") }, size = ButtonSize.Small, variant = ButtonVariant.Tertiary, leadingIcon = ZillitIcons.Close)
            }
        }
    }
}
