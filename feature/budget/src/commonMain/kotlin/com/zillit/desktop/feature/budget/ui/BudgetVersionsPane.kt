package com.zillit.desktop.feature.budget.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitActionMenu
import com.zillit.desktop.core.designsystem.component.ZillitBadge
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitFileBadge
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitMenuEntry
import com.zillit.desktop.core.designsystem.component.ZillitMenuEntries
import com.zillit.desktop.core.designsystem.component.ZillitMenuSurface
import com.zillit.desktop.core.designsystem.component.ZillitScrollColumn
import com.zillit.desktop.core.designsystem.component.ZillitSearchField
import com.zillit.desktop.core.designsystem.component.ZillitSectionLabel
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.budget.domain.BudgetActivity
import com.zillit.desktop.feature.budget.domain.BudgetChatEntry
import com.zillit.desktop.feature.budget.domain.BudgetDocument
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * The rail once a budget is open: the version picker, the document card with
 * its "More" menu, and the people and rooms discussing it
 * (`CommonBudget.jsx:renderBudgetView` + `ChatUserAndGroupList.jsx`).
 */
@Composable
internal fun VersionsPane(
    state: BudgetUiState,
    onEvent: (BudgetEvent) -> Unit,
    seams: BudgetScreenSeams,
) {
    when {
        state.loading && !state.hasDocuments -> LoadingRows()
        !state.hasDocuments -> RailEmpty(
            title = str(S.desktop_budget_none_uploaded),
            message = if (state.canPost) {
                str(S.desktop_budget_upload_pdf_hint)
            } else {
                "A budget appears here once someone with posting rights uploads one."
            },
            icon = ZillitIcons.File,
        )

        else -> Column(Modifier.fillMaxSize()) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = ZillitTheme.spacing.lg),
                verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            ) {
                VersionPicker(state, onEvent, seams)
                state.selected?.let { DocumentCard(state, it, onEvent) }
            }
            ZillitSectionLabel(
                text = str(S.conversations),
                modifier = Modifier.padding(
                    start = ZillitTheme.spacing.lg,
                    end = ZillitTheme.spacing.lg,
                    top = ZillitTheme.spacing.md,
                    bottom = ZillitTheme.spacing.xs,
                ),
            )
            ChatList(state, onEvent, seams, Modifier.weight(1f))
        }
    }
}

// -- the version picker -----------------------------------------------------------

/**
 * The web's antd `Select` of versions: the open one's title, a badge for
 * what waits on the others, and a caret; the menu lists every version with
 * who uploaded it and when, searchable by uploader
 * (`CommonBudget.jsx:1878-1932`, `SelectOptionComponent`).
 */
@Composable
@Suppress("LongMethod") // One dialog, drawn in one place.
private fun VersionPicker(state: BudgetUiState, onEvent: (BudgetEvent) -> Unit, seams: BudgetScreenSeams) {
    val colors = ZillitTheme.colors
    val selected = state.selected
    Box(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(ZillitTheme.shapes.medium)
                .background(colors.surfaceSunken)
                .border(1.dp, if (state.versionMenuOpen) colors.accent else colors.border, ZillitTheme.shapes.medium)
                .clickable { onEvent(BudgetEvent.VersionMenu(!state.versionMenuOpen)) }
                .padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            Column(Modifier.weight(1f)) {
                ZillitText(
                    text = selected?.displayTitle(seams.nameOf) ?: str(S.desktop_select_a_file),
                    style = ZillitTheme.typography.bodyMedium,
                    color = colors.textPrimary,
                    maxLines = 1,
                )
                ZillitText(
                    text = if (selected?.id == state.latest?.id) {
                        str(S.desktop_budget_versions_latest, state.documents.size)
                    } else {
                        str(S.desktop_budget_versions_older, state.documents.size)
                    },
                    style = ZillitTheme.typography.labelSmall,
                    color = colors.textMuted,
                    maxLines = 1,
                )
            }
            if (state.otherVersionsUnread > 0) ZillitBadge(count = state.otherVersionsUnread)
            ZillitIcon(
                if (state.versionMenuOpen) ZillitIcons.ChevronUp else ZillitIcons.ChevronDown,
                tint = colors.textSecondary,
                size = 16.dp,
            )
        }
        ZillitMenuSurface(
            expanded = state.versionMenuOpen,
            onDismissRequest = { onEvent(BudgetEvent.VersionMenu(false)) },
            offset = DpOffset(0.dp, ZillitTheme.spacing.xs),
        ) {
            Column(Modifier.width(PICKER_WIDTH).padding(ZillitTheme.spacing.sm)) {
                ZillitSearchField(
                    value = state.versionSearch,
                    onValueChange = { onEvent(BudgetEvent.VersionSearch(it)) },
                    placeholder = str(S.desktop_search_by_uploader),
                    modifier = Modifier.fillMaxWidth(),
                )
                val rows = state.versionsMatching(seams.nameOf)
                // A plain column in a scroll box: a lazy list inside a
                // DropdownMenu measures against infinite height and crashes.
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = PICKER_MAX_HEIGHT)
                        .verticalScroll(rememberScrollState())
                        .padding(top = ZillitTheme.spacing.xs),
                ) {
                    if (rows.isEmpty()) {
                        ZillitText(
                            text = str(S.desktop_budget_no_version_by_uploader),
                            style = ZillitTheme.typography.bodySmall,
                            color = colors.textMuted,
                            modifier = Modifier.padding(ZillitTheme.spacing.sm),
                        )
                    }
                    rows.forEach { row -> VersionRow(row, row.document.id == selected?.id, seams.nameOf, onEvent) }
                }
            }
        }
    }
}

