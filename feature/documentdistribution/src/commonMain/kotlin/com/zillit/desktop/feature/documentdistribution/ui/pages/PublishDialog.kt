package com.zillit.desktop.feature.documentdistribution.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.common.EpochDate
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitCheckbox
import com.zillit.desktop.core.designsystem.component.ZillitDateField
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitEmptyState
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitScrollColumn
import com.zillit.desktop.core.designsystem.component.ZillitSelect
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.documentdistribution.domain.LibraryDocument
import com.zillit.desktop.feature.documentdistribution.domain.LibraryFolder
import com.zillit.desktop.feature.documentdistribution.domain.PublishAccess
import com.zillit.desktop.feature.documentdistribution.domain.PublishCard
import com.zillit.desktop.feature.documentdistribution.domain.PublishMode
import com.zillit.desktop.feature.documentdistribution.domain.PublishTarget
import com.zillit.desktop.feature.documentdistribution.domain.PublishedFile
import com.zillit.desktop.feature.documentdistribution.domain.ScheduleTypeChoice
import com.zillit.desktop.feature.documentdistribution.domain.formatBytes
import com.zillit.desktop.feature.documentdistribution.ui.DocDistEvent
import com.zillit.desktop.feature.documentdistribution.ui.DocDistUiState
import com.zillit.desktop.feature.documentdistribution.ui.PublishState
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn

/**
 * Publishing documents *into* another tool rather than emailing them out — a
 * call sheet onto the unit's chat, a schedule onto the schedule tool.
 *
 * ## Two columns, because the choice drives everything else
 *
 * The web's `PublishModal` is a split: the destination tiles stay in view down
 * the left while the right-hand column fills with whatever that destination
 * asks for. Each side scrolls on its own so the Publish button can never be
 * pushed off the bottom, and the tiles are the one thing always on screen —
 * every field, every rule and every refusal on the right is a consequence of
 * which tile is lit.
 *
 * ## Every destination is offered, including the ones you cannot post to
 *
 * Filtering the rail to what the viewer may send to is what this dialog did
 * first, and it is the failure the phones reported: somebody who cannot find
 * Production Report has no way to learn whether they lack a right or the
 * production never enabled it. A tile they cannot use is tagged "View only"
 * or "No access" and offers the ask instead (`PublishModal` →
 * `visibleCategories`, and [com.zillit.desktop.core.permissions.gatedClick]).
 */
@Composable
fun PublishDialog(state: DocDistUiState, onEvent: (DocDistEvent) -> Unit) {
    val publish = state.publish ?: return
    ZillitDialogShell(
        title = str(
            if (publish.folder != null) S.desktop_docdist_publish_folder else S.dd_publish_documents,
        ),
        subtitle = publish.folder?.let { folderPath(state, it) },
        visible = true,
        onDismiss = { onEvent(DocDistEvent.ClosePublish) },
        icon = ZillitIcons.Send,
        width = DIALOG_WIDTH.dp,
        scrollable = false,
        actions = { PublishFooter(state, publish, onEvent) },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().height(BODY_HEIGHT.dp),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg),
        ) {
            DestinationRail(state, publish, onEvent)
            FieldsColumn(state, publish, onEvent)
        }
    }
}

// -- the left rail ------------------------------------------------------------

@Composable
private fun androidx.compose.foundation.layout.RowScope.DestinationRail(
    state: DocDistUiState,
    publish: PublishState,
    onEvent: (DocDistEvent) -> Unit,
) {
    ZillitScrollColumn(
        modifier = Modifier.width(RAIL_WIDTH.dp).fillMaxHeight(),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        FieldLabel(str(S.dd_publish_destination_heading))
        PublishCard.all.forEach { card ->
            DestinationTile(card, state, publish, onEvent)
        }
        AccessNotice(state, publish, onEvent)
    }
}

/**
 * One tile.
 *
 * A tool with several destinations opens a menu rather than selecting — the
 * backend takes one category, and "Schedule Full & One Line" is three of them.
 * A tool with a single destination selects directly: a one-entry menu is a
 * worse version of a button.
 */
