package com.zillit.desktop.feature.formsignature.ui.pages

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitPageHeader
import com.zillit.desktop.core.designsystem.component.ZillitScrollColumn
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.formsignature.domain.PdfPageImage
import com.zillit.desktop.feature.formsignature.domain.SignSpot
import com.zillit.desktop.feature.formsignature.domain.SignSpotKind
import com.zillit.desktop.feature.formsignature.ui.DetailState
import com.zillit.desktop.feature.formsignature.ui.FormSignatureEvent
import com.zillit.desktop.feature.formsignature.ui.FormSignatureUiState
import com.zillit.desktop.feature.formsignature.ui.decodeImageBitmap

/**
 * An open document: its pages, the boxes waiting for this reader, and the
 * act of signing.
 *
 * Boxes are drawn from the same PDF-point numbers the server sent, converted
 * to pixels per page — the one place the two coordinate spaces meet.
 */
@Composable
internal fun DetailPage(
    detail: DetailState,
    onEvent: (FormSignatureEvent) -> Unit,
) {
    ZillitPageHeader(
        eyebrow = "Documents & Signature",
        title = detail.title,
        actions = {
            ZillitButton(
                text = "Back",
                onClick = { onEvent(FormSignatureEvent.CloseDetail) },
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
                leadingIcon = ZillitIcons.ArrowLeft,
            )
            if (detail.canSign) {
                ZillitButton(
                    text = if (detail.mySpots.isEmpty()) "Sign here first" else "Sign document",
                    onClick = { onEvent(FormSignatureEvent.SignOpenDocument) },
                    size = ButtonSize.Small,
                    leadingIcon = ZillitIcons.Edit,
                    enabled = detail.readyToSign,
                    loading = detail.signing,
                )
            }
        },
    )

    SigningNotice(detail)
    DetailBody(detail, onEvent)
}

@Composable
private fun SigningNotice(detail: DetailState) {
    when {
        detail.alreadySigned -> ZillitNotice(
            text = "You have signed this document.",
            tone = StatusTone.Done,
            icon = ZillitIcons.Tick,
        )

        detail.canSign && detail.mySpots.isNotEmpty() -> ZillitNotice(
            text = "${detail.mySpots.size} box(es) are waiting for your signature — " +
                "shown outlined on the pages. Signing stamps your saved marks into " +
                "every one of them.",
            tone = StatusTone.Pending,
            icon = ZillitIcons.Info,
        )

        detail.canSign -> ZillitNotice(
            text = "No boxes were placed for you — click on a page where your " +
                "signature should go, then sign.",
            tone = StatusTone.Pending,
            icon = ZillitIcons.Info,
        )
    }
}

@Composable
private fun DetailBody(detail: DetailState, onEvent: (FormSignatureEvent) -> Unit) {
    when {
        detail.notPdf -> ZillitNotice(
            text = "This document is not a PDF, so it cannot be previewed or " +
                "signed here.",
            tone = StatusTone.Neutral,
            icon = ZillitIcons.Warning,
        )

        detail.loadingPages -> ZillitSpinner()

        else -> ZillitScrollColumn(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            detail.pages.forEach { page ->
                DocumentPage(
                    page = page,
                    spots = detail.mySpots.filter { it.page == page.page } +
                        listOfNotNull(detail.freeSpot?.takeIf { it.page == page.page }),
                    tappable = detail.canSign && detail.mySpots.isEmpty(),
                    onTap = { x, y ->
                        onEvent(FormSignatureEvent.PlaceFreeSpot(page.page, x, y))
                    },
                )
            }
        }
    }
}

/** One page image with its overlay boxes; shared with the send flow. */
@Composable
internal fun DocumentPage(
    page: PdfPageImage,
    spots: List<SignSpot>,
    tappable: Boolean,
    onTap: (Float, Float) -> Unit,
    spotLabel: (SignSpot) -> String = { it.kind.label },
    onSpotTap: ((SignSpot) -> Unit)? = null,
) {
    val bitmap = remember(page.page, page.imageBytes.size) { decodeImageBitmap(page.imageBytes) }
        ?: return
    val density = LocalDensity.current

    // Rendered at the source pixel width so the pixel rectangles line up 1:1
    // with the image — scaling the image without scaling the overlay is the
    // classic way to draw boxes in the wrong place.
    val widthDp = with(density) { page.widthPx.toDp() }
    val heightDp = with(density) { page.heightPx.toDp() }

    Box(
        modifier = Modifier
            .width(widthDp)
            .border(1.dp, ZillitTheme.colors.border)
            .background(Color.White)
            .pointerInput(tappable, page.page) {
                if (tappable) {
                    detectTapGestures { offset -> onTap(offset.x, offset.y) }
                }
            },
    ) {
        Image(
            bitmap = bitmap,
            contentDescription = "Page ${page.page}",
            modifier = Modifier.size(widthDp, heightDp),
            contentScale = ContentScale.FillBounds,
        )
        spots.forEach { spot ->
            val rect = page.pixelRect(spot)
            val x = with(density) { rect[0].toDp() }
            val y = with(density) { rect[1].toDp() }
            val w = with(density) { rect[2].toDp() }
            val h = with(density) { rect[3].toDp() }
            Box(
                modifier = Modifier
                    .offset(x = x, y = y)
                    .size(w, h)
                    .background(spotFill(spot.kind))
                    .border(1.dp, spotEdge(spot.kind))
                    .let { base ->
                        if (onSpotTap == null) {
                            base
                        } else {
                            base.clickable { onSpotTap(spot) }
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                ZillitText(
                    text = spotLabel(spot),
                    style = ZillitTheme.typography.bodySmall,
                    color = spotEdge(spot.kind),
                )
            }
        }
    }
}

// Blue for signatures, amber for initials — the web's overlay colours.
private val SIGNATURE_FILL = Color(0x332B6BD8)
private val SIGNATURE_EDGE = Color(0xFF2B6BD8)
private val INITIALS_FILL = Color(0x33D8912B)
private val INITIALS_EDGE = Color(0xFFD8912B)

private fun spotFill(kind: SignSpotKind): Color = when (kind) {
    SignSpotKind.Signature -> SIGNATURE_FILL
    SignSpotKind.Initials -> INITIALS_FILL
}

private fun spotEdge(kind: SignSpotKind): Color = when (kind) {
    SignSpotKind.Signature -> SIGNATURE_EDGE
    SignSpotKind.Initials -> INITIALS_EDGE
}
