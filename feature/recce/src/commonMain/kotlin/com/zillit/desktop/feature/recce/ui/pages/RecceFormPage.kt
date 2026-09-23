@file:Suppress("LongMethod", "TooManyFunctions") // A form is a linear layout; one composable per section.

package com.zillit.desktop.feature.recce.ui.pages

import androidx.compose.foundation.ScrollState
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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDateField
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitSelect
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.core.units.ProductionUnit
import com.zillit.desktop.feature.recce.domain.RecceCrewMember
import com.zillit.desktop.feature.recce.domain.RecceStatus
import com.zillit.desktop.feature.recce.domain.StopKind
import com.zillit.desktop.feature.recce.domain.Weather
import com.zillit.desktop.feature.recce.ui.PersonEditor
import com.zillit.desktop.feature.recce.ui.RecceEditor
import com.zillit.desktop.feature.recce.ui.RecceEvent
import com.zillit.desktop.feature.recce.ui.RecceField
import com.zillit.desktop.feature.recce.ui.RecceUiState
import com.zillit.desktop.feature.recce.ui.StopEditor
import com.zillit.desktop.feature.recce.ui.components.DashedAddRow
import com.zillit.desktop.feature.recce.ui.components.Labelled
import com.zillit.desktop.feature.recce.ui.components.MutedText
import com.zillit.desktop.feature.recce.ui.components.RecceBody
import com.zillit.desktop.feature.recce.ui.components.RecceCanvas
import com.zillit.desktop.feature.recce.ui.components.RecceCard
import com.zillit.desktop.feature.recce.ui.components.RecceColors
import com.zillit.desktop.feature.recce.ui.components.RecceLocationControl
import com.zillit.desktop.feature.recce.ui.components.RecceTimeField
import com.zillit.desktop.feature.recce.ui.components.RecceToolHeader
import com.zillit.desktop.feature.recce.ui.components.SectionNumber

/**
 * The create / edit form — the web's `RecceForm`: three numbered sections
 * and a sticky footer. The recce day is a date field and every clock value
 * a time field; all are recombined to local epoch-ms on save. Publish
 * validates the four required fields; Save as Draft validates nothing.
 */
@Composable
internal fun RecceFormPage(
    state: RecceUiState,
    onEvent: (RecceEvent) -> Unit,
    editing: Boolean,
    scroll: ScrollState = rememberScrollState(),
) {
    val editor = state.editor
    Column(Modifier.fillMaxSize()) {
        RecceToolHeader(
            title = if (editing) str(S.recce_edit_recce) else str(S.recce_create_recce),
            onBack = { onEvent(RecceEvent.RequestCancel) },
        )
        if (editor == null) {
            RecceCanvas { ZillitSpinner() }
            return@Column
        }
        RecceBody(modifier = Modifier.weight(1f), narrow = true, scroll = scroll) {
            ErrorNotice(state, onEvent)
            ImportantSection(state, editor, onEvent)
            ScheduleSection(state, editor, onEvent)
            PersonnelSection(state.crew, editor, onEvent)
        }
        Footer(editor, onEvent)
    }
}

/** A numbered card with a title and a description — the web's `FormSection`. */
@Composable
private fun FormSection(
    n: Int,
    title: String,
    description: String,
    action: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    RecceCard(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
        Column(Modifier.padding(24.dp)) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionNumber(n)
                Column(Modifier.weight(1f)) {
                    ZillitText(
                        text = title,
                        style = ZillitTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = ZillitTheme.colors.textPrimary,
                    )
                    MutedText(description, Modifier.padding(top = 2.dp))
                }
                action?.invoke()
            }
            Spacer(Modifier.height(18.dp))
            content()
        }
    }
}

// ---------------------------------------------------------- 1 · important

