package com.zillit.desktop.feature.drive.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDivider
import com.zillit.desktop.core.designsystem.component.ZillitEmptyState
import com.zillit.desktop.core.designsystem.component.ZillitErrorState
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitScrollColumn
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitVerticalDivider
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.drive.domain.MarkdownBlock
import com.zillit.desktop.feature.drive.domain.MarkdownSpan
import com.zillit.desktop.feature.drive.domain.isPlainTextDocument
import com.zillit.desktop.feature.drive.domain.parseMarkdown
import com.zillit.desktop.feature.drive.ui.DriveEvent
import com.zillit.desktop.feature.drive.ui.TextDocState

/**
 * A `.md` or `.txt` file open in Drive's own viewer — `MarkdownDocument.jsx`.
 *
 * Rendered for anyone who may read it, with the source beside it for anyone
 * who may edit; `.txt` is shown exactly as written, with no rendered pane,
 * because Markdown in a plain-text file is not markup (REL-BUG-10). Saving is
 * manual — the button or Ctrl/Cmd+S — and makes a version, so the history
 * panel, the change list and Restore work on it as they do on any other file.
 *
 * It covers the Drive pane rather than floating over it: this is a place you
 * are in, with its own way back, not a dialog.
 */
@Composable
internal fun TextDocPane(doc: TextDocState, onEvent: (DriveEvent) -> Unit) {
    val colors = ZillitTheme.colors
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.canvas)
            // Ctrl/Cmd+S anywhere in the document saves, as it does in the
            // editors — people who have been typing do not reach for a button.
            .onPreviewKeyEvent { event ->
                val save = event.type == KeyEventType.KeyDown &&
                    event.key == Key.S &&
                    (event.isMetaPressed || event.isCtrlPressed)
                if (save && doc.isEditing && doc.dirty) {
                    onEvent(DriveEvent.SaveTextDoc())
                    true
                } else {
                    false
                }
            },
    ) {
        TextDocHeader(doc, onEvent)
        ZillitDivider()
        TextDocBars(doc, onEvent)
        Box(modifier = Modifier.fillMaxSize()) {
            when {
                doc.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = colors.accent)
                }

                doc.error != null -> ZillitErrorState(
                    message = doc.error,
                    onRetry = { onEvent(DriveEvent.ReloadTextDoc) },
                )

                else -> TextDocBody(doc, onEvent)
            }
        }
    }
}

/** Back, the name, whether it is saved, and the three controls. */
@Composable
private fun TextDocHeader(doc: TextDocState, onEvent: (DriveEvent) -> Unit) {
    val colors = ZillitTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surface)
            .padding(horizontal = ZillitTheme.spacing.lg, vertical = ZillitTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        ZillitButton(
            text = str(S.desktop_drive_back_to_drive),
            onClick = { onEvent(DriveEvent.CloseTextDoc) },
            variant = ButtonVariant.Tertiary,
            size = ButtonSize.Small,
            leadingIcon = ZillitIcons.ArrowLeft,
        )
        ZillitText(
            text = doc.item.name,
            style = ZillitTheme.typography.titleSmall,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        if (doc.canEdit && doc.versionView == null) {
            ZillitText(
                text = if (doc.dirty) str(S.cs_exit_title) else str(S.dm_notice_template_status_saved),
                style = ZillitTheme.typography.labelSmall,
                color = if (doc.dirty) colors.warning else colors.textMuted,
            )
        }
        if (doc.canEdit && doc.versionView == null) {
            ViewEditToggle(editing = doc.editing, onSelect = { onEvent(DriveEvent.TextDocEditing(it)) })
        }
        if (doc.isEditing) {
            ZillitButton(
                text = str(S.save),
                onClick = { onEvent(DriveEvent.SaveTextDoc()) },
                size = ButtonSize.Small,
                enabled = doc.dirty && !doc.saving,
                leadingIcon = ZillitIcons.Check,
            )
        }
        // Unsaved changes first: the history panel can restore over them.
        ZillitButton(
            text = str(S.desktop_drive_version_history),
            onClick = { onEvent(DriveEvent.OpenVersionHistory(doc.item)) },
            variant = ButtonVariant.Secondary,
            size = ButtonSize.Small,
            enabled = !doc.dirty,
            leadingIcon = ZillitIcons.Clock,
        )
    }
}

