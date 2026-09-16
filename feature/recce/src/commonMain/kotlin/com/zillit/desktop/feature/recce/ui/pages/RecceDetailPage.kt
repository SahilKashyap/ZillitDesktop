@file:Suppress("LongMethod", "TooManyFunctions") // Pages are linear layouts that branch on the record's shape.

package com.zillit.desktop.feature.recce.ui.pages

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitAvatar
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTooltip
import com.zillit.desktop.core.designsystem.component.copyTextToClipboard
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.media.decodeImageBitmap
import com.zillit.desktop.feature.recce.domain.Recce
import com.zillit.desktop.feature.recce.domain.RecceClock
import com.zillit.desktop.feature.recce.domain.RecceStop
import com.zillit.desktop.feature.recce.domain.StopKind
import com.zillit.desktop.feature.recce.ui.RecceEvent
import com.zillit.desktop.feature.recce.ui.RecceUiState
import com.zillit.desktop.feature.recce.ui.RouteMapState
import com.zillit.desktop.feature.recce.ui.components.DescLabel
import com.zillit.desktop.feature.recce.ui.components.MutedText
import com.zillit.desktop.feature.recce.ui.components.RecceBody
import com.zillit.desktop.feature.recce.ui.components.RecceCanvas
import com.zillit.desktop.feature.recce.ui.components.RecceCard
import com.zillit.desktop.feature.recce.ui.components.RecceCardHead
import com.zillit.desktop.feature.recce.ui.components.RecceColors
import com.zillit.desktop.feature.recce.ui.components.RecceIcons
import com.zillit.desktop.feature.recce.ui.components.RecceLink
import com.zillit.desktop.feature.recce.ui.components.RecceStatusTag
import com.zillit.desktop.feature.recce.ui.components.RecceTag
import com.zillit.desktop.feature.recce.ui.components.RecceToolHeader
import com.zillit.desktop.feature.recce.ui.components.TagKind
import com.zillit.desktop.feature.recce.ui.components.UnitTag
import com.zillit.desktop.feature.recce.ui.components.W3WChip
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * One recce — the web's `RecceDetail`: the header with its status and
 * actions, the unit and date meta row, the Important-information cells, the
 * schedule timeline (W3W + map links + travel notes), the route picture and
 * the personnel list.
 */
@Composable
internal fun RecceDetailPage(state: RecceUiState, onEvent: (RecceEvent) -> Unit) {
    val recce = state.selected
    Column {
        RecceToolHeader(
            title = recce?.title?.ifBlank { "Untitled recce" } ?: "Recce",
            onBack = { onEvent(RecceEvent.Back) },
            titleExtra = { recce?.let { RecceStatusTag(it) } },
            actions = {
                if (recce != null) {
                    ZillitButton(
                        text = "Generate PDF",
                        onClick = { onEvent(RecceEvent.GeneratePdf) },
                        variant = ButtonVariant.Secondary,
                        leadingIcon = ZillitIcons.Download,
                    )
                    ZillitButton(
                        text = "Print",
                        onClick = { onEvent(RecceEvent.PrintPdf) },
                        variant = ButtonVariant.Secondary,
                        leadingIcon = ZillitIcons.Print,
                        loading = state.busy,
                    )
                    ZillitButton(
                        text = "Delete",
                        onClick = { onEvent(RecceEvent.Delete(recce.id)) },
                        variant = ButtonVariant.Danger,
                        leadingIcon = ZillitIcons.Trash,
                    )
                    ZillitButton(
                        text = "Edit",
                        onClick = { onEvent(RecceEvent.Edit(recce.id)) },
                        leadingIcon = ZillitIcons.Edit,
                    )
                }
            },
        )
        if (recce == null) {
            RecceCanvas { ZillitSpinner() }
            return@Column
        }
        RecceBody {
            ErrorNotice(state, onEvent)
            MetaRow(state, recce)
            Spacer(Modifier.height(18.dp))
            ImportantInformation(recce, onEvent)
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.Top) {
                ScheduleCard(recce, onEvent, Modifier.weight(1f))
                Column(Modifier.width(RAIL_WIDTH), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    RouteCard(state.routeMap)
                    PersonnelCard(recce)
                }
            }
        }
    }
}

