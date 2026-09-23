package com.zillit.desktop.feature.crewlist.ui.dialogs

import androidx.compose.animation.animateColorAsState
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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitAvatar
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitSearchField
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTooltip
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.crewlist.ui.CrewListEvent
import com.zillit.desktop.feature.crewlist.ui.DepartmentOrderState
import com.zillit.desktop.feature.crewlist.ui.PeopleOrderState
import com.zillit.desktop.feature.crewlist.ui.components.CrewCellField
import com.zillit.desktop.feature.crewlist.ui.components.CrewCopy
import com.zillit.desktop.feature.crewlist.ui.components.CrewIcons
import com.zillit.desktop.feature.crewlist.ui.components.ReorderList
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

/**
 * The listing order, opened from the banner's "Click Here" — the web's
 * `ChangePriorityList`: drag a department by its grip or type the priority it
 * should move to; click one to order the people inside it (`DragUser`); Reset
 * puts the list back in creation order; Save sends the whole order. Leaving
 * with changes asks whether to save them.
 */
@Composable
internal fun DepartmentOrderDialog(
    editor: DepartmentOrderState?,
    copy: CrewCopy,
    faces: suspend (String) -> ImageBitmap?,
    onEvent: (CrewListEvent) -> Unit,
) {
    val shown = editor ?: DepartmentOrderState()
    ZillitDialogShell(
        title = copy.t("set_department_priority_staff_list", str(S.desktop_cl_listing_order_for_tool)),
        subtitle = copy.t(
            "change_priority_modal_title",
            str(S.set_department_priority_header),
        ),
        icon = CrewIcons.Swap,
        visible = editor != null && !editor.confirmDiscard,
        onDismiss = { onEvent(CrewListEvent.Admin.CloseDepartments) },
        width = 720.dp,
        scrollable = false,
        actions = {
            ZillitTooltip(copy.t("reset_tooltip", str(S.desktop_cl_reset_tooltip))) {
                ZillitButton(
                    text = copy.t("reset", str(S.reset)),
                    variant = ButtonVariant.Tertiary,
                    leadingIcon = ZillitIcons.Reload,
                    enabled = !shown.loading && !shown.saving,
                    onClick = { onEvent(CrewListEvent.Admin.ResetDepartments) },
                )
            }
            Spacer(Modifier.weight(1f))
            ZillitButton(
                text = copy.t("Cancel", str(S.cancel)),
                variant = ButtonVariant.Secondary,
                enabled = !shown.saving,
                onClick = { onEvent(CrewListEvent.Admin.CloseDepartments) },
            )
            ZillitButton(
                text = copy.t("save", str(S.save)),
                loading = shown.saving,
                enabled = shown.order.isChanged && !shown.saving,
                onClick = { onEvent(CrewListEvent.Admin.SaveDepartments) },
            )
        },
    ) {
        when {
            shown.loading -> Box(Modifier.fillMaxWidth().height(260.dp), contentAlignment = Alignment.Center) {
                ZillitSpinner(size = 24.dp)
            }
            shown.failure != null -> ZillitText(
                text = shown.failure,
                style = ZillitTheme.typography.bodyMedium,
                color = ZillitTheme.colors.danger,
            )
            else -> DepartmentList(shown, copy, onEvent)
        }
    }

    DiscardChangesDialog(visible = editor?.confirmDiscard == true, copy = copy, onEvent = onEvent)
    PeopleOrderDialog(editor?.people, copy, faces, onEvent)
}

/** "Do you want to save changes?" — Yes saves, No throws the new order away and closes. */
@Composable
private fun DiscardChangesDialog(visible: Boolean, copy: CrewCopy, onEvent: (CrewListEvent) -> Unit) {
    ZillitDialogShell(
        title = copy.t("warning_title", str(S.desktop_warning)),
        icon = ZillitIcons.Warning,
        visible = visible,
        onDismiss = { onEvent(CrewListEvent.Admin.ResolveDiscard(save = false)) },
        width = 400.dp,
        actions = {
            ZillitButton(
                text = copy.t("No", str(S.no)),
                variant = ButtonVariant.Tertiary,
                onClick = { onEvent(CrewListEvent.Admin.ResolveDiscard(save = false)) },
            )
            ZillitButton(
                text = copy.t("Yes", str(S.yes)),
                onClick = { onEvent(CrewListEvent.Admin.ResolveDiscard(save = true)) },
            )
        },
    ) {
        ZillitText(
            text = copy.t("project_departments_unsaved_warning", str(S.do_you_want_to_save_changes)),
            style = ZillitTheme.typography.bodyMedium,
            color = ZillitTheme.colors.textSecondary,
        )
    }
}

