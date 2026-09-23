package com.zillit.desktop.feature.home.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.DropdownMenu
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitAvatar
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.component.rememberWheelScroll
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.home.ui.decodeImageBitmap

/**
 * Who to invite, as a searchable dropdown and a row of chips.
 *
 * The dropdown searches by name or designation — a coordinator looking for
 * "the gaffer" should not need to remember who holds the job — and each row
 * shows the face, the name, and the designation, because productions have two
 * Sams and only one of them is in Camera. The chosen crew sit above it as
 * chips, each with its own remove: taking someone off a thirty-name event
 * must not mean hunting for them in the list again.
 *
 * Picking someone keeps the menu open. Events go to groups of people; a menu
 * that closed on every choice would need reopening a dozen times.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun InviteePicker(
    invitees: List<EventInvitee>,
    selectedIds: Set<String>,
    onToggle: (String) -> Unit,
    loadAvatar: suspend (String) -> ByteArray?,
) {
    var open by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf("") }

    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
        val chosen = invitees.filter { it.userId in selectedIds }
        if (chosen.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
                verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
            ) {
                chosen.forEach { invitee ->
                    InviteeChip(
                        invitee = invitee,
                        loadAvatar = loadAvatar,
                        onRemove = { onToggle(invitee.userId) },
                    )
                }
            }
        }

        Box {
            ZillitButton(
                text = str(S.desktop_cal_add_crew),
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
                leadingIcon = ZillitIcons.UserPlus,
                onClick = { open = true; search = "" },
                enabled = invitees.isNotEmpty(),
            )
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                CrewMenu(
                    invitees = invitees,
                    selectedIds = selectedIds,
                    search = search,
                    onSearch = { search = it },
                    loadAvatar = loadAvatar,
                    onToggle = onToggle,
                )
            }
        }
    }
}

/** One chosen crew member: face, name, and its own remove. */
@Composable
private fun InviteeChip(
    invitee: EventInvitee,
    loadAvatar: suspend (String) -> ByteArray?,
    onRemove: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        modifier = Modifier
            .clip(ZillitTheme.shapes.pill)
            .background(ZillitTheme.colors.surfaceRaised)
            .padding(
                start = ZillitTheme.spacing.xxs,
                end = ZillitTheme.spacing.xs,
                top = ZillitTheme.spacing.xxs,
                bottom = ZillitTheme.spacing.xxs,
            ),
    ) {
        ZillitAvatar(
            name = invitee.name,
            image = rememberInviteeAvatar(invitee.userId, loadAvatar),
            size = CHIP_AVATAR,
        )
        ZillitText(
            text = invitee.name,
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textPrimary,
            maxLines = 1,
        )
        ZillitIcon(
            icon = ZillitIcons.Close,
            contentDescription = str(S.bs_chip_remove, invitee.name),
            tint = ZillitTheme.colors.textMuted,
            size = CHIP_REMOVE,
            modifier = Modifier.clip(ZillitTheme.shapes.pill).clickable(onClick = onRemove),
        )
    }
}

/** The searchable crew list inside the dropdown. */
@Composable
private fun CrewMenu(
    invitees: List<EventInvitee>,
    selectedIds: Set<String>,
    search: String,
    onSearch: (String) -> Unit,
    loadAvatar: suspend (String) -> ByteArray?,
    onToggle: (String) -> Unit,
) {
    Column(
        modifier = Modifier.width(MENU_WIDTH).padding(ZillitTheme.spacing.sm),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        ZillitTextField(
            value = search,
            onValueChange = onSearch,
            placeholder = str(S.desktop_cal_search_name_or_designation),
            modifier = Modifier.fillMaxWidth(),
        )

        val needle = search.trim()
        val matches = invitees.filter {
            needle.isBlank() ||
                it.name.contains(needle, ignoreCase = true) ||
                it.designation.orEmpty().contains(needle, ignoreCase = true)
        }

        val state = rememberLazyListState()
        // Fixed, not heightIn: the menu asks its content for intrinsic
        // measurements, which a lazy list cannot answer
        // (the [TimePickerField] lesson).
        LazyColumn(
            state = state,
            modifier = Modifier
                .height(MENU_LIST_HEIGHT)
                .then(rememberWheelScroll(state)),
        ) {
            items(matches, key = EventInvitee::userId) { invitee ->
                CrewChoice(
                    invitee = invitee,
                    selected = invitee.userId in selectedIds,
                    loadAvatar = loadAvatar,
                    onClick = { onToggle(invitee.userId) },
                )
            }
        }
    }
}

/** One crew member in the list: face, name, designation, and a tick when on. */
@Composable
private fun CrewChoice(
    invitee: EventInvitee,
    selected: Boolean,
    loadAvatar: suspend (String) -> ByteArray?,
    onClick: () -> Unit,
) {
    val colors = ZillitTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        modifier = Modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.small)
            .background(
                when {
                    selected -> colors.accentSoft
                    hovered -> colors.surfaceHover
                    else -> Color.Transparent
                },
            )
            .hoverable(interaction)
            .clickable(onClick = onClick)
            .padding(horizontal = ZillitTheme.spacing.xs, vertical = ZillitTheme.spacing.xxs),
    ) {
        ZillitAvatar(
            name = invitee.name,
            image = rememberInviteeAvatar(invitee.userId, loadAvatar),
            size = ROW_AVATAR,
        )
        Column(Modifier.weight(1f)) {
            ZillitText(
                text = invitee.name,
                style = ZillitTheme.typography.bodySmall.copy(
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                ),
                color = if (selected) colors.accentText else colors.textPrimary,
                maxLines = 1,
            )
            invitee.designation?.let { role ->
                ZillitText(
                    text = role,
                    style = ZillitTheme.typography.labelSmall,
                    color = colors.textMuted,
                    maxLines = 1,
                )
            }
        }
        if (selected) {
            ZillitIcon(
                icon = ZillitIcons.Tick,
                contentDescription = null,
                tint = colors.accentText,
                size = TICK,
            )
        }
    }
}

/**
 * Fetch-and-decode for one face — null until it lands, and permanently for
 * crew without a picture, which [ZillitAvatar] answers with initials. The
 * fetch layer caches, so reopening the menu costs nothing.
 */
@Composable
private fun rememberInviteeAvatar(
    userId: String,
    load: suspend (String) -> ByteArray?,
): ImageBitmap? = produceState<ImageBitmap?>(initialValue = null, userId) {
    value = load(userId)?.let(::decodeImageBitmap)
}.value

private val MENU_WIDTH = 320.dp
private val MENU_LIST_HEIGHT = 240.dp
private val ROW_AVATAR = 28.dp
private val CHIP_AVATAR = 20.dp
private val CHIP_REMOVE = 14.dp
private val TICK = 14.dp
