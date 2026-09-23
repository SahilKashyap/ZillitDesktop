package com.zillit.desktop.feature.documentdistribution.ui.pages

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitScrollColumn
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.media.decodeImageBitmap
import com.zillit.desktop.core.permissions.gatedClick
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.documentdistribution.domain.FileKind
import com.zillit.desktop.feature.documentdistribution.domain.fileKindOf
import com.zillit.desktop.feature.documentdistribution.domain.formatBytes
import com.zillit.desktop.feature.documentdistribution.ui.DocDistEvent
import com.zillit.desktop.feature.documentdistribution.ui.DocDistUiState
import com.zillit.desktop.feature.documentdistribution.ui.PreviewState

/**
 * The in-app preview — the web's `FilePreviewModal`: PDF pages and images
 * inline, a vCard as text, everything else a "download to open" card. When
 * opened from the composer's watermark eye, the stamp is drawn over the
 * pages so the sender sees what each recipient will.
 */
@Suppress("LongMethod") // One screen section; splitting it separates each control from its state.
@Composable
internal fun FilePreviewDialog(state: DocDistUiState, onEvent: (DocDistEvent) -> Unit) {
    val preview = state.preview
    val document = preview?.document
    val stamp = state.composer.watermarkPreview?.takeIf { it.id == document?.id }?.let { state.composer.watermark }
    val canPost = state.viewer.canPost
    val canDownload = state.viewer.canDownload
    val available = preview != null && !preview.loading && preview.error == null
    ZillitDialogShell(
        title = document?.name.orEmpty(),
        subtitle = document?.let {
            formatBytes(it.sizeBytes) +
                prettyIsoDate(it.documentDate).takeIf { d -> d != "—" }?.let { d -> " · $d" }.orEmpty()
        },
        visible = preview != null,
        onDismiss = { onEvent(DocDistEvent.ClosePreview) },
        icon = ZillitIcons.Eye,
        width = PREVIEW_WIDTH.dp,
        maxHeight = PREVIEW_HEIGHT.dp,
        scrollable = false,
        actions = {
            if (stamp == null && available && document != null) {
                ZillitButton(
                    text = str(S.desktop_docdist_send_by_email),
                    onClick = gatedClick(canPost, { onEvent(askPost) }) { onEvent(
                        DocDistEvent.DistributeDocument(document.id),
                    ) },
                    variant = ButtonVariant.Secondary,
                    leadingIcon = ZillitIcons.Send,
                )
                if (document.isWatermarkable) {
                    ZillitButton(
                        text = str(S.dd_watermark),
                        onClick = gatedClick(canDownload, { onEvent(askDownload) }) { onEvent(
                            DocDistEvent.OpenWatermarkDownload(document.id),
                        ) },
                        variant = ButtonVariant.Secondary,
                        leadingIcon = ZillitIcons.Shield,
                    )
                }
                ZillitButton(
                    text = str(S.download),
                    onClick = gatedClick(canDownload, { onEvent(askDownload) }) { onEvent(
                        DocDistEvent.DownloadDocument(document.id),
                    ) },
                    leadingIcon = ZillitIcons.Download,
                    loading = preview.downloading,
                )
            } else {
                ZillitButton(
                    text = str(S.close),
                    onClick = { onEvent(DocDistEvent.ClosePreview) },
                    variant = ButtonVariant.Tertiary,
                )
            }
        },
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(PREVIEW_BODY_HEIGHT.dp)
                .clip(ZillitTheme.shapes.medium)
                .background(VIEWER_BACKDROP),
            contentAlignment = Alignment.Center,
        ) {
            if (preview != null) PreviewBody(preview, stamp, onEvent, canPost)
        }
    }
}