@Composable
private fun DepartmentList(editor: DepartmentOrderState, copy: CrewCopy, onEvent: (CrewListEvent) -> Unit) {
    val rows = editor.order.current
    val needle = editor.query.trim()
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // The one gesture nothing else on the screen advertises: a click opens the people inside.
        ZillitNotice(
            text = copy.t(
                "change_priority_designation_hint",
                str(S.desktop_cl_change_priority_designation_hint),
            ),
            tone = StatusTone.Progress,
        )
        ZillitSearchField(
            value = editor.query,
            onValueChange = { onEvent(CrewListEvent.Admin.SearchDepartments(it)) },
            placeholder = copy.t("placeholder", str(S.desktop_cl_search_department)),
            modifier = Modifier.fillMaxWidth(),
        )
        ReorderList(
            visible = rows.withIndex().filter { needle.isEmpty() || it.value.name.contains(needle, ignoreCase = true) },
            total = rows.size,
            key = { it.id },
            rowPitch = ROW_HEIGHT + ROW_GAP,
            gap = ROW_GAP,
            filtered = needle.isNotEmpty(),
            onMove = { from, to -> onEvent(CrewListEvent.Admin.MoveDepartment(from, to)) },
            modifier = Modifier.fillMaxWidth().height(LIST_HEIGHT),
        ) { index, department, lifted, handle, rowModifier ->
            OrderRow(
                position = index + 1,
                total = rows.size,
                lifted = lifted,
                dragHandle = handle,
                draggable = needle.isEmpty(),
                copy = copy,
                modifier = rowModifier.height(ROW_HEIGHT),
                onPosition = { position -> onEvent(CrewListEvent.Admin.PositionDepartment(index, position)) },
            ) { hovered ->
                ZillitText(
                    text = department.name,
                    style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = if (hovered) ZillitTheme.colors.accentText else ZillitTheme.colors.textPrimary,
                    maxLines = 1,
                    modifier = Modifier
                        .weight(1f)
                        .pointerHoverIcon(PointerIcon.Hand)
                        .clickable { onEvent(CrewListEvent.Admin.OpenPeople(department)) }
                        .padding(vertical = 6.dp),
                )
            }
        }
    }
}

/**
 * One department's people in their listing order — the web's `DragUser`:
 * faces, names and designations; drag or "Change Order"; Save keeps the list
 * open and says what the server said.
 */
@Composable
private fun PeopleOrderDialog(
    people: PeopleOrderState?,
    copy: CrewCopy,
    faces: suspend (String) -> ImageBitmap?,
    onEvent: (CrewListEvent) -> Unit,
) {
    val held = remember { arrayOfNulls<PeopleOrderState>(1) }
    people?.let { held[0] = it }
    val shown = people ?: held[0]
    ZillitDialogShell(
        title = shown?.order?.department?.name ?: str(S.desktop_cl_department_details),
        icon = ZillitIcons.Users,
        visible = people != null,
        onDismiss = { onEvent(CrewListEvent.Admin.ClosePeople) },
        width = 600.dp,
        scrollable = false,
        actions = {
            ZillitButton(
                text = copy.t("Close", str(S.close)),
                variant = ButtonVariant.Secondary,
                onClick = { onEvent(CrewListEvent.Admin.ClosePeople) },
            )
            ZillitButton(
                text = copy.t("save", str(S.save)),
                loading = shown?.saving == true,
                enabled = shown != null && !shown.saving && !shown.loading && shown.order.isChanged,
                onClick = { onEvent(CrewListEvent.Admin.SavePeople) },
            )
        },
    ) {
        when {
            shown == null || shown.loading ->
                Box(Modifier.fillMaxWidth().height(240.dp), contentAlignment = Alignment.Center) {
                    ZillitSpinner(size = 24.dp)
                }
            shown.failure != null -> ZillitText(shown.failure, color = ZillitTheme.colors.danger)
            shown.order.current.isEmpty() ->
                Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
                    ZillitText(copy.t("NODATAFOUND", str(S.no_data_found)), color = ZillitTheme.colors.textMuted)
                }
            else -> PeopleList(shown, copy, faces, onEvent)
        }
    }
}