/** Preview / Edit, the web's `Segmented` — two glyphs in one sunken pill. */
@Composable
private fun ViewEditToggle(editing: Boolean, onSelect: (Boolean) -> Unit) {
    val colors = ZillitTheme.colors
    Row(
        modifier = Modifier
            .clip(ZillitTheme.shapes.small)
            .background(colors.surfaceHover)
            .padding(TOGGLE_INSET),
        horizontalArrangement = Arrangement.spacedBy(TOGGLE_INSET),
    ) {
        listOf(false, true).forEach { edit ->
            val active = edit == editing
            ZillitButton(
                text = if (edit) str(S.edit) else str(S.preview),
                onClick = { onSelect(edit) },
                variant = if (active) ButtonVariant.Primary else ButtonVariant.Tertiary,
                size = ButtonSize.Small,
                leadingIcon = if (edit) ZillitIcons.Edit else ZillitIcons.Eye,
            )
        }
    }
}

/** The discard question, the save conflict and the version being read. */
@Composable
private fun TextDocBars(doc: TextDocState, onEvent: (DriveEvent) -> Unit) {
    if (doc.confirmDiscard) DiscardBar(onEvent)
    doc.conflict?.let { ConflictBar(it, doc.saving, onEvent) }
    doc.versionView?.let { VersionBar(it.versionNumber, onEvent) }
}

/** Closing with unsaved changes asks once, here rather than in a dialog. */
@Composable
private fun DiscardBar(onEvent: (DriveEvent) -> Unit) {
    ZillitNotice(
        text = str(S.desktop_drive_text_discard_question),
        tone = StatusTone.Pending,
        icon = ZillitIcons.Info,
        modifier = Modifier.padding(ZillitTheme.spacing.md),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
            ZillitButton(
                text = str(S.ah_keep_editing),
                onClick = { onEvent(DriveEvent.KeepEditingTextDoc) },
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
            )
            ZillitButton(
                text = str(S.desktop_dm_discard_changes_lower),
                onClick = { onEvent(DriveEvent.CloseTextDoc) },
                variant = ButtonVariant.Danger,
                size = ButtonSize.Small,
            )
        }
    }
}

/**
 * Somebody saved while this was open. The server's own words, and both ways
 * out of it: take theirs, or keep mine.
 */
@Composable
private fun ConflictBar(message: String, saving: Boolean, onEvent: (DriveEvent) -> Unit) {
    ZillitNotice(
        text = message,
        tone = StatusTone.Rejected,
        icon = ZillitIcons.Info,
        modifier = Modifier.padding(ZillitTheme.spacing.md),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
            ZillitButton(
                text = str(S.desktop_drive_text_load_latest),
                onClick = { onEvent(DriveEvent.ReloadTextDoc) },
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
            )
            ZillitButton(
                text = str(S.desktop_drive_text_save_anyway),
                onClick = { onEvent(DriveEvent.SaveTextDoc(overwrite = true)) },
                variant = ButtonVariant.Danger,
                size = ButtonSize.Small,
                enabled = !saving,
            )
        }
    }
}

/** A stored version is being read, not the live file. */
@Composable
private fun VersionBar(versionNumber: Int, onEvent: (DriveEvent) -> Unit) {
    ZillitNotice(
        text = str(S.desktop_drive_text_viewing_version, versionNumber),
        tone = StatusTone.Progress,
        icon = ZillitIcons.Clock,
        modifier = Modifier.padding(ZillitTheme.spacing.md),
    ) {
        ZillitButton(
            text = str(S.desktop_drive_text_back_to_current),
            onClick = { onEvent(DriveEvent.CloseTextDocVersion) },
            variant = ButtonVariant.Secondary,
            size = ButtonSize.Small,
        )
    }
}

/**
 * The source, the rendering, or both side by side.
 *
 * Markdown being edited shows the source on the left and what it will look
 * like on the right, which is the whole point of editing Markdown; `.txt` has
 * nothing to render, so its editor takes the width.
 */
@Composable
private fun TextDocBody(doc: TextDocState, onEvent: (DriveEvent) -> Unit) {
    val plain = doc.item.isPlainTextDocument
    Row(modifier = Modifier.fillMaxSize()) {
        if (doc.isEditing) {
            Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                TextDocEditor(doc.text, plain) { onEvent(DriveEvent.TextDocDraft(it)) }
            }
        }
        if (!(plain && doc.isEditing)) {
            if (doc.isEditing) ZillitVerticalDivider()
            Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                if (doc.shownText.isBlank()) {
                    ZillitEmptyState(
                        title = str(S.desktop_drive_text_empty_title),
                        message = str(S.desktop_drive_text_empty_message),
                        icon = ZillitIcons.File,
                    )
                } else if (plain) {
                    PlainTextView(doc.shownText)
                } else {
                    MarkdownView(doc.shownText)
                }
            }
        }
    }
}

