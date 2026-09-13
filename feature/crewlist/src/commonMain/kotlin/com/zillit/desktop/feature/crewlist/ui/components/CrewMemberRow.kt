package com.zillit.desktop.feature.crewlist.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitAvatar
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTooltip
import com.zillit.desktop.feature.crewlist.domain.CrewMember
import com.zillit.desktop.feature.crewlist.domain.DialCode
import com.zillit.desktop.feature.crewlist.domain.MemberCells
import com.zillit.desktop.feature.crewlist.domain.MemberOverride
import com.zillit.desktop.feature.crewlist.domain.PhoneField
import com.zillit.desktop.feature.crewlist.domain.PhoneProblem

/**
 * One member of the crew sheet — `CrewListCustomUserCard.jsx`. Read mode is
 * plain text; edit mode turns the phone and the PROFILE address into inputs
 * and padlocks PROJECT. Clicking anywhere but an input opens the member's
 * profile drawer.
 */
@Composable
internal fun CrewMemberRow(
    member: CrewMember,
    cells: MemberCells,
    editable: Boolean,
    problem: PhoneProblem?,
    avatar: ImageBitmap?,
    copy: CrewCopy,
    dialCodes: List<DialCode>,
    onEdit: (MemberOverride) -> Unit,
    onOpen: () -> Unit,
) {
    val colors = ZillitTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val background by animateColorAsState(
        if (hovered) colors.surfaceHover else Color.Transparent,
        label = "crewRow",
    )
    Column(Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(background)
                .hoverable(interaction)
                .pointerHoverIcon(PointerIcon.Hand)
                .clickable(interactionSource = interaction, indication = null, onClick = onOpen)
                .padding(horizontal = 16.dp, vertical = 11.dp),
        ) {
            CrewGrid(
                name = { NameCell(member, avatar, copy) },
                designation = {
                    ZillitText(
                        text = copy.label(member.designationName),
                        style = ZillitTheme.typography.bodyMedium.copy(fontSize = 13.5.sp),
                        color = colors.textSecondary,
                        maxLines = 1,
                        modifier = Modifier.padding(top = NAME_BASELINE_NUDGE),
                    )
                },
                phone = {
                    if (editable) {
                        // The cell takes its own presses, so a click beside an input
                        // edits rather than opening the drawer — the web's stopPropagation.
                        Box(Modifier.swallowPresses()) { PhoneEditor(cells, problem, copy, dialCodes, onEdit) }
                    } else {
                        ZillitText(
                            text = cells.phoneDisplay.ifEmpty { "-" },
                            style = ZillitTheme.typography.bodyMedium.copy(fontSize = 13.5.sp),
                            color = colors.textSecondary,
                            maxLines = 1,
                            modifier = Modifier.padding(start = 8.dp, top = NAME_BASELINE_NUDGE),
                        )
                    }
                },
                email = {
                    Box(if (editable) Modifier.swallowPresses() else Modifier) {
                        EmailCell(cells, editable, copy, onEdit)
                    }
                },
            )
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.divider))
    }
}

@Composable
private fun NameCell(member: CrewMember, avatar: ImageBitmap?, copy: CrewCopy) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ZillitAvatar(name = member.fullName, size = AVATAR, image = avatar)
        Column(Modifier.weight(1f, fill = false)) {
            ZillitText(
                text = member.fullName,
                style = ZillitTheme.typography.bodyMedium.copy(fontSize = 14.sp, fontWeight = FontWeight.Medium),
                color = ZillitTheme.colors.textPrimary,
                maxLines = 1,
            )
            if (member.isExternal) {
                ZillitText(
                    text = copy.t("not_on_zillit", "Not on Zillit"),
                    style = ZillitTheme.typography.labelSmall.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
                    color = crewPalette().externalInk,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun PhoneEditor(
    cells: MemberCells,
    problem: PhoneProblem?,
    copy: CrewCopy,
    dialCodes: List<DialCode>,
    onEdit: (MemberOverride) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            DialCodePicker(
                value = cells.countryCode,
                codes = dialCodes,
                placeholder = copy.t("Code", "Code"),
                isError = problem?.field == PhoneField.CountryCode,
                onPick = { code -> onEdit(MemberOverride(countryCode = code)) },
                modifier = Modifier.width(DIAL_CODE_WIDTH),
            )
            CrewCellField(
                value = cells.phone,
                onValueChange = { typed -> onEdit(MemberOverride(phone = typed.filter { it in '0'..'9' })) },
                placeholder = copy.t("Phone", "Phone"),
                isError = problem?.field == PhoneField.Number,
                digits = true,
                modifier = Modifier.weight(1f),
            )
        }
        problem?.let { CellError(it.message) }
    }
}

@Composable
private fun EmailCell(
    cells: MemberCells,
    editable: Boolean,
    copy: CrewCopy,
    onEdit: (MemberOverride) -> Unit,
) {
    val profile = copy.t("crew_list_profile_email_label", "PROFILE")
    val project = copy.t("crew_list_project_email_label", "PROJECT")
    Column(
        modifier = Modifier.padding(start = if (editable) 0.dp else 8.dp),
        verticalArrangement = Arrangement.spacedBy(if (editable) 6.dp else 3.dp),
    ) {
        EmailLine(profile) {
            if (editable) {
                CrewCellField(
                    value = cells.profileEmail,
                    onValueChange = { onEdit(MemberOverride(email = it)) },
                    placeholder = copy.t("poEmailLable", "Email"),
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                CellValue(cells.profileEmail, copy.t("crew_list_email_not_set", "Not set"))
            }
        }
        EmailLine(project) {
            if (editable) {
                val locked = copy.t(
                    "crew_list_project_email_locked_tip",
                    "Project email is auto-generated and cannot be changed",
                )
                ZillitTooltip(locked) {
                    LockedField(cells.projectEmail, Modifier.fillMaxWidth())
                }
            } else {
                CellValue(cells.projectEmail, "—")
            }
        }
    }
}

private val AVATAR = 36.dp
private val DIAL_CODE_WIDTH = 100.dp

/** Lines a one-line cell up with the name beside its 36-point avatar. */
private val NAME_BASELINE_NUDGE = 8.dp
