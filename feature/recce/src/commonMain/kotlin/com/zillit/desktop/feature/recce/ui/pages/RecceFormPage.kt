@file:Suppress("LongMethod", "TooManyFunctions") // A form is a linear layout; one composable per section.

package com.zillit.desktop.feature.recce.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.common.MapsLink
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitPageHeader
import com.zillit.desktop.core.designsystem.component.ZillitSectionCard
import com.zillit.desktop.core.designsystem.component.ZillitSelect
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.units.ProductionUnit
import com.zillit.desktop.feature.recce.domain.RecceCrewMember
import com.zillit.desktop.feature.recce.domain.RecceStatus
import com.zillit.desktop.feature.recce.domain.StopKind
import com.zillit.desktop.feature.recce.domain.Weather
import com.zillit.desktop.feature.recce.ui.ErrorNotice
import com.zillit.desktop.feature.recce.ui.PersonEditor
import com.zillit.desktop.feature.recce.ui.RecceEditor
import com.zillit.desktop.feature.recce.ui.RecceEvent
import com.zillit.desktop.feature.recce.ui.RecceUiState
import com.zillit.desktop.feature.recce.ui.StopEditor

/**
 * The create/edit form: the web's three numbered sections and its sticky
 * footer. Coordinates are typed — there is no map — and a pasted Google
 * Maps link fills them, exactly as the web's link box does.
 */
@Composable
internal fun RecceFormPage(state: RecceUiState, onEvent: (RecceEvent) -> Unit, editing: Boolean) {
    val editor = state.editor ?: return
    val colors = ZillitTheme.colors
    Column(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(ZillitTheme.spacing.lg),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        ) {
            ZillitPageHeader(
                title = if (editing) "Edit recce" else "Create recce",
                actions = {
                    ZillitButton(
                        text = "Cancel",
                        onClick = { onEvent(RecceEvent.CancelEdit) },
                        variant = ButtonVariant.Tertiary,
                    )
                },
            )
            ErrorNotice(state, onEvent)
            ImportantSection(state, editor, onEvent)
            ScheduleSection(editor, onEvent)
            PersonnelSection(state.crew, editor, onEvent)
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = ZillitTheme.spacing.lg, vertical = ZillitTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            ZillitText(
                text = "${editor.stops.count { it.kind != StopKind.Lunch }} stops · " +
                    "${editor.personnel.size} personnel",
                style = ZillitTheme.typography.bodySmall,
                color = colors.textMuted,
                modifier = Modifier.weight(1f),
            )
            ZillitButton(
                text = "Save as Draft",
                onClick = { onEvent(RecceEvent.SaveDraft) },
                variant = ButtonVariant.Secondary,
                loading = editor.saving == RecceStatus.Draft,
                enabled = editor.saving == null,
            )
            ZillitButton(
                text = "Publish recce",
                onClick = { onEvent(RecceEvent.Publish) },
                loading = editor.saving == RecceStatus.Published,
                enabled = editor.saving == null,
            )
        }
    }
}

@Composable
private fun ImportantSection(state: RecceUiState, editor: RecceEditor, onEvent: (RecceEvent) -> Unit) {
    ZillitSectionCard(title = "1. Important information") {
        Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
            Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
                ZillitTextField(
                    value = editor.title,
                    onValueChange = { onEvent(RecceEvent.EditorChanged(title = it)) },
                    label = "Recce title",
                    modifier = Modifier.weight(TITLE_WEIGHT),
                )
                Column(Modifier.weight(1f)) {
                    ZillitText(
                        text = "Unit",
                        style = ZillitTheme.typography.bodySmall,
                        color = ZillitTheme.colors.textMuted,
                    )
                    ZillitSelect(
                        value = state.units.firstOrNull { it.id == editor.unit }
                            ?: editor.unit.takeIf { it.isNotBlank() }?.let { ProductionUnit(it, it) },
                        options = listOf<ProductionUnit?>(null) + state.units,
                        onSelect = { onEvent(RecceEvent.EditorChanged(unit = it?.id, clearUnit = it == null)) },
                        label = { it?.name ?: "No unit" },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
                ZillitTextField(
                    value = editor.dateYmd,
                    onValueChange = { onEvent(RecceEvent.EditorChanged(dateYmd = it)) },
                    label = "Date",
                    placeholder = "YYYY-MM-DD",
                    modifier = Modifier.weight(1f),
                )
                ZillitTextField(
                    value = editor.rdv.time,
                    onValueChange = { onEvent(RecceEvent.EditorChanged(rdv = editor.rdv.copy(time = it))) },
                    label = "RDV time",
                    placeholder = "HH:mm",
                    modifier = Modifier.weight(1f),
                )
                ZillitTextField(
                    value = editor.station,
                    onValueChange = { onEvent(RecceEvent.EditorChanged(station = it)) },
                    label = "Nearest station",
                    modifier = Modifier.weight(TITLE_WEIGHT),
                )
            }
            LocationFields(
                label = "Rendezvous point",
                stop = editor.rdv,
                onChange = { onEvent(RecceEvent.EditorChanged(rdv = it)) },
            )
            WeatherFields(editor.weather) { onEvent(RecceEvent.EditorChanged(weather = it)) }
        }
    }
}

