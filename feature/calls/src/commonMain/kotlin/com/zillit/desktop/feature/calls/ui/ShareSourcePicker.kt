package com.zillit.desktop.feature.calls.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Image
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitLazyColumn
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.feature.calls.domain.ShareSource

/**
 * "Choose what to share" — the app's own, because embedded Chromium has none.
 *
 * Chrome shows this dialog for a web page, but that UI lives in the browser
 * shell rather than the content layer CEF exposes, so an embedded browser
 * offers the user no choice and no cancel at all. This is the replacement, and
 * it is deliberately the same shape as the one people already know from the
 * browser and from WhatsApp: screens first, then windows, each a picture you
 * click, with Cancel and Share at the bottom.
 *
 * Drawn by the host inside its own top-level window. That is not decoration —
 * the call's picture is a heavyweight browser surface that paints over every
 * Compose layer inside its rectangle, so a dialog composed into the call
 * window would be invisible exactly where it needs to be seen. See
 * [CallDevicePanel] for the same scar.
 */
@Composable
fun ShareSourcePicker(
    picker: SharePicker,
    /** The decoded preview for a source, or null while none has arrived. */
    preview: (ShareSource) -> ImageBitmap?,
    onChoose: (String) -> Unit,
    onShare: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = ZillitTheme.colors
    val spacing = ZillitTheme.spacing
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.surface)
            .padding(spacing.lg),
        verticalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        ZillitText(
            text = "Choose what to share",
            style = ZillitTheme.typography.titleMedium,
            color = colors.textPrimary,
        )

        Box(modifier = Modifier.weight(1f)) {
            when {
                picker.loading -> Centred("Looking for screens and windows…")
                picker.isEmpty -> Centred(
                    "No screens or windows could be listed. Sharing will send your whole screen.",
                )
                else -> SourceGrid(picker, preview, onChoose)
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(spacing.sm, Alignment.End),
        ) {
            ZillitButton(text = "Cancel", onClick = onCancel, variant = ButtonVariant.Tertiary)
            ZillitButton(
                text = "Share",
                onClick = onShare,
                // Never disabled: with nothing listed, or nothing picked,
                // Share still means "share my screen" — which is what the app
                // did before this dialog existed and is never the wrong
                // answer to pressing Share.
                variant = ButtonVariant.Primary,
            )
        }
    }
}

@Composable
private fun SourceGrid(
    picker: SharePicker,
    preview: (ShareSource) -> ImageBitmap?,
    onChoose: (String) -> Unit,
) {
    val spacing = ZillitTheme.spacing
    val listState = rememberLazyListState()
    ZillitLazyColumn(
        state = listState,
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        if (picker.screens.isNotEmpty()) {
            item { SectionHeading("Screen") }
            items(picker.screens.chunked(COLUMNS), key = { row -> "s-" + row.first().id }) { row ->
                SourceRow(row, picker.chosenId, preview, onChoose)
            }
        }
        if (picker.windows.isNotEmpty()) {
            item { SectionHeading("Window") }
            items(picker.windows.chunked(COLUMNS), key = { row -> "w-" + row.first().id }) { row ->
                SourceRow(row, picker.chosenId, preview, onChoose)
            }
        }
    }
}

@Composable
private fun SourceRow(
    row: List<ShareSource>,
    chosenId: String,
    preview: (ShareSource) -> ImageBitmap?,
    onChoose: (String) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        row.forEach { source ->
            SourceTile(
                source = source,
                chosen = source.id == chosenId,
                preview = preview(source),
                onChoose = { onChoose(source.id) },
                modifier = Modifier.weight(1f),
            )
        }
        // Keeps a short last row the same tile width as a full one, rather
        // than stretching two tiles across three columns.
        repeat(COLUMNS - row.size) { Box(modifier = Modifier.weight(1f)) {} }
    }
}

@Composable
private fun SourceTile(
    source: ShareSource,
    chosen: Boolean,
    preview: ImageBitmap?,
    onChoose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = ZillitTheme.colors
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(TILE_CORNER))
            .background(if (chosen) colors.surfaceRaised else colors.surface)
            .border(
                if (chosen) CHOSEN_BORDER else TILE_BORDER,
                if (chosen) colors.accent else colors.border,
                RoundedCornerShape(TILE_CORNER),
            )
            .clickable(onClick = onChoose)
            .padding(ZillitTheme.spacing.xs),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(PREVIEW_RATIO)
                .clip(RoundedCornerShape(PREVIEW_CORNER))
                .background(colors.surfaceSunken),
            contentAlignment = Alignment.Center,
        ) {
            if (preview != null) {
                Image(
                    bitmap = preview,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                )
            } else {
                // A tile with no picture is still pickable and still shares.
                // Previews are real screen captures and arrive one by one.
                ZillitText(
                    text = if (source.isScreen) "Screen" else "Window",
                    style = ZillitTheme.typography.labelSmall,
                    color = colors.textSecondary,
                )
            }
        }
        ZillitText(
            text = source.name,
            style = ZillitTheme.typography.label,
            color = colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (source.app.isNotBlank()) {
            ZillitText(
                text = source.app,
                style = ZillitTheme.typography.labelSmall,
                color = colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SectionHeading(text: String) {
    ZillitText(
        text = text,
        style = ZillitTheme.typography.labelSmall,
        color = ZillitTheme.colors.textSecondary,
        modifier = Modifier.padding(top = ZillitTheme.spacing.xs),
    )
}

@Composable
private fun Centred(text: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        ZillitText(
            text = text,
            style = ZillitTheme.typography.bodyMedium,
            color = ZillitTheme.colors.textSecondary,
        )
    }
}

private const val COLUMNS = 3
private val TILE_CORNER = 10.dp
private val PREVIEW_CORNER = 6.dp
private val TILE_BORDER = 1.dp
private val CHOSEN_BORDER = 2.dp
private const val PREVIEW_RATIO = 16f / 10f
