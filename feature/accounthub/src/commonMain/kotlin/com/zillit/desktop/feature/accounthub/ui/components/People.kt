package com.zillit.desktop.feature.accounthub.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitAvatar
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitSearchField
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.accounthub.domain.HubUser
import com.zillit.desktop.feature.accounthub.domain.HubUsers

/** A person as a small avatar and their name — the web's `PersonAvatar` beside a label. */
@Composable
fun PersonChip(name: String, modifier: Modifier = Modifier, role: String? = null, size: Dp = 24.dp) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        ZillitAvatar(name = name, size = size)
        Column {
            ZillitText(text = name, style = ZillitTheme.typography.bodyMedium, maxLines = 1)
            if (!role.isNullOrBlank()) FieldHint(role)
        }
    }
}

/** Up to four avatars overlapping, then "+N" — the web's `AvatarStack`. */
@Composable
fun AvatarStack(names: List<String>, modifier: Modifier = Modifier, size: Dp = 22.dp, max: Int = 4) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        names.take(max).forEachIndexed { index, name ->
            Box(modifier = Modifier.offset(x = (-index * (size / 3).value).dp)) {
                ZillitAvatar(
                    name = name,
                    size = size,
                    modifier = Modifier.border(1.dp, ZillitTheme.colors.surface, CircleShape),
                )
            }
        }
        if (names.size > max) {
            ZillitText(
                text = "+${names.size - max}",
                style = ZillitTheme.typography.labelSmall,
                color = ZillitTheme.colors.textMuted,
                modifier = Modifier.padding(start = ZillitTheme.spacing.xxs),
            )
        }
    }
}

/**
 * A user picker — search, one row per person with their role, an Admin
 * badge, and "Added" on the ones already in — the web's `UserPickerModal`.
 */
@Suppress("LongMethod") // A screen, read top to bottom; the order is the reading order.
@Composable
fun UserPickerDialog(
    visible: Boolean,
    title: String,
    users: List<HubUser>,
    selected: List<String>,
    search: String,
    onSearch: (String) -> Unit,
    onToggle: (String) -> Unit,
    onApply: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    applyLabel: String = "Done",
) {
    val colors = ZillitTheme.colors
    val shown = HubUsers.search(users, search)
    ZillitDialogShell(
        title = title,
        subtitle = subtitle,
        icon = ZillitIcons.Users,
        visible = visible,
        onDismiss = onDismiss,
        modifier = modifier,
        scrollable = false,
        actions = {
            ZillitButton(text = "Cancel", onClick = onDismiss, variant = ButtonVariant.Tertiary)
            ZillitButton(text = applyLabel, onClick = onApply)
        },
    ) {
        ZillitSearchField(
            value = search,
            onValueChange = onSearch,
            placeholder = "Search users...",
            modifier = Modifier.fillMaxWidth(),
        )
        Column(modifier = Modifier.fillMaxWidth().heightIn(max = PICKER_LIST).verticalScroll(rememberScrollState())) {
            if (shown.isEmpty()) FieldHint(
                if (users.isEmpty()) "No team members" else "No results for “$search”",
                Modifier.padding(ZillitTheme.spacing.sm),
            )
            shown.forEach { user ->
                val picked = user.id in selected
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(ZillitTheme.shapes.medium)
                        .background(if (picked) colors.surfaceSelected else colors.surface)
                        .clickable { onToggle(user.id) }
                        .padding(horizontal = ZillitTheme.spacing.sm, vertical = ZillitTheme.spacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                ) {
                    ZillitAvatar(name = user.name, size = 28.dp)
                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            ZillitText(
                                text = user.name.ifBlank { user.id },
                                style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                maxLines = 1,
                            )
                            if (user.isAdmin) Pill("Admin", tone = StatusTone.Progress)
                        }
                        if (user.roleLabel.isNotBlank()) FieldHint(user.roleLabel)
                    }
                    if (picked) {
                        Pill("Added", tone = StatusTone.Done)
                    } else {
                        ZillitIcon(icon = ZillitIcons.Add, tint = colors.textMuted, size = 16.dp)
                    }
                }
            }
        }
        FieldHint("${selected.size} selected")
    }
}

/** The "L1: 2 · L2: 1" summary the department rows print. */
fun levelSummary(levels: List<Pair<Int, Int>>): String =
    levels.joinToString(" · ") { (level, count) -> "L$level: $count" }

private val PICKER_LIST = 360.dp