@Composable
private fun VersionRow(
    row: BudgetDirectoryVersion,
    isOpen: Boolean,
    nameOf: (String) -> String?,
    onEvent: (BudgetEvent) -> Unit,
) {
    val colors = ZillitTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Column(
        Modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.medium)
            .background(
                when {
                    isOpen -> colors.surfaceSelected
                    hovered -> colors.surfaceHover
                    else -> colors.surfaceRaised
                },
            )
            .hoverable(interaction)
            .clickable { onEvent(BudgetEvent.SelectVersion(row.document.id)) }
            .padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.sm),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            ZillitText(
                text = row.document.displayTitle(nameOf),
                style = ZillitTheme.typography.titleSmall,
                color = colors.textPrimary,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            if (row.unread > 0) ZillitBadge(count = row.unread)
            if (isOpen) ZillitIcon(ZillitIcons.Check, tint = colors.accentText, size = 14.dp)
        }
        ZillitText(
            text = str(S.desktop_budget_uploaded_by, row.document.uploaderLabel(nameOf)),
            style = ZillitTheme.typography.labelSmall,
            color = colors.textSecondary,
            maxLines = 1,
        )
        ZillitText(
            text = str(S.desktop_budget_uploaded_on, uploadedOnLabel(row.document.createdMillis)),
            style = ZillitTheme.typography.labelSmall,
            color = colors.textMuted,
            maxLines = 1,
        )
    }
}

// -- the document card ----------------------------------------------------------------

/**
 * The open version's file (`CommonBudget.jsx:1948-2057`): its icon and name,
 * and the "More" menu — View, Download, and for an admin the two counts. A
 * spreadsheet gets a plain Download instead, as on the web.
 */
