package com.zillit.desktop.feature.formsignature.ui.pages

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
import com.zillit.desktop.core.designsystem.component.ZillitCheckbox
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitSectionLabel
import com.zillit.desktop.core.designsystem.component.ZillitSelect
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.formsignature.domain.SignSpotKind
import com.zillit.desktop.feature.formsignature.ui.FormSignatureEvent
import com.zillit.desktop.feature.formsignature.ui.FormSignatureUiState
import com.zillit.desktop.feature.formsignature.ui.SendState

/**
 * Upload & send for signature — the web's two-step drawer as one dialog.
 *
 * Step one is the envelope: title, signers, options. Step two is placement:
 * pick a signer and a mark kind, then click each page where their box goes.
 * The dialog refuses to send until every signer has at least one box, which
 * is the server's own rule surfaced early.
 */
@Composable
internal fun SendDialog(state: FormSignatureUiState, onEvent: (FormSignatureEvent) -> Unit) {
    val send = state.send

    ZillitDialogShell(
        title = "Send for signature",
        subtitle = send?.fileName,
        visible = send != null,
        onDismiss = { onEvent(FormSignatureEvent.CancelSend) },
        icon = ZillitIcons.Send,
        width = DIALOG_WIDTH.dp,
        actions = {
            if (send?.placing == true) {
                ZillitButton(
                    text = "Back to details",
                    onClick = { onEvent(FormSignatureEvent.EditSend(send.copy(placing = false))) },
                    variant = ButtonVariant.Tertiary,
                    size = ButtonSize.Small,
                )
                ZillitButton(
                    text = "Send",
                    onClick = { onEvent(FormSignatureEvent.SubmitSend) },
                    size = ButtonSize.Small,
                    enabled = send.everySignerCovered && !send.sending,
                    loading = send.sending,
                )
            } else {
                ZillitButton(
                    text = "Place signature boxes",
                    onClick = { onEvent(FormSignatureEvent.BeginPlacement) },
                    size = ButtonSize.Small,
                    enabled = send?.chosen?.isNotEmpty() == true,
                )
            }
        },
    ) {
        if (send == null) return@ZillitDialogShell
        if (send.placing) PlacementStep(send, onEvent) else EnvelopeStep(send, onEvent)
    }
}

@Composable
private fun EnvelopeStep(send: SendState, onEvent: (FormSignatureEvent) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
        ZillitTextField(
            value = send.title,
            onValueChange = { onEvent(FormSignatureEvent.EditSend(send.copy(title = it))) },
            label = "Document name",
        )

        ZillitSectionLabel("Signers")
        if (send.options.isEmpty()) {
            ZillitText(
                text = "Loading the project's crew…",
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textSecondary,
            )
        }
        send.options.forEach { option ->
            val chosen = option.userId in send.chosen
            ZillitCheckbox(
                checked = chosen,
                onCheckedChange = { now ->
                    val next = if (now) send.chosen + option.userId else send.chosen - option.userId
                    onEvent(FormSignatureEvent.EditSend(send.copy(chosen = next)))
                },
                label = option.fullName.ifBlank { option.email.ifBlank { option.userId } },
            )
        }

        ZillitSectionLabel("Options")
        ZillitCheckbox(
            checked = send.onlySignature,
            onCheckedChange = {
                onEvent(FormSignatureEvent.EditSend(send.copy(onlySignature = it)))
            },
            label = "Signature only (no initials boxes)",
        )
        ZillitCheckbox(
            checked = send.senderSigns,
            onCheckedChange = {
                onEvent(FormSignatureEvent.EditSend(send.copy(senderSigns = it)))
            },
            label = "I also need to sign this document",
        )
        ZillitNotice(
            text = "Signers are asked in the order chosen. Each signer needs at " +
                "least one box placed in the next step.",
            tone = StatusTone.Neutral,
            icon = ZillitIcons.Info,
        )
    }
}

@Composable
private fun PlacementStep(send: SendState, onEvent: (FormSignatureEvent) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
        // Fixed widths, because ZillitSelect fills its max width when left
        // unconstrained — the first select swallowed this row whole, pushed
        // the pages below the fold, and wrapped the counter one letter per
        // line down the dialog's edge. Seen live, 2026-08-13.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ZillitSelect(
                value = send.activeSigner ?: send.chosen.first(),
                options = send.chosen,
                onSelect = { onEvent(FormSignatureEvent.EditSend(send.copy(activeSigner = it))) },
                label = { id -> send.options.firstOrNull { it.userId == id }?.fullName ?: id },
                modifier = Modifier.width(SIGNER_SELECT_WIDTH.dp),
            )
            ZillitSelect(
                value = send.activeKind,
                options = if (send.onlySignature) {
                    listOf(SignSpotKind.Signature)
                } else {
                    SignSpotKind.entries.toList()
                },
                onSelect = { onEvent(FormSignatureEvent.EditSend(send.copy(activeKind = it))) },
                label = { it.label },
                modifier = Modifier.width(KIND_SELECT_WIDTH.dp),
            )
            ZillitText(
                text = "${send.placedCount} box(es) placed — click a page to add, " +
                    "click a box to remove",
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textSecondary,
                modifier = Modifier.weight(1f),
            )
        }

        send.pages.forEach { page -> PlacementPage(send, page, onEvent) }

        if (!send.everySignerCovered) {
            ZillitNotice(
                text = "Every signer needs at least one box before this can be sent.",
                tone = StatusTone.Pending,
                icon = ZillitIcons.Warning,
            )
        }
    }
}

@Composable
private fun PlacementPage(
    send: SendState,
    page: com.zillit.desktop.feature.formsignature.domain.PdfPageImage,
    onEvent: (FormSignatureEvent) -> Unit,
) {
    val spotsOnPage = send.spots.flatMap { (signer, spots) ->
        spots.filter { it.page == page.page }.map { signer to it }
    }
    DocumentPage(
        page = page,
        spots = spotsOnPage.map { it.second },
        tappable = true,
        onTap = { x, y -> onEvent(FormSignatureEvent.PlaceSendSpot(page.page, x, y)) },
        spotLabel = { spot ->
            val signer = spotsOnPage.firstOrNull { it.second == spot }?.first
            val name = send.options.firstOrNull { it.userId == signer }?.fullName
            (name ?: "").split(" ").firstOrNull().orEmpty().ifBlank { spot.kind.label }
        },
        onSpotTap = { spot ->
            val owner = spotsOnPage.firstOrNull { it.second == spot }?.first
            if (owner != null) {
                val index = send.spots[owner].orEmpty().indexOf(spot)
                if (index >= 0) {
                    onEvent(FormSignatureEvent.RemoveSendSpot(owner, index))
                }
            }
        },
    )
}

private const val DIALOG_WIDTH = 900
private const val SIGNER_SELECT_WIDTH = 240
private const val KIND_SELECT_WIDTH = 150