@Composable
private fun MetaRow(state: RecceUiState, recce: Recce) {
    val colors = ZillitTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        val unit = state.unitName(recce.unit).localised()
        if (unit.isNotBlank()) UnitTag(unit)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ZillitIcon(icon = ZillitIcons.Calendar, tint = colors.textMuted, size = 15.dp)
            MutedText(RecceClock.longDateLabel(recce.dateMs).ifBlank { "No date set" })
        }
    }
}

@Composable
private fun ImportantInformation(recce: Recce, onEvent: (RecceEvent) -> Unit) {
    RecceCard {
        RecceCardHead("Important information")
        Row(Modifier.height(IntrinsicSize.Min)) {
            InfoCell(ZillitIcons.Clock, "Rendezvous", Modifier.weight(1f)) {
                BigValue(RecceClock.hm(recce.rdv.timeMs).ifBlank { "—" })
                if (recce.rdv.place.isNotBlank()) SubValue(recce.rdv.place)
                if (recce.rdv.address.isNotBlank()) SubValue(recce.rdv.address)
                LocationLinks(recce.rdv, small = true, onEvent = onEvent, topPadding = 8.dp)
            }
            CellDivider()
            InfoCell(RecceIcons.Train, "Nearest station", Modifier.weight(1f)) {
                BigValue(recce.station.ifBlank { "—" }, size = 15.sp)
            }
            CellDivider()
            InfoCell(RecceIcons.Cloud, "Weather", Modifier.weight(1f)) {
                BigValue(recce.weather.ifBlank { "—" }, size = 15.sp)
            }
            CellDivider()
            InfoCell(RecceIcons.MapPin, "Stops", Modifier.weight(1f)) {
                BigValue("${recce.locationCount} locations", size = 15.sp)
                val stops = recce.itinerary
                if (stops.isNotEmpty()) {
                    val span = listOf(RecceClock.hm(stops.first().timeMs), RecceClock.hm(stops.last().timeMs))
                        .filter { it.isNotBlank() }
                        .joinToString(" – ")
                    if (span.isNotBlank()) SubValue(span)
                }
            }
        }
    }
}

@Composable
private fun InfoCell(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    modifier: Modifier,
    content: @Composable () -> Unit,
) {
    Column(modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        DescLabel(icon, label)
        Spacer(Modifier.height(5.dp))
        content()
    }
}

@Composable
private fun CellDivider() {
    Box(Modifier.fillMaxHeight().width(1.dp).background(ZillitTheme.colors.divider))
}

@Composable
private fun BigValue(text: String, size: androidx.compose.ui.unit.TextUnit = 17.sp) {
    ZillitText(
        text = text,
        style = ZillitTheme.typography.titleMedium.copy(fontSize = size, fontWeight = FontWeight.Bold),
        color = ZillitTheme.colors.textPrimary,
    )
}

@Composable
private fun SubValue(text: String) {
    MutedText(text)
}

/** The W3W chip and the Map link a placed stop carries. */
@Composable
private fun LocationLinks(
    stop: RecceStop,
    small: Boolean,
    onEvent: (RecceEvent) -> Unit,
    topPadding: androidx.compose.ui.unit.Dp = 0.dp,
) {
    val w3w = stop.w3wUrl
    val maps = stop.mapsUrl
    if (w3w == null && maps == null) return
    Row(
        modifier = Modifier.padding(top = topPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        w3w?.let { W3WChip(words = stop.w3w, onOpen = { onEvent(RecceEvent.OpenUrl(it)) }, small = small) }
        maps?.let { RecceLink(text = "Map", onClick = { onEvent(RecceEvent.OpenUrl(it)) }) }
    }
}

// ------------------------------------------------------------------ schedule

@Composable
private fun ScheduleCard(recce: Recce, onEvent: (RecceEvent) -> Unit, modifier: Modifier) {
    RecceCard(modifier) {
        RecceCardHead("Schedule")
        Column(Modifier.padding(horizontal = 24.dp, vertical = 22.dp)) {
            if (recce.crewNote.isNotBlank()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(ZillitTheme.colors.accentSoft)
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ZillitIcon(icon = ZillitIcons.Info, tint = ZillitTheme.colors.accentText, size = 16.dp)
                    ZillitText(
                        text = recce.crewNote,
                        style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = ZillitTheme.colors.accentText,
                    )
                }
                Spacer(Modifier.height(18.dp))
            }
            if (recce.itinerary.isEmpty()) MutedText("No stops on this recce yet.")
            recce.itinerary.forEachIndexed { index, stop ->
                TimelineStop(index + 1, stop, last = index == recce.itinerary.lastIndex, onEvent)
            }
        }
    }
}

