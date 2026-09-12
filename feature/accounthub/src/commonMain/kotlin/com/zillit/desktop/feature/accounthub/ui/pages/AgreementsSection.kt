package com.zillit.desktop.feature.accounthub.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitSectionLabel
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.accounthub.domain.AgreementDocument
import com.zillit.desktop.feature.accounthub.ui.AccountHubEvent
import com.zillit.desktop.feature.accounthub.ui.AccountHubUiState
import com.zillit.desktop.feature.accounthub.ui.QueuedAgreementFile
import com.zillit.desktop.feature.accounthub.ui.SetupRemoval
import com.zillit.desktop.feature.accounthub.ui.components.FieldHint
import com.zillit.desktop.feature.accounthub.ui.components.SectionShell
import com.zillit.desktop.core.designsystem.component.ZillitFileBadge

private const val TITLE_WIDTH = 200
private const val KILOBYTE = 1024.0

/** One decimal place on megabytes; a 20 MB cap does not need two. */
private const val ONE_DECIMAL = 10.0

/**
 * The production's standard agreement documents.
 *
 * A deal memo offers these as additional documents for signature, so an empty
 * list here is a deal memo that cannot carry its own paperwork.
 *
 * There is no section-level save, unlike its neighbours: uploading appends and
 * the bin removes, each on its own route. The pending queue is the only dirty
 * state, and it carries its own "Upload" beside the count — the shape the web
 * settled on for the same reason.
 */
@Composable
internal fun AgreementsSection(
    state: AccountHubUiState,
    onEvent: (AccountHubEvent) -> Unit,
    canAttach: Boolean,
    canOpen: Boolean = false,
) {
    val setup = state.setup
    val editable = state.viewer.canEdit && canAttach

    SectionShell(
        title = "Agreements Documents",
        description = "Master contract templates and signed agreements. Pick one or more files, then give each a " +
            "name + optional description before saving.",
        editable = editable,
        extraActions = {
            if (editable) {
                ZillitButton(
                    text = "Add PDFs",
                    onClick = { onEvent(AccountHubEvent.PickAgreementFiles) },
                    variant = ButtonVariant.Secondary,
                    size = ButtonSize.Small,
                    leadingIcon = ZillitIcons.Add,
                    enabled = !setup.agreementsUploading,
                )
            }
        },
    ) {
        FieldHint("PDF only, 20 MB a file — the signing flow takes nothing else. ${setup.agreements.size} stored.")

        if (!canAttach) {
            ZillitNotice(
                text = "Uploading is unavailable — this window has no file storage wired.",
                tone = StatusTone.Neutral,
                icon = ZillitIcons.Info,
            )
        }

        UploadQueue(setup.agreementQueue, setup.agreementsUploading, onEvent)

        ZillitSectionLabel("Stored documents")
        if (setup.agreements.isEmpty() && !setup.agreementsLoading) {
            EmptyLine("No agreement documents yet.")
        }
        setup.agreements.forEach { document ->
            StoredRow(
                document = document,
                editable = state.viewer.canEdit,
                canOpen = canOpen,
                onOpen = { onEvent(AccountHubEvent.OpenAgreementDocument(document.id)) },
                onRemove = { onEvent(AccountHubEvent.AskRemove(SetupRemoval.AgreementRow(document))) },
            )
        }
    }
}

/** The picked-but-unsent files, with the one control that sends them. */
@Composable
private fun UploadQueue(
    queue: List<QueuedAgreementFile>,
    uploading: Boolean,
    onEvent: (AccountHubEvent) -> Unit,
) {
    if (queue.isEmpty()) return
    QueueHeader(queue.size, uploading, onEvent)
    queue.forEachIndexed { index, row ->
        QueuedRow(
            row = row,
            enabled = !uploading,
            onChange = { next ->
                onEvent(
                    AccountHubEvent.EditAgreementQueue(
                        queue.mapIndexed { i, existing -> if (i == index) next else existing },
                    ),
                )
            },
            onRemove = {
                onEvent(AccountHubEvent.EditAgreementQueue(queue.filterIndexed { i, _ -> i != index }))
            },
        )
    }
}

@Composable
private fun QueueHeader(count: Int, uploading: Boolean, onEvent: (AccountHubEvent) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitSectionLabel("Ready to upload")
        ZillitStatusPill(label = "$count waiting", tone = StatusTone.Pending)
        ZillitButton(
            text = "Save all $count",
            onClick = { onEvent(AccountHubEvent.UploadAgreementFiles) },
            size = ButtonSize.Small,
            loading = uploading,
            enabled = !uploading,
        )
    }
}

/** A picked file, named and described before it is sent. */
@Composable
private fun QueuedRow(
    row: QueuedAgreementFile,
    enabled: Boolean,
    onChange: (QueuedAgreementFile) -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitTextField(
            value = row.title,
            onValueChange = { onChange(row.copy(title = it)) },
            placeholder = "Title *",
            enabled = enabled,
            modifier = Modifier.width(TITLE_WIDTH.dp),
        )
        ZillitTextField(
            value = row.description,
            onValueChange = { onChange(row.copy(description = it)) },
            placeholder = "+ Add description",
            enabled = enabled,
            modifier = Modifier.weight(1f),
        )
        ZillitText(
            text = "${row.file.name} · ${row.file.bytes.asFileSize()}",
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textSecondary,
        )
        ZillitIconButton(
            icon = ZillitIcons.Trash,
            contentDescription = "Remove from pending",
            onClick = onRemove,
            enabled = enabled,
        )
    }
}

@Composable
private fun StoredRow(
    document: AgreementDocument,
    editable: Boolean,
    canOpen: Boolean,
    onOpen: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            ZillitText(
                text = document.title.ifBlank { document.name },
                style = ZillitTheme.typography.bodyMedium,
            )
            ZillitText(
                text = listOfNotNull(
                    document.description.takeIf { it.isNotBlank() },
                    document.name.takeIf { it.isNotBlank() },
                    document.fileSize.takeIf { it > 0 }?.asFileSize(),
                ).joinToString(" · "),
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textSecondary,
            )
        }
        ZillitFileBadge(fileName = document.name.ifBlank { document.title })
        if (canOpen && document.media.isNotBlank()) {
            ZillitButton(
                text = "Open",
                onClick = onOpen,
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
                leadingIcon = ZillitIcons.Download,
            )
        }
        if (editable) {
            ZillitIconButton(
                icon = ZillitIcons.Trash,
                contentDescription = "Remove ${document.title.ifBlank { document.name }}",
                onClick = onRemove,
            )
        }
    }
}

/** Kilobytes below a megabyte, megabytes above — the sizes a 20 MB cap spans. */
private fun Long.asFileSize(): String {
    val kb = this / KILOBYTE
    if (kb < KILOBYTE) return "${kb.toInt()} KB"
    val tenths = ((kb / KILOBYTE) * ONE_DECIMAL).toInt() / ONE_DECIMAL
    return "$tenths MB"
}
