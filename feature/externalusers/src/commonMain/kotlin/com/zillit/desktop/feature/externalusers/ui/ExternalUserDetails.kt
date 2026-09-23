package com.zillit.desktop.feature.externalusers.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.externalusers.domain.ExternalUser
import com.zillit.desktop.feature.externalusers.domain.ExternalUserBucket
import com.zillit.desktop.feature.externalusers.domain.Gender
import com.zillit.desktop.feature.externalusers.domain.phoneLine

/**
 * The read-only card — the web's `UserViewDetails.jsx`, a bordered
 * description table. Rows the record does not carry are left out, as there;
 * phone and gender always show, reading N/A when blank (ZL-15294).
 */
@Composable
internal fun ExternalUserDetails(
    user: ExternalUser,
    state: ExternalUsersUiState,
    onEvent: (ExternalUsersEvent) -> Unit,
) {
    val department = state.departments.firstOrNull { it.id == user.departmentId }
    val designation = department?.designations?.firstOrNull { it.id == user.designationId }

    ZillitDialogShell(
        title = str(S.user_details),
        subtitle = user.fullName,
        icon = ZillitIcons.User,
        visible = true,
        width = DETAILS_WIDTH,
        onDismiss = { onEvent(ExternalUsersEvent.CloseDetails) },
        actions = {
            if (state.viewer.mayEdit(user)) {
                ZillitButton(
                    text = str(S.edit),
                    variant = ButtonVariant.Secondary,
                    leadingIcon = ZillitIcons.Edit,
                    onClick = {
                        onEvent(ExternalUsersEvent.CloseDetails)
                        onEvent(ExternalUsersEvent.Edit(user))
                    },
                )
            }
            ZillitButton(text = str(S.close), onClick = { onEvent(ExternalUsersEvent.CloseDetails) })
        },
    ) {
        DescriptionTable {
            if (user.fullName.isNotBlank()) DescriptionRow(str(S.full_name)) { Plain(user.fullName) }
            if (user.email.isNotBlank()) DescriptionRow(str(S.email)) { EmailLink(user.email, onEvent) }
            DescriptionRow(str(S.phone)) { PhoneValue(user.phoneLine) }
            DescriptionRow(str(S.gender)) {
                Plain(Gender.labelOf(user.gender).ifBlank { str(S.na) }, muted = user.gender.isBlank())
            }
            if (user.departmentId.isNotBlank()) {
                DescriptionRow(str(S.department)) { Plain(department?.name?.localised().orEmpty()) }
            }
            if (user.designationId.isNotBlank()) {
                DescriptionRow(str(S.designation)) { Plain(designation?.name?.localised().orEmpty()) }
            }
            if (user.userType.isNotBlank()) {
                DescriptionRow(str(S.user_type)) { Plain(ExternalUserBucket.of(user.userType).typeLabel(user)) }
            }
            OtherInfoRow(user, onEvent)
        }
    }
}

/** The free-form rows, each `label: value` — an address or a number gets the main fields' affordances. */
@Composable
private fun OtherInfoRow(user: ExternalUser, onEvent: (ExternalUsersEvent) -> Unit) {
    val extras = user.otherInfo.filter { it.value.isNotBlank() }
    if (extras.isEmpty()) return
    DescriptionRow(str(S.desktop_eu_other_info)) {
        Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
            extras.forEach { row ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
                ) {
                    ZillitText(
                        text = "${row.label}:",
                        style = ZillitTheme.typography.label,
                        color = ZillitTheme.colors.textPrimary,
                    )
                    when {
                        row.value.looksLikeEmail() -> EmailLink(row.value.trim(), onEvent)
                        row.value.looksLikeNumber() -> PhoneValue(row.value.trim())
                        else -> Plain(row.value)
                    }
                }
            }
        }
    }
}

/** The bordered two-column table: shaded label cells, hairlines between rows. */
@Composable
private fun DescriptionTable(rows: @Composable () -> Unit) {
    val colors = ZillitTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.medium)
            .border(1.dp, colors.border, ZillitTheme.shapes.medium),
    ) {
        rows()
    }
}

@Composable
private fun DescriptionRow(label: String, value: @Composable () -> Unit) {
    val colors = ZillitTheme.colors
    Column {
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            Box(
                Modifier
                    .width(LABEL_WIDTH)
                    .fillMaxHeight()
                    .background(colors.surfaceSunken)
                    .padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.sm),
                contentAlignment = Alignment.CenterStart,
            ) {
                ZillitText(text = label, style = ZillitTheme.typography.label, color = colors.textSecondary)
            }
            Box(Modifier.width(1.dp).fillMaxHeight().background(colors.border))
            Box(
                Modifier
                    .weight(1f)
                    .padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.sm),
                contentAlignment = Alignment.CenterStart,
            ) {
                value()
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))
    }
}

@Composable
private fun Plain(text: String, muted: Boolean = false) {
    ZillitText(
        text = text,
        style = ZillitTheme.typography.bodyMedium,
        color = if (muted) ZillitTheme.colors.textMuted else ZillitTheme.colors.textPrimary,
    )
}

/** The web's `checkEmail`: an address somewhere in the text. */
private fun String.looksLikeEmail(): Boolean = EMAIL.containsMatchIn(this)

/** The web's `checkNumber`: an optional plus and seven to fifteen digits, nothing else. */
private fun String.looksLikeNumber(): Boolean = NUMBER.matches(trim())

private val EMAIL = Regex("""[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}""", RegexOption.IGNORE_CASE)
private val NUMBER = Regex("""\+?\d{7,15}""")
private val DETAILS_WIDTH = 560.dp
private val LABEL_WIDTH = 140.dp
