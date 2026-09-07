package com.zillit.desktop.feature.saportal.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.ImeAction
import com.zillit.desktop.core.common.EpochDate
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitEmptyState
import com.zillit.desktop.core.designsystem.component.ZillitSectionCard
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.saportal.domain.ArtisteQuery
import com.zillit.desktop.feature.saportal.domain.QueryMessage
import com.zillit.desktop.feature.saportal.ui.SaEvent
import com.zillit.desktop.feature.saportal.ui.SaUiState

/**
 * Questions the artiste has raised about a day, and the production's answers.
 *
 * Every thread hangs off one voucher — the server requires it — so a query
 * is always about something specific rather than a general message channel.
 */
@Composable
internal fun ColumnScope.QueriesPage(state: SaUiState, onEvent: (SaEvent) -> Unit) {
    state.openQuery?.let { thread ->
        QueryThread(thread, state.replyDraft, onEvent)
        return
    }

    if (state.queries.isEmpty()) {
        if (!state.loading) {
            ZillitEmptyState(
                title = "No queries",
                message = "If a day's hours or pay look wrong, raise it from that day.",
                icon = ZillitIcons.Info,
            )
        }
        return
    }

    state.openQueries.forEach { query -> QueryRow(query, onEvent) }
}

@Composable
private fun QueryRow(query: ArtisteQuery, onEvent: (SaEvent) -> Unit) {
    ZillitSectionCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                ZillitText(
                    text = query.title.ifBlank { query.topic.ifBlank { "Query" } },
                    style = ZillitTheme.typography.titleSmall,
                )
                ZillitText(
                    text = query.lastMessage.ifBlank { "No messages yet" },
                    style = ZillitTheme.typography.bodySmall,
                    color = ZillitTheme.colors.textSecondary,
                    maxLines = 1,
                )
                query.voucherCode.takeIf { it.isNotBlank() }?.let {
                    ZillitText(
                        text = "About $it",
                        style = ZillitTheme.typography.bodySmall,
                        color = ZillitTheme.colors.textMuted,
                    )
                }
            }
            ZillitStatusPill(
                label = if (query.resolved) "Resolved" else "Open",
                tone = if (query.resolved) StatusTone.Done else StatusTone.Pending,
            )
            ZillitButton(
                text = "Open",
                onClick = { onEvent(SaEvent.OpenQuery(query.id)) },
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
            )
        }
    }
}

@Composable
private fun ColumnScope.QueryThread(
    query: ArtisteQuery,
    draft: String,
    onEvent: (SaEvent) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitButton(
            text = "Back",
            onClick = { onEvent(SaEvent.CloseQuery) },
            variant = ButtonVariant.Tertiary,
            size = ButtonSize.Small,
        )
        ZillitText(
            text = query.title.ifBlank { "Query" },
            style = ZillitTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
        )
        if (!query.resolved) {
            ZillitButton(
                text = "Mark resolved",
                onClick = { onEvent(SaEvent.ResolveQuery(query.id)) },
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
            )
        }
    }

    query.messages.forEach { message -> MessageBubble(message) }

    ZillitTextField(
        value = draft,
        onValueChange = { onEvent(SaEvent.ReplyDraft(it)) },
        placeholder = if (query.resolved) "Replying reopens this query" else "Add to this query",
        onImeAction = { onEvent(SaEvent.SendReply) },
        imeAction = ImeAction.Send,
        modifier = Modifier.fillMaxWidth(),
    )
    ZillitButton(
        text = "Send",
        onClick = { onEvent(SaEvent.SendReply) },
        size = ButtonSize.Small,
        enabled = draft.isNotBlank(),
    )
}

@Composable
private fun MessageBubble(message: QueryMessage) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.medium)
            .background(
                if (message.fromArtiste) {
                    ZillitTheme.colors.surfaceRaised
                } else {
                    ZillitTheme.colors.surfaceSunken
                },
            )
            .padding(ZillitTheme.spacing.sm),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        ) {
            ZillitText(
                // "You" rather than the artiste's own name: they know who they
                // are, and the project's name is the one worth reading.
                text = if (message.fromArtiste) "You" else message.authorName.ifBlank { "Project" },
                style = ZillitTheme.typography.label,
                modifier = Modifier.weight(1f),
            )
            ZillitText(
                text = EpochDate.dateTime(message.at),
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textMuted,
            )
        }
        message.authorRole.takeIf { it.isNotBlank() && !message.fromArtiste }?.let {
            ZillitText(
                text = it,
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textMuted,
            )
        }
        ZillitText(text = message.text, style = ZillitTheme.typography.bodySmall)
    }
}
