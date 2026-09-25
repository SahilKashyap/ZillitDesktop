package com.zillit.desktop.feature.cardexpenses.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.cardexpenses.ui.CardEvent
import com.zillit.desktop.feature.cardexpenses.ui.CardUiState
import com.zillit.desktop.feature.cardexpenses.ui.date

/**
 * A receipt's query thread (`QueryPanel.jsx`): who asked what, oldest first,
 * and a line to add to it. The first message opens the thread.
 */
@Suppress("LongMethod") // The thread and its composer, read top to bottom.
@Composable
fun QueryDialog(state: CardUiState, onEvent: (CardEvent) -> Unit) {
    val query = state.query ?: return
    ZillitDialogShell(
        title = str(S.ah_query_label),
        subtitle = query.title,
        icon = ZillitIcons.Info,
        visible = true,
        width = QUERY_WIDTH,
        onDismiss = { onEvent(CardEvent.CloseQuery) },
        actions = {
            ZillitButton(
                text = str(S.close),
                onClick = { onEvent(CardEvent.CloseQuery) },
                variant = ButtonVariant.Tertiary,
            )
            Spacer(Modifier.weight(1f))
            ZillitButton(
                text = str(S.send),
                onClick = { onEvent(CardEvent.SendQuery) },
                leadingIcon = ZillitIcons.Send,
                enabled = query.text.isNotBlank() && !query.loading && !query.sending,
                loading = query.sending,
            )
        },
    ) {
        when {
            query.loading -> Row(Modifier.fillMaxWidth(), Arrangement.Center) { ZillitSpinner() }
            query.thread.messages.isEmpty() -> ZillitText(
                text = str(S.ah_no_queries_yet),
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textMuted,
            )

            else -> query.thread.messages.forEach { message ->
                val mine = message.userId == state.viewer.userId
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
                    verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
                ) {
                    ZillitText(
                        text = listOfNotNull(
                            if (mine) str(S.txt_me) else state.personName(message.userId),
                            date(message.at).takeIf { it != "—" },
                        ).joinToString(" · "),
                        style = ZillitTheme.typography.bodySmall,
                        color = ZillitTheme.colors.textMuted,
                    )
                    ZillitText(text = message.text, style = ZillitTheme.typography.bodyMedium)
                }
            }
        }
        ZillitTextField(
            value = query.text,
            onValueChange = { onEvent(CardEvent.EditQuery(it)) },
            placeholder = str(S.type_a_message),
            singleLine = false,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private val QUERY_WIDTH = 560.dp
