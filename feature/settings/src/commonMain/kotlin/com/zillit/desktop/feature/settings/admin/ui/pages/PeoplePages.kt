package com.zillit.desktop.feature.settings.admin.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.TagTone
import com.zillit.desktop.core.designsystem.component.ZillitAvatar
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitCheckbox
import com.zillit.desktop.core.designsystem.component.ZillitLazyColumn
import com.zillit.desktop.core.designsystem.component.ZillitSectionLabel
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitTag
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.settings.admin.domain.AccessType
import com.zillit.desktop.feature.settings.admin.domain.CrewMember
import com.zillit.desktop.feature.settings.admin.domain.RightsSection
import com.zillit.desktop.feature.settings.admin.domain.SosEntryType
import com.zillit.desktop.feature.settings.admin.domain.ToolRights
import com.zillit.desktop.feature.settings.admin.ui.AdminDestination
import com.zillit.desktop.feature.settings.admin.ui.AdminConfirmation
import com.zillit.desktop.feature.settings.admin.ui.AdminEvent
import com.zillit.desktop.feature.settings.admin.ui.AdminUiState
import com.zillit.desktop.feature.settings.admin.ui.RightsToggle

/**
 * The pages about people: the crew, their rights, and who is pre-approved.
 *
 * Grouped by subject rather than one file per page, because they read each
 * other's data — the rights page is the crew list with a second pane, and the
 * pre-approval form is filled from the department tree.
 */

/**
 * Crew and admins.
 *
 * Two switches per person and both of them matter: one decides whether they can
 * open the production at all, the other whether they can change it for everyone.
 * Granting admin asks first and revoking does not, matching both phone clients —
 * the asymmetry is right, because the dangerous direction is the one that hands
 * out power.
 */
@Composable
fun CrewPage(state: AdminUiState, onEvent: (AdminEvent) -> Unit, onBack: () -> Unit) {
    AdminPage(
        title = AdminDestination.Crew.title,
        description = str(S.desktop_crew_page_description),
        state = state,
        onEvent = onEvent,
        onBack = onBack,
        search = str(S.desktop_search_by_name_department_or_role),
        bodyScrolls = true,
    ) {
        val rows = state.crewMatching
        when {
            rows.isEmpty() && state.hasLoaded && state.query.isNotBlank() ->
                EmptyRow(str(S.desktop_nobody_matches_query, state.query))

            rows.isEmpty() && state.hasLoaded ->
                EmptyRow(str(S.desktop_nobody_has_joined))

            else -> {
                val listState = rememberLazyListState()
                ZillitLazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
                ) {
                    items(rows, key = { it.userId }) { person ->
                        RowCard { CrewRow(person, onEvent) }
                    }
                }
            }
        }
    }
}

@Composable
private fun CrewRow(person: CrewMember, onEvent: (AdminEvent) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(ZillitTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        ZillitAvatar(name = person.fullName, userId = person.userId)

        Column(Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
            ) {
                ZillitText(text = person.fullName, style = ZillitTheme.typography.titleSmall, maxLines = 1)
                if (person.isAdmin) ZillitTag(str(S.admin), tone = TagTone.Accent)
                if (!person.isActive) {
                    ZillitStatusPill(label = str(S.desktop_off_the_project), tone = StatusTone.Neutral)
                }
            }
            // Department and role arrive as translation keys, like everywhere
            // else the crew list is shown.
            val role = listOfNotNull(person.department, person.designation)
                .joinToString(" · ") { it.localised() }
            ZillitText(
                text = role.ifBlank { person.email.orEmpty() },
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textMuted,
                maxLines = 1,
            )
        }

        // Withheld rather than disabled when there is no device to key the
        // change on — a switch that always refuses is worse than none.
        if (person.deviceId != null) {
            ZillitCheckbox(
                checked = person.isActive,
                onCheckedChange = { onEvent(AdminEvent.CrewActiveChanged(person.userId, it)) },
                label = str(S.desktop_on_the_project),
            )
        }
        ZillitCheckbox(
            checked = person.isAdmin,
            onCheckedChange = { onEvent(AdminEvent.AdminAccessChanged(person.userId, it)) },
            label = str(S.desktop_administrator),
            // An admin who is off the production is a state worth being able to
            // undo, but not one worth being able to create.
            enabled = person.isActive,
        )
    }
}

/**
 * The permission grid — one person at a time.
 *
 * The web renders every crew member against every tool as one enormous table.
 * This picks a person and shows theirs, which is the same task with a shape
 * that fits on a screen and cannot silently misalign a column. See
 * [ToolRights] for the full argument.
 */
