package com.zillit.desktop.feature.settings.admin.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ColumnWidth
import com.zillit.desktop.core.designsystem.component.TableColumn
import com.zillit.desktop.core.designsystem.component.ZillitAvatar
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitCheckbox
import com.zillit.desktop.core.designsystem.component.ZillitDataTable
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitSearchField
import com.zillit.desktop.core.designsystem.component.ZillitSelect
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitSwitch
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.zillitVerticalScroll
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.settings.account.allowsPrivateName
import com.zillit.desktop.feature.settings.admin.domain.AccessType
import com.zillit.desktop.feature.settings.admin.domain.AdminUnit
import com.zillit.desktop.feature.settings.admin.domain.CrewMember
import com.zillit.desktop.feature.settings.admin.domain.CrewStatus
import com.zillit.desktop.feature.settings.admin.domain.Department
import com.zillit.desktop.feature.settings.admin.domain.JobTitle
import com.zillit.desktop.feature.settings.admin.domain.RightsSection
import com.zillit.desktop.feature.settings.admin.domain.ToolRights
import com.zillit.desktop.feature.settings.admin.domain.UnitKind
import com.zillit.desktop.feature.settings.admin.ui.AdminConfirmation
import com.zillit.desktop.feature.settings.admin.ui.AdminEvent
import com.zillit.desktop.feature.settings.admin.ui.AdminForm
import com.zillit.desktop.feature.settings.admin.ui.AdminUiState

/**
 * The dialogs User Management opens, each after its web counterpart:
 * `AllUserInfo.jsx` (Change Profile and the admin confirmation),
 * `AlluserPostingRights.jsx` and `Allowchatuser.jsx`.
 */

/**
 * Change Profile — department, designation, unit and, for the three
 * designations that may hide a name, Keep Name Private. The web's footer is a
 * lone Submit, and so is this one.
 */