@Composable
private fun ImportantSection(state: RecceUiState, editor: RecceEditor, onEvent: (RecceEvent) -> Unit) {
    FormSection(1, str(S.recce_section_important), str(S.desktop_recce_important_description)) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
                ZillitTextField(
                    value = editor.title,
                    onValueChange = { onEvent(RecceEvent.EditorChanged(title = it)) },
                    label = str(S.desktop_recce_title_label),
                    placeholder = str(S.recce_field_title_hint),
                    errorText = editor.errors[RecceField.Title],
                    modifier = Modifier.weight(1f),
                )
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Labelled(label = str(S.dm_step2_unit), modifier = Modifier.weight(1f)) {
                        val chosen = state.units.firstOrNull { it.id == editor.unit }
                            ?: editor.unit.takeIf { it.isNotBlank() }?.let { ProductionUnit(it, it) }
                        ZillitSelect(
                            value = chosen,
                            options = listOf<ProductionUnit?>(null) + state.units,
                            onSelect = { onEvent(RecceEvent.EditorChanged(unit = it?.id, clearUnit = it == null)) },
                            label = { it?.name?.localised() ?: str(S.select_unit) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    ZillitDateField(
                        value = editor.dateYmd,
                        onValueChange = { onEvent(RecceEvent.EditorChanged(dateYmd = it)) },
                        label = str(S.date),
                        errorText = editor.errors[RecceField.Date],
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            RecceTimeField(
                value = editor.rdv.time,
                onValueChange = { onEvent(RecceEvent.EditorChanged(rdv = editor.rdv.copy(time = it))) },
                label = str(S.desktop_recce_rdv_time),
                errorText = editor.errors[RecceField.RdvTime],
                modifier = Modifier.width(RDV_TIME_WIDTH),
            )
            RecceLocationControl(
                stop = editor.rdv,
                onChange = { onEvent(RecceEvent.EditorChanged(rdv = it)) },
                preview = editor.rdv.pin?.let { state.previews[it] },
                previewKnown = editor.rdv.pin?.let { it in state.previews } ?: false,
                onNeedPreview = { onEvent(RecceEvent.NeedPreview(it)) },
                onOpenUrl = { onEvent(RecceEvent.OpenUrl(it)) },
                label = str(S.desktop_recce_rendezvous_point),
                namePlaceholder = str(S.desktop_recce_rdv_place_placeholder),
                errorText = editor.errors[RecceField.RdvPlace],
            )
            W3WField(
                value = editor.rdv.w3w,
                onValueChange = { onEvent(RecceEvent.EditorChanged(rdv = editor.rdv.copy(w3w = it))) },
                hint = str(S.desktop_recce_w3w_display_hint),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
                ZillitTextField(
                    value = editor.station,
                    onValueChange = { onEvent(RecceEvent.EditorChanged(station = it)) },
                    label = str(S.recce_field_station),
                    placeholder = str(S.desktop_recce_station_placeholder),
                    modifier = Modifier.weight(1f),
                )
                Labelled(label = str(S.recce_field_weather), modifier = Modifier.weight(WEATHER_WEIGHT)) {
                    WeatherControl(editor.weather) { onEvent(RecceEvent.EditorChanged(weather = it)) }
                }
            }
        }
    }
}

/** The What3Words input with its red `///` prefix — the web's `W3WControl`. */
@Composable
private fun W3WField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    hint: String? = null,
) {
    val colors = ZillitTheme.colors
    Labelled(label = str(S.recce_w3w_label), modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .height(W3W_PREFIX_HEIGHT)
                    .clip(RoundedCornerShape(topStart = 6.dp, bottomStart = 6.dp))
                    .background(colors.surfaceSunken)
                    .border(1.dp, colors.border, RoundedCornerShape(topStart = 6.dp, bottomStart = 6.dp))
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                ZillitText(
                    text = "///",
                    style = ZillitTheme.typography.label.copy(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Black,
                        fontSize = 13.sp,
                    ),
                    color = RecceColors.w3wRed(),
                )
            }
            ZillitTextField(
                value = value,
                onValueChange = { onValueChange(it.removePrefix("///").trim()) },
                placeholder = "leaned.sushi.port",
                modifier = Modifier.weight(1f),
            )
        }
        hint?.let { MutedText(it, size = 12.sp) }
    }
}

/**
 * Weather is one free-text string in the model, entered through a builder so
 * the reader picks °C/°F rather than typing the symbol — the web's
 * `WeatherControl`.
 */
@Composable
private fun WeatherControl(weather: Weather, onChange: (Weather) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ZillitTextField(
            value = weather.low,
            onValueChange = { onChange(weather.copy(low = it.filterNumber())) },
            placeholder = str(S.recce_weather_low),
            modifier = Modifier.width(TEMP_WIDTH),
        )
        MutedText(str(S.recce_weather_to))
        ZillitTextField(
            value = weather.high,
            onValueChange = { onChange(weather.copy(high = it.filterNumber())) },
            placeholder = str(S.recce_weather_high),
            modifier = Modifier.width(TEMP_WIDTH),
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
            placeholder = str(S.recce_weather_conditions),
            modifier = Modifier.weight(1f),
        )
    }
}

