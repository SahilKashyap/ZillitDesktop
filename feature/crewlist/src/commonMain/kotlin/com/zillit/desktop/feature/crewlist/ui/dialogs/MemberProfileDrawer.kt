package com.zillit.desktop.feature.crewlist.ui.dialogs

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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.common.EpochDate
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitAvatar
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTooltip
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.crewlist.domain.CrewMember
import com.zillit.desktop.feature.crewlist.ui.components.CrewCopy
import com.zillit.desktop.feature.crewlist.ui.components.CrewDrawer
import com.zillit.desktop.feature.crewlist.ui.components.CrewIcons
import com.zillit.desktop.feature.crewlist.ui.components.crewPalette

/**
 * A crew member's profile — the web's `InfoSider` as the crew list opens it
 * (`fromCrewList`): face, name and designation; Audio · Video · Chat · Email as
 * a labelled strip; then User Details (unit, joining date) and Contact Details
 * (email, Zillit email, phone). External contacts have no Zillit account, so
 * they get no call or chat; nobody calls, chats or mails themselves.
 */
@Composable
internal fun MemberProfileDrawer(
    member: CrewMember?,
    copy: CrewCopy,
    selfUserId: String,
    hideUnit: Boolean,
    faces: suspend (String) -> ImageBitmap?,
    actions: CrewContactActions,
    onClose: () -> Unit,
) {
    val held = remember { arrayOfNulls<CrewMember>(1) }
    member?.let { held[0] = it }
    CrewDrawer(visible = member != null, width = 520.dp, onDismiss = onClose) {
        val shown = member ?: held[0] ?: return@CrewDrawer
        val face by produceState<ImageBitmap?>(null, shown.userId) { value = faces(shown.userId) }
        val isSelf = shown.userId == selfUserId
        Header(shown, face, copy, onClose)
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            ActionStrip(shown, isSelf, copy, actions, onClose)
            Column(Modifier.padding(20.dp)) {
                SectionLabel(copy.t("UserDetails", "User Details"))
                DetailCard {
                    if (!hideUnit) {
                        DetailRow(ZillitIcons.Home, copy.t("Unit", "Unit"), last = false) {
                            DetailValue(copy.label(shown.unitName).ifBlank { "—" })
                        }
                    }
                    DetailRow(ZillitIcons.Calendar, copy.t("joining_date", "Joining Date"), last = true) {
                        DetailValue(joiningDate(shown.joiningDate).ifBlank { "—" })
                    }
                }
                ContactDetails(shown, isSelf, copy, actions)
            }
        }
    }
}

@Composable
private fun Header(member: CrewMember, face: ImageBitmap?, copy: CrewCopy, onClose: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.clip(CircleShape).background(Brush.linearGradient(listOf(AMBER, AMBER_DEEP))).padding(2.dp),
        ) {
            Box(Modifier.clip(CircleShape).background(ZillitTheme.colors.surface).padding(2.dp)) {
                ZillitAvatar(name = member.fullName, size = 38.dp, image = face)
            }
        }
        Column(Modifier.weight(1f)) {
            ZillitText(
                text = member.fullName,
                style = ZillitTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 1,
            )
            ZillitText(
                text = copy.label(member.designationName),
                style = ZillitTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                color = crewPalette().grip,
                maxLines = 1,
            )
        }
        ZillitIconButton(icon = ZillitIcons.Close, contentDescription = copy.t("Close", "Close"), onClick = onClose)
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(ZillitTheme.colors.divider))
}

@Composable
private fun ActionStrip(
    member: CrewMember,
    isSelf: Boolean,
    copy: CrewCopy,
    actions: CrewContactActions,
    onClose: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(ZillitTheme.colors.surfaceSunken)
            .padding(horizontal = 18.dp, vertical = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(30.dp, Alignment.CenterHorizontally),
    ) {
        val onZillit = !member.isExternal
        if (onZillit) {
            ActionButton(ZillitIcons.Phone, copy.t("Audio", "Audio"), enabled = !isSelf && actions.call != null) {
                actions.call?.invoke(member, false)
            }
            ActionButton(CrewIcons.Video, copy.t("Video", "Video"), enabled = !isSelf && actions.call != null) {
                actions.call?.invoke(member, true)
            }
            ActionButton(ZillitIcons.Chat, copy.t("Chat", "Chat"), enabled = !isSelf && actions.chat != null) {
                onClose()
                actions.chat?.invoke(member)
            }
        }
        ActionButton(ZillitIcons.Mail, copy.t("Email", "Email"), enabled = !isSelf && actions.email != null) {
            actions.email?.invoke(composeAddress(member))
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(ZillitTheme.colors.divider))
}

/**
 * Where the Email button writes: the member's Zillit mailbox when they have
 * one, else the address on their row — never an empty compose for someone who
 * has an address (the web's `resolveComposeEmail`).
 */
internal fun composeAddress(member: CrewMember): String = member.projectEmail.ifBlank { member.profileEmail }

/** A round amber button and its label — dimmed, not hidden, when it cannot be used. */
@Composable
private fun ActionButton(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            modifier = Modifier
                .size(46.dp)
                .then(if (enabled) Modifier.shadow(lift(hovered), CircleShape, spotColor = AMBER) else Modifier)
                .clip(CircleShape)
                .background(
                    if (enabled) {
                        Brush.linearGradient(listOf(AMBER, AMBER_DEEP))
                    } else {
                        Brush.linearGradient(listOf(ZillitTheme.colors.border, ZillitTheme.colors.border))
                    },
                )
                .hoverable(interaction)
                .then(if (enabled) Modifier.pointerHoverIcon(PointerIcon.Hand) else Modifier)
                .clickable(enabled = enabled, interactionSource = interaction, indication = null, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            val ink = if (enabled) Color.White else ZillitTheme.colors.textDisabled
            ZillitIcon(icon = icon, contentDescription = label, tint = ink, size = 20.dp)
        }
        ZillitText(
            text = label,
            style = ZillitTheme.typography.labelSmall.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
            color = ZillitTheme.colors.textMuted,
        )
    }
}

