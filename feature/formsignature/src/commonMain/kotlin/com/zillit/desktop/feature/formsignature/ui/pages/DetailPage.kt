@file:Suppress("MagicNumber") // The web's 800-wide document column and its small offsets.

package com.zillit.desktop.feature.formsignature.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitScrollColumn
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.formsignature.ui.DetailSource
import com.zillit.desktop.feature.formsignature.ui.DetailState
import com.zillit.desktop.feature.formsignature.ui.FormSignatureEvent
import com.zillit.desktop.feature.formsignature.ui.components.DraggableBox
import com.zillit.desktop.feature.formsignature.ui.components.InfoBand
import com.zillit.desktop.feature.formsignature.ui.components.MarkImage
import com.zillit.desktop.feature.formsignature.ui.components.PageCanvas
import com.zillit.desktop.feature.formsignature.ui.components.PlaceholderBox
import com.zillit.desktop.feature.formsignature.ui.components.spotEdge

/**
 * An open document — the web's `FormDetailsV2`: Download and Print on the
 * right, the guidance band, one page at a time with Previous/Next, the
 * placeholders to click (or the mark to drag), and the footer's buttons.
 */
@Composable
internal fun DetailPage(detail: DetailState, onEvent: (FormSignatureEvent) -> Unit) {
    val colors = ZillitTheme.colors
    Box(Modifier.fillMaxSize().background(colors.canvas)) {
        when {
            detail.loadingPages -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { ZillitSpinner() }
            else -> ZillitScrollColumn(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = ZillitTheme.spacing.lg),
            ) {
                Column(
                    modifier = Modifier.width(COLUMN_WIDTH.dp),
                    verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
                ) {
                    TopActions(detail, onEvent)
                    Guidance(detail)
                    if (detail.notPdf) NotPdfCard(detail) else Pages(detail, onEvent)
                    Footer(detail, onEvent)
                }
            }
        }
        if (detail.busy) {
            Box(
                Modifier.fillMaxSize().background(colors.scrim.copy(alpha = 0.25f)),
                contentAlignment = Alignment.Center,
            ) {
                ZillitSpinner()
            }
        }
    }
}

@Composable
private fun TopActions(detail: DetailState, onEvent: (FormSignatureEvent) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm, Alignment.End),
    ) {
        ZillitButton(
            text = "Download in device",
            onClick = { onEvent(FormSignatureEvent.DownloadDetail) },
            variant = ButtonVariant.Secondary,
            size = ButtonSize.Small,
            leadingIcon = ZillitIcons.Download,
        )
        if (!detail.notPdf) {
            ZillitButton(
                text = "Print",
                onClick = { onEvent(FormSignatureEvent.PrintDetail) },
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
                leadingIcon = ZillitIcons.Print,
            )
        }
    }
}

