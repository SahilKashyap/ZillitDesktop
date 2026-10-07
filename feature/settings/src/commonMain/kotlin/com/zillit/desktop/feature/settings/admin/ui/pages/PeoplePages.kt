package com.zillit.desktop.feature.settings.admin.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ColumnWidth
import com.zillit.desktop.core.designsystem.component.TableColumn
import com.zillit.desktop.core.designsystem.component.TagTone
import com.zillit.desktop.core.designsystem.component.ZillitAvatar
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDataTable
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitSwitch
import com.zillit.desktop.core.designsystem.component.ZillitTag
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTooltip
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.settings.admin.domain.CrewMember
import com.zillit.desktop.feature.settings.admin.domain.SosEntryType
import com.zillit.desktop.feature.settings.admin.ui.AdminConfirmation
import com.zillit.desktop.feature.settings.admin.ui.AdminDestination
import com.zillit.desktop.feature.settings.admin.ui.AdminEvent
import com.zillit.desktop.feature.settings.admin.ui.AdminUiState

/**
 * The pages about people: the crew, their rights, and who is pre-approved.
 *
 * Grouped by subject rather than one file per page, because they read each
 * other's data — the rights page is the crew list with a second pane, and the
 * pre-approval form is filled from the department tree.
 */

/**
 * User Management — the web's `AllUserInfo.jsx`, column for column.
 *
 * One row per person with a switch for being on the production and one for
 * administering it, then three doors: Change Profile, their Posting Rights,
 * and — for someone whose name is private — who may chat with them. Someone
 * switched off keeps the row so Active can bring them back; every other
 * control on it answers with the web's "disabled user" warning instead.
 */
@Composable
fun CrewPage(
    state: AdminUiState,
    onEvent: (AdminEvent) -> Unit,
    onBack: () -> Unit,
    isOtherType: Boolean = false,
) {
    AdminPage(
        title = AdminDestination.Crew.title,
        description = str(S.desktop_crew_page_description),
        state = state,
        onEvent = onEvent,
        onBack = onBack,
        search = str(S.search),
        bodyScrolls = true,
        maxWidth = WIDE_PAGE,
    ) {
        ZillitDataTable(
            rows = state.crewMatching,
            columns = crewColumns(state, onEvent, isOtherType),
            key = { it.userId },
            loading = state.isLoading && !state.hasLoaded,
            emptyTitle = if (state.query.isNotBlank()) {
                str(S.desktop_nobody_matches_query, state.query)
            } else {
                str(S.desktop_nobody_has_joined)
            },
            modifier = Modifier.fillMaxWidth().weight(1f),
        )
    }
}

@Suppress("LongMethod") // The web's eight columns, each a few lines.
private fun crewColumns(
    state: AdminUiState,
    onEvent: (AdminEvent) -> Unit,
    isOtherType: Boolean,
): List<TableColumn<CrewMember>> = listOf(
    TableColumn(str(S.desktop_um_profile_picture), ColumnWidth.Fixed(PICTURE_COLUMN)) { person ->
        ZillitAvatar(name = person.fullName, userId = person.userId, size = PICTURE)
    },
    TableColumn(str(S.user_name), ColumnWidth.Weight(1f)) { person ->
        ZillitText(text = person.fullName, style = ZillitTheme.typography.bodyMedium, maxLines = 1)
    },
    TableColumn(str(S.designation), ColumnWidth.Weight(1f)) { person ->
        val designation = person.designation?.localised().orEmpty()
        ZillitTooltip(designation) {
            ZillitText(text = designation, style = ZillitTheme.typography.bodyMedium, maxLines = 1)
        }
    },
    TableColumn(str(S.active), ColumnWidth.Fixed(SWITCH_COLUMN)) { person ->
        ZillitSwitch(
            checked = person.isActive,
            onCheckedChange = { onEvent(AdminEvent.CrewActiveChanged(person.userId, it)) },
            enabled = !state.isSaving,
        )
    },
    TableColumn(str(S.admin), ColumnWidth.Fixed(SWITCH_COLUMN)) { person ->
        ZillitSwitch(
            // Off on a switched-off person whatever the flag says, as the web shows it.
            checked = person.isAdmin && !person.isRemoved,
            onCheckedChange = { onEvent(AdminEvent.AdminAccessChanged(person.userId, it)) },
            enabled = !state.isSaving,
        )
    },
    TableColumn(str(S.desktop_um_edit_user_details), ColumnWidth.Fixed(ACTION_COLUMN)) { person ->
        ZillitTooltip(str(S.desktop_um_edit_user_info) + ":") {
            RowAction(ZillitIcons.Edit, str(S.desktop_um_edit_user_details), person.isRemoved) {
                onEvent(AdminEvent.OpenEditCrew(person.userId, withUnit = !isOtherType))
            }
        }
    },
    TableColumn(str(S.posting_rights), ColumnWidth.Fixed(ACTION_COLUMN)) { person ->
        RowAction(PostingRightsIcon, str(S.posting_rights), person.isRemoved) {
            onEvent(AdminEvent.OpenPostingRights(person.userId))
        }
    },
    TableColumn("", ColumnWidth.Fixed(CHAT_COLUMN)) { person ->
        // Only someone whose name is hidden needs a list of who may reach them.
        if (person.keepNamePrivate && !person.isRemoved) {
            ZillitTooltip(str(S.add_edit_user_for_allow_chat) + ":") {
                RowAction(ZillitIcons.UserPlus, str(S.add_edit_user_for_allow_chat), dimmed = false) {
                    onEvent(AdminEvent.OpenAllowChat(person.userId))
                }
            }
        }
    },
)

/**
 * An icon door on a row. Dimmed but still clickable on a switched-off person,
 * so the click can say why nothing opens — the web's `not-allowed` cursor
 * plus its warning toast.
 */
@Composable
private fun RowAction(icon: ImageVector, description: String, dimmed: Boolean, onClick: () -> Unit) {
    ZillitIconButton(
        icon = icon,
        contentDescription = description,
        onClick = onClick,
        tint = if (dimmed) ZillitTheme.colors.textDisabled else ZillitTheme.colors.textSecondary,
    )
}

/** The web's posting-rights glyph — a clipboard with a list on it (`AllUserInfo.jsx`). */
internal val PostingRightsIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "PostingRights",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).addPath(
        pathData = addPathNodes(
            "M15 4h3a1 1 0 0 1 1 1v15a1 1 0 0 1-1 1H6a1 1 0 0 1-1-1V5a1 1 0 0 1 1-1h3m0 3h6m-3 5h3m-6 " +
                "0h.01M12 16h3m-6 0h.01M10 3v4h4V3h-4Z",
        ),
        stroke = SolidColor(Color.Black),
        strokeLineWidth = 2f,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
    ).build()
}

private val WIDE_PAGE = 1280.dp
private val PICTURE = 44.dp
private val PICTURE_COLUMN = 150.dp
private val SWITCH_COLUMN = 88.dp
private val ACTION_COLUMN = 132.dp
private val CHAT_COLUMN = 56.dp

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

