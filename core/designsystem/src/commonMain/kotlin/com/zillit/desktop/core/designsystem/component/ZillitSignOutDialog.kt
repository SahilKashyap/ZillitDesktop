package com.zillit.desktop.core.designsystem.component

import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

/**
 * The question Logout asks first — the same one Settings asks (and Android:
 * "Logout" / "Are you sure?") — because a stray click would sign the person
 * out of every production at once.
 *
 * Shared by the shell's rail and the production list, which offers Logout
 * before any production is open. The dialog fills its parent, so compose it
 * at a screen's root, never inside a narrow column or a header row: in the
 * rail's 60pt column the card came out rail-width, its buttons crushed.
 */
@Composable
fun ZillitSignOutDialog(visible: Boolean, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    ZillitDialogShell(
        title = str(S.desktop_sign_out_title),
        subtitle = str(S.desktop_sign_out_body),
        icon = ZillitIcons.Logout,
        visible = visible,
        onDismiss = onDismiss,
        width = SIGN_OUT_DIALOG_WIDTH,
        actions = {
            Spacer(Modifier.weight(1f))
            ZillitButton(text = str(S.cancel), variant = ButtonVariant.Secondary, onClick = onDismiss)
            ZillitButton(text = str(S.desktop_sign_out), onClick = onConfirm)
        },
    ) {
        ZillitText(
            text = str(S.desktop_sign_out_note),
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textMuted,
        )
    }
}

private val SIGN_OUT_DIALOG_WIDTH = 380.dp
