package com.zillit.desktop.feature.crewlist.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.TagTone
import com.zillit.desktop.core.designsystem.component.ZillitAvatar
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitCheckbox
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitLazyColumn
import com.zillit.desktop.core.designsystem.component.ZillitSearchField
import com.zillit.desktop.core.designsystem.component.ZillitTag
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.feature.crewlist.domain.CrewMember

/**
 * The Crew List roster: Unit → Department → People, and the Generate PDF
 * dialog — the phones' read-only surface at a desktop width.
 */
@Composable
fun CrewListScreen(
    state: CrewListUiState,
    visibleUnits: () -> List<com.zillit.desktop.feature.crewlist.domain.CrewUnit>,
    onEvent: (CrewListEvent) -> Unit,
    modifier: Modifier = Modifier,
    /**
     * The Crew List widget's shape: no room for three columns, so a member's
     * phone and email sit under their name and the header's buttons lose
     * their labels. The same rows, the same rules — only narrower.
     */
    compact: Boolean = false,
) {
    Box(modifier.fillMaxSize().background(ZillitTheme.colors.canvas)) {
        when {
            state.viewer.isBlocked -> Centred("You don't have access to the Crew List.")
            else -> Roster(state, visibleUnits, onEvent, compact)
        }

        state.generating?.let { dialog ->
            ZillitDialogShell(
                title = "Generate PDF",
                visible = true,
                onDismiss = { onEvent(CrewListEvent.CancelGenerate) },
                actions = {
                    ZillitButton(
                        text = "Cancel",
                        variant = ButtonVariant.Tertiary,
                        onClick = { onEvent(CrewListEvent.CancelGenerate) },
                    )
                    ZillitButton(
                        text = if (dialog.isWorking) "Generating…" else "Generate & view",
                        enabled = !dialog.isWorking,
                        onClick = { onEvent(CrewListEvent.GenerateAndView) },
                    )
                },
            ) {
                ZillitText(
                    text = "The crew list is rendered by the server and saved to your Downloads.",
                    style = ZillitTheme.typography.bodyMedium,
                    color = ZillitTheme.colors.textSecondary,
                )
                ZillitCheckbox(
                    checked = dialog.hideExternalLabel,
                    onCheckedChange = { onEvent(CrewListEvent.HideExternal(it)) },
                    label = "Hide the \"Not on Zillit\" label on external users",
                )
            }
        }
    }
}

@Composable
private fun Roster(
    state: CrewListUiState,
    visibleUnits: () -> List<com.zillit.desktop.feature.crewlist.domain.CrewUnit>,
    onEvent: (CrewListEvent) -> Unit,
    compact: Boolean,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(if (compact) ZillitTheme.spacing.sm else ZillitTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            if (compact) {
                // The widget's own bar already names the tool; the search is
                // what the width is worth spending on.
                ZillitSearchField(
                    value = state.query,
                    onValueChange = { onEvent(CrewListEvent.Search(it)) },
                    placeholder = "Search name or role",
                    modifier = Modifier.weight(1f),
                )
                ZillitIconButton(
                    icon = ZillitIcons.Reload,
                    contentDescription = "Refresh the crew list",
                    onClick = { onEvent(CrewListEvent.Refresh) },
                )
                ZillitIconButton(
                    icon = ZillitIcons.File,
                    contentDescription = "Generate PDF",
                    onClick = { onEvent(CrewListEvent.OpenGenerate) },
                )
            } else {
                ZillitText(text = "Crew List", style = ZillitTheme.typography.titleLarge)
                Spacer(Modifier.weight(1f))
                ZillitSearchField(
                    value = state.query,
                    onValueChange = { onEvent(CrewListEvent.Search(it)) },
                    placeholder = "Search name or role",
                    modifier = Modifier.width(SEARCH_WIDTH),
                )
                ZillitButton(
                    text = "Refresh",
                    variant = ButtonVariant.Secondary,
                    onClick = { onEvent(CrewListEvent.Refresh) },
                )
                ZillitButton(
                    text = "Generate PDF",
                    leadingIcon = ZillitIcons.File,
                    onClick = { onEvent(CrewListEvent.OpenGenerate) },
                )
            }
        }

        val units = visibleUnits()
        when {
            state.isLoading && state.units.isEmpty() -> Centred("Loading crew…")
            units.isEmpty() -> Centred("No users found.")
            else -> GroupedRows(units, compact)
        }
    }
}