/** The source, in a monospaced field that fills its half. */
@Composable
private fun TextDocEditor(text: String, plain: Boolean, onChange: (String) -> Unit) {
    val colors = ZillitTheme.colors
    BasicTextField(
        value = text,
        onValueChange = onChange,
        modifier = Modifier
            .fillMaxSize()
            .padding(ZillitTheme.spacing.md)
            .clip(ZillitTheme.shapes.medium)
            .background(colors.surface)
            .border(1.dp, colors.border, ZillitTheme.shapes.medium)
            .padding(ZillitTheme.spacing.md),
        textStyle = ZillitTheme.typography.bodyMedium.copy(
            color = colors.textPrimary,
            fontFamily = FontFamily.Monospace,
        ),
        cursorBrush = SolidColor(colors.accent),
        decorationBox = { field ->
            Box {
                if (text.isEmpty()) {
                    val hint = if (plain) S.desktop_drive_text_placeholder else S.desktop_drive_md_placeholder
                    ZillitText(
                        text = str(hint),
                        style = ZillitTheme.typography.bodyMedium,
                        color = colors.textMuted,
                    )
                }
                field()
            }
        },
    )
}

/** `.txt`, exactly as written. */
@Composable
private fun PlainTextView(text: String) {
    ZillitScrollColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(ZillitTheme.spacing.lg),
    ) {
        ZillitText(
            text = text,
            style = ZillitTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
        )
    }
}

/** Markdown, rendered block by block. */
@Composable
private fun MarkdownView(text: String) {
    val blocks = parseMarkdown(text)
    ZillitScrollColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(ZillitTheme.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        blocks.forEach { block -> MarkdownBlockView(block) }
    }
}

@Composable
private fun MarkdownBlockView(block: MarkdownBlock) {
    val colors = ZillitTheme.colors
    when (block) {
        is MarkdownBlock.Heading -> ZillitText(
            text = block.spans.annotated(),
            style = headingStyleFor(block.level),
            modifier = Modifier.padding(top = ZillitTheme.spacing.sm),
        )

        is MarkdownBlock.Paragraph -> ZillitText(text = block.spans.annotated())

        is MarkdownBlock.ListItem -> Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
            ZillitText(text = block.marker, color = colors.textMuted)
            ZillitText(text = block.spans.annotated(), modifier = Modifier.weight(1f))
        }

        is MarkdownBlock.Quote -> Row(modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .width(QUOTE_BAR)
                    .fillMaxHeight()
                    .background(colors.border),
            )
            Spacer(Modifier.width(ZillitTheme.spacing.sm))
            ZillitText(
                text = block.spans.annotated(),
                color = colors.textSecondary,
                modifier = Modifier.weight(1f),
            )
        }

        is MarkdownBlock.Code -> Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(ZillitTheme.shapes.small)
                .background(colors.surfaceHover)
                .padding(ZillitTheme.spacing.md),
        ) {
            ZillitText(
                text = block.text,
                style = ZillitTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            )
        }

        MarkdownBlock.Rule -> Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(RULE_HEIGHT)
                .background(colors.border),
        )
    }
}

/** Heading sizes, scaled off the app's own title styles. */
@Composable
private fun headingStyleFor(level: Int) = when (level) {
    1 -> ZillitTheme.typography.displayLarge
    2 -> ZillitTheme.typography.titleLarge
    3 -> ZillitTheme.typography.titleMedium
    else -> ZillitTheme.typography.titleSmall
}

/**
 * Spans as one annotated string.
 *
 * A link is [LinkAnnotation.Url] with Compose's own handler: the address came
 * out of a file in this production's Drive, and what it opens is the system's
 * decision, not this module's.
 */
@Composable
private fun List<MarkdownSpan>.annotated(): AnnotatedString {
    val colors = ZillitTheme.colors
    val linkStyles = TextLinkStyles(
        SpanStyle(color = colors.accent, textDecoration = TextDecoration.Underline),
    )
    return buildAnnotatedString {
        this@annotated.forEach { span ->
            val style = SpanStyle(
                fontWeight = if (span.bold) FontWeight.SemiBold else null,
                fontStyle = if (span.italic) FontStyle.Italic else null,
                fontFamily = if (span.code) FontFamily.Monospace else null,
                background = if (span.code) colors.surfaceHover else colors.canvas.copy(alpha = 0f),
                fontSize = if (span.code) CODE_SIZE else TextUnit.Unspecified,
            )
            val href = span.href
            if (href != null) {
                withLink(LinkAnnotation.Url(href, linkStyles)) { withStyle(style) { append(span.text) } }
            } else {
                withStyle(style) { append(span.text) }
            }
        }
    }
}

private val TOGGLE_INSET = 2.dp
private val QUOTE_BAR = 3.dp
private val RULE_HEIGHT = 1.dp
private val CODE_SIZE = 12.sp
