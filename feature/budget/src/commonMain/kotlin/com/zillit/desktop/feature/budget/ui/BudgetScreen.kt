package com.zillit.desktop.feature.budget.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitAvatar
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitDivider
import com.zillit.desktop.core.designsystem.component.ZillitEmptyState
import com.zillit.desktop.core.designsystem.component.ZillitFileBadge
import com.zillit.desktop.core.designsystem.component.ZillitPageHeader
import com.zillit.desktop.core.designsystem.component.ZillitScrollColumn
import com.zillit.desktop.core.designsystem.component.ZillitSectionCard
import com.zillit.desktop.core.designsystem.component.ZillitStatTile
import com.zillit.desktop.core.designsystem.component.ZillitTab
import com.zillit.desktop.core.designsystem.component.ZillitTabStrip
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.feature.budget.domain.BudgetDocument
import com.zillit.desktop.feature.budget.domain.BudgetType

/**
 * The Budget tool.
 *
 * Two budgets, one screen — the main budget for the production and one per
 * department — as the web serves both from a single page. The tab strip only
 * ever offers what this viewer's rights allow, so there is no route to a
 * "no access" state inside the tool.
 */
@Composable
fun BudgetScreen(
    state: BudgetUiState,
    onEvent: (BudgetEvent) -> Unit,
    modifier: Modifier = Modifier,
    /**
     * The discussion that belongs to the budget on screen, supplied by the
     * host: the chat tool's own thread, scoped to this budget's tool and
     * department. Null draws the documents alone — which is what a host
     * without a chat surface (and every render test) gets.
     */
    conversation: (@Composable () -> Unit)? = null,
) {
    Box(modifier = modifier.fillMaxSize().background(ZillitTheme.colors.canvas)) {
        when {
            state.viewer.hasNoAccess -> ZillitEmptyState(
                title = "No budget access",
                message = "Neither the main budget nor your department's is shared with you.",
                icon = ZillitIcons.Shield,
                modifier = Modifier.align(Alignment.Center),
            )

            else -> Column(Modifier.fillMaxSize()) {
                Header(state, onEvent)
                ZillitDivider()
                Body(state, onEvent, conversation)
            }
        }
        if (state.membersOpen) MembersDialog(state, onEvent)
    }
}

@Composable
private fun Header(state: BudgetUiState, onEvent: (BudgetEvent) -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(ZillitTheme.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        ZillitPageHeader(
            title = "Budget",
            eyebrow = "Film tools",
            description = "The production's budget and each department's, with who has seen them.",
            actions = {
                ZillitButton(
                    text = "Members",
                    onClick = { onEvent(BudgetEvent.ShowMembers) },
                    variant = ButtonVariant.Secondary,
                    leadingIcon = ZillitIcons.Users,
                )
                if (state.canPostHere) {
                    ZillitButton(
                        text = "Upload budget",
                        onClick = { onEvent(BudgetEvent.Upload) },
                        leadingIcon = ZillitIcons.Upload,
                        loading = state.busy,
                    )
                }
            },
        )
        // One tab and nothing to switch to is a control that only takes up
        // room; the header already names where you are.
        if (state.tabs.size > 1) {
            ZillitTabStrip(
                tabs = state.tabs.map { ZillitTab(id = it.name, label = it.label) },
                activeId = state.tab.name,
                onSelect = { id ->
                    BudgetTab.entries.firstOrNull { it.name == id }
                        ?.let { onEvent(BudgetEvent.TabChanged(it)) }
                },
            )
        }
    }
}

@Composable
private fun Body(
    state: BudgetUiState,
    onEvent: (BudgetEvent) -> Unit,
    conversation: (@Composable () -> Unit)?,
) {
    Row(Modifier.fillMaxSize()) {
        if (state.tab == BudgetTab.Department) {
            DepartmentList(state, onEvent)
            ZillitDivider(Modifier.width(1.dp).fillMaxSize())
        }
        // The document is the subject; the conversation about it is the
        // larger half, as on the web (a 30/70 split there).
        DocumentPane(state, onEvent, Modifier.weight(DOCUMENT_WEIGHT))
        if (conversation != null) {
            ZillitDivider(Modifier.width(1.dp).fillMaxSize())
            Box(Modifier.weight(CONVERSATION_WEIGHT).fillMaxSize()) { conversation() }
        }
    }
}