/** A temperature: digits, one sign, one point — what `InputNumber` lets through. */
private fun String.filterNumber(): String = buildString {
    this@filterNumber.forEachIndexed { index, c ->
        when {
            c.isDigit() -> append(c)
            c == '-' && index == 0 -> append(c)
            c == '.' && !contains('.') -> append(c)
        }
    }
}

// ----------------------------------------------------------- 2 · schedule

@Composable
private fun ScheduleSection(state: RecceUiState, editor: RecceEditor, onEvent: (RecceEvent) -> Unit) {
    FormSection(
        n = 2,
        title = str(S.recce_section_schedule),
        description = str(S.desktop_recce_schedule_description),
        action = {
            ZillitButton(
                text = str(S.recce_add_stop),
                onClick = { onEvent(RecceEvent.AddStop) },
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
                leadingIcon = ZillitIcons.Add,
            )
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ZillitTextField(
                value = editor.crewNote,
                onValueChange = { onEvent(RecceEvent.EditorChanged(crewNote = it)) },
                label = str(S.recce_field_crew_note),
                placeholder = str(S.desktop_recce_crew_note_placeholder),
                helperText = str(S.desktop_recce_crew_note_helper),
                modifier = Modifier.fillMaxWidth(),
            )
            editor.stops.forEachIndexed { index, stop ->
                StopCard(state, index, stop, editor.stops.size, onEvent)
            }
            // Add another stop without scrolling back up to the section header.
            DashedAddRow(text = str(S.recce_add_stop), onClick = { onEvent(RecceEvent.AddStop) })
        }
    }
}

@Composable
private fun StopCard(state: RecceUiState, index: Int, stop: StopEditor, count: Int, onEvent: (RecceEvent) -> Unit) {
    val colors = ZillitTheme.colors
    val change = { updated: StopEditor -> onEvent(RecceEvent.StopChanged(index, updated)) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(colors.surfaceSunken)
            .border(1.dp, colors.border, RoundedCornerShape(8.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
            RecceTimeField(
                value = stop.time,
                onValueChange = { change(stop.copy(time = it)) },
                label = str(S.recce_field_time),
                modifier = Modifier.width(STOP_TIME_WIDTH),
            )
            Labelled(label = str(S.recce_field_type), modifier = Modifier.width(KIND_WIDTH)) {
                ZillitSelect(
                    value = stop.kind,
                    options = StopKind.entries,
                    onSelect = { change(stop.copy(kind = it)) },
                    label = { it.label },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.weight(1f))
            Row(
                modifier = Modifier.padding(top = ACTIONS_TOP),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ZillitIconButton(
                    icon = ZillitIcons.ChevronUp,
                    contentDescription = str(S.desktop_recce_move_stop_up),
                    onClick = { onEvent(RecceEvent.MoveStop(index, -1)) },
                    enabled = index > 0,
                )
                ZillitIconButton(
                    icon = ZillitIcons.ChevronDown,
                    contentDescription = str(S.desktop_recce_move_stop_down),
                    onClick = { onEvent(RecceEvent.MoveStop(index, 1)) },
                    enabled = index < count - 1,
                )
                RemoveButton(str(S.desktop_recce_remove_stop)) { onEvent(RecceEvent.RemoveStop(index)) }
            }
        }
        RecceLocationControl(
            stop = stop,
            onChange = change,
            preview = stop.pin?.let { state.previews[it] },
            previewKnown = stop.pin?.let { it in state.previews } ?: false,
            onNeedPreview = { onEvent(RecceEvent.NeedPreview(it)) },
            onOpenUrl = { onEvent(RecceEvent.OpenUrl(it)) },
        )
        W3WField(
            value = stop.w3w,
            onValueChange = { change(stop.copy(w3w = it)) },
            modifier = Modifier.widthIn(max = W3W_MAX_WIDTH),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
            ZillitTextField(
                value = stop.contact,
                onValueChange = { change(stop.copy(contact = it)) },
                label = str(S.desktop_recce_location_contact_optional),
                placeholder = str(S.desktop_recce_location_contact_placeholder),
                modifier = Modifier.weight(1f),
            )
            RecceTimeField(
                value = stop.endTime,
                onValueChange = { change(stop.copy(endTime = it)) },
                label = str(S.desktop_recce_end_time_optional),
                modifier = Modifier.width(KIND_WIDTH),
            )
        }
        ZillitTextField(
            value = stop.description,
            onValueChange = { change(stop.copy(description = it)) },
            label = str(S.desktop_notes_optional),
            placeholder = str(S.desktop_recce_stop_notes_placeholder),
            singleLine = false,
            modifier = Modifier.fillMaxWidth(),
        )
        ZillitTextField(
            value = stop.travel,
            onValueChange = { change(stop.copy(travel = it)) },
            label = str(S.recce_field_travel),
            placeholder = str(S.desktop_recce_travel_placeholder),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** The bordered trash button beside a stop's header — the web's outlined remove. */
@Composable
private fun RemoveButton(description: String, onClick: () -> Unit) {
    val colors = ZillitTheme.colors
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(colors.surface)
            .border(1.dp, colors.border, RoundedCornerShape(6.dp)),
    ) {
        ZillitIconButton(icon = ZillitIcons.Trash, contentDescription = description, onClick = onClick)
    }
}

// ---------------------------------------------------------- 3 · personnel

@Composable
private fun PersonnelSection(crew: List<RecceCrewMember>, editor: RecceEditor, onEvent: (RecceEvent) -> Unit) {
    FormSection(
        n = 3,
        title = str(S.recce_section_personnel),
        description = str(S.desktop_recce_personnel_description),
        action = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val available = crew.filter { member -> editor.personnel.none { it.userId == member.userId } }
                if (crew.isNotEmpty()) {
                    CrewPicker(available) { onEvent(RecceEvent.AddCrewMember(it.userId)) }
                }
                ZillitButton(
                    text = str(S.recce_add_person),
                    onClick = { onEvent(RecceEvent.AddPerson) },
                    variant = ButtonVariant.Secondary,
                    size = ButtonSize.Small,
                    leadingIcon = ZillitIcons.Add,
                )
            }
        },
    ) {
        val colors = ZillitTheme.colors
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .border(1.dp, colors.border, RoundedCornerShape(8.dp)),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.surfaceSunken)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(
                    S.name,
                    S.recce_field_role,
                    S.recce_field_email,
                    S.contact,
                    S.recce_field_note,
                ).forEach { column ->
                    ZillitText(
                        text = str(column),
                        style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                        color = colors.textSecondary,
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.width(ROW_ACTION_WIDTH))
            }
            editor.personnel.forEachIndexed { index, person ->
                Box(Modifier.fillMaxWidth().height(1.dp).background(colors.divider))
                PersonRow(index, person, onEvent)
            }
        }
    }
}

