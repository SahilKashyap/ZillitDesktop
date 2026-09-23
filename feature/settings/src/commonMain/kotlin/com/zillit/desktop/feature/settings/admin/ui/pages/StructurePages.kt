package com.zillit.desktop.feature.settings.admin.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.TagTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitCheckbox
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitSectionLabel
import com.zillit.desktop.core.designsystem.component.ZillitTag
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.settings.admin.domain.Department
import com.zillit.desktop.feature.settings.admin.domain.ProductionTool
import com.zillit.desktop.feature.settings.admin.domain.ToolGroup
import com.zillit.desktop.feature.settings.admin.ui.AdminDestination
import com.zillit.desktop.feature.settings.admin.ui.AdminConfirmation
import com.zillit.desktop.feature.settings.admin.ui.AdminEvent
import com.zillit.desktop.feature.settings.admin.ui.AdminUiState
import com.zillit.desktop.feature.settings.admin.ui.NameKind

/**
 * The pages about how the production is arranged: departments, job titles, the
 * crew list's order, and the tools.
 *
 * What they share is the `system_defined` rule — every one of these lists ships
 * with entries the production did not create and cannot delete, and each page
 * withholds the delete rather than offering one the server refuses.
 */

/**
 * Departments.
 *
 * Add and delete only; there is no rename route on any client, and inventing
 * one here would be a button that 404s.
 */
@Composable
fun DepartmentsPage(state: AdminUiState, onEvent: (AdminEvent) -> Unit, onBack: () -> Unit) {
    AdminPage(
        title = AdminDestination.Departments.title,
        description = str(S.desktop_departments_page_description),
        state = state,
        onEvent = onEvent,
        onBack = onBack,
        search = str(S.invitees_search_departments),
        action = {
            ZillitButton(
                text = str(S.desktop_new_department),
                onClick = { onEvent(AdminEvent.OpenName(NameKind.Department)) },
                size = ButtonSize.Small,
            )
        },
    ) {
        RowCard {
            val rows = state.departmentsMatching
            when {
                rows.isEmpty() && state.hasLoaded && state.query.isNotBlank() ->
                    EmptyRow(str(S.desktop_no_department_matches, state.query))

                rows.isEmpty() && state.hasLoaded ->
                    EmptyRow(str(S.desktop_no_departments_yet_period))

                else -> rows.forEachIndexed { index, department ->
                    if (index > 0) RowRule()
                    DepartmentRow(department, onEvent)
                }
            }
        }
    }
}

@Composable
private fun DepartmentRow(department: Department, onEvent: (AdminEvent) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(ZillitTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        Column(Modifier.weight(1f)) {
            ZillitText(
                text = department.name.localised(),
                style = ZillitTheme.typography.titleSmall,
                maxLines = 1,
            )
            ZillitText(
                text = when (department.jobTitles.size) {
                    0 -> str(S.desktop_no_job_titles)
                    1 -> str(S.desktop_job_title_count_one, 1)
                    else -> str(S.desktop_job_title_count_other, department.jobTitles.size)
                },
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textMuted,
            )
        }

        if (department.systemDefined) {
            // Says why there is no delete, rather than leaving a gap where the
            // other rows have a button.
            ZillitTag(str(S.desktop_built_in), tone = TagTone.Neutral)
        } else {
            RemoveButton(str(S.delete)) {
                onEvent(
                    AdminEvent.Ask(
                        AdminConfirmation.RemoveDepartment(department.id, department.name.localised()),
                    ),
                )
            }
        }
    }
}

/**
 * Job titles, inside a department.
 *
 * Two panes rather than a page per department: a job title only exists inside
 * one, and the server nests them in the same call, so the department list is
 * already loaded and picking one costs nothing.
 */