@Composable
private fun DestinationTile(
    card: PublishCard,
    state: DocDistUiState,
    publish: PublishState,
    onEvent: (DocDistEvent) -> Unit,
) {
    val access = state.viewer.accessFor(card.category)
    val active = card.holds(publish.target?.category)
    val chosen = card.options.firstOrNull { it.category == publish.target?.category }
    Box {
        TileSurface(
            accent = accentFor(card.key),
            active = active,
            onClick = {
                if (card.single) {
                    onEvent(DocDistEvent.ChoosePublishTarget(card.options.first().category))
                } else {
                    onEvent(DocDistEvent.OpenPublishCard(card.key))
                }
            },
        ) {
            TileIcon(card.key)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
            ) {
                TileTitle(card, access)
                // A menu tile keeps this row for whichever destination was
                // picked; with nothing picked there is nothing to say.
                val sub = if (card.single) card.description else chosen?.label ?: card.description
                sub?.let {
                    ZillitText(
                        text = it,
                        style = ZillitTheme.typography.bodySmall,
                        color = ZillitTheme.colors.textSecondary,
                        maxLines = 2,
                    )
                }
            }
            if (active) {
                ZillitIcon(icon = ZillitIcons.Check, tint = ZillitTheme.colors.success, size = TICK.dp)
            }
        }
        if (!card.single) {
            MenuPopup(
                expanded = publish.openCard == card.key,
                onDismiss = { onEvent(DocDistEvent.OpenPublishCard(null)) },
                entries = card.options.map { option ->
                    MenuEntry(
                        label = option.label,
                        onClick = { onEvent(DocDistEvent.ChoosePublishTarget(option.category)) },
                        icon = if (option.category == publish.target?.category) ZillitIcons.Check else null,
                    )
                },
            )
        }
    }
}

@Composable
private fun TileTitle(card: PublishCard, access: PublishAccess) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        ZillitText(
            text = card.label,
            style = ZillitTheme.typography.label.copy(fontWeight = FontWeight.SemiBold),
            color = ZillitTheme.colors.textPrimary,
            maxLines = 1,
        )
        if (!card.single) {
            ZillitIcon(
                icon = ZillitIcons.ChevronDown,
                tint = ZillitTheme.colors.textMuted,
                size = CHEVRON.dp,
            )
        }
        if (!access.canPost) {
            ZillitStatusPill(
                label = str(
                    if (access.visible) S.dd_publish_view_only_badge else S.dd_publish_no_access_badge,
                ),
                tone = StatusTone.Pending,
            )
        }
    }
}

/** The tile's own surface: a tinted well that lights up with its accent when chosen. */
@Composable
private fun TileSurface(
    accent: Color,
    active: Boolean,
    onClick: () -> Unit,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    val c = ZillitTheme.colors
    HoverRow(
        selected = active,
        onClick = onClick,
        modifier = Modifier
            .clip(ZillitTheme.shapes.medium)
            .background(if (active) accent.copy(alpha = TILE_WASH) else c.surfaceSunken)
            .border(
                width = if (active) ACTIVE_EDGE.dp else HAIRLINE.dp,
                color = if (active) accent else c.border,
                shape = ZillitTheme.shapes.medium,
            ),
    ) { _ -> content() }
}

@Composable
private fun TileIcon(key: String) {
    val accent = accentFor(key)
    Box(
        modifier = Modifier
            .size(TILE_ICON.dp)
            .clip(ZillitTheme.shapes.medium)
            .background(accent.copy(alpha = ICON_WASH)),
        contentAlignment = Alignment.Center,
    ) {
        ZillitIcon(icon = iconFor(key), tint = accent, size = (TILE_ICON / 2).dp)
    }
}

/**
 * What the rail says when the chosen destination is not this person's to post
 * to — and what, if anything, they can do about it.
 *
 * A module the production never enabled offers no button: no grant turns it
 * on, and a Request that cannot help is worse than a sentence that explains.
 */
@Composable
private fun AccessNotice(state: DocDistUiState, publish: PublishState, onEvent: (DocDistEvent) -> Unit) {
    val target = publish.target ?: return
    val access = state.viewer.accessFor(target.category)
    if (access.canPost) return
    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
        ZillitText(
            text = str(
                if (access.enabled) S.dd_publish_no_posting_rights_msg else S.dd_publish_tool_not_enabled,
                target.label,
            ),
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.danger,
        )
        if (access.enabled) {
            ZillitButton(
                text = str(S.dd_publish_request_posting_rights),
                onClick = { onEvent(DocDistEvent.RequestPublishRights) },
                size = ButtonSize.Small,
            )
        }
    }
}

