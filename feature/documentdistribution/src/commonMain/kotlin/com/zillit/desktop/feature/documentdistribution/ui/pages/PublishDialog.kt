package com.zillit.desktop.feature.documentdistribution.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.common.EpochDate
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitCheckbox
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitScrollColumn
import com.zillit.desktop.core.designsystem.component.ZillitSectionLabel
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.documentdistribution.domain.PublishMode
import com.zillit.desktop.feature.documentdistribution.domain.PublishTarget
import com.zillit.desktop.feature.documentdistribution.domain.ScheduleTypeChoice
import com.zillit.desktop.feature.documentdistribution.ui.DocDistEvent
import com.zillit.desktop.feature.documentdistribution.ui.DocDistUiState
import com.zillit.desktop.feature.documentdistribution.ui.PublishState

/**
 * Publishing documents *into* another tool rather than emailing them out — a
 * call sheet onto the unit's chat, a schedule onto the schedule tool.
 *
 * Each destination asks for different things, and asks for them because its
 * receiving endpoint refuses the publish without them. The fields therefore
 * follow the chosen destination rather than all being shown at once, and the
 * confirm button reports the first missing one instead of sending a request
 * that comes back as an untranslated key.
 */
@Composable
fun PublishDialog(state: DocDistUiState, onEvent: (DocDistEvent) -> Unit) {
    val publish = state.publish ?: return
    val count = publish.draft.documentIds.size

    ZillitDialogShell(
        title = "Publish",
        subtitle = "$count file" + (if (count == 1) "" else "s") + " into another tool",
        visible = true,
        onDismiss = { onEvent(DocDistEvent.ClosePublish) },
        icon = ZillitIcons.Send,
    ) {
        DestinationPicker(state, publish, onEvent)
        publish.target?.let { target ->
            TargetFields(target, publish, state.viewer.isTelevision, onEvent)
            if (publish.loadingPublished) ZillitSpinner()
            if (publish.offersMode) RepublishOptions(publish, onEvent)
        }
        PublishActions(state, publish, onEvent)
    }
}

@Composable
private fun ColumnScope.DestinationPicker(
    state: DocDistUiState,
    publish: PublishState,
    onEvent: (DocDistEvent) -> Unit,
) {
    ZillitSectionLabel("Destination")
    val offered = state.viewer.targets()
    if (offered.isEmpty()) {
        ZillitText(
            // Named for what it is: the rights that matter belong to the tool
            // being published *into*, so "ask for Document Distribution
            // access" would send them to the wrong administrator.
            text = "You do not have posting rights on any tool that accepts published documents.",
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textSecondary,
        )
        return
    }
    offered.groupBy { it.group }.forEach { (group, targets) ->
        if (group != null) {
            ZillitText(
                text = group,
                style = ZillitTheme.typography.labelSmall,
                color = ZillitTheme.colors.textMuted,
            )
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
        ) {
            targets.forEach { target ->
                ZillitButton(
                    text = target.label,
                    onClick = { onEvent(DocDistEvent.ChoosePublishTarget(target.category)) },
                    variant = if (publish.target?.category == target.category) {
                        ButtonVariant.Secondary
                    } else {
                        ButtonVariant.Tertiary
                    },
                    size = ButtonSize.Small,
                )
            }
        }
    }
}

