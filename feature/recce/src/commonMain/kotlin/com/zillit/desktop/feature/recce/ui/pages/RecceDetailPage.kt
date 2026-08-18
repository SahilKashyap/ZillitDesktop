@file:Suppress("LongMethod", "CyclomaticComplexMethod") // Pages are linear layouts that branch on the record's shape.

package com.zillit.desktop.feature.recce.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitPageHeader
import com.zillit.desktop.core.designsystem.component.ZillitSectionCard
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.feature.recce.domain.RecceClock
import com.zillit.desktop.feature.recce.domain.RecceStop
import com.zillit.desktop.feature.recce.domain.StopKind
import com.zillit.desktop.feature.recce.ui.ErrorNotice
import com.zillit.desktop.feature.recce.ui.RecceEvent
import com.zillit.desktop.feature.recce.ui.RecceUiState

/** One recce: the important-information cells, the timeline, and the personnel. */
@Composable
internal fun RecceDetailPage(state: RecceUiState, onEvent: (RecceEvent) -> Unit) {
    val recce = state.selected
    val colors = ZillitTheme.colors
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(ZillitTheme.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        ZillitPageHeader(
            title = recce?.title?.ifBlank { "Untitled recce" } ?: "Recce",
            eyebrow = recce?.let {
                "${state.unitName(it.unit)}  ·  ${RecceClock.longDateLabel(it.dateMs)}".trim(' ', '·')
            },
            actions = {
                ZillitButton(text = "Back", onClick = { onEvent(RecceEvent.Back) }, variant = ButtonVariant.Tertiary)
                if (recce != null) {
                    ZillitStatusPill(
                        label = if (recce.isPublished) "Published · v${recce.version}" else "Draft",
                        tone = if (recce.isPublished) StatusTone.Done else StatusTone.Pending,
                    )
                    ZillitButton(
                        text = "Generate PDF",
                        onClick = { onEvent(RecceEvent.GeneratePdf) },
                        variant = ButtonVariant.Secondary,
                        loading = state.busy,
                    )
                    ZillitButton(
                        text = "Delete",
                        onClick = { onEvent(RecceEvent.Delete(recce.id)) },
                        variant = ButtonVariant.Danger,
                    )
                    ZillitButton(text = "Edit", onClick = { onEvent(RecceEvent.Edit(recce.id)) })
                }
            },
        )
        ErrorNotice(state, onEvent)
        if (recce == null) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { ZillitSpinner() }
            return@Column
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        ) {
            ZillitSectionCard(title = "Important information") {
                Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
                    InfoCell("Rendezvous", Modifier.weight(1f)) {
                        ZillitText(
                            text = RecceClock.hm(recce.rdv.timeMs).ifBlank { "—" },
                            style = ZillitTheme.typography.titleMedium,
                            color = colors.textPrimary,
                        )
                        PlaceLines(recce.rdv, onEvent)
                    }
                    InfoCell("Nearest station", Modifier.weight(1f)) {
                        ZillitText(text = recce.station.ifBlank { "—" }, style = ZillitTheme.typography.bodyMedium)
                    }
                    InfoCell("Weather", Modifier.weight(1f)) {
                        ZillitText(text = recce.weather.ifBlank { "—" }, style = ZillitTheme.typography.bodyMedium)
                    }
                    InfoCell("Stops", Modifier.weight(1f)) {
                        val stops = recce.itinerary
                        ZillitText(
                            text = "${recce.locationCount} locations",
                            style = ZillitTheme.typography.titleMedium,
                            color = colors.textPrimary,
                        )
                        if (stops.isNotEmpty()) {
                            val span = listOf(RecceClock.hm(stops.first().timeMs), RecceClock.hm(stops.last().timeMs))
                                .filter { it.isNotBlank() }.joinToString(" – ")
                            if (span.isNotBlank()) {
                                ZillitText(
                                    text = span,
                                    style = ZillitTheme.typography.bodySmall,
                                    color = colors.textMuted,
                                )
                            }
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
                ZillitSectionCard(
                    modifier = Modifier.weight(TIMELINE_WEIGHT),
                    title = "Schedule",
                    meta = recce.crewNote.takeIf { it.isNotBlank() },
                ) {
                    if (recce.itinerary.isEmpty()) {
                        ZillitText(
                            text = "No stops yet.",
                            style = ZillitTheme.typography.bodyMedium,
                            color = colors.textMuted,
                        )
                    }
                    recce.itinerary.forEachIndexed { index, stop ->
                        TimelineRow(index + 1, stop, onEvent)
                        if (stop.travel.isNotBlank()) {
                            Row(
                                modifier = Modifier.padding(
                                    start = TRAVEL_INDENT,
                                    top = ZillitTheme.spacing.xs,
                                    bottom = ZillitTheme.spacing.xs,
                                ),
                                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                            ) {
                                ZillitText(
                                    text = "→",
                                    style = ZillitTheme.typography.bodySmall,
                                    color = colors.textMuted,
                                )
                                ZillitText(
                                    text = stop.travel,
                                    style = ZillitTheme.typography.bodySmall,
                                    color = colors.textMuted,
                                )
                            }
                        }
                    }
                }
                ZillitSectionCard(modifier = Modifier.weight(1f), title = "Recce personnel") {
                    val people = recce.realPersonnel
                    if (people.isEmpty()) {
                        ZillitText(
                            text = "No personnel listed.",
                            style = ZillitTheme.typography.bodyMedium,
                            color = colors.textMuted,
                        )
                    }
                    people.forEach { person ->
                        Column(Modifier.padding(vertical = ZillitTheme.spacing.xs)) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                ZillitText(
                                    text = person.name.ifBlank { "—" },
                                    style = ZillitTheme.typography.bodyMedium,
                                    color = colors.textPrimary,
                                )
                                if (person.note.isNotBlank()) ZillitStatusPill(
                                    label = person.note,
                                    tone = StatusTone.Neutral,
                                )
                            }
                            val line = listOf(
                                person.role,
                                person.contact.takeIf { it != "Production" }.orEmpty(),
                                person.email,
                            )
                                .filter { it.isNotBlank() }.joinToString("  ·  ")
                            if (line.isNotBlank()) {
                                ZillitText(
                                    text = line,
                                    style = ZillitTheme.typography.bodySmall,
                                    color = colors.textMuted,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoCell(label: String, modifier: Modifier, content: @Composable () -> Unit) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
        ZillitText(
            text = label.uppercase(),
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textMuted,
        )
        content()
    }
}

@Composable
private fun PlaceLines(stop: RecceStop, onEvent: (RecceEvent) -> Unit) {
    val colors = ZillitTheme.colors
    if (stop.place.isNotBlank()) ZillitText(
        text = stop.place,
        style = ZillitTheme.typography.bodyMedium,
        color = colors.textPrimary,
    )
    if (stop.address.isNotBlank()) ZillitText(
        text = stop.address,
        style = ZillitTheme.typography.bodySmall,
        color = colors.textSecondary,
    )
    Row(
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        stop.w3wUrl?.let { url ->
            ZillitButton(
                text = "///${stop.w3w}",
                onClick = { onEvent(RecceEvent.OpenUrl(url)) },
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
            )
        }
        stop.mapsUrl?.let { url ->
            ZillitButton(
                text = "Map",
                onClick = { onEvent(RecceEvent.OpenUrl(url)) },
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
            )
        }
    }
}

@Composable
private fun TimelineRow(number: Int, stop: RecceStop, onEvent: (RecceEvent) -> Unit) {
    val colors = ZillitTheme.colors
    val node = when (stop.kind) {
        StopKind.Rendezvous, StopKind.Start -> BRAND
        StopKind.End -> colors.success
        StopKind.Lunch -> colors.textMuted
        StopKind.Continue -> colors.textSecondary
    }
    Row(
        modifier = Modifier.padding(vertical = ZillitTheme.spacing.xs),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        Box(
            modifier = Modifier
                .size(NODE)
                .background(node, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            ZillitText(
                text = if (stop.kind == StopKind.Lunch) "L" else number.toString(),
                style = ZillitTheme.typography.bodySmall,
                color = Color.White,
            )
        }
        Column(Modifier.width(TIME_WIDTH)) {
            ZillitText(
                text = RecceClock.hm(stop.timeMs).ifBlank { "—" },
                style = ZillitTheme.typography.bodyMedium,
                color = colors.textPrimary,
            )
            val end = RecceClock.hm(stop.endTimeMs)
            if (end.isNotBlank()) ZillitText(
                text = "to $end",
                style = ZillitTheme.typography.bodySmall,
                color = colors.textMuted,
            )
            ZillitText(text = stop.kind.wire, style = ZillitTheme.typography.bodySmall, color = node)
        }
        Column(Modifier.weight(1f)) {
            PlaceLines(stop, onEvent)
            if (stop.description.isNotBlank()) {
                ZillitText(
                    text = stop.description,
                    style = ZillitTheme.typography.bodySmall,
                    color = colors.textSecondary,
                )
            }
            if (stop.contact.isNotBlank()) {
                ZillitText(
                    text = "Contact: ${stop.contact}",
                    style = ZillitTheme.typography.bodySmall,
                    color = colors.textMuted,
                )
            }
        }
    }
}

/** The recce brand amber the web paints its Rendezvous and Start nodes with. */
private val BRAND = Color(0xFFF99300)
private val NODE = 26.dp
private val TIME_WIDTH = 72.dp
private val TRAVEL_INDENT = 40.dp
private const val TIMELINE_WEIGHT = 2f