@Composable
@Suppress("LongMethod") // One dialog, drawn in one place.
private fun DocumentCard(state: BudgetUiState, document: BudgetDocument, onEvent: (BudgetEvent) -> Unit) {
    val colors = ZillitTheme.colors
    val file = document.file
    Row(
        Modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.large)
            .background(colors.surfaceSunken)
            .padding(ZillitTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        ZillitFileBadge(fileName = file?.name ?: "budget.pdf")
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
            ZillitText(
                text = file?.name ?: str(S.desktop_budget_no_file_on_version),
                style = ZillitTheme.typography.bodyMedium,
                color = colors.textPrimary,
                maxLines = 1,
            )
            val meta = listOfNotNull(
                file?.sizeBytes?.takeIf { it > 0 }?.let(::readableSize),
                document.episode.takeIf { it.isNotBlank() }?.let { str(S.desktop_episode_numbered, it) },
            ).joinToString(" · ")
            if (meta.isNotBlank()) {
                ZillitText(
                    text = meta,
                    style = ZillitTheme.typography.labelSmall,
                    color = colors.textMuted,
                    maxLines = 1,
                )
            }
        }
        if (file?.isSpreadsheet == true) {
            ZillitButton(
                text = str(S.download),
                onClick = { onEvent(BudgetEvent.DownloadFile) },
                variant = ButtonVariant.Secondary,
                leadingIcon = ZillitIcons.Download,
            )
        } else {
            Box {
                ZillitButton(
                    text = str(S.more),
                    onClick = { onEvent(BudgetEvent.MoreMenu(true)) },
                    variant = ButtonVariant.Secondary,
                    leadingIcon = ZillitIcons.Paperclip,
                    enabled = file?.isPresent == true,
                )
                ZillitActionMenu(
                    expanded = state.moreMenuOpen,
                    onDismissRequest = { onEvent(BudgetEvent.MoreMenu(false)) },
                    entries = buildList {
                        add(ZillitMenuEntry.Action(str(S.view), ZillitIcons.Eye) { onEvent(BudgetEvent.ViewFile) })
                        add(
                            ZillitMenuEntry.Action(str(S.download), ZillitIcons.Download) {
                                onEvent(BudgetEvent.DownloadFile)
                            },
                        )
                        if (state.viewer.isAdmin) {
                            add(ZillitMenuEntry.Divider)
                            add(
                                ZillitMenuEntry.Action(str(S.download_count), ZillitIcons.BarChart) {
                                    onEvent(BudgetEvent.ShowActivity(BudgetActivity.Download))
                                },
                            )
                            add(
                                ZillitMenuEntry.Action(str(S.view_count), ZillitIcons.Users) {
                                    onEvent(BudgetEvent.ShowActivity(BudgetActivity.View))
                                },
                            )
                        }
                    },
                )
            }
        }
    }
}

// -- the conversation list ---------------------------------------------------------------

/** `ChatUserAndGroupList.jsx`: a card per person or room, with its unread. */
@Composable
@Suppress("CyclomaticComplexMethod") // One dialog, drawn in one place.
private fun ChatList(
    state: BudgetUiState,
    onEvent: (BudgetEvent) -> Unit,
    seams: BudgetScreenSeams,
    modifier: Modifier = Modifier,
) {
    val document = state.selected ?: return
    when {
        state.chatsLoading && state.chats.isEmpty() -> Box(modifier) { LoadingRows() }
        state.chats.isEmpty() -> Box(modifier) {
            RailEmpty(
                title = str(S.empty_conversations),
                message = if (state.canChat) {
                    str(S.desktop_budget_no_conversations_hint)
                } else {
                    str(S.desktop_budget_conversations_latest)
                },
                icon = ZillitIcons.Users,
            )
        }

        else -> ZillitScrollColumn(
            modifier = modifier.fillMaxWidth(),
            contentPadding = PaddingValues(
                start = ZillitTheme.spacing.lg,
                end = ZillitTheme.spacing.lg,
                bottom = CHAT_LIST_CLEARANCE,
            ),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        ) {
            state.chats.forEach { entry ->
                val name = when (entry) {
                    is BudgetChatEntry.Person -> seams.nameOf(entry.userId) ?: entry.name.ifBlank { str(S.crew_member) }
                    is BudgetChatEntry.Group -> entry.name
                }
                RailRowCard(
                    title = name,
                    titleSuffix = (entry as? BudgetChatEntry.Person)?.takeIf { it.isAdmin }?.let { "(Admin)" },
                    subtitle = when (entry) {
                        is BudgetChatEntry.Person -> if (entry.hasLeft) str(S.disconnected) else entry.designation
                        is BudgetChatEntry.Group -> entry.memberIds.size.let { "$it member" + if (it == 1) "" else "s" }
                    },
                    leading = {
                        when (entry) {
                            is BudgetChatEntry.Person -> RailFace(name, entry.userId, seams.loadAvatar)
                            is BudgetChatEntry.Group -> Box(
                                Modifier.size(40.dp).clip(CircleShape)
                                    .background(ZillitTheme.colors.accentSoft),
                                contentAlignment = Alignment.Center,
                            ) { ZillitIcon(ZillitIcons.Users, tint = ZillitTheme.colors.accentText, size = 18.dp) }
                        }
                    },
                    badge = state.unread.ofChat(document.id, entry.key),
                    selected = state.selectedChat?.key == entry.key,
                    trailingChevron = false,
                    onClick = { onEvent(BudgetEvent.OpenChat(entry)) },
                )
            }
        }
    }
}