@Composable
private fun ColumnScope.TargetFields(
    target: PublishTarget,
    publish: PublishState,
    isTelevision: Boolean,
    onEvent: (DocDistEvent) -> Unit,
) {
    val draft = publish.draft
    fun edit(next: com.zillit.desktop.feature.documentdistribution.domain.PublishDraft) =
        onEvent(DocDistEvent.EditPublishDraft(next))

    if (target.singleFile && draft.documentIds.size > 1) {
        ZillitText(
            text = "${target.label} takes one document at a time — a second would replace it.",
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.danger,
        )
    }
    if (target.needsName) {
        ZillitTextField(
            value = draft.name,
            onValueChange = { edit(draft.copy(name = it)) },
            label = "Name",
            placeholder = "Day Out of Days",
        )
    }
    if (target.needsScene) {
        ZillitTextField(
            value = draft.sceneNumber,
            onValueChange = { edit(draft.copy(sceneNumber = it)) },
            label = "Scene number",
            placeholder = "12A",
        )
    }
    if (target.needsScheduleType) {
        ZillitSectionLabel("Which schedule is this?")
        Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
            ScheduleTypeChoice.entries.forEach { choice ->
                ZillitButton(
                    text = choice.label,
                    onClick = { edit(draft.copy(scheduleType = choice.wire)) },
                    variant = if (draft.scheduleType == choice.wire) {
                        ButtonVariant.Secondary
                    } else {
                        ButtonVariant.Tertiary
                    },
                    size = ButtonSize.Small,
                )
            }
        }
    }
    if (target.needsEpisodeOnTelevision && isTelevision) {
        ZillitTextField(
            value = draft.episode,
            onValueChange = { edit(draft.copy(episode = it)) },
            label = "Episode",
            placeholder = "101",
        )
    }
    if (target.takesNote) {
        ZillitTextField(
            value = draft.note,
            onValueChange = { edit(draft.copy(note = it)) },
            label = "Note (optional)",
            placeholder = "Anything the recipients should know",
        )
    }
}

/**
 * Add or replace, offered only once something is already published there.
 *
 * The backend answers `publication_mode_not_supported` if `mode` reaches a
 * destination that does not republish, so this block is the gate on sending
 * it at all — see [PublishState.offersMode].
 */
@Composable
private fun ColumnScope.RepublishOptions(publish: PublishState, onEvent: (DocDistEvent) -> Unit) {
    ZillitSectionLabel("Already published there")
    Row(
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PublishMode.entries.forEach { mode ->
            ZillitButton(
                text = mode.label,
                onClick = {
                    onEvent(
                        DocDistEvent.EditPublishDraft(
                            publish.draft.copy(
                                mode = mode,
                                // Leaving targets ticked behind an "add" would
                                // send a replace list the server acts on.
                                replaceChatIds = if (mode == PublishMode.Add) {
                                    emptyList()
                                } else {
                                    publish.draft.replaceChatIds
                                },
                            ),
                        ),
                    )
                },
                variant = if (publish.draft.mode == mode) {
                    ButtonVariant.Secondary
                } else {
                    ButtonVariant.Tertiary
                },
                size = ButtonSize.Small,
            )
        }
    }
    if (publish.draft.mode != PublishMode.Replace) return
    ReplaceTargets(publish, onEvent)
}

/** Which of the live files this publish stands in for. */
@Composable
private fun ReplaceTargets(publish: PublishState, onEvent: (DocDistEvent) -> Unit) {
    ZillitScrollColumn(
        modifier = Modifier.fillMaxWidth().height(PUBLISHED_LIST_HEIGHT.dp),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
    ) {
        publish.alreadyPublished.forEach { file ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ZillitCheckbox(
                    checked = file.chatId in publish.draft.replaceChatIds,
                    onCheckedChange = { onEvent(DocDistEvent.ToggleReplaceTarget(file.chatId)) },
                )
                Column(modifier = Modifier.fillMaxWidth()) {
                    ZillitText(text = file.name, maxLines = 1)
                    file.publishedAt?.let { stamp ->
                        ZillitText(
                            text = EpochDate.dateTime(stamp),
                            style = ZillitTheme.typography.bodySmall,
                            color = ZillitTheme.colors.textMuted,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.PublishActions(
    state: DocDistUiState,
    publish: PublishState,
    onEvent: (DocDistEvent) -> Unit,
) {
    val problem = publish.problem(state.viewer.isTelevision)
    if (problem != null && publish.target != null) {
        ZillitText(
            text = problem,
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textSecondary,
        )
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm, Alignment.End),
    ) {
        ZillitButton(
            text = "Cancel",
            onClick = { onEvent(DocDistEvent.ClosePublish) },
            variant = ButtonVariant.Tertiary,
        )
        ZillitButton(
            text = "Publish",
            onClick = { onEvent(DocDistEvent.ConfirmPublish) },
            loading = publish.saving,
            enabled = !publish.saving && problem == null,
        )
    }
}

private const val PUBLISHED_LIST_HEIGHT = 180