// -- the right-hand column ----------------------------------------------------

@Composable
private fun androidx.compose.foundation.layout.RowScope.FieldsColumn(
    state: DocDistUiState,
    publish: PublishState,
    onEvent: (DocDistEvent) -> Unit,
) {
    val target = publish.target
    if (target == null) {
        Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
            ZillitEmptyState(
                title = str(S.dd_publish_pick_destination),
                icon = ZillitIcons.Send,
            )
        }
        return
    }
    ZillitScrollColumn(
        modifier = Modifier.weight(1f).fillMaxHeight(),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        TargetFields(target, publish, state.viewer.isTelevision, onEvent)
        when {
            publish.loadingPublished -> LoadingPublished()
            publish.offersMode -> RepublishOptions(publish, onEvent)
            else -> FilesToPublish(publish, onEvent)
        }
    }
}

/** The fields this destination asks for, in the web's order: episode first. */
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

    if (target.needsEpisodeOnTelevision && isTelevision) {
        ZillitTextField(
            value = draft.episode,
            onValueChange = { edit(draft.copy(episode = it.take(SHORT_FIELD_MAX))) },
            label = str(S.dd_publish_episode_hint),
            placeholder = "101",
        )
    }
    if (target.needsName) {
        ZillitTextField(
            value = draft.name,
            onValueChange = { edit(draft.copy(name = it.take(LONG_FIELD_MAX))) },
            label = str(S.dd_publish_name_hint),
            placeholder = str(S.desktop_day_out_of_days),
        )
    }
    if (target.needsScene) {
        ZillitTextField(
            value = draft.sceneNumber,
            onValueChange = { edit(draft.copy(sceneNumber = it.take(SHORT_FIELD_MAX))) },
            label = str(S.dd_publish_scene_number_hint),
            placeholder = "12A",
        )
    }
    if (target.needsScheduleType) SchedulePicker(draft.scheduleType) { edit(draft.copy(scheduleType = it)) }
    if (target.needsScheduleDate) {
        // Optional on the web, and sent only when picked — the endpoint fills
        // its own default, so an unasked-for date is a decision nobody made.
        ZillitDateField(
            value = draft.scheduleDate?.let(EpochDate::isoDate).orEmpty(),
            onValueChange = { iso -> edit(draft.copy(scheduleDate = epochFromIso(iso))) },
            label = str(S.dd_publish_schedule_date_label),
            placeholder = str(S.dd_publish_schedule_date_pick),
        )
    }
    if (target.takesNote) {
        ZillitTextField(
            value = draft.note,
            onValueChange = { edit(draft.copy(note = it.take(NOTE_MAX))) },
            // One catalogue, reused by text: "Note (optional)" is already
            // translated under E-Signature's key — a second key for the same
            // words would render in English under all 22 languages.
            label = str(S.docusign_sidebar_note_label),
            placeholder = str(S.dd_note_hint),
        )
    }
}

/**
 * "Which schedule does this page belong to?" — the live tool's own radio,
 * which the `/schedule-distribution/page` endpoint reads as `schedule_type`.
 */
@Composable
private fun ColumnScope.SchedulePicker(chosen: String, onPick: (String) -> Unit) {
    // The catalogue string already ends in its own asterisk.
    FieldLabel(str(S.dd_publish_schedule_type_hint))
    Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
        ScheduleTypeChoice.entries.forEach { choice ->
            ZillitButton(
                text = choice.label,
                onClick = { onPick(choice.wire) },
                variant = if (chosen == choice.wire) ButtonVariant.Secondary else ButtonVariant.Tertiary,
                size = ButtonSize.Small,
            )
        }
    }
}

/**
 * Whether there are options at all is not known until the read lands, so the
 * slot is held rather than letting the block appear under the cursor a beat
 * later — and the file picker it owns move with it.
 */
@Composable
private fun ColumnScope.LoadingPublished() {
    FieldLabel(str(S.dd_publish_republish_label))
    Row(
        modifier = Modifier.fillMaxWidth().height(SKELETON_HEIGHT.dp),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitSpinner()
        ZillitText(
            text = str(S.dd_publish_loading_published),
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textSecondary,
        )
    }
}