/**
 * The chat bubble's menu (`CommonBudget.jsx:2129-2172`): the two ways to
 * start a conversation on the latest version, or the sentence saying why
 * not on an older one.
 */
@Composable
internal fun ChatActionsMenu(state: BudgetUiState, onEvent: (BudgetEvent) -> Unit) {
    ZillitMenuSurface(
        expanded = state.chatMenuOpen,
        onDismissRequest = { onEvent(BudgetEvent.ChatMenu(false)) },
        offset = DpOffset(0.dp, -(MENU_LIFT)),
    ) {
        if (state.canChat) {
            ZillitMenuEntries(
                entries = listOf(
                    ZillitMenuEntry.Action(str(S.cs_add_member), ZillitIcons.UserPlus) {
                        onEvent(BudgetEvent.ShowMembers(BudgetMembersDialog.Kind.Member))
                    },
                    ZillitMenuEntry.Action(str(S.create_group), ZillitIcons.Users) {
                        onEvent(BudgetEvent.ShowMembers(BudgetMembersDialog.Kind.Group))
                    },
                ),
                onDismiss = { onEvent(BudgetEvent.ChatMenu(false)) },
            )
        } else {
            ZillitText(
                text = if (state.hasDocuments) {
                    str(S.desktop_budget_cannot_chat_previous)
                } else {
                    str(S.desktop_budget_upload_to_chat)
                },
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textSecondary,
                modifier = Modifier.width(HINT_WIDTH).padding(ZillitTheme.spacing.md),
            )
        }
    }
}

// -- labels -----------------------------------------------------------------------------

/** The title the web shows — or, for a row with none, "Uploaded by - name". */
internal fun BudgetDocument.displayTitle(nameOf: (String) -> String?): String =
    title.ifBlank { str(S.desktop_budget_uploaded_by, uploaderLabel(nameOf)) }

internal fun BudgetDocument.uploaderLabel(nameOf: (String) -> String?): String =
    nameOf(uploadedById) ?: uploadedByName.ifBlank { str(S.desktop_unknown) }

/** "Sep 15, 2026, 06:44 PM" — `toLocaleDateString('en-US', {…hour12})`. */
internal fun uploadedOnLabel(epochMillis: Long, zone: TimeZone = TimeZone.currentSystemDefault()): String {
    if (epochMillis <= 0) return "—"
    val time = Instant.fromEpochMilliseconds(epochMillis).toLocalDateTime(zone)
    val month = time.month.name.take(MONTH_ABBREVIATION).lowercase().replaceFirstChar { it.titlecase() }
    val day = time.day.toString().padStart(2, '0')
    val hour12 = when (val h = time.hour % HOURS_ON_CLOCK) {
        0 -> HOURS_ON_CLOCK
        else -> h
    }
    val minute = time.minute.toString().padStart(2, '0')
    val meridiem = if (time.hour < HOURS_ON_CLOCK) "AM" else "PM"
    return "$month $day, ${time.year}, ${hour12.toString().padStart(2, '0')}:$minute $meridiem"
}

/** "1.2 MB" — the same shape the drive's size column uses. */
internal fun readableSize(bytes: Long): String = when {
    bytes < BYTES_PER_KB -> "$bytes B"
    bytes < BYTES_PER_KB * BYTES_PER_KB -> str(S.desktop_size_kb, round1(bytes / BYTES_PER_KB))
    else -> str(S.desktop_size_mb, round1(bytes / (BYTES_PER_KB * BYTES_PER_KB)))
}

private fun round1(value: Double): Double = (value * TENTHS).toInt() / TENTHS

private const val BYTES_PER_KB = 1024.0
private const val TENTHS = 10.0
private const val MONTH_ABBREVIATION = 3
private const val HOURS_ON_CLOCK = 12
private val PICKER_WIDTH = 332.dp
private val PICKER_MAX_HEIGHT = 360.dp
private val HINT_WIDTH = 220.dp
private val MENU_LIFT = 8.dp
private val CHAT_LIST_CLEARANCE = 88.dp
