package com.zillit.desktop.feature.calls.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitSwitch
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.calls.data.livekit.Line3InCall
import com.zillit.desktop.feature.calls.data.livekit.LiveKitCallPolicy

/**
 * The host's call-level controls — the web's `HostControlsPanel.tsx`, as a
 * panel beside the picture like every other call panel.
 *
 * A master switch and the flags under it, then the one-shot actions. The
 * flags are drawn inert while the master is off, as the web greys them, so
 * the panel reads as "nothing is restricted" rather than as seven switches
 * that happen to be on.
 */
@Composable
fun CallHostControlsPanel(
    policy: LiveKitCallPolicy,
    onPolicy: (LiveKitCallPolicy) -> Unit,
    onAction: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(PANEL_CORNER))
            .background(CallPalette.menu)
            .padding(vertical = ZillitTheme.spacing.sm),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = ZillitTheme.spacing.lg, vertical = ZillitTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            ZillitIcon(icon = ZillitIcons.Shield, contentDescription = null, tint = CallPalette.text, size = ROW_ICON)
            ZillitText(
                text = str(S.desktop_call_host_controls),
                style = ZillitTheme.typography.titleSmall,
                color = CallPalette.text,
                modifier = Modifier.weight(1f),
            )
            Box(modifier = Modifier.clickable(onClick = onClose)) {
                ZillitIcon(
                    icon = ZillitIcons.Close,
                    contentDescription = str(S.close),
                    tint = CallPalette.muted,
                    size = ROW_ICON,
                )
            }
        }
        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
            PolicySwitches(policy, onPolicy)
            ZillitText(
                text = str(S.dd_actions),
                style = ZillitTheme.typography.labelSmall,
                color = CallPalette.muted,
                modifier = Modifier.padding(horizontal = ZillitTheme.spacing.lg, vertical = ZillitTheme.spacing.sm),
            )
            ActionRow(ZillitIcons.MicOff, str(S.desktop_call_mute_everyone)) { onAction(Line3InCall.ACTION_MUTE_ALL) }
            ActionRow(ZillitIcons.Hand, str(S.desktop_call_lower_all_hands)) {
                onAction(Line3InCall.ACTION_LOWER_HANDS)
            }
            ActionRow(ZillitIcons.Photo, str(S.desktop_call_clear_everyones_background)) {
                onAction(Line3InCall.ACTION_CLEAR_BACKGROUNDS)
            }
        }
    }
}

/** The master switch, then the flags it gates — the web's order (`HostControlsPanel.tsx:45-69`). */
@Composable
private fun PolicySwitches(policy: LiveKitCallPolicy, onPolicy: (LiveKitCallPolicy) -> Unit) {
    PolicySwitch(str(S.desktop_call_enable_host_controls), policy.on, enabled = true) { onPolicy(policy.copy(on = it)) }
    val live = policy.on
    PolicySwitch(str(S.desktop_call_allow_chat), policy.chatEnabled, live) { onPolicy(policy.copy(chatEnabled = it)) }
    PolicySwitch(str(S.desktop_call_allow_background_effects), policy.bgEffectsAllowed, live) {
        onPolicy(policy.copy(bgEffectsAllowed = it))
    }
    PolicySwitch(str(S.desktop_call_lock_screen_sharing), policy.screenShareLocked, live) {
        onPolicy(policy.copy(screenShareLocked = it))
    }
    PolicySwitch(str(S.desktop_call_allow_joining_via_link), policy.linkJoinEnabled, live) {
        onPolicy(policy.copy(linkJoinEnabled = it))
    }
    PolicySwitch(str(S.desktop_call_allow_raise_hand), policy.handRaiseAllowed, live) {
        onPolicy(policy.copy(handRaiseAllowed = it))
    }
    PolicySwitch(str(S.desktop_call_allow_reactions), policy.reactionsAllowed, live) {
        onPolicy(policy.copy(reactionsAllowed = it))
    }
    PolicySwitch(str(S.desktop_call_allow_call_recording), policy.recordingAllowed, live) {
        onPolicy(policy.copy(recordingAllowed = it))
    }
}

@Composable
private fun PolicySwitch(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else INERT_ALPHA)
            .padding(horizontal = ZillitTheme.spacing.lg, vertical = ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitText(
            text = label,
            style = ZillitTheme.typography.bodyMedium,
            color = CallPalette.text,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        ZillitSwitch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
private fun ActionRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = ZillitTheme.spacing.lg, vertical = ZillitTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        ZillitIcon(icon = icon, contentDescription = null, tint = CallPalette.text, size = ROW_ICON)
        ZillitText(text = label, style = ZillitTheme.typography.bodyMedium, color = CallPalette.text, maxLines = 1)
    }
}

private val PANEL_CORNER = 12.dp
private val ROW_ICON = 18.dp
private const val INERT_ALPHA = 0.45f