@Composable
fun RightsPage(state: AdminUiState, onEvent: (AdminEvent) -> Unit, onBack: () -> Unit) {
    AdminPage(
        title = AdminDestination.Rights.title,
        description = str(S.desktop_rights_grid_detail),
        state = state,
        onEvent = onEvent,
        onBack = onBack,
        search = str(S.desktop_search_by_name_or_role),
        bodyScrolls = true,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().weight(1f),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        ) {
            CrewPicker(state, onEvent, Modifier.width(PICKER_WIDTH))
            RightsPanel(state, onEvent, Modifier.weight(1f))
        }
    }
}

@Composable
private fun CrewPicker(state: AdminUiState, onEvent: (AdminEvent) -> Unit, modifier: Modifier) {
    val rows = state.crewMatching
    RowCard(modifier) {
        if (rows.isEmpty() && state.hasLoaded) {
            EmptyRow(if (state.query.isBlank()) str(S.desktop_no_crew_yet) else str(S.desktop_nobody_matches))
            return@RowCard
        }

        val listState = rememberLazyListState()
        ZillitLazyColumn(state = listState) {
            items(rows, key = { it.userId }) { person ->
                SelectableRow(
                    selected = person.userId == state.selection.userId,
                    onClick = { onEvent(AdminEvent.SelectCrew(person.userId)) },
                ) {
                    Column(Modifier.weight(1f)) {
                        ZillitText(
                            text = person.fullName,
                            style = ZillitTheme.typography.bodyMedium,
                            maxLines = 1,
                        )
                        person.designation?.let { role ->
                            ZillitText(
                                text = role.localised(),
                                style = ZillitTheme.typography.labelSmall,
                                color = ZillitTheme.colors.textMuted,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RightsPanel(state: AdminUiState, onEvent: (AdminEvent) -> Unit, modifier: Modifier) {
    val person = state.selectedCrew
    if (person == null) {
        RowCard(modifier) { EmptyRow(str(S.desktop_pick_someone_for_rights)) }
        return
    }

    if (person.isAdmin) {
        // An admin passes every check by definition. Saying so beats a page of
        // ticked boxes that spring back — their access is changed by taking
        // their admin rights away, not here.
        RowCard(modifier) {
            EmptyRow(str(S.desktop_admin_reaches_everything, person.fullName))
        }
        return
    }

    if (state.selection.isLoadingRights) {
        RowCard(modifier) { EmptyRow(str(S.desktop_reading_access, person.fullName)) }
        return
    }

    val listState = rememberLazyListState()
    ZillitLazyColumn(
        state = listState,
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        RightsSection.entries.forEach { section ->
            val tools = state.rights(section)
            if (tools.isEmpty()) return@forEach

            item(key = "header-${section.wire}") {
                ZillitSectionLabel(section.label)
            }
            item(key = "card-${section.wire}") {
                RowCard {
                    tools.forEachIndexed { index, rights ->
                        if (index > 0) RowRule()
                        RightsRow(rights, onEvent)
                    }
                }
            }
        }

        if (RightsSection.entries.all { state.rights(it).isEmpty() }) {
            item { RowCard { EmptyRow(str(S.desktop_no_tools_to_grant)) } }
        }
    }
}

@Composable
private fun RightsRow(rights: ToolRights, onEvent: (AdminEvent) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(ZillitTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        ZillitText(
            text = rights.toolName.localised(),
            style = ZillitTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
            maxLines = 1,
        )
        AccessType.entries.forEach { access ->
            ZillitCheckbox(
                checked = rights.granted(access),
                onCheckedChange = { on ->
                    onEvent(
                        AdminEvent.RightsToggled(
                            RightsToggle(rights.toolIdentifier, rights.section, access, on),
                        ),
                    )
                },
                label = access.label,
                // The server says which of these are not this admin's to
                // change. Disabled rather than springing back after the call.
                enabled = !rights.locked(access),
            )
        }
    }
}

/**
 * Pre-approved crew.
 *
 * People who skip the approval queue: they use the production code and are let
 * straight in with the department and role set here. Read-only once added — a
 * pre-approval that could be edited would be a placement someone may already be
 * holding.
 */
@Composable
fun PreApprovedPage(state: AdminUiState, onEvent: (AdminEvent) -> Unit, onBack: () -> Unit) {
    AdminPage(
        title = AdminDestination.PreApproved.title,
        description = str(S.desktop_pre_approved_page_description),
        state = state,
        onEvent = onEvent,
        onBack = onBack,
        search = str(S.desktop_search_by_name_or_email),
        action = {
            ZillitButton(
                text = str(S.desktop_pre_approve_someone),
                onClick = { onEvent(AdminEvent.OpenPreApproval()) },
                size = ButtonSize.Small,
                variant = ButtonVariant.Primary,
                // The form needs a department and a role to offer, and both
                // come from the department tree.
                enabled = state.departments.isNotEmpty(),
            )
        },
    ) {
        val rows = state.preApprovedMatching
        RowCard {
            when {
                rows.isEmpty() && state.hasLoaded && state.query.isNotBlank() ->
                    EmptyRow(str(S.desktop_nobody_matches_query, state.query))

                rows.isEmpty() && state.hasLoaded ->
                    EmptyRow(str(S.desktop_nobody_pre_approved))

                else -> rows.forEachIndexed { index, person ->
                    if (index > 0) RowRule()
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(ZillitTheme.spacing.md),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
                    ) {
                        ZillitAvatar(name = person.fullName)
                        Column(Modifier.weight(1f)) {
                            ZillitText(
                                text = person.fullName,
                                style = ZillitTheme.typography.titleSmall,
                                maxLines = 1,
                            )
                            val detail = listOfNotNull(
                                person.designationName?.localised(),
                                person.departmentName?.localised(),
                                person.unitName?.localised(),
                                person.email,
                            ).joinToString(" · ")
                            ZillitText(
                                text = detail,
                                style = ZillitTheme.typography.bodySmall,
                                color = ZillitTheme.colors.textMuted,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * SOS recipients.
 *
 * Who is alerted when someone on this production raises an SOS. Two kinds, and
 * the difference is real: a crew recipient is named by their user id and the
 * server fills in their number, an outsider is a number typed in — a unit
 * nurse, a local fixer, a hospital.
 */
@Suppress("LongMethod") // One page; its length is the row it renders.
@Composable
fun SosPage(state: AdminUiState, onEvent: (AdminEvent) -> Unit, onBack: () -> Unit) {
    AdminPage(
        title = AdminDestination.Sos.title,
        description = str(S.desktop_sos_page_description),
        state = state,
        onEvent = onEvent,
        onBack = onBack,
        action = {
            ZillitButton(
                text = str(S.desktop_cal_add_crew),
                onClick = { onEvent(AdminEvent.OpenSos(SosEntryType.Crew)) },
                size = ButtonSize.Small,
                variant = ButtonVariant.Secondary,
                enabled = state.crew.isNotEmpty(),
            )
            ZillitButton(
                text = str(S.desktop_add_outsider),
                onClick = { onEvent(AdminEvent.OpenSos(SosEntryType.Outsider)) },
                size = ButtonSize.Small,
            )
        },
    ) {
        RowCard {
            if (state.sos.isEmpty() && state.hasLoaded) {
                // Not a neutral empty state. An SOS with nobody to alert is a
                // safety gap, and the page should say so rather than look tidy.
                EmptyRow(str(S.desktop_nobody_alerted))
                return@RowCard
            }

            state.sos.forEachIndexed { index, recipient ->
                if (index > 0) RowRule()
                Row(
                    modifier = Modifier.fillMaxWidth().padding(ZillitTheme.spacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
                ) {
                    ZillitAvatar(name = recipient.name, userId = recipient.userId)
                    Column(Modifier.weight(1f)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
                        ) {
                            ZillitText(
                                text = recipient.name,
                                style = ZillitTheme.typography.titleSmall,
                                maxLines = 1,
                            )
                            ZillitTag(
                                label = if (recipient.entryType == SosEntryType.Crew) str(S.crew) else str(S.outsider),
                                tone = TagTone.Neutral,
                            )
                        }
                        val detail = listOfNotNull(
                            recipient.relationship?.localised(),
                            recipient.designation?.localised(),
                            recipient.dialled,
                        ).joinToString(" · ")
                        ZillitText(
                            text = detail,
                            style = ZillitTheme.typography.bodySmall,
                            color = ZillitTheme.colors.textMuted,
                            maxLines = 1,
                        )
                    }
                    RemoveButton(str(S.remove)) {
                        onEvent(
                            AdminEvent.Ask(AdminConfirmation.RemoveSos(recipient.id, recipient.name)),
                        )
                    }
                }
            }
        }
    }
}

private val PICKER_WIDTH = 260.dp
