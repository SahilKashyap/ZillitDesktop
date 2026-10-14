package com.zillit.desktop.feature.calls.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

/**
 * Decline with a message: three ready lines and a box to write your own.
 *
 * The same offer the phones make from their ring screen — on Android a bottom
 * sheet over the call, here the ring card's own face while it is open, because
 * the desktop card is already a small floating thing and a sheet over a sheet
 * is two dismissals for one answer.
 *
 * Every row sends and declines in one press. That is the point of the feature:
 * someone reaching for it has already decided not to answer, and a confirm
 * step would only hold the caller's ring open while they read it.
 */
@Composable
fun CallQuickReplyPanel(
    onSend: (String) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var custom by remember { mutableStateOf("") }
    val send = { text: String -> if (text.isNotBlank()) onSend(text.trim()) }
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        ZillitText(
            text = str(S.message),
            style = ZillitTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
            color = CallPalette.muted,
            maxLines = 1,
        )
        quickReplyPresets().forEach { preset ->
            PresetRow(preset) { send(preset) }
        }
        ZillitTextField(
            value = custom,
            // Clamped here rather than through the field's own `maxLength`,
            // which draws a 0/200 counter: a one-line reply is not a form
            // field, and the count is noise beside three ready sentences.
            onValueChange = { custom = it.take(REPLY_LIMIT) },
            placeholder = str(S.type_a_message),
            singleLine = true,
            imeAction = ImeAction.Send,
            onImeAction = { send(custom) },
            containerColor = CallPalette.control,
            contentColor = CallPalette.text,
            modifier = Modifier.fillMaxWidth(),
            trailingContent = {
                ZillitIcon(
                    icon = ZillitIcons.Send,
                    contentDescription = str(S.send),
                    tint = if (custom.isBlank()) CallPalette.muted else CallPalette.accent,
                    size = SEND_ICON,
                    modifier = Modifier.clickable(enabled = custom.isNotBlank()) { send(custom) },
                )
            },
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(ROW_CORNER))
                .clickable(onClick = onCancel)
                .padding(vertical = ZillitTheme.spacing.sm),
            contentAlignment = Alignment.Center,
        ) {
            ZillitText(
                text = str(S.cancel),
                style = ZillitTheme.typography.label,
                color = CallPalette.muted,
                maxLines = 1,
            )
        }
    }
}

/**
 * The three lines Android offers, word for word
 * (`txt_call_message_one`…`_three`), so a crew member who declines from a
 * phone one day and a Mac the next sends the same sentence.
 */
@Composable
fun quickReplyPresets(): List<String> =
    listOf(str(S.txt_call_message_one), str(S.txt_call_message_two), str(S.txt_call_message_three))

@Composable
private fun PresetRow(text: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(ROW_CORNER))
            .background(CallPalette.control)
            .clickable(onClick = onClick)
            .padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.sm),
        contentAlignment = Alignment.CenterStart,
    ) {
        ZillitText(
            text = text,
            style = ZillitTheme.typography.bodySmall,
            color = CallPalette.text,
            maxLines = 1,
        )
    }
}

private val ROW_CORNER = 10.dp
private val SEND_ICON = 18.dp

/** A decline's reply is a sentence, not a note; the chat row that carries it is one line. */
private const val REPLY_LIMIT = 200