/** One stop on the rail: the time column, the node and its line, the stop card, the travel note. */
@Composable
private fun TimelineStop(number: Int, stop: RecceStop, last: Boolean, onEvent: (RecceEvent) -> Unit) {
    val colors = ZillitTheme.colors
    val kindColor = RecceColors.kind(stop.kind)
    Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        Column(Modifier.width(TIME_WIDTH).padding(top = 2.dp), horizontalAlignment = Alignment.End) {
            ZillitText(
                text = RecceClock.hm(stop.timeMs).ifBlank { "—" },
                style = ZillitTheme.typography.titleMedium.copy(fontSize = 17.sp, fontWeight = FontWeight.ExtraBold),
                color = colors.textPrimary,
                textAlign = TextAlign.End,
            )
            val end = RecceClock.hm(stop.endTimeMs)
            if (end.isNotBlank()) {
                ZillitText(
                    text = "– $end",
                    style = ZillitTheme.typography.bodyLarge,
                    color = colors.textSecondary,
                    textAlign = TextAlign.End,
                )
            }
            // "RENDEZVOUS" is the widest kind; the column is sized so it never wraps.
            ZillitText(
                text = stop.kind.wire.uppercase(),
                style = ZillitTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 0.3.sp,
                ),
                color = kindColor,
                textAlign = TextAlign.End,
                maxLines = 1,
            )
        }
        Column(Modifier.fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
            StopNode(number, stop.kind)
            if (!last) {
                Box(
                    Modifier
                        .padding(vertical = 4.dp)
                        .width(2.dp)
                        .weight(1f)
                        .heightIn(min = 18.dp)
                        .background(colors.border),
                )
            }
        }
        Column(Modifier.weight(1f).padding(bottom = if (last) 0.dp else 22.dp)) {
            StopCard(stop, onEvent)
            if (stop.travel.isNotBlank()) {
                Row(
                    modifier = Modifier.padding(top = 10.dp, start = 2.dp),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ZillitIcon(icon = RecceIcons.Navigation, tint = colors.textMuted, size = 16.dp)
                    MutedText(stop.travel)
                }
            }
        }
    }
}