@Composable
private fun GroupedRows(units: List<com.zillit.desktop.feature.crewlist.domain.CrewUnit>, compact: Boolean) {
    val listState = rememberLazyListState()

    ZillitLazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
    ) {
        units.forEach { unit ->
            item(key = "unit-${unit.unitName}") {
                Banner(unit.unitName.localised(), ZillitTheme.colors.surfaceSunken)
            }
            unit.departments.forEach { department ->
                item(key = "dept-${unit.unitName}-${department.departmentName}") {
                    Banner(department.departmentName.localised(), ZillitTheme.colors.surface)
                }
                items(
                    count = department.members.size,
                    key = { i -> "m-${unit.unitName}-${department.departmentName}-${department.members[i].userId}" },
                ) { index ->
                    MemberRow(department.members[index], compact)
                }
            }
        }
    }
}

@Composable
private fun Banner(text: String, background: androidx.compose.ui.graphics.Color) {
    ZillitText(
        text = text,
        style = ZillitTheme.typography.titleSmall,
        modifier = Modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.small)
            .background(background)
            .padding(horizontal = ZillitTheme.spacing.sm, vertical = ZillitTheme.spacing.xs),
    )
}

@Composable
private fun MemberRow(member: CrewMember, compact: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ZillitTheme.spacing.sm, vertical = ZillitTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        ZillitAvatar(name = member.fullName)
        val phone = listOf(member.countryCode, member.phone)
            .filter { it.isNotBlank() }
            .joinToString("")
            .ifBlank { "-" }
        MemberIdentity(
            member = member,
            phone = phone,
            compact = compact,
            modifier = Modifier.weight(if (compact) 1f else NAME_SHARE),
        )
        if (!compact) {
            ZillitText(
                text = phone,
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textSecondary,
                maxLines = 1,
                modifier = Modifier.weight(PHONE_SHARE),
            )
            ZillitText(
                text = member.primaryEmail.ifBlank { "-" },
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textSecondary,
                maxLines = 1,
                modifier = Modifier.weight(EMAIL_SHARE),
            )
        }
    }
}

/** A member's name, role, and — where the row is too narrow for columns — their contact line. */
@Composable
private fun MemberIdentity(member: CrewMember, phone: String, compact: Boolean, modifier: Modifier) {
    Column(modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        ) {
            ZillitText(
                text = member.fullName,
                style = ZillitTheme.typography.bodyMedium,
                color = ZillitTheme.colors.textPrimary,
                maxLines = 1,
            )
            if (member.isExternal) ZillitTag("Not on Zillit", tone = TagTone.Neutral)
        }
        member.designationName.takeIf { it.isNotBlank() }?.let { designation ->
            ZillitText(
                text = designation.localised(),
                style = ZillitTheme.typography.labelSmall,
                color = ZillitTheme.colors.textMuted,
                maxLines = 1,
            )
        }
        // Compact has no columns to put these in, so they ride under the
        // name — still one line, still in this order.
        if (compact) {
            val contact = listOf(phone, member.primaryEmail)
                .filter { it.isNotBlank() && it != "-" }
                .joinToString(" · ")
            if (contact.isNotBlank()) {
                ZillitText(
                    text = contact,
                    style = ZillitTheme.typography.bodySmall,
                    color = ZillitTheme.colors.textSecondary,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun Centred(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        ZillitText(
            text = text,
            style = ZillitTheme.typography.bodyMedium,
            color = ZillitTheme.colors.textMuted,
        )
    }
}

private const val NAME_SHARE = 0.4f
private const val PHONE_SHARE = 0.25f
private const val EMAIL_SHARE = 0.35f
private val SEARCH_WIDTH = androidx.compose.ui.unit.Dp(240f)