@Composable
internal fun EditCrewDialog(form: AdminForm.EditCrew, state: AdminUiState, onEvent: (AdminEvent) -> Unit) {
    val department = state.departments.firstOrNull { it.id == form.departmentId }
    val designation = department?.jobTitles?.firstOrNull { it.id == form.designationId }
    // The dashboard's own sections arrive on the same list and are no unit to join.
    val units = state.joinUnits.filter { it.identifier !in DASHBOARD_UNITS }

    ZillitDialogShell(
        title = "${str(S.change_profile)} - ${form.name}",
        visible = true,
        onDismiss = { onEvent(AdminEvent.CloseForm) },
        actions = {
            ZillitButton(
                text = str(S.submit),
                onClick = { onEvent(AdminEvent.SubmitForm) },
                enabled = !state.isSaving,
                loading = state.isSaving,
            )
        },
    ) {
        FieldLabel(str(S.desktop_um_change_department))
        ZillitSelect(
            value = department ?: NoDepartment,
            options = state.departments,
            // The designations belong to the department, so the old one goes.
            onSelect = { onEvent(AdminEvent.EditCrewChanged(form.copy(departmentId = it.id, designationId = null))) },
            label = { it.name.localised() },
            searchable = true,
            modifier = Modifier.fillMaxWidth(),
        )

        FieldLabel(str(S.desktop_um_change_designation))
        ZillitSelect(
            value = designation ?: NoDesignation,
            options = department?.jobTitles.orEmpty(),
            onSelect = { onEvent(AdminEvent.EditCrewChanged(form.copy(designationId = it.id))) },
            label = { it.name.localised() },
            searchable = true,
            enabled = department != null,
            modifier = Modifier.fillMaxWidth(),
        )

        if (form.withUnit) {
            FieldLabel(str(S.desktop_um_change_unit))
            ZillitSelect(
                value = units.firstOrNull { it.id == form.unitId } ?: NoUnit,
                options = units,
                onSelect = { onEvent(AdminEvent.EditCrewChanged(form.copy(unitId = it.id))) },
                label = { it.name.localised() },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (allowsPrivateName(designation?.name)) {
            ZillitSwitch(
                checked = form.keepNamePrivate,
                onCheckedChange = { onEvent(AdminEvent.EditCrewChanged(form.copy(keepNamePrivate = it))) },
                label = str(S.keep_name_private),
            )
        }

        form.error?.let { DialogError(it) }
    }
}

/**
 * Posting Rights for one person: who they are and a Home / Tools picker on
 * top, then the tools of that list with a switch per right. A switch the
 * server says is not this admin's to move is drawn disabled.
 */
@Composable
internal fun PostingRightsDialog(
    form: AdminForm.PostingRights,
    state: AdminUiState,
    onEvent: (AdminEvent) -> Unit,
) {
    val person = state.crew.firstOrNull { it.userId == form.userId }

    ZillitDialogShell(
        title = str(S.posting_rights),
        visible = true,
        onDismiss = { onEvent(AdminEvent.CloseForm) },
        width = WIDE_DIALOG,
        scrollable = false,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(ZillitTheme.shapes.medium)
                .border(1.dp, ZillitTheme.colors.border, ZillitTheme.shapes.medium)
                .padding(ZillitTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        ) {
            ZillitAvatar(name = person?.fullName.orEmpty(), userId = form.userId, size = HEADER_PICTURE)
            Column(Modifier.weight(1f)) {
                ZillitText(text = person?.fullName.orEmpty(), style = ZillitTheme.typography.titleSmall)
                ZillitText(
                    text = person?.designation?.localised().orEmpty(),
                    style = ZillitTheme.typography.bodySmall,
                    color = ZillitTheme.colors.textMuted,
                )
            }
            ZillitSelect(
                value = form.section,
                options = RightsSection.entries,
                onSelect = { onEvent(AdminEvent.RightsSectionChanged(it)) },
                label = { it.label },
                showInitials = false,
                searchable = false,
                modifier = Modifier.weight(1f),
            )
        }

        if (form.isLoading) {
            Box(Modifier.fillMaxWidth().height(RIGHTS_HEIGHT), contentAlignment = Alignment.Center) {
                ZillitSpinner()
            }
        } else {
            ZillitDataTable(
                rows = form.shown,
                columns = rightsColumns(form, onEvent),
                key = { it.unitId },
                modifier = Modifier.fillMaxWidth().height(RIGHTS_HEIGHT),
            )
        }

        form.error?.let { DialogError(it) }
    }
}

private fun rightsColumns(
    form: AdminForm.PostingRights,
    onEvent: (AdminEvent) -> Unit,
): List<TableColumn<ToolRights>> {
    fun rightColumn(header: String, access: AccessType) =
        TableColumn<ToolRights>(header, ColumnWidth.Fixed(RIGHT_COLUMN)) { row ->
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                ZillitSwitch(
                    checked = row.granted(access),
                    onCheckedChange = { onEvent(AdminEvent.RightToggled(row.unitId, access, it)) },
                    enabled = row.updatable(access) && (row.unitId + access.wire) !in form.busy,
                )
            }
        }

    return listOf(
        TableColumn(form.section.label, ColumnWidth.Weight(1f)) { row ->
            ZillitText(text = row.name.localised(), style = ZillitTheme.typography.bodyMedium, maxLines = 1)
        },
        rightColumn(str(S.posting_rights), AccessType.Post),
        rightColumn(str(S.viewing_rights), AccessType.View),
        rightColumn(str(S.desktop_um_downloading_rights), AccessType.Download),
    )
}

/**
 * Who a private-name crew member may chat with: everyone on the production
 * but them and the reader, ticked by who is allowed now. Select All and
 * Submit sit in the footer, as on the web.
 */
@Composable
internal fun AllowChatDialog(form: AdminForm.AllowChat, state: AdminUiState, onEvent: (AdminEvent) -> Unit) {
    val target = state.crew.firstOrNull { it.userId == form.userId }
    val candidates = chatCandidates(form, state)
    val allTicked = candidates.isNotEmpty() && candidates.all { it.userId in form.selected }

    ZillitDialogShell(
        title = str(S.select_user),
        visible = true,
        onDismiss = { onEvent(AdminEvent.CloseForm) },
        width = WIDE_DIALOG,
        scrollable = false,
        actions = {
            ZillitCheckbox(
                checked = allTicked,
                onCheckedChange = {
                    val ids = candidates.map(CrewMember::userId)
                    val selected = if (allTicked) form.selected - ids.toSet() else form.selected + ids
                    onEvent(AdminEvent.AllowChatChanged(form.copy(selected = selected)))
                },
                label = str(S.select_all),
                enabled = candidates.isNotEmpty(),
            )
            ZillitButton(
                text = str(S.submit),
                onClick = { onEvent(AdminEvent.SubmitForm) },
                enabled = !form.isLoading && candidates.isNotEmpty() && !state.isSaving,
                loading = state.isSaving,
            )
        },
    ) {
        ZillitSearchField(
            value = form.query,
            onValueChange = { onEvent(AdminEvent.AllowChatChanged(form.copy(query = it))) },
            placeholder = str(S.search_by_user_name),
            modifier = Modifier.fillMaxWidth(),
        )
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            ZillitAvatar(name = target?.fullName.orEmpty(), userId = form.userId, size = TARGET_PICTURE)
        }

        ChatCandidates(form, candidates, onEvent)
        form.error?.let { DialogError(it) }
    }
}

/**
 * Everyone the person could be allowed to chat with: the web's filter — not
 * someone who left or is pending, not the reader, not the person themselves —
 * then its search by name.
 */
private fun chatCandidates(form: AdminForm.AllowChat, state: AdminUiState): List<CrewMember> =
    state.crew.filter {
        it.status != CrewStatus.Left && it.status != CrewStatus.Pending &&
            it.userId != state.selfUserId && it.userId != form.userId &&
            it.fullName.contains(form.query.trim(), ignoreCase = true)
    }