@Composable
private fun PersonRow(index: Int, person: PersonEditor, onEvent: (RecceEvent) -> Unit) {
    val change = { updated: PersonEditor -> onEvent(RecceEvent.PersonChanged(index, updated)) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitTextField(
            value = person.name,
            onValueChange = { change(person.copy(name = it)) },
            placeholder = str(S.recce_field_name),
            modifier = Modifier.weight(1f),
        )
        ZillitTextField(
            value = person.role,
            onValueChange = { change(person.copy(role = it)) },
            placeholder = str(S.recce_field_role),
            modifier = Modifier.weight(1f),
        )
        ZillitTextField(
            value = person.email,
            onValueChange = { change(person.copy(email = it)) },
            placeholder = "name@email.com",
            modifier = Modifier.weight(1f),
        )
        ZillitTextField(
            value = person.contact,
            onValueChange = { change(person.copy(contact = it)) },
            placeholder = "07xxx xxx xxx",
            modifier = Modifier.weight(1f),
        )
        ZillitTextField(
            value = person.note,
            onValueChange = { change(person.copy(note = it)) },
            placeholder = str(S.desktop_recce_person_note_placeholder),
            modifier = Modifier.weight(1f),
        )
        Box(Modifier.width(ROW_ACTION_WIDTH), contentAlignment = Alignment.Center) {
            ZillitIconButton(
                icon = ZillitIcons.Trash,
                contentDescription = str(S.desktop_recce_remove_person),
                onClick = { onEvent(RecceEvent.RemovePerson(index)) },
            )
        }
    }
}

/**
 * "+ Add from project crew…" — a searchable dropdown over the crew not yet
 * on the list, each option two lines (name; designation · contact), the
 * web's `recce-crew-select`: on-brand outline and tint so it reads as a
 * tappable picker rather than a greyed-out field.
 */