@Composable
private fun ContactDetails(member: CrewMember, isSelf: Boolean, copy: CrewCopy, actions: CrewContactActions) {
    val primary = member.profileEmail
    // The Zillit mailbox, only when it is a different address from the one above it.
    val zillit = member.projectEmail.takeIf { it.isNotBlank() && it != primary }.orEmpty()
    val phone = if (member.phone.isNotBlank()) member.countryCode + member.phone else ""
    if (primary.isBlank() && zillit.isBlank() && phone.isBlank()) return

    SectionLabel(copy.t("contact_details", "Contact Details"))
    DetailCard {
        if (primary.isNotBlank()) {
            DetailRow(ZillitIcons.Mail, copy.t("Email", "Email"), last = zillit.isBlank() && phone.isBlank()) {
                LinkValue(primary, enabled = actions.email != null) { actions.email?.invoke(primary) }
            }
        }
        if (zillit.isNotBlank()) {
            DetailRow(ZillitIcons.Mail, copy.t("zillitEmailLabel", "Zillit Email"), last = phone.isBlank()) {
                LinkValue(zillit, enabled = !isSelf && actions.email != null) { actions.email?.invoke(zillit) }
            }
        }
        if (phone.isNotBlank()) {
            DetailRow(ZillitIcons.Phone, copy.t("Phone", "Phone"), last = true) {
                val gsm = copy.t(
                    "you_can_call_gsm_contacts_through_mobile",
                    "You can call GSM contacts only through a mobile device.",
                )
                ZillitTooltip(gsm) {
                    DetailValue(phone)
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    ZillitText(
        text = text.uppercase(),
        style = ZillitTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = 0.08.em),
        color = ZillitTheme.colors.textMuted,
        modifier = Modifier.padding(bottom = 10.dp),
    )
}

@Composable
private fun DetailCard(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 22.dp)
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, ZillitTheme.colors.border, RoundedCornerShape(12.dp))
            .background(ZillitTheme.colors.surface),
    ) { content() }
}

@Composable
private fun DetailRow(icon: ImageVector, label: String, last: Boolean, value: @Composable () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)).background(crewPalette().accentTile),
            contentAlignment = Alignment.Center,
        ) {
            ZillitIcon(icon = icon, tint = crewPalette().grip, size = 16.dp)
        }
        ZillitText(
            text = label,
            style = ZillitTheme.typography.bodyMedium.copy(fontSize = 14.sp, fontWeight = FontWeight.Medium),
            color = ZillitTheme.colors.textSecondary,
            maxLines = 1,
        )
        Box(Modifier.weight(1f).padding(start = 12.dp), contentAlignment = Alignment.CenterEnd) { value() }
    }
    if (!last) Box(Modifier.fillMaxWidth().height(1.dp).background(ZillitTheme.colors.divider))
}

@Composable
private fun DetailValue(text: String) {
    ZillitText(
        text = text,
        style = ZillitTheme.typography.bodyMedium.copy(fontSize = 14.sp, fontWeight = FontWeight.Bold),
        color = ZillitTheme.colors.textPrimary,
        textAlign = TextAlign.End,
        maxLines = 2,
    )
}

@Composable
private fun LinkValue(text: String, enabled: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    ZillitText(
        text = text,
        style = ZillitTheme.typography.bodyMedium.copy(
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            textDecoration = if (enabled && hovered) TextDecoration.Underline else TextDecoration.None,
        ),
        color = if (enabled && hovered) ZillitTheme.colors.info else ZillitTheme.colors.textPrimary,
        textAlign = TextAlign.End,
        maxLines = 2,
        modifier = Modifier
            .hoverable(interaction)
            .then(if (enabled) Modifier.pointerHoverIcon(PointerIcon.Hand) else Modifier)
            .clickable(enabled = enabled, interactionSource = interaction, indication = null, onClick = onClick),
    )
}

/**
 * The server's phrasing, shown as is; an epoch (seconds or milliseconds) is
 * read as a date first.
 */
internal fun joiningDate(raw: String): String {
    val trimmed = raw.trim()
    val number = trimmed.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }?.toLongOrNull() ?: return trimmed
    val millis = if (number < EPOCH_SECONDS_LIMIT) number * MILLIS_PER_SECOND else number
    return EpochDate.date(millis).ifBlank { trimmed }
}

/** The action buttons rise a little under the pointer. */
private fun lift(hovered: Boolean) = if (hovered) 10.dp else 6.dp

private val AMBER = Color(0xFFF99300)
private val AMBER_DEEP = Color(0xFFE07C00)
private const val EPOCH_SECONDS_LIMIT = 100_000_000_000L
private const val MILLIS_PER_SECOND = 1000L