/** ZL-17530: the signing guidance, for both flows. */
@Composable
private fun Guidance(detail: DetailState) {
    when {
        detail.alreadySigned -> ZillitNotice(
            text = "You have signed this document.",
            tone = StatusTone.Done,
            icon = ZillitIcons.Tick,
        )
        detail.offersSend && detail.placeholderFlow -> InfoBand {
            ZillitText(
                "Click on the highlighted placeholder boxes to add your signature or initials. " +
                    "The placeholders show where signatures are required.",
                style = ZillitTheme.typography.bodySmall,
            )
        }
        detail.offersSend -> InfoBand {
            ZillitText(
                "If you need to place the same signature on the following pages, navigate to the next page. " +
                    "Drag and drop the highlighted signature to the desired location, then click the Sign button " +
                    "below to set the signature where it was placed.",
                style = ZillitTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun Pages(detail: DetailState, onEvent: (FormSignatureEvent) -> Unit) {
    val page = detail.current ?: return
    val paging = detail.freeMark == null
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        ZillitText("Page", style = ZillitTheme.typography.bodyMedium, color = ZillitTheme.colors.textSecondary)
        ZillitText(
            "${detail.page + 1} / ${detail.pageCount}",
            style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
        )
        Spacer(Modifier.weight(1f))
        ZillitIconButton(
            icon = ZillitIcons.ChevronLeft,
            contentDescription = "Previous page",
            enabled = paging && detail.page > 0,
            onClick = { onEvent(FormSignatureEvent.TurnPage(-1)) },
        )
        ZillitIconButton(
            icon = ZillitIcons.ChevronRight,
            contentDescription = "Next page",
            enabled = paging && detail.page < detail.pageCount - 1,
            onClick = { onEvent(FormSignatureEvent.TurnPage(1)) },
        )
    }
    PageCanvas(page = page) {
        if (detail.placeholderFlow && detail.offersSend) {
            detail.placeholders.filter { it.page == page.page }.forEach { spot ->
                PlaceholderBox(page, spot) { onEvent(FormSignatureEvent.TapPlaceholder(spot)) }
            }
        }
        detail.freeMark?.takeIf { it.page == page.page }?.let { mark ->
            DraggableBox(
                x = mark.x,
                y = mark.y,
                width = mark.width,
                height = mark.height,
                edge = Color(0xFFFC9404),
                aspect = mark.aspect,
                onMove = { x, y, w, _ -> onEvent(FormSignatureEvent.MoveFreeMark(x, y, w)) },
                onConfirm = { onEvent(FormSignatureEvent.ConfirmFreeMark) },
                onCancel = { onEvent(FormSignatureEvent.CancelFreeMark) },
            ) {
                MarkImage(mark.png)
            }
        }
    }
}

/** A Word document: the web's card with the document icon and its name. */
@Composable
private fun NotPdfCard(detail: DetailState) {
    val colors = ZillitTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.medium)
            .background(colors.surface)
            .border(1.dp, colors.border, ZillitTheme.shapes.medium)
            .padding(ZillitTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        Box(
            modifier = Modifier.size(56.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFFE3ECFF)),
            contentAlignment = Alignment.Center,
        ) {
            ZillitText(
                detail.stored?.extension?.take(4) ?: "DOC",
                style = ZillitTheme.typography.label.copy(fontWeight = FontWeight.Bold),
                color = Color(0xFF2B6BD8),
            )
        }
        Column {
            ZillitText(detail.title, style = ZillitTheme.typography.titleSmall)
            ZillitText(
                if (detail.stored?.isWord == true) {
                    "A Word document — it is converted to PDF when you add a signature."
                } else {
                    "This file cannot be shown here. Download it to view it."
                },
                style = ZillitTheme.typography.bodySmall,
                color = colors.textSecondary,
            )
        }
    }
}

/** The web's `getFooterButtons`, branch for branch. */
@Composable
private fun Footer(detail: DetailState, onEvent: (FormSignatureEvent) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        if (detail.source == DetailSource.LibraryAll) {
            ZillitButton(
                text = "Transfer this form to your My Downloads",
                onClick = { onEvent(FormSignatureEvent.TransferToDownloads) },
                variant = ButtonVariant.Secondary,
                loading = detail.transferring,
                modifier = Modifier.weight(1f),
            )
        }
        if (detail.offersFreeSign) {
            val placing = detail.freeMark != null
            ZillitButton(
                text = if (placing) "Sign Document" else "Add Signature",
                onClick = {
                    onEvent(if (placing) FormSignatureEvent.ConfirmFreeMark else FormSignatureEvent.AddSignature)
                },
                variant = ButtonVariant.Secondary,
                enabled = !detail.busy,
                leadingIcon = if (placing) ZillitIcons.Tick else ZillitIcons.Signature,
                modifier = Modifier.weight(1f),
            )
        }
        if (detail.offersSend) {
            ZillitButton(
                text = "Send Document",
                onClick = { onEvent(FormSignatureEvent.AskSendSigned) },
                enabled = detail.readyToSend,
                loading = detail.sending,
                leadingIcon = ZillitIcons.Send,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

private const val COLUMN_WIDTH = 800