/**
 * Addition / Replace / Publish as a New File (ZL-20245).
 *
 * Each option is its own bordered card holding everything that option needs —
 * the replace target's menu, and the file picker — so the fields on screen
 * visibly belong to the choice above them rather than floating after the
 * group. That is also why the picker lives here rather than at the foot of
 * the column: with options on screen it is *that option's* input.
 */
@Composable
private fun ColumnScope.RepublishOptions(publish: PublishState, onEvent: (DocDistEvent) -> Unit) {
    val module = publish.target?.label.orEmpty()
    RequiredLabel(str(S.dd_publish_republish_label))
    ZillitText(
        text = str(S.dd_publish_republish_hint),
        style = ZillitTheme.typography.bodySmall,
        color = ZillitTheme.colors.textSecondary,
    )
    PublishMode.entries.forEach { mode ->
        ModeCard(mode, module, publish, onEvent)
    }
}

@Composable
private fun ModeCard(
    mode: PublishMode,
    module: String,
    publish: PublishState,
    onEvent: (DocDistEvent) -> Unit,
) {
    val c = ZillitTheme.colors
    val chosen = publish.draft.mode == mode
    // A replacement is one live file for one new one, so the option is only
    // takeable with exactly one ticked. Said out loud beneath, never silently.
    val takeable = mode != PublishMode.Replace || publish.draft.documentIds.size == 1
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.medium)
            .background(if (chosen) c.accentSoft else c.surface)
            .border(
                width = if (chosen) ACTIVE_EDGE.dp else HAIRLINE.dp,
                color = if (chosen) c.accent else c.border,
                shape = ZillitTheme.shapes.medium,
            )
            .padding(ZillitTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        DocRadio(
            selected = chosen,
            onSelect = { onEvent(DocDistEvent.SetPublishMode(mode)) },
            label = mode.label(module),
            enabled = takeable,
        )
        ZillitText(
            text = mode.hint(module),
            style = ZillitTheme.typography.bodySmall,
            color = c.textSecondary,
        )
        if (!takeable) {
            ZillitText(
                text = str(S.dd_publish_replace_single_hint),
                style = ZillitTheme.typography.bodySmall,
                color = c.warning,
            )
        }
        if (!chosen) return@Column
        if (mode == PublishMode.Replace) ReplaceTargetPicker(publish, onEvent)
        FilesToPublish(publish, onEvent)
    }
}

/**
 * Which live file this publish stands in for.
 *
 * Names repeat — every call sheet is called "Call Sheet" — so the stamp
 * disambiguates, and the value carried is always the chat id, never the name.
 */
@Composable
private fun ReplaceTargetPicker(publish: PublishState, onEvent: (DocDistEvent) -> Unit) {
    val chosen = publish.alreadyPublished.firstOrNull { it.chatId == publish.draft.replaceChatId }
    RequiredLabel(str(S.dd_publish_replace_target))
    ZillitSelect(
        value = chosen,
        options = publish.alreadyPublished,
        onSelect = { onEvent(DocDistEvent.ChooseReplaceTarget(it?.chatId)) },
        label = { it?.let(::publishedLabel) ?: str(S.dd_publish_replace_target_placeholder) },
        modifier = Modifier.fillMaxWidth(),
        showInitials = false,
    )
}

private fun publishedLabel(file: PublishedFile): String =
    file.publishedAt?.let { "${file.name} · ${EpochDate.dateTime(it)}" } ?: file.name

// -- files to publish ---------------------------------------------------------

/**
 * The rows going out, and which of them are ticked.
 *
 * It has two homes, one definition: under whichever republish option is
 * selected when there are options, and at the foot of the column when there
 * are not.
 */
