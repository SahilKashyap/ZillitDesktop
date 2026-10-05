package com.zillit.desktop.feature.costumesetsync.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.DroppedFile
import com.zillit.desktop.core.designsystem.component.ZillitCheckbox
import com.zillit.desktop.core.designsystem.component.ZillitDateField
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.component.externalFileDrop
import com.zillit.desktop.feature.costumesetsync.domain.DayKeys
import com.zillit.desktop.feature.costumesetsync.domain.PickedFile
import com.zillit.desktop.feature.costumesetsync.ui.LocalSync
import com.zillit.desktop.feature.costumesetsync.ui.t
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import kotlinx.coroutines.launch

private val SCRIPT_TYPES = setOf("fdx", "fountain", "txt", "pdf")
private val PREP_KEYS = listOf("prep_start_date", "prep_end_date", "prep_wrap_date")
private val WRAP_KEYS = listOf("wrap_date", "prep_wrap_date")
private val CARD = RoundedCornerShape(14.dp)

/** The numbered steps: the type (only when Zillit doesn't say it), the script, the estimated dates. */
@Composable
internal fun FirstRunSteps(state: FirstRunState) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        var number = 0
        if (state.askType) {
            StepCard(++number, FirstRunIcons.FilmSlate, t("csync_field_type"), required = true) { TypeTiles(state) }
        }
        StepCard(++number, FirstRunIcons.FileText, t("csync_setup_script_q"), required = true) { ScriptBody(state) }
        StepCard(
            ++number,
            FirstRunIcons.CalendarDots,
            t("csync_setup_dates_label"),
            required = false,
            aside = { DateSwitches(state) },
        ) { DatesBody(state) }
    }
}

/** A step: its number, icon, title and Required / Optional tag, anything for its right edge, then its body. */
@Composable
private fun StepCard(
    number: Int,
    icon: ImageVector,
    title: String,
    required: Boolean,
    aside: (@Composable RowScope.() -> Unit)? = null,
    body: @Composable () -> Unit,
) {
    val colors = ZillitTheme.colors
    Column(
        Modifier.fillMaxWidth().background(colors.surface, CARD).border(1.dp, colors.border, CARD)
            .padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            NumberDot(number)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                ZillitIcon(icon, tint = colors.accentText, size = 18.dp)
                ZillitText(
                    title,
                    style = ZillitTheme.typography.bodyLarge.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
                )
                Tag(if (required) t("csync_required") else str(S.dm_step9_optional), required)
            }
            aside?.let {
                Row(
                    Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                    content = it,
                )
            }
        }
        body()
    }
}