@Composable
private fun PeopleList(
    people: PeopleOrderState,
    copy: CrewCopy,
    faces: suspend (String) -> ImageBitmap?,
    onEvent: (CrewListEvent) -> Unit,
) {
    val rows = people.order.current
    val needle = people.query.trim()
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ZillitSearchField(
            value = people.query,
            onValueChange = { onEvent(CrewListEvent.Admin.SearchPeople(it)) },
            placeholder = copy.t("Search", str(S.search)),
            modifier = Modifier.fillMaxWidth(),
        )
        ReorderList(
            visible = rows.withIndex().filter { (_, person) ->
                needle.isEmpty() || person.name.contains(needle, ignoreCase = true) ||
                    copy.label(person.designation).contains(needle, ignoreCase = true)
            },
            total = rows.size,
            key = { it.userId },
            rowPitch = PERSON_HEIGHT + ROW_GAP,
            gap = ROW_GAP,
            filtered = needle.isNotEmpty(),
            onMove = { from, to -> onEvent(CrewListEvent.Admin.MovePerson(from, to)) },
            modifier = Modifier.fillMaxWidth().height(LIST_HEIGHT),
        ) { index, person, lifted, handle, rowModifier ->
            val face by produceState<ImageBitmap?>(null, person.userId) { value = faces(person.userId) }
            OrderRow(
                position = index + 1,
                total = rows.size,
                lifted = lifted,
                dragHandle = handle,
                draggable = needle.isEmpty(),
                copy = copy,
                modifier = rowModifier.height(PERSON_HEIGHT),
                updateLabel = copy.t("change_order", str(S.desktop_cl_change_order)),
                onPosition = { position -> onEvent(CrewListEvent.Admin.PositionPerson(index, position)) },
            ) { _ ->
                ZillitAvatar(name = person.name, size = 36.dp, image = face)
                Column(Modifier.weight(1f)) {
                    ZillitText(
                        text = person.name,
                        style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                        maxLines = 1,
                    )
                    ZillitText(
                        text = "${copy.t("Designation", str(S.designation))}: ${copy.label(person.designation)}",
                        style = ZillitTheme.typography.bodySmall,
                        color = ZillitTheme.colors.textMuted,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/**
 * A sortable row: grip, priority badge, the row's own content, and — on hover
 * — a field to type the new position with its Update button, as the web
 * reveals it.
 */
@Composable
@Suppress("LongParameterList") // A row's parts, each drawn once.
private fun OrderRow(
    position: Int,
    total: Int,
    lifted: Boolean,
    dragHandle: Modifier,
    draggable: Boolean,
    copy: CrewCopy,
    modifier: Modifier,
    updateLabel: String? = null,
    onPosition: (Int) -> Unit,
    content: @Composable RowScope.(hovered: Boolean) -> Unit,
) {
    val colors = ZillitTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    var typed by remember { mutableStateOf("") }
    val fill by animateColorAsState(
        when {
            lifted -> colors.surfaceRaised
            hovered -> colors.surfaceHover
            else -> colors.surface
        },
        label = "orderRow",
    )
    Row(
        modifier = modifier
            .then(if (lifted) Modifier.shadow(10.dp, RoundedCornerShape(10.dp)) else Modifier)
            .clip(RoundedCornerShape(10.dp))
            .background(fill)
            .border(1.dp, if (lifted) colors.accent else colors.border, RoundedCornerShape(10.dp))
            .hoverable(interaction)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(6.dp))
                .then(if (draggable) dragHandle.pointerHoverIcon(PointerIcon.Hand) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            val grip = if (draggable) colors.textMuted else colors.textDisabled
            ZillitIcon(icon = CrewIcons.Grip, tint = grip, size = 16.dp)
        }
        PositionBadge(position)
        content(hovered)
        if (hovered || typed.isNotEmpty()) {
            PositionEditor(
                typed = typed,
                total = total,
                copy = copy,
                updateLabel = updateLabel,
                onType = { typed = it },
                onPosition = { position ->
                    onPosition(position)
                    typed = ""
                },
            )
        }
    }
}

@Composable
private fun PositionBadge(position: Int) {
    Box(
        Modifier
            .width(34.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(ZillitTheme.colors.accentSoft)
            .padding(vertical = 3.dp),
        contentAlignment = Alignment.Center,
    ) {
        ZillitText(
            text = position.toString(),
            style = ZillitTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
            color = ZillitTheme.colors.accentText,
            maxLines = 1,
        )
    }
}

/** The hover-revealed "move to" field and its button — Update for departments, Change Order for people. */
@Composable
private fun PositionEditor(
    typed: String,
    total: Int,
    copy: CrewCopy,
    updateLabel: String?,
    onType: (String) -> Unit,
    onPosition: (Int) -> Unit,
) {
    if (updateLabel == null) {
        ZillitText(
            text = copy.t("enter_priority", str(S.enter_priority_number_to_move_department)),
            style = ZillitTheme.typography.labelSmall,
            color = ZillitTheme.colors.textMuted,
            maxLines = 1,
        )
    }
    CrewCellField(
        value = typed,
        onValueChange = { raw -> onType(raw.filter { it in '0'..'9' }.take(MAX_POSITION_DIGITS)) },
        placeholder = "#",
        digits = true,
        modifier = Modifier.width(56.dp),
    )
    val number = typed.toIntOrNull()
    ZillitButton(
        text = updateLabel ?: copy.t("update", str(S.update)),
        variant = ButtonVariant.Secondary,
        size = ButtonSize.Small,
        enabled = number != null && number in 1..total,
        onClick = { number?.let(onPosition) },
    )
}

private val ROW_HEIGHT = 46.dp
private val PERSON_HEIGHT = 58.dp
private val ROW_GAP = 6.dp
private val LIST_HEIGHT = 400.dp
private const val MAX_POSITION_DIGITS = 4