/** The numbered disc — the fork for lunch, the flag (filled amber) for the end. */
@Composable
private fun StopNode(number: Int, kind: StopKind) {
    val colors = ZillitTheme.colors
    val end = kind == StopKind.End
    val lunch = kind == StopKind.Lunch
    val ring = if (lunch) colors.textMuted else RecceColors.Brand
    Box(
        modifier = Modifier
            .size(NODE)
            .background(if (end) RecceColors.Brand else colors.surface, CircleShape)
            .border(2.dp, ring, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        when {
            lunch -> ZillitIcon(icon = RecceIcons.Fork, tint = colors.textMuted, size = 15.dp)
            end -> ZillitIcon(icon = RecceIcons.Flag, tint = Color.White, size = 15.dp)
            else -> ZillitText(
                text = number.toString(),
                style = ZillitTheme.typography.labelSmall.copy(fontSize = 13.sp, fontWeight = FontWeight.ExtraBold),
                color = RecceColors.Brand,
            )
        }
    }
}

@Composable
private fun StopCard(stop: RecceStop, onEvent: (RecceEvent) -> Unit) {
    val colors = ZillitTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(colors.surface)
            .border(1.dp, colors.border, RoundedCornerShape(8.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            ZillitText(
                text = stop.place.ifBlank { "Unnamed stop" },
                style = ZillitTheme.typography.titleSmall.copy(fontSize = 15.sp, fontWeight = FontWeight.Bold),
                color = colors.textPrimary,
            )
            if (stop.address.isNotBlank()) MutedText(stop.address)
            if (stop.description.isNotBlank()) MutedText(stop.description, Modifier.padding(top = 4.dp))
            if (stop.contact.isNotBlank()) {
                Row(
                    modifier = Modifier.padding(top = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    ZillitIcon(icon = ZillitIcons.Phone, tint = colors.textMuted, size = 13.dp)
                    MutedText(stop.contact, size = 12.5.sp)
                }
            }
        }
        LocationLinks(stop, small = false, onEvent = onEvent)
    }
}

// --------------------------------------------------------------- right rail

/** The route picture, or the web's prompt while nothing plots. */
@Composable
private fun RouteCard(route: RouteMapState?) {
    val colors = ZillitTheme.colors
    val image = remember(route?.image) { route?.image?.let(::decodeImageBitmap) }
    RecceCard {
        RecceCardHead("Route")
        Box(
            modifier = Modifier.fillMaxWidth().height(ROUTE_HEIGHT).background(colors.surfaceSunken),
            contentAlignment = Alignment.Center,
        ) {
            when {
                image != null -> Image(
                    bitmap = image,
                    contentDescription = "Route map",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
                route == null || route.loading -> ZillitSpinner()
                else -> Box(Modifier.padding(20.dp), contentAlignment = Alignment.Center) {
                    ZillitText(
                        text = if (route.plotted == 0) {
                            "Add an address or pick a location on a stop to plot the route here."
                        } else {
                            "The map picture could not be fetched — the pins are still on each stop."
                        },
                        style = ZillitTheme.typography.bodyMedium.copy(fontSize = 12.5.sp, lineHeight = 19.sp),
                        color = colors.textSecondary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.width(240.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun PersonnelCard(recce: Recce) {
    val colors = ZillitTheme.colors
    val people = recce.realPersonnel
    RecceCard {
        RecceCardHead("Recce personnel") { RecceTag(text = people.size.toString(), kind = TagKind.Neutral) }
        if (people.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
                MutedText("No personnel listed.")
            }
        }
        people.forEachIndexed { index, person ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(11.dp),
            ) {
                ZillitAvatar(name = person.name.ifBlank { "?" }, size = 32.dp)
                // The note goes under the role rather than beside the name —
                // the rail is 360dp and the phone number sits on the right,
                // so a name and a tag on one line would truncate the name.
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    ZillitText(
                        text = person.name.ifBlank { "—" },
                        style = ZillitTheme.typography.bodyLarge.copy(fontSize = 13.5.sp, fontWeight = FontWeight.Bold),
                        color = colors.textPrimary,
                        maxLines = 1,
                    )
                    if (person.role.isNotBlank()) MutedText(person.role, size = 12.5.sp)
                    if (person.note.isNotBlank()) {
                        RecceTag(text = person.note, kind = TagKind.Info, modifier = Modifier.padding(top = 2.dp))
                    }
                }
                if (person.hasPhone) ContactChip(person.contact)
            }
            if (index < people.lastIndex) Box(Modifier.fillMaxWidth().height(1.dp).background(colors.divider))
        }
    }
}

/** The phone number in mono — a click copies it, the desktop's stand-in for `tel:`. */
@Composable
private fun ContactChip(contact: String) {
    val scope = rememberCoroutineScope()
    var copied by remember { mutableStateOf(false) }
    ZillitTooltip(text = if (copied) "Copied" else "Copy number") {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .clickable {
                    copyTextToClipboard(contact.replace(" ", ""))
                    copied = true
                    scope.launch {
                        delay(COPIED_MS)
                        copied = false
                    }
                }
                .padding(horizontal = 4.dp, vertical = 2.dp),
        ) {
            ZillitText(
                text = contact,
                style = ZillitTheme.typography.bodyMedium.copy(fontSize = 12.5.sp, fontFamily = FontFamily.Monospace),
                color = RecceColors.link(),
                maxLines = 1,
            )
        }
    }
}

private val RAIL_WIDTH = 360.dp
private val TIME_WIDTH = 92.dp
private val NODE = 32.dp
private val ROUTE_HEIGHT = 230.dp
private const val COPIED_MS = 1_500L