@Composable
private fun ColumnScope.FilesToPublish(publish: PublishState, onEvent: (DocDistEvent) -> Unit) {
    val target = publish.target ?: return
    val c = ZillitTheme.colors
    val selectable = publish.selectable
    val allTicked = selectable.isNotEmpty() && publish.draft.documentIds.size == selectable.size
    FieldLabel(
        text = str(S.dd_files_to_publish),
        required = true,
        count = publish.draft.documentIds.size,
        trailing = {
            if (publish.files.isNotEmpty() && !target.singleFile) {
                ZillitButton(
                    text = str(if (allTicked) S.dd_deselect_all else S.dd_select_all),
                    onClick = { onEvent(DocDistEvent.ToggleAllPublishFiles) },
                    variant = ButtonVariant.Tertiary,
                    size = ButtonSize.Small,
                    enabled = selectable.isNotEmpty(),
                )
            }
        },
    )
    if (target.pdfOnly) {
        RuleNote(str(S.dd_publish_pdf_only_note, target.label))
    }
    if (target.singleFile) {
        RuleNote(str(S.dd_publish_single_file_note, target.label))
    }
    if (publish.files.isEmpty()) {
        ZillitText(
            text = str(S.desktop_docdist_no_files_to_publish),
            style = ZillitTheme.typography.bodySmall,
            color = c.textMuted,
        )
        return
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.medium)
            .border(HAIRLINE.dp, c.border, ZillitTheme.shapes.medium)
            .padding(ZillitTheme.spacing.xxs),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
    ) {
        publish.files.forEach { file -> FileRow(file, publish, onEvent) }
    }
}

@Composable
private fun FileRow(file: LibraryDocument, publish: PublishState, onEvent: (DocDistEvent) -> Unit) {
    val c = ZillitTheme.colors
    val ticked = file.id in publish.draft.documentIds
    val blocked = !publish.canTick(file)
    // Republish-gated destinations take an optional display name per file; the
    // server appends the original extension, so nothing here touches one.
    val named = publish.target?.republishable == true && ticked
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.medium)
            .background(if (ticked) c.surfaceSelected else Color.Transparent),
    ) {
        HoverRow(onClick = if (blocked) null else { { onEvent(DocDistEvent.TogglePublishFile(file.id)) } }) { _ ->
            ZillitCheckbox(
                checked = ticked,
                onCheckedChange = { onEvent(DocDistEvent.TogglePublishFile(file.id)) },
                enabled = !blocked,
            )
            FileGlyph(file, size = FILE_GLYPH.dp)
            Column(Modifier.weight(1f)) {
                ZillitText(
                    text = file.name,
                    style = ZillitTheme.typography.label,
                    color = if (blocked) c.textDisabled else c.textPrimary,
                    maxLines = 1,
                )
                FileRowMeta(file, blocked)
            }
        }
        if (!named) return@Column
        ZillitTextField(
            value = publish.draft.displayNames[file.id].orEmpty(),
            onValueChange = { onEvent(DocDistEvent.SetPublishDisplayName(file.id, it)) },
            placeholder = str(S.dd_publish_display_name_placeholder),
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = DISPLAY_NAME_INSET.dp,
                    end = ZillitTheme.spacing.md,
                    bottom = ZillitTheme.spacing.xs,
                ),
        )
    }
}

/** Size, and the two stamps that explain a row: refused here, or sent before. */
@Composable
private fun FileRowMeta(file: LibraryDocument, blocked: Boolean) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        ZillitText(
            text = formatBytes(file.sizeBytes),
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textMuted,
        )
        if (blocked) ZillitStatusPill(label = str(S.dd_publish_pdf_only), tone = StatusTone.Neutral)
        if (file.publishedCategory != null) {
            ZillitStatusPill(label = str(S.dd_previously_published), tone = StatusTone.Pending)
        }
    }
}

// -- footer -------------------------------------------------------------------

@Composable
private fun androidx.compose.foundation.layout.RowScope.PublishFooter(
    state: DocDistUiState,
    publish: PublishState,
    onEvent: (DocDistEvent) -> Unit,
) {
    val c = ZillitTheme.colors
    val canPost = publish.target?.let { state.viewer.accessFor(it.category).canPost } ?: true
    val problem = publish.problem(state.viewer.isTelevision)
    FlowRow(
        modifier = Modifier.weight(1f),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
    ) {
        ZillitText(
            text = str(
                S.desktop_docdist_n_of_m_files_selected,
                publish.draft.documentIds.size,
                publish.files.size,
            ),
            style = ZillitTheme.typography.bodySmall,
            color = c.textSecondary,
        )
        publish.target?.let {
            ZillitStatusPill(label = it.label, tone = StatusTone.Pending)
        }
        // The web leaves this to a toast on the press; saying it here instead
        // means nobody hunts for the field a greyed-out button is waiting on.
        if (problem != null && publish.target != null && canPost) {
            ZillitText(
                text = problem,
                style = ZillitTheme.typography.bodySmall,
                color = c.textSecondary,
            )
        }
    }
    ZillitButton(
        text = str(S.cancel),
        onClick = { onEvent(DocDistEvent.ClosePublish) },
        variant = ButtonVariant.Tertiary,
    )
    ZillitButton(
        text = str(S.dd_publish_action),
        onClick = { onEvent(DocDistEvent.ConfirmPublish) },
        loading = publish.saving,
        // Kept pressable without the right: the press answers "ask an admin",
        // which is the one thing that helps. Only a real gap in the form
        // disables it.
        enabled = !publish.saving && (problem == null || !canPost),
        leadingIcon = ZillitIcons.Send,
    )
}