@Composable
private fun WeatherFields(weather: Weather, onChange: (Weather) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm), verticalAlignment = Alignment.Bottom) {
        ZillitTextField(
            value = weather.low,
            onValueChange = { onChange(weather.copy(low = it)) },
            label = "Weather low",
            modifier = Modifier.width(NUMBER_WIDTH),
        )
        ZillitTextField(
            value = weather.high,
            onValueChange = { onChange(weather.copy(high = it)) },
            label = "High",
            modifier = Modifier.width(NUMBER_WIDTH),
        )
        ZillitSelect(
            value = weather.unit,
            options = listOf("C", "F"),
            onSelect = { onChange(weather.copy(unit = it)) },
            label = { "°$it" },
            modifier = Modifier.width(UNIT_WIDTH),
        )
        ZillitTextField(
            value = weather.conditions,
            onValueChange = { onChange(weather.copy(conditions = it)) },
            label = "Conditions",
            placeholder = "light cloud",
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * A place: name, address, W3W, and typed coordinates. A pasted Google Maps
 * link fills the coordinates — the web's own trick for a map-less entry.
 */
@Composable
private fun LocationFields(label: String, stop: StopEditor, onChange: (StopEditor) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
        Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
            ZillitTextField(
                value = stop.place,
                onValueChange = { onChange(stop.copy(place = it)) },
                label = label,
                modifier = Modifier.weight(1f),
            )
            ZillitTextField(
                value = stop.address,
                onValueChange = { onChange(stop.copy(address = it)) },
                label = "Address",
                modifier = Modifier.weight(TITLE_WEIGHT),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
            ZillitTextField(
                value = stop.w3w,
                onValueChange = { onChange(stop.copy(w3w = it.removePrefix("///"))) },
                label = "What3Words",
                placeholder = "word.word.word",
                modifier = Modifier.weight(1f),
            )
            ZillitTextField(
                value = stop.latText,
                onValueChange = { onChange(stop.copy(latText = it)) },
                label = "Latitude",
                modifier = Modifier.weight(1f),
            )
            ZillitTextField(
                value = stop.lngText,
                onValueChange = { onChange(stop.copy(lngText = it)) },
                label = "Longitude",
                modifier = Modifier.weight(1f),
            )
            // Its own text, so a link typed or pasted in pieces still parses
            // once whole; the coordinates land in the fields beside it and
            // the link stays visible as the source until the field is cleared.
            var link by remember { mutableStateOf("") }
            ZillitTextField(
                value = link,
                onValueChange = { typed ->
                    link = typed
                    parseLatLngFromUrl(typed)?.let { (lat, lng) ->
                        onChange(stop.copy(latText = lat.toString(), lngText = lng.toString()))
                    }
                },
                label = "Paste a Google Maps link",
                placeholder = "https://www.google.com/maps/@…",
                helperText = "No coordinates in that link"
                    .takeIf { link.isNotBlank() && parseLatLngFromUrl(link) == null },
                modifier = Modifier.weight(TITLE_WEIGHT),
            )
        }
    }
}

@Composable
private fun ScheduleSection(editor: RecceEditor, onEvent: (RecceEvent) -> Unit) {
    ZillitSectionCard(
        title = "2. Schedule",
        action = {
            ZillitButton(
                text = "Add stop",
                onClick = { onEvent(RecceEvent.AddStop) },
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
                leadingIcon = ZillitIcons.Add,
            )
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
            ZillitTextField(
                value = editor.crewNote,
                onValueChange = { onEvent(RecceEvent.EditorChanged(crewNote = it)) },
                label = "Crew note",
                placeholder = "Shown under the schedule header",
                modifier = Modifier.fillMaxWidth(),
            )
            editor.stops.forEachIndexed { index, stop ->
                StopCard(index, stop, editor.stops.size, onEvent)
            }
        }
    }
}

@Composable
private fun StopCard(index: Int, stop: StopEditor, count: Int, onEvent: (RecceEvent) -> Unit) {
    val change = { updated: StopEditor -> onEvent(RecceEvent.StopChanged(index, updated)) }
    ZillitSectionCard(title = "Stop ${index + 1}") {
        Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                verticalAlignment = Alignment.Bottom,
            ) {
                ZillitTextField(
                    value = stop.time,
                    onValueChange = { change(stop.copy(time = it)) },
                    label = "Time",
                    placeholder = "HH:mm",
                    modifier = Modifier.width(NUMBER_WIDTH),
                )
                ZillitTextField(
                    value = stop.endTime,
                    onValueChange = { change(stop.copy(endTime = it)) },
                    label = "End time",
                    placeholder = "HH:mm",
                    modifier = Modifier.width(NUMBER_WIDTH),
                )
                Column(Modifier.width(KIND_WIDTH)) {
                    ZillitText(
                        text = "Type",
                        style = ZillitTheme.typography.bodySmall,
                        color = ZillitTheme.colors.textMuted,
                    )
                    ZillitSelect(
                        value = stop.kind,
                        options = StopKind.entries,
                        onSelect = { change(stop.copy(kind = it)) },
                        label = { it.wire },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                ZillitTextField(
                    value = stop.contact,
                    onValueChange = { change(stop.copy(contact = it)) },
                    label = "Location contact",
                    placeholder = "Name - phone",
                    modifier = Modifier.weight(1f),
                )
                ZillitIconButton(
                    icon = ZillitIcons.ArrowLeft,
                    contentDescription = "Move up",
                    onClick = { onEvent(RecceEvent.MoveStop(index, -1)) },
                    enabled = index > 0,
                )
                ZillitIconButton(
                    icon = ZillitIcons.ArrowRight,
                    contentDescription = "Move down",
                    onClick = { onEvent(RecceEvent.MoveStop(index, 1)) },
                    enabled = index < count - 1,
                )
                ZillitIconButton(
                    icon = ZillitIcons.Trash,
                    contentDescription = "Remove stop",
                    onClick = { onEvent(RecceEvent.RemoveStop(index)) },
                )
            }
            LocationFields(label = "Location", stop = stop, onChange = change)
            Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
                ZillitTextField(
                    value = stop.description,
                    onValueChange = { change(stop.copy(description = it)) },
                    label = "Notes",
                    singleLine = false,
                    modifier = Modifier.weight(1f),
                )
                ZillitTextField(
                    value = stop.travel,
                    onValueChange = { change(stop.copy(travel = it)) },
                    label = "Travel note to next stop",
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun PersonnelSection(crew: List<RecceCrewMember>, editor: RecceEditor, onEvent: (RecceEvent) -> Unit) {
    ZillitSectionCard(
        title = "3. Recce personnel",
        action = {
            ZillitButton(
                text = "Add person",
                onClick = { onEvent(RecceEvent.AddPerson) },
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
                leadingIcon = ZillitIcons.Add,
            )
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
            val available = crew.filter { member -> editor.personnel.none { it.userId == member.userId } }
            if (available.isNotEmpty()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                ) {
                    ZillitText(
                        text = "Add from project crew",
                        style = ZillitTheme.typography.bodySmall,
                        color = ZillitTheme.colors.textMuted,
                    )
                    ZillitSelect(
                        value = null,
                        options = listOf<RecceCrewMember?>(null) + available,
                        onSelect = { it?.let { member -> onEvent(RecceEvent.AddCrewMember(member.userId)) } },
                        label = { member ->
                            member?.let { "${it.name} · ${it.role}".trimEnd(' ', '·') } ?: "Pick a crew member…"
                        },
                        modifier = Modifier.width(CREW_WIDTH),
                    )
                }
            }
            editor.personnel.forEachIndexed { index, person -> PersonRow(index, person, onEvent) }
        }
    }
}

@Composable
private fun PersonRow(index: Int, person: PersonEditor, onEvent: (RecceEvent) -> Unit) {
    val change = { updated: PersonEditor -> onEvent(RecceEvent.PersonChanged(index, updated)) }
    Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm), verticalAlignment = Alignment.Bottom) {
        ZillitTextField(
            value = person.name,
            onValueChange = { change(person.copy(name = it)) },
            label = "Name",
            modifier = Modifier.weight(1f),
        )
        ZillitTextField(
            value = person.role,
            onValueChange = { change(person.copy(role = it)) },
            label = "Role",
            modifier = Modifier.weight(1f),
        )
        ZillitTextField(
            value = person.email,
            onValueChange = { change(person.copy(email = it)) },
            label = "Email",
            modifier = Modifier.weight(1f),
        )
        ZillitTextField(
            value = person.contact,
            onValueChange = { change(person.copy(contact = it)) },
            label = "Contact",
            modifier = Modifier.weight(1f),
        )
        ZillitTextField(
            value = person.note,
            onValueChange = { change(person.copy(note = it)) },
            label = "Note",
            modifier = Modifier.weight(1f),
        )
        ZillitIconButton(
            icon = ZillitIcons.Trash,
            contentDescription = "Remove person",
            onClick = { onEvent(RecceEvent.RemovePerson(index)) },
        )
    }
}

/** The web's `parseLatLngFromUrl`, shared with Transportation. */
internal fun parseLatLngFromUrl(url: String): Pair<Double, Double>? = MapsLink.parseLatLng(url)

private val NUMBER_WIDTH = 96.dp
private val UNIT_WIDTH = 80.dp
private val KIND_WIDTH = 140.dp
private val CREW_WIDTH = 320.dp
private const val TITLE_WEIGHT = 2f