@Suppress("LongMethod") // Two panes of one page; splitting hides the pairing.
@Composable
fun JobTitlesPage(state: AdminUiState, onEvent: (AdminEvent) -> Unit, onBack: () -> Unit) {
    val department = state.selectedDepartment

    AdminPage(
        title = AdminDestination.JobTitles.title,
        description = str(S.desktop_designations_detail),
        state = state,
        onEvent = onEvent,
        onBack = onBack,
        action = {
            ZillitButton(
                text = str(S.desktop_new_job_title),
                onClick = { onEvent(AdminEvent.OpenName(NameKind.JobTitle)) },
                size = ButtonSize.Small,
                // Nothing to add it to until a department is picked, and the
                // call is keyed on one.
                enabled = department != null,
            )
        },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().weight(1f),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        ) {
            RowCard(Modifier.width(PICKER_WIDTH)) {
                if (state.departments.isEmpty() && state.hasLoaded) {
                    EmptyRow(str(S.desktop_no_departments_yet))
                }
                state.departments.forEachIndexed { index, row ->
                    if (index > 0) RowRule()
                    SelectableRow(
                        selected = row.id == state.selection.departmentId,
                        onClick = { onEvent(AdminEvent.SelectDepartment(row.id)) },
                    ) {
                        ZillitText(
                            text = row.name.localised(),
                            style = ZillitTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                        )
                        ZillitText(
                            text = row.jobTitles.size.toString(),
                            style = ZillitTheme.typography.labelSmall,
                            color = ZillitTheme.colors.textMuted,
                        )
                    }
                }
            }

            RowCard(Modifier.weight(1f)) {
                when {
                    department == null -> EmptyRow(str(S.desktop_pick_department_for_job_titles))

                    department.jobTitles.isEmpty() ->
                        EmptyRow(str(S.desktop_department_has_no_job_titles_yet, department.name.localised()))

                    else -> department.jobTitles.forEachIndexed { index, title ->
                        if (index > 0) RowRule()
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(ZillitTheme.spacing.md),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                        ) {
                            ZillitText(
                                text = title.name.localised(),
                                style = ZillitTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                            )
                            if (title.systemDefined) {
                                ZillitTag(str(S.desktop_built_in), tone = TagTone.Neutral)
                            } else {
                                RemoveButton(str(S.delete)) {
                                    onEvent(
                                        AdminEvent.Ask(
                                            AdminConfirmation.RemoveJobTitle(
                                                departmentId = department.id,
                                                id = title.id,
                                                name = title.name.localised(),
                                            ),
                                        ),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * The order departments appear in on the crew list.
 *
 * Arranged locally and saved in one go, matching all three clients. A request
 * per nudge would be a dozen requests to move one department three places, and
 * an admin who walks away mid-arrangement would leave a half-sorted list on the
 * server.
 *
 * Buttons rather than drag: the phone clients drag because a phone has no other
 * gesture, and a keyboard-reachable pair of arrows is both easier to aim and
 * the only version a screen reader can use.
 */
@Suppress("LongMethod") // One page; its length is the row it renders.
@Composable
fun CrewOrderPage(
    state: AdminUiState,
    onEvent: (AdminEvent) -> Unit,
    onBack: () -> Unit,
    /** Corporate and event productions call the same page a staff list. */
    isOtherType: Boolean,
) {
    val order = state.selection.order
    val changed = state.selection.isReordered(state.departments)

    AdminPage(
        // The only page whose name depends on the production: Android swaps
        // the same two words (`set_department_priority_staff_list`).
        title = if (isOtherType) str(S.set_department_priority_staff_list) else str(S.desktop_crew_list_order_crew),
        description = if (isOtherType) {
            str(S.desktop_crew_list_order_description_staff)
        } else {
            str(S.desktop_crew_list_order_description_crew)
        },
        state = state,
        onEvent = onEvent,
        onBack = onBack,
        action = {
            ZillitButton(
                text = str(S.reset),
                onClick = { onEvent(AdminEvent.ResetOrder) },
                size = ButtonSize.Small,
                variant = ButtonVariant.Tertiary,
                enabled = changed,
            )
            ZillitButton(
                text = str(S.desktop_save_order),
                onClick = { onEvent(AdminEvent.SaveOrder) },
                size = ButtonSize.Small,
                // Nothing moved, nothing to save. A live Save on an unchanged
                // list invites a pointless write.
                enabled = changed && !state.isSaving,
                loading = state.isSaving,
            )
        },
    ) {
        if (changed) {
            ZillitText(
                text = if (isOtherType) str(S.desktop_order_not_saved_staff) else str(S.desktop_order_not_saved_crew),
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.warning,
            )
        }

        RowCard {
            if (order.isEmpty() && state.hasLoaded) {
                EmptyRow(str(S.desktop_no_departments_to_order))
            }
            order.forEachIndexed { index, department ->
                if (index > 0) RowRule()
                Row(
                    modifier = Modifier.fillMaxWidth().padding(ZillitTheme.spacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                ) {
                    ZillitText(
                        text = "${index + 1}",
                        style = ZillitTheme.typography.labelSmall,
                        color = ZillitTheme.colors.textMuted,
                        modifier = Modifier.width(POSITION_WIDTH),
                    )
                    ZillitText(
                        text = department.name.localised(),
                        style = ZillitTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                    )
                    // Turned over, because the icon set has no chevron pointing
                    // up. Without this both buttons are the same downward
                    // chevron and the only thing telling them apart is which
                    // one you happen to click — the label is not visible.
                    ZillitIconButton(
                        icon = ZillitIcons.ChevronDown,
                        contentDescription = str(S.desktop_move_up, department.name.localised()),
                        onClick = { onEvent(AdminEvent.MoveDepartment(department.id, -1)) },
                        enabled = index > 0,
                        modifier = Modifier.rotate(HALF_TURN),
                    )
                    ZillitIconButton(
                        icon = ZillitIcons.ChevronDown,
                        contentDescription = str(S.desktop_move_down, department.name.localised()),
                        onClick = { onEvent(AdminEvent.MoveDepartment(department.id, 1)) },
                        enabled = index < order.lastIndex,
                    )
                }
            }
        }
    }
}

/**
 * Which tools this production runs.
 *
 * A checklist saved in one go, not a switch per row that writes immediately.
 * Both phone clients do the same, and the reason is the confirmation: switching
 * a tool off hides everything posted in it for everybody, and an admin working
 * down a list of forty should be able to change their mind before any of it
 * lands.
 */
@Composable
fun ToolAvailabilityPage(state: AdminUiState, onEvent: (AdminEvent) -> Unit, onBack: () -> Unit) {
    AdminPage(
        title = AdminDestination.ToolAvailability.title,
        description = str(S.desktop_tools_page_description),
        state = state,
        onEvent = onEvent,
        onBack = onBack,
        search = str(S.tools_search_hint),
        action = {
            ZillitButton(
                text = str(S.desktop_save_tools),
                onClick = { onEvent(AdminEvent.SaveTools) },
                size = ButtonSize.Small,
                enabled = !state.isSaving && state.tools.isNotEmpty(),
                loading = state.isSaving,
            )
        },
    ) {
        RowCard {
            val rows = state.toolsMatching
            if (rows.isEmpty() && state.hasLoaded) {
                EmptyRow(
                    if (state.query.isBlank()) {
                        str(S.desktop_no_tools_to_configure)
                    } else {
                        str(S.desktop_no_tool_matches, state.query)
                    },
                )
            }
            rows.forEachIndexed { index, tool ->
                if (index > 0) RowRule()
                ToolRow(tool, onEvent)
            }
        }
    }
}

@Composable
private fun ToolRow(tool: ProductionTool, onEvent: (AdminEvent) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(ZillitTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        ZillitText(
            text = tool.name.localised(),
            style = ZillitTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
            maxLines = 1,
        )
        if (tool.isLocked) {
            // These two are how an admin undoes a mistake made on this very
            // page. Both phone clients refuse to switch them off, and so does
            // the server.
            ZillitTag(str(S.desktop_always_on), tone = TagTone.Neutral)
        }
        ZillitCheckbox(
            checked = tool.enabled,
            onCheckedChange = { onEvent(AdminEvent.ToolEnabledChanged(tool.identifier, it)) },
            enabled = !tool.isLocked,
        )
    }
}

/**
 * Tool groups — the headings on the Film Tools grid.
 *
 * Six ship with the product and can be renamed but not deleted; an admin adds
 * more. Moving a tool is immediate, unlike the availability page, because it is
 * one tool and it is reversible in a click.
 */
@Composable
fun ToolGroupsPage(state: AdminUiState, onEvent: (AdminEvent) -> Unit, onBack: () -> Unit) {
    AdminPage(
        title = AdminDestination.ToolGroups.title,
        description = str(S.desktop_tool_groups_description),
        state = state,
        onEvent = onEvent,
        onBack = onBack,
        action = {
            ZillitButton(
                text = str(S.desktop_new_group),
                onClick = { onEvent(AdminEvent.OpenName(NameKind.ToolGroup)) },
                size = ButtonSize.Small,
            )
        },
    ) {
        ZillitSectionLabel(str(S.groups_txt))
        RowCard {
            if (state.toolGroups.isEmpty() && state.hasLoaded) {
                EmptyRow(str(S.desktop_no_groups_on_project))
            }
            state.toolGroups.forEachIndexed { index, group ->
                if (index > 0) RowRule()
                ToolGroupRow(group, state.tools, onEvent)
            }
        }

        ZillitSectionLabel(str(S.desktop_where_each_tool_sits))
        RowCard {
            if (state.tools.isEmpty() && state.hasLoaded) EmptyRow(str(S.desktop_no_tools_to_place))
            state.tools.forEachIndexed { index, tool ->
                if (index > 0) RowRule()
                ToolPlacementRow(tool, state.toolGroups, onEvent)
            }
        }
    }
}

@Composable
private fun ToolGroupRow(
    group: ToolGroup,
    tools: List<ProductionTool>,
    onEvent: (AdminEvent) -> Unit,
) {
    val inGroup = tools.count { it.groupIdentifier == group.identifier }

    Row(
        modifier = Modifier.fillMaxWidth().padding(ZillitTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        Column(Modifier.weight(1f)) {
            ZillitText(
                text = group.name.localised(),
                style = ZillitTheme.typography.titleSmall,
                maxLines = 1,
            )
            ZillitText(
                text = if (inGroup == 1) {
                    str(S.desktop_tool_count_one, inGroup)
                } else {
                    str(S.desktop_tool_count_other, inGroup)
                },
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textMuted,
            )
        }

        ZillitButton(
            text = str(S.rename),
            onClick = {
                onEvent(
                    AdminEvent.OpenName(
                        kind = NameKind.ToolGroup,
                        targetId = group.id,
                        initial = group.name.localised(),
                    ),
                )
            },
            size = ButtonSize.Small,
            variant = ButtonVariant.Tertiary,
        )

        when {
            !group.isDeletable -> ZillitTag(str(S.desktop_built_in), tone = TagTone.Neutral)

            // The server refuses this with `tool_group_in_use`. Checked here so
            // the reader is told what to do rather than shown a rejection.
            inGroup > 0 -> ZillitTag(str(S.desktop_move_its_tools_first), tone = TagTone.Neutral)

            else -> RemoveButton(str(S.delete)) {
                onEvent(
                    AdminEvent.Ask(AdminConfirmation.RemoveToolGroup(group.id, group.name.localised())),
                )
            }
        }
    }
}

@Composable
private fun ToolPlacementRow(
    tool: ProductionTool,
    groups: List<ToolGroup>,
    onEvent: (AdminEvent) -> Unit,
) {
    // "Not in a group" is a real destination, not the absence of one — a tool
    // out of every group still shows on the grid, ungrouped.
    val options = listOf(UNGROUPED) + groups
    val current = groups.firstOrNull { it.identifier == tool.groupIdentifier } ?: UNGROUPED

    Row(
        modifier = Modifier.fillMaxWidth().padding(ZillitTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        ZillitText(
            text = tool.name.localised(),
            style = ZillitTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
            maxLines = 1,
        )
        com.zillit.desktop.core.designsystem.component.ZillitSelect(
            value = current,
            options = options,
            onSelect = { chosen -> onEvent(AdminEvent.MoveTool(tool.identifier, chosen.identifier)) },
            label = { it.name.localised() },
            modifier = Modifier.width(GROUP_PICKER_WIDTH),
        )
    }
}

/** The synthetic option for a tool that belongs to no group. */
private val UNGROUPED: ToolGroup get() = ToolGroup(id = "", identifier = "", name = str(S.desktop_not_in_a_group))

private val PICKER_WIDTH = 260.dp
private val GROUP_PICKER_WIDTH = 220.dp
private val POSITION_WIDTH = 24.dp

/** Half a turn, to point a downward chevron up. */
private const val HALF_TURN = 180f