// -- shared bits --------------------------------------------------------------

/** A field label marked required, the web's red asterisk and all. */
@Composable
private fun RequiredLabel(text: String) = FieldLabel(text = text, required = true)

@Composable
private fun RuleNote(text: String) {
    ZillitText(
        text = text,
        style = ZillitTheme.typography.bodySmall,
        color = ZillitTheme.colors.textSecondary,
    )
}

/**
 * The tile palette, by card key.
 *
 * The web gives each destination its own hue so the rail reads as seven
 * places rather than seven rows; these are the theme's file hues, which are
 * already pitched to stay legible on both the pale and the dark well.
 */
@Composable
private fun accentFor(key: String): Color {
    val files = ZillitTheme.colors.files
    return when (key) {
        PublishTarget.CallSheet.category -> files.blue
        PublishTarget.ProductionReport.category -> files.purple
        PublishTarget.Info.category -> files.teal
        PublishTarget.ConfidentialInfo.category -> files.red
        PublishTarget.Dod.category -> files.indigo
        PublishTarget.ScheduleFull.category -> files.green
        PublishTarget.Script.category -> files.pink
        else -> files.neutral
    }
}

private fun iconFor(key: String) = when (key) {
    PublishTarget.CallSheet.category -> ZillitIcons.Ledger
    PublishTarget.ProductionReport.category -> ZillitIcons.File
    PublishTarget.Info.category -> ZillitIcons.Info
    PublishTarget.ConfidentialInfo.category -> ZillitIcons.Lock
    PublishTarget.Dod.category -> ZillitIcons.Calendar
    PublishTarget.ScheduleFull.category -> ZillitIcons.Clock
    PublishTarget.Script.category -> ZillitIcons.Draft
    else -> ZillitIcons.Send
}

/**
 * The folder's path, built from its ancestors — the web's `folder.path`.
 *
 * Shown under the title so a publish from "Day 12/Call Sheets" says which
 * subtree it is about, not just the leaf's name, which repeats across days.
 */
private fun folderPath(state: DocDistUiState, folder: LibraryFolder): String {
    val names = ArrayDeque<String>()
    var current: LibraryFolder? = folder
    // Bounded by the folder count: a cycle in the parent chain would otherwise
    // spin here rather than showing a wrong subtitle.
    repeat(state.folders.size + 1) {
        val step = current ?: return@repeat
        names.addFirst(step.name)
        current = step.parentId?.let { id -> state.folders.firstOrNull { it.id == id } }
    }
    return names.joinToString("/", prefix = "/")
}

/** `YYYY-MM-DD` from the date field to the epoch millis `schedule_date` takes. */
private fun epochFromIso(iso: String): Long? = runCatching {
    LocalDate.parse(iso.trim()).atStartOfDayIn(TimeZone.currentSystemDefault()).toEpochMilliseconds()
}.getOrNull()

private const val DIALOG_WIDTH = 1000
private const val BODY_HEIGHT = 520
private const val RAIL_WIDTH = 360
private const val TILE_ICON = 36
private const val FILE_GLYPH = 30
private const val TICK = 16
private const val CHEVRON = 12
private const val SKELETON_HEIGHT = 96
private const val DISPLAY_NAME_INSET = 76
private const val NOTE_MAX = 140
private const val SHORT_FIELD_MAX = 40
private const val LONG_FIELD_MAX = 120
private const val TILE_WASH = 0.12f
private const val ICON_WASH = 0.16f
private const val ACTIVE_EDGE = 1.5
private const val HAIRLINE = 1