/** The departments that have a budget, one row each. */
@Composable
private fun DepartmentList(state: BudgetUiState, onEvent: (BudgetEvent) -> Unit) {
    ZillitScrollColumn(
        modifier = Modifier.width(DEPARTMENT_PANE).fillMaxSize(),
        contentPadding = PaddingValues(ZillitTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        if (state.departmentBudgets.isEmpty()) {
            ZillitText(
                text = if (state.loading) "Loading…" else "No department has uploaded a budget yet.",
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textSecondary,
            )
        }
        state.departmentBudgets.forEach { document ->
            val chosen = document.id == state.selected?.id
            ZillitSectionCard(
                modifier = Modifier.fillMaxWidth(),
                title = document.label(),
                meta = if (document.file?.isPresent == true) "Budget attached" else "No file",
                icon = if (chosen) ZillitIcons.Check else null,
            ) {
                ZillitButton(
                    text = if (chosen) "Showing" else "Open",
                    onClick = { onEvent(BudgetEvent.Select(document.id)) },
                    variant = if (chosen) ButtonVariant.Secondary else ButtonVariant.Tertiary,
                    enabled = !chosen,
                )
            }
        }
    }
}

/** Whichever budget is selected: its file, its numbers, its actions. */
@Composable
private fun DocumentPane(state: BudgetUiState, onEvent: (BudgetEvent) -> Unit, modifier: Modifier = Modifier) {
    val document = state.selected
    ZillitScrollColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(ZillitTheme.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        when {
            state.loading && document == null -> ZillitText(
                text = "Loading…",
                style = ZillitTheme.typography.bodyMedium,
                color = ZillitTheme.colors.textSecondary,
            )

            document?.file?.isPresent != true -> ZillitEmptyState(
                title = "No budget uploaded",
                message = if (state.canPostHere) {
                    "Upload a budget and everyone with access will see it here."
                } else {
                    "Nobody has uploaded this budget yet."
                },
                icon = ZillitIcons.File,
            )

            else -> DocumentCard(state, document, onEvent)
        }
        state.error?.let { message -> Message(message, tone = true, onEvent) }
        state.notice?.let { message -> Message(message, tone = false, onEvent) }
    }
}

@Composable
private fun DocumentCard(state: BudgetUiState, document: BudgetDocument, onEvent: (BudgetEvent) -> Unit) {
    val file = document.file ?: return
    ZillitSectionCard(
        modifier = Modifier.fillMaxWidth(),
        title = document.label(),
        meta = document.uploadedByName.takeIf { it.isNotBlank() }?.let { "Uploaded by $it" },
    ) {
        FileRow(state, document, onEvent)
        CountRow(state)
    }
}

/** The file itself, and everything that can be done to it. */
@Composable
private fun FileRow(state: BudgetUiState, document: BudgetDocument, onEvent: (BudgetEvent) -> Unit) {
    val file = document.file ?: return
    Row(
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitFileBadge(fileName = file.name)
        Column(Modifier.weight(1f)) {
            ZillitText(
                text = file.name,
                style = ZillitTheme.typography.bodyMedium,
                color = ZillitTheme.colors.textPrimary,
            )
            if (file.sizeBytes > 0) {
                ZillitText(
                    text = readableSize(file.sizeBytes),
                    style = ZillitTheme.typography.labelSmall,
                    color = ZillitTheme.colors.textMuted,
                )
            }
        }
        ZillitButton(
            text = "Open",
            onClick = { onEvent(BudgetEvent.OpenFile) },
            variant = ButtonVariant.Secondary,
            leadingIcon = ZillitIcons.Eye,
        )
        if (state.viewer.canDownload(document.type)) {
            ZillitButton(
                text = "Download",
                onClick = { onEvent(BudgetEvent.DownloadFile) },
                variant = ButtonVariant.Secondary,
                leadingIcon = ZillitIcons.Download,
            )
        }
        if (state.canPostHere) {
            ZillitButton(
                text = "Remove",
                onClick = { onEvent(BudgetEvent.Delete(document.id)) },
                variant = ButtonVariant.Tertiary,
                leadingIcon = ZillitIcons.Trash,
                enabled = !state.busy,
            )
        }
    }
}

/** Who has looked, and who has taken a copy. An unknown count shows a dash. */
@Composable
private fun CountRow(state: BudgetUiState) {
    Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
        ZillitStatTile(
            label = "Views",
            value = state.viewCount?.toString() ?: "—",
            icon = ZillitIcons.Eye,
            modifier = Modifier.weight(1f),
        )
        ZillitStatTile(
            label = "Downloads",
            value = state.downloadCount?.toString() ?: "—",
            icon = ZillitIcons.Download,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun Message(message: String, tone: Boolean, onEvent: (BudgetEvent) -> Unit) {
    ZillitSectionCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            ZillitText(
                text = message,
                style = ZillitTheme.typography.bodySmall,
                color = if (tone) ZillitTheme.colors.danger else ZillitTheme.colors.textSecondary,
                modifier = Modifier.weight(1f),
            )
            ZillitButton(
                text = "Dismiss",
                onClick = { onEvent(BudgetEvent.DismissMessage) },
                variant = ButtonVariant.Tertiary,
            )
        }
    }
}

@Composable
private fun MembersDialog(state: BudgetUiState, onEvent: (BudgetEvent) -> Unit) {
    ZillitDialogShell(
        title = "Who can see this budget",
        subtitle = state.tab.label,
        icon = ZillitIcons.Users,
        visible = true,
        onDismiss = { onEvent(BudgetEvent.DismissMembers) },
    ) {
        if (state.members.isEmpty()) {
            ZillitText(
                text = "Nobody else has been given this budget.",
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textSecondary,
            )
        }
        state.members.forEach { member ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = ZillitTheme.spacing.xxs),
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ZillitAvatar(name = member.fullName)
                Column {
                    ZillitText(
                        text = member.fullName,
                        style = ZillitTheme.typography.bodyMedium,
                        color = ZillitTheme.colors.textPrimary,
                    )
                    if (member.departmentName.isNotBlank()) {
                        ZillitText(
                            text = member.departmentName,
                            style = ZillitTheme.typography.labelSmall,
                            color = ZillitTheme.colors.textMuted,
                        )
                    }
                }
            }
        }
    }
}

/** "1.2 MB" — the same shape the drive's size column uses. */
internal fun readableSize(bytes: Long): String = when {
    bytes < BYTES_PER_KB -> "$bytes B"
    bytes < BYTES_PER_KB * BYTES_PER_KB -> "${round1(bytes / BYTES_PER_KB)} KB"
    else -> "${round1(bytes / (BYTES_PER_KB * BYTES_PER_KB))} MB"
}

/** One decimal place, without pulling in a formatter for two call sites. */
private fun round1(value: Double): Double = (value * TENTHS).toInt() / TENTHS

private const val BYTES_PER_KB = 1024.0
private const val TENTHS = 10.0

/**
 * What to call a budget on screen.
 *
 * The list endpoint does not always carry `department_name` — a live
 * department budget came back with none — so the type decides the fallback.
 * Naming an unnamed department budget "Main budget" told the reader the
 * opposite of the truth.
 */
internal fun BudgetDocument.label(): String = departmentName.ifBlank {
    when (type) {
        BudgetType.Main -> "Main budget"
        else -> "Department budget"
    }
}

private val DEPARTMENT_PANE = 280.dp
private const val DOCUMENT_WEIGHT = 1f
private const val CONVERSATION_WEIGHT = 1.4f