@Suppress("LongMethod") // One screen section; splitting it separates each control from its state.
@Composable
private fun PreviewBody(
    preview: PreviewState,
    stamp: com.zillit.desktop.feature.documentdistribution.domain.WatermarkStyle?,
    onEvent: (DocDistEvent) -> Unit,
    canPost: Boolean,
) {
    val kind = fileKindOf(preview.document.contentType, preview.document.name)
    when {
        preview.loading -> Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        ) {
            ZillitSpinner()
            ZillitText(text = str(S.dd_opening_document), color = Color(0xFFCBD5E1))
        }
        preview.error != null -> Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
            modifier = Modifier.padding(ZillitTheme.spacing.xl),
        ) {
            ZillitIcon(icon = ZillitIcons.Warning, tint = Color(0xFFFBBF24), size = 48.dp)
            ZillitText(
                text = str(S.dd_load_failed),
                style = ZillitTheme.typography.titleMedium,
                color = Color.White,
            )
            ZillitText(
                text = preview.error,
                color = Color(0xFFCBD5E1),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            ZillitButton(
                text = str(S.dd_remove_record),
                onClick = gatedClick(canPost, { onEvent(askPost) }) { onEvent(DocDistEvent.RemoveMissingRecord) },
                variant = ButtonVariant.Danger,
                leadingIcon = ZillitIcons.Trash,
            )
        }
        kind == FileKind.Pdf && preview.pages.isNotEmpty() -> ZillitScrollColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(ZillitTheme.spacing.lg),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            preview.pages.forEach { page -> StampedImage(page, stamp) }
        }
        kind == FileKind.Image && preview.bytes != null -> Box(
            Modifier.fillMaxSize().padding(ZillitTheme.spacing.lg),
            contentAlignment = Alignment.Center,
        ) {
            StampedImage(preview.bytes, stamp, fit = true)
        }
        kind == FileKind.VCard -> ZillitScrollColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(ZillitTheme.spacing.xl),
        ) {
            ZillitText(
                text = preview.text.orEmpty().ifBlank { "(empty)" },
                style = ZillitTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = Color(0xFFE2E8F0),
            )
        }
        else -> DownloadCard(kind, preview, onEvent)
    }
}

/** A rendered page or picture with the composer's stamp drawn across it. */
@Composable
private fun StampedImage(
    bytes: ByteArray,
    stamp: com.zillit.desktop.feature.documentdistribution.domain.WatermarkStyle?,
    fit: Boolean = false,
) {
    val bitmap = remember(bytes) { decodeImageBitmap(bytes) } ?: return
    Box(modifier = Modifier.widthIn(max = PAGE_WIDTH.dp), contentAlignment = Alignment.Center) {
        Image(
            bitmap = bitmap,
            contentDescription = null,
            modifier = if (fit) Modifier.fillMaxSize() else Modifier.fillMaxWidth(),
            contentScale = ContentScale.Fit,
        )
        if (stamp != null) {
            val lines = stamp.render(emptyList()).split('\n').filter { it.isNotBlank() }.take(2)
            Column(modifier = Modifier.rotate(-30f), horizontalAlignment = Alignment.CenterHorizontally) {
                lines.forEachIndexed { index, line ->
                    val size = (STAMP_SP * stamp.size.scale * (if (index == 0) 1.0 else 0.7)).toFloat()
                    ZillitText(
                        text = line,
                        style = ZillitTheme.typography.titleLarge.copy(
                            fontSize = size.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 2.sp,
                        ),
                        color = hexColor(stamp.color).copy(alpha = stamp.opacity.toFloat()),
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun DownloadCard(kind: FileKind, preview: PreviewState, onEvent: (DocDistEvent) -> Unit) {
    val (title, sub) = when (kind) {
        FileKind.Word -> str(S.desktop_docdist_word_document) to str(S.desktop_docdist_word_preview_hint)
        FileKind.Excel -> str(S.desktop_file_kind_spreadsheet) to str(S.desktop_docdist_excel_preview_hint)
        FileKind.ImageUnsupported ->
            str(S.desktop_preview_not_available) to str(S.desktop_docdist_image_not_renderable)
        else -> str(S.desktop_docdist_preview_not_available_for_type) to str(S.desktop_docdist_download_to_open_native)
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        modifier = Modifier.padding(ZillitTheme.spacing.xl),
    ) {
        FileGlyph(preview.document, size = 64.dp)
        ZillitText(text = title, style = ZillitTheme.typography.titleMedium, color = Color.White)
        ZillitText(text = sub, color = Color(0xFFCBD5E1), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        ZillitButton(
            text = str(S.dd_download_to_open),
            onClick = { onEvent(DocDistEvent.DownloadDocument(preview.document.id)) },
            leadingIcon = ZillitIcons.Download,
            loading = preview.downloading,
        )
    }
}

private val VIEWER_BACKDROP = Color(0xFF1F2937)
private const val PREVIEW_WIDTH = 1100
private const val PREVIEW_HEIGHT = 860
private const val PREVIEW_BODY_HEIGHT = 680
private const val PAGE_WIDTH = 900
private const val STAMP_SP = 34.0