@Composable
private fun NumberDot(number: Int) {
    val colors = ZillitTheme.colors
    Box(
        Modifier.size(26.dp).background(colors.accentSoft, CircleShape)
            .border(1.dp, colors.accent.copy(alpha = 0.35f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        ZillitText(
            number.toString(),
            style = ZillitTheme.typography.label.copy(fontSize = 13.sp, fontWeight = FontWeight.Bold),
            color = colors.accentText,
        )
    }
}

/** The Required (accent) / Optional (muted) pill. */
@Composable
private fun Tag(text: String, required: Boolean) {
    val colors = ZillitTheme.colors
    val tint = if (required) colors.accentText else colors.textMuted
    val line = if (required) colors.accent.copy(alpha = 0.35f) else colors.border
    Box(
        Modifier.background(if (required) colors.accentSoft else Color.Transparent, CircleShape)
            .border(1.dp, line, CircleShape).padding(horizontal = 8.dp, vertical = 1.dp),
    ) {
        ZillitText(
            text,
            style = ZillitTheme.typography.labelSmall.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
            color = tint,
        )
    }
}

/** Feature / TV Series, side by side; the chosen one is lit. */
@Composable
private fun TypeTiles(state: FirstRunState) {
    val colors = ZillitTheme.colors
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        listOf(
            Triple("FEATURE", "csync_setup_feature", FirstRunIcons.FilmSlate),
            Triple("EPISODIC", "csync_setup_series", FirstRunIcons.Television),
        ).forEach { (value, label, icon) ->
            val on = state.type == value
            val shape = RoundedCornerShape(10.dp)
            val ink = if (on) colors.accentText else colors.textPrimary
            Row(
                Modifier.weight(1f).clip(shape).background(if (on) colors.accentSoft else colors.surface)
                    .border(1.dp, if (on) colors.accent else colors.border, shape)
                    .clickable { state.type = value }.padding(horizontal = 12.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ZillitIcon(icon, tint = ink, size = 22.dp)
                ZillitText(t(label), style = ZillitTheme.typography.bodyLarge, color = ink)
            }
        }
    }
}

/** The drop zone, then the revision name beside its label. */
@Composable
private fun ScriptBody(state: FirstRunState) {
    val colors = ZillitTheme.colors
    ScriptDrop(state)
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        ZillitText(
            t("csync_field_revision"),
            style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
            color = colors.textMuted,
        )
        ZillitTextField(
            state.revision,
            { state.revision = it },
            Modifier.width(260.dp),
            placeholder = t("csync_setup_revision_placeholder"),
        )
    }
}

/** One compact row: the upload mark, then what to do (or the chosen file). Click browses; a dropped file works too. */
@Composable
private fun ScriptDrop(state: FirstRunState) {
    val ctx = LocalSync.current
    val scope = rememberCoroutineScope()
    val colors = ZillitTheme.colors
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    var over by remember { mutableStateOf(false) }
    val picked = state.file
    val lit = over || hovered
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(if (picked != null || lit) colors.accentSoft else colors.canvas)
            .dashedBorder(dropLine(picked != null, lit), solid = picked != null)
            .externalFileDrop(true, { over = it }, { files -> files.firstOrNull()?.let { state.file = it.picked() } })
            .hoverable(hover)
            .clickable {
                scope.launch { ctx.host.pick(SCRIPT_TYPES, multiple = false).firstOrNull()?.let { state.file = it } }
            }
            .padding(horizontal = 20.dp, vertical = 18.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DropTile(picked != null)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            ZillitText(
                picked?.name ?: t("csync_setup_drop"),
                style = ZillitTheme.typography.bodyLarge.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            ZillitText(
                t(if (picked != null) "csync_setup_drop_change" else "csync_setup_drop_hint"),
                style = ZillitTheme.typography.bodyMedium,
                color = colors.textMuted,
            )
        }
    }
}

/** The border colour: a muted grey at rest, the accent when lit, a soft accent once a file is chosen. */
@Composable
private fun dropLine(picked: Boolean, lit: Boolean): Color {
    val colors = ZillitTheme.colors
    return when {
        lit -> colors.accent
        picked -> colors.accent.copy(alpha = 0.35f)
        else -> lerp(colors.surface, colors.textMuted, 0.45f)
    }
}

/** The 44dp tile: the upload mark, or the file's own once one is chosen. */
@Composable
private fun DropTile(picked: Boolean) {
    val colors = ZillitTheme.colors
    val shape = RoundedCornerShape(12.dp)
    Box(
        Modifier.size(44.dp).background(colors.surface, shape)
            .border(1.dp, if (picked) colors.accent.copy(alpha = 0.35f) else colors.border, shape),
        contentAlignment = Alignment.Center,
    ) {
        val glyph = if (picked) FirstRunIcons.FileText else FirstRunIcons.UploadSimple
        ZillitIcon(glyph, tint = if (picked) colors.accentText else colors.info, size = 24.dp)
    }
}

private fun DroppedFile.picked() = PickedFile(name, bytes, contentType)

/** A 1.5dp border, dashed while nothing is chosen, solid after. */
private fun Modifier.dashedBorder(color: Color, solid: Boolean): Modifier = drawBehind {
    drawRoundRect(
        color = color,
        cornerRadius = CornerRadius(12.dp.toPx()),
        style = Stroke(
            1.5.dp.toPx(),
            pathEffect = if (solid) null else PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx())),
        ),
    )
}

/** The extra rows' switches sit in the heading, so ticking one never moves them under the pointer. */
@Composable
private fun RowScope.DateSwitches(state: FirstRunState) {
    ZillitCheckbox(
        state.withPrep,
        { on -> state.toggle(on, PREP_KEYS) { state.withPrep = it } },
        label = t("csync_setup_add_prep"),
    )
    ZillitCheckbox(
        state.withWrap,
        { on -> state.toggle(on, WRAP_KEYS) { state.withWrap = it } },
        label = t("csync_setup_add_wrap"),
    )
}

/** Shoot dates always; Prep dates and Wrap dates when ticked. A later date never sits before its start. */
@Composable
private fun DatesBody(state: FirstRunState) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        DateRow(t("csync_setup_shoot_dates")) {
            DateCell(state, "start_date", t("csync_field_start_date"))
            DateCell(state, "end_date", t("csync_field_end_date"), after = "start_date")
        }
        if (state.withPrep) {
            DateRow(t("csync_setup_prep_dates")) {
                DateCell(state, "prep_start_date", t("csync_field_start_date"))
                DateCell(state, "prep_end_date", t("csync_field_end_date"), after = "prep_start_date")
            }
        }
        if (state.withWrap) {
            DateRow(t("csync_setup_wrap_dates")) {
                DateCell(state, "wrap_date", t("csync_field_shoot_wrap"), after = "start_date")
                if (state.withPrep) {
                    DateCell(state, "prep_wrap_date", t("csync_field_prep_wrap"), after = "prep_start_date")
                } else {
                    Box(Modifier.weight(1f))
                }
            }
        }
    }
}

/** A label column (Shoot / Prep / Wrap), then that row's two pickers. */
@Composable
private fun DateRow(title: String, fields: @Composable RowScope.() -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Bottom) {
        ZillitText(
            title,
            Modifier.width(110.dp).padding(bottom = 7.dp),
            style = ZillitTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
        )
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(14.dp), content = fields)
    }
}

/** One date picker; [after] is the key of the date it can't precede. */
@Composable
private fun RowScope.DateCell(state: FirstRunState, key: String, label: String, after: String? = null) {
    ZillitDateField(
        value = state.date(key),
        onValueChange = { state.setDate(key, it) },
        modifier = Modifier.weight(1f),
        label = label,
        placeholder = str(S.ah_select_date),
        minDate = after?.let { DayKeys.parse(state.date(it)) },
    )
}