@Composable
private fun ChatCandidates(form: AdminForm.AllowChat, candidates: List<CrewMember>, onEvent: (AdminEvent) -> Unit) {
    Column(Modifier.fillMaxWidth().heightIn(max = CHAT_LIST_HEIGHT).zillitVerticalScroll()) {
        when {
            form.isLoading -> Box(Modifier.fillMaxWidth().padding(ZillitTheme.spacing.lg), Alignment.Center) {
                ZillitSpinner()
            }
            candidates.isEmpty() -> EmptyRow(str(S.no_user_found))
            else -> candidates.forEachIndexed { index, person ->
                if (index > 0) RowRule()
                ChatCandidateRow(person, ticked = person.userId in form.selected) { on ->
                    val selected = if (on) form.selected + person.userId else form.selected - person.userId
                    onEvent(AdminEvent.AllowChatChanged(form.copy(selected = selected)))
                }
            }
        }
    }
}

@Composable
private fun ChatCandidateRow(person: CrewMember, ticked: Boolean, onTick: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onTick(!ticked) }.padding(ZillitTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        ZillitAvatar(name = person.fullName, userId = person.userId)
        Column(Modifier.weight(1f)) {
            ZillitText(text = person.fullName, style = ZillitTheme.typography.titleSmall, maxLines = 1)
            ZillitText(
                text = person.designation?.localised().orEmpty(),
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textMuted,
                maxLines = 1,
            )
        }
        ZillitCheckbox(checked = ticked, onCheckedChange = onTick)
    }
}

/**
 * Confirm Admin Rights, as the web draws it: the person's picture, name,
 * designation and a department pill over the warning, with Cancel and Yes.
 */
@Composable
internal fun GrantAdminDialog(confirmation: AdminConfirmation.GrantAdmin, onEvent: (AdminEvent) -> Unit) {
    ZillitDialogShell(
        title = confirmation.title,
        visible = true,
        onDismiss = { onEvent(AdminEvent.DismissConfirmation) },
        width = GRANT_DIALOG,
        actions = {
            ZillitButton(
                text = str(S.cancel),
                onClick = { onEvent(AdminEvent.DismissConfirmation) },
                variant = ButtonVariant.Tertiary,
            )
            ZillitButton(text = confirmation.confirmLabel, onClick = { onEvent(AdminEvent.ConfirmAction) })
        },
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        ) {
            ZillitAvatar(name = confirmation.name, userId = confirmation.userId, size = GRANT_PICTURE)
            ZillitText(text = confirmation.name, style = ZillitTheme.typography.titleLarge)
            confirmation.designation?.let {
                ZillitText(
                    text = it.localised(),
                    style = ZillitTheme.typography.bodySmall,
                    color = ZillitTheme.colors.textMuted,
                )
            }
            confirmation.department?.let {
                ZillitText(
                    text = it.localised(),
                    style = ZillitTheme.typography.label,
                    color = ZillitTheme.colors.accentText,
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(ZillitTheme.colors.accentSoft)
                        .padding(horizontal = ZillitTheme.spacing.sm, vertical = 2.dp),
                )
            }
        }
        ZillitText(
            text = confirmation.message,
            style = ZillitTheme.typography.bodyLarge,
            color = ZillitTheme.colors.textSecondary,
            textAlign = TextAlign.Start,
            modifier = Modifier
                .fillMaxWidth()
                .clip(ZillitTheme.shapes.medium)
                .background(ZillitTheme.colors.accentSoft)
                .padding(ZillitTheme.spacing.md),
        )
    }
}

@Composable
private fun FieldLabel(text: String) {
    ZillitText(text = text, style = ZillitTheme.typography.label, color = ZillitTheme.colors.textSecondary)
}

@Composable
private fun DialogError(message: String) {
    ZillitText(text = message, style = ZillitTheme.typography.bodySmall, color = ZillitTheme.colors.danger)
}

/** `home/unit` sections the web leaves out of Change Unit (`AllUserInfo.jsx`). */
private val DASHBOARD_UNITS = setOf("home_unit_calendar", "home_unit_call_sheet", "home_unit_notices")

private val NoDepartment: Department get() = Department(id = "", name = str(S.select_department))
private val NoDesignation: JobTitle get() = JobTitle(id = "", name = str(S.select_designation))
private val NoUnit: AdminUnit get() = AdminUnit(id = "", name = str(S.select_unit), kind = UnitKind.Shooting)

private val WIDE_DIALOG = 800.dp
private val GRANT_DIALOG = 640.dp
private val RIGHTS_HEIGHT = 420.dp
private val CHAT_LIST_HEIGHT = 500.dp
private val RIGHT_COLUMN = 150.dp
private val HEADER_PICTURE = 50.dp
private val TARGET_PICTURE = 64.dp
private val GRANT_PICTURE = 88.dp