@Composable
private fun CrewPicker(available: List<RecceCrewMember>, onPick: (RecceCrewMember) -> Unit) {
    val colors = ZillitTheme.colors
    var open by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Box {
        Row(
            modifier = Modifier
                .width(CREW_PICKER_WIDTH)
                .height(32.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(colors.accentSoft)
                .border(1.dp, if (hovered) RecceColors.BrandStrong else RecceColors.Brand, RoundedCornerShape(8.dp))
                .hoverable(interaction)
                .clickable {
                    query = ""
                    open = true
                }
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ZillitText(
                text = "+ " + str(S.recce_add_from_crew),
                style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = colors.accentText,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            ZillitIcon(icon = ZillitIcons.ChevronDown, tint = colors.accentText, size = 14.dp)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Column(Modifier.width(CREW_PICKER_WIDTH).padding(horizontal = 8.dp, vertical = 4.dp)) {
                ZillitTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = str(S.desktop_recce_crew_search_placeholder),
                    leadingIcon = ZillitIcons.Search,
                    modifier = Modifier.fillMaxWidth(),
                )
                val needle = query.trim()
                val matches = available.filter { member ->
                    needle.isEmpty() || listOf(member.name, member.role.localised(), member.contact)
                        .any { it.contains(needle, ignoreCase = true) }
                }
                if (matches.isEmpty()) {
                    Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                        MutedText(if (available.isEmpty()) {
                            str(S.desktop_recce_everyone_on_list)
                        } else {
                            str(S.desktop_sos_no_crew_match)
                        })
                    }
                }
                // A FIXED height: a lazy list inside a menu measures against infinity otherwise.
                LazyColumn(Modifier.fillMaxWidth().height(CREW_LIST_HEIGHT)) {
                    items(matches.size) { index ->
                        val member = matches[index]
                        CrewOption(member) {
                            onPick(member)
                            open = false
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CrewOption(member: RecceCrewMember, onClick: () -> Unit) {
    val colors = ZillitTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val meta = listOf(member.role.localised(), member.contact).filter { it.isNotBlank() }.joinToString(" · ")
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(if (hovered) colors.surfaceHover else colors.surfaceRaised)
            .hoverable(interaction)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        ZillitText(
            text = member.name,
            style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
            color = colors.textPrimary,
            maxLines = 1,
        )
        if (meta.isNotBlank()) MutedText(meta, size = 12.sp)
    }
}

// --------------------------------------------------------------- footer

/** The sticky footer: the counter, Cancel, Save as Draft, Publish recce. */
@Composable
private fun Footer(editor: RecceEditor, onEvent: (RecceEvent) -> Unit) {
    val colors = ZillitTheme.colors
    Column {
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))
        Box(Modifier.fillMaxWidth().background(colors.surface).padding(horizontal = 28.dp, vertical = 12.dp)) {
            Row(
                modifier = Modifier.widthIn(max = FOOTER_WIDTH).fillMaxWidth().align(Alignment.Center),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ZillitText(
                    text = str(S.recce_footer_summary, editor.stopCount, editor.personnel.size),
                    style = ZillitTheme.typography.bodyMedium,
                    color = colors.textMuted,
                )
                Spacer(Modifier.weight(1f))
                ZillitButton(
                    text = str(S.recce_cancel),
                    onClick = { onEvent(RecceEvent.RequestCancel) },
                    variant = ButtonVariant.Tertiary,
                    enabled = editor.saving == null,
                )
                ZillitButton(
                    text = str(S.recce_save_draft),
                    onClick = { onEvent(RecceEvent.SaveDraft) },
                    variant = ButtonVariant.Secondary,
                    loading = editor.saving == RecceStatus.Draft,
                    enabled = editor.saving == null,
                )
                ZillitButton(
                    text = str(S.recce_publish),
                    onClick = { onEvent(RecceEvent.Publish) },
                    leadingIcon = ZillitIcons.Check,
                    loading = editor.saving == RecceStatus.Published,
                    enabled = editor.saving == null,
                )
            }
        }
    }
}

private val RDV_TIME_WIDTH = 160.dp
private val W3W_PREFIX_HEIGHT = 32.dp
private val TEMP_WIDTH = 84.dp
private val UNIT_WIDTH = 90.dp
private val STOP_TIME_WIDTH = 160.dp
private val ACTIONS_TOP = 20.dp
private val KIND_WIDTH = 170.dp
private val W3W_MAX_WIDTH = 360.dp
private val ROW_ACTION_WIDTH = 36.dp
private val CREW_PICKER_WIDTH = 260.dp
private val CREW_LIST_HEIGHT = 220.dp
private val FOOTER_WIDTH = 1200.dp
private const val WEATHER_WEIGHT = 1.6f
