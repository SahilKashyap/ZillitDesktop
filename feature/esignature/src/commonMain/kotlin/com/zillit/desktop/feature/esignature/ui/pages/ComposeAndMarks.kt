package com.zillit.desktop.feature.esignature.ui.pages

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
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
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.esignature.domain.EsignPage
import com.zillit.desktop.feature.esignature.domain.FieldStyle
import com.zillit.desktop.core.designsystem.component.ZillitChoiceChip
import com.zillit.desktop.feature.esignature.domain.FieldType
import com.zillit.desktop.feature.esignature.ui.ComposeState
import com.zillit.desktop.feature.esignature.ui.EsignEvent
import com.zillit.desktop.feature.esignature.ui.EsignUiState
import com.zillit.desktop.feature.esignature.ui.decodeImageBitmap

/**
 * Sending an envelope: details and signers, then field placement by
 * clicking the rendered pages. Coordinates leave here in PDF points with a
 * top-left origin — this service's convention, no flip anywhere.
 */
@Composable
internal fun ComposeDialog(state: EsignUiState, onEvent: (EsignEvent) -> Unit) {
    val compose = state.compose

    ZillitDialogShell(
        title = "Send for e-signature",
        subtitle = compose?.fileName,
        visible = compose != null,
        onDismiss = { onEvent(EsignEvent.CancelCompose) },
        icon = ZillitIcons.Send,
        width = DIALOG_WIDTH.dp,
        actions = {
            if (compose?.placing == true) {
                ZillitButton(
                    text = "Back to details",
                    onClick = { onEvent(EsignEvent.EditCompose(compose.copy(placing = false))) },
                    variant = ButtonVariant.Tertiary,
                    size = ButtonSize.Small,
                )
                ZillitButton(
                    text = "Send",
                    onClick = { onEvent(EsignEvent.SubmitCompose) },
                    size = ButtonSize.Small,
                    enabled = compose.everySignerCovered && !compose.sending,
                    loading = compose.sending,
                )
            } else {
                ZillitButton(
                    text = "Place fields",
                    onClick = { onEvent(EsignEvent.BeginPlacement) },
                    size = ButtonSize.Small,
                    enabled = compose?.chosen?.isNotEmpty() == true,
                )
            }
        },
    ) {
        if (compose == null) return@ZillitDialogShell
        if (compose.placing) PlacementStep(compose, onEvent) else DetailsStep(compose, onEvent)
    }
}

@Composable
private fun DetailsStep(compose: ComposeState, onEvent: (EsignEvent) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
        ZillitTextField(
            value = compose.title,
            onValueChange = { onEvent(EsignEvent.EditCompose(compose.copy(title = it))) },
            label = "Title",
        )
        ZillitTextField(
            value = compose.description,
            onValueChange = { onEvent(EsignEvent.EditCompose(compose.copy(description = it))) },
            label = "Message to signers",
        )
        EnvelopeOptions(compose, onEvent)
        ZillitSectionLabel("SIGNERS, IN ORDER")
        if (compose.options.isEmpty()) {
            ZillitText(
                text = "No crew members with access were found.",
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textSecondary,
            )
        }
        compose.options.forEach { option -> SignerRow(compose, option, onEvent) }
        ZillitNotice(
            text = "Each signer needs at least one placed field. Signature and " +
                "initials fields are stamped by the service from each signer's " +
                "saved marks.",
            tone = StatusTone.Neutral,
            icon = ZillitIcons.Info,
        )
    }
}

@Composable
private fun SignerRow(
    compose: ComposeState,
    option: com.zillit.desktop.feature.esignature.domain.SignerOptionLike,
    onEvent: (EsignEvent) -> Unit,
) {
    ZillitCheckbox(
        checked = option.userId in compose.chosen,
        onCheckedChange = { now ->
            val next = if (now) compose.chosen + option.userId else compose.chosen - option.userId
            onEvent(EsignEvent.EditCompose(compose.copy(chosen = next)))
        },
        label = option.fullName.ifBlank { option.email.ifBlank { option.userId } },
    )
    // The service refuses a recipient without an address; for a chosen
    // signer whose crew row carries none, the sender types one — the same
    // act as the web's ad-hoc external emails.
    if (option.userId in compose.chosen && option.email.isBlank()) {
        ZillitTextField(
            value = compose.emailOverrides[option.userId].orEmpty(),
            onValueChange = { typed ->
                onEvent(
                    EsignEvent.EditCompose(
                        compose.copy(
                            emailOverrides = compose.emailOverrides + (option.userId to typed),
                        ),
                    ),
                )
            },
            label = "Email for ${option.fullName.ifBlank { "this signer" }} (required)",
            placeholder = "name@example.com",
        )
    }
}

@Composable
private fun PlacementStep(compose: ComposeState, onEvent: (EsignEvent) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ZillitSelect(
                value = compose.activeSigner ?: compose.chosen.first(),
                options = compose.chosen,
                onSelect = { onEvent(EsignEvent.EditCompose(compose.copy(activeSigner = it))) },
                label = { id -> compose.options.firstOrNull { it.userId == id }?.fullName ?: id },
                modifier = Modifier.width(SIGNER_SELECT_WIDTH.dp),
            )
            ZillitSelect(
                value = compose.activeType,
                options = listOf(
                    FieldType.SignHere,
                    FieldType.InitialHere,
                    FieldType.DateSigned,
                    FieldType.Text,
                    FieldType.FullName,
                    FieldType.Checkbox,
                    FieldType.Phone,
                    FieldType.Number,
                    FieldType.Url,
                    FieldType.Dropdown,
                    FieldType.Attachment,
                ),
                onSelect = { onEvent(EsignEvent.EditCompose(compose.copy(activeType = it))) },
                label = { it.label },
                modifier = Modifier.width(TYPE_SELECT_WIDTH.dp),
            )
            if (compose.activeType.isTyped) StyleRow(compose, onEvent)
            ZillitText(
                text = "${compose.placedCount} field(s) placed — click a page to add, " +
                    "click a field to remove",
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textSecondary,
                modifier = Modifier.weight(1f),
            )
        }
        compose.pages.forEach { page -> ComposePage(compose, page, onEvent) }
    }
}

@Composable
private fun ComposePage(
    compose: ComposeState,
    page: EsignPage,
    onEvent: (EsignEvent) -> Unit,
) {
    val bitmap = remember(page.page, page.imageBytes.size) { decodeImageBitmap(page.imageBytes) }
        ?: return
    val density = LocalDensity.current
    val widthDp = with(density) { page.widthPx.toDp() }
    val heightDp = with(density) { page.heightPx.toDp() }
    val scale = page.widthPx / page.widthPt

    val onPage = compose.placed.flatMap { (signer, fields) ->
        fields.mapIndexedNotNull { index, field ->
            if (field.page == page.page) Triple(signer, index, field) else null
        }
    }

    Box(
        modifier = Modifier
            .width(widthDp)
            .border(1.dp, ZillitTheme.colors.border)
            .background(Color.White)
            .pointerInput(page.page) {
                detectTapGestures { offset ->
                    onEvent(EsignEvent.PlaceField(page.page, offset.x, offset.y))
                }
            },
    ) {
        Image(
            bitmap = bitmap,
            contentDescription = "Page ${page.page}",
            modifier = Modifier.size(widthDp, heightDp),
            contentScale = ContentScale.FillBounds,
        )
        onPage.forEach { (signer, index, field) ->
            val x = with(density) { (field.x * scale).toFloat().toDp() }
            val y = with(density) { (field.y * scale).toFloat().toDp() }
            val w = with(density) { (FieldType.DEFAULT_WIDTH * scale).toFloat().toDp() }
            val h = with(density) { (FieldType.DEFAULT_HEIGHT * scale).toFloat().toDp() }
            val name = compose.options.firstOrNull { it.userId == signer }?.fullName ?: signer
            Box(
                modifier = Modifier
                    .offset(x = x, y = y)
                    .size(w, h)
                    .background(FIELD_FILL)
                    .border(1.dp, FIELD_EDGE)
                    .clickable { onEvent(EsignEvent.RemovePlaced(signer, index)) },
                contentAlignment = Alignment.Center,
            ) {
                ZillitText(
                    text = "${field.type.label} · ${name.split(" ").firstOrNull().orEmpty()}",
                    style = ZillitTheme.typography.bodySmall,
                    color = FIELD_EDGE,
                )
            }
        }
    }
}

/**
 * The saved marks drawer: the signature and initials the service stamps for
 * this user, drawn here and stored on the envelope service's own route.
 */
@Composable
internal fun MarksDialog(state: EsignUiState, onEvent: (EsignEvent) -> Unit) {
    val marks = state.marks

    ZillitDialogShell(
        title = "Saved marks",
        subtitle = "What the service stamps when you sign",
        visible = state.showMarks,
        onDismiss = { onEvent(EsignEvent.ToggleMarks) },
        icon = ZillitIcons.Edit,
    ) {
        if (marks.loading && marks.items.isEmpty()) ZillitSpinner()
        Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
            MarkRow(state, isSignature = true, onEvent = onEvent)
            MarkRow(state, isSignature = false, onEvent = onEvent)
        }
    }

    DrawMarkDialog(state, onEvent)
}

@Composable
private fun MarkRow(
    state: EsignUiState,
    isSignature: Boolean,
    onEvent: (EsignEvent) -> Unit,
) {
    val mark = state.marks.items.firstOrNull { it.isSignature == isSignature }
    val image = mark?.let { state.marks.images[it.id] }

    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ZillitText(text = if (isSignature) "Signature" else "Initials")
            Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
                ZillitButton(
                    text = if (mark == null) "Draw" else "Redraw",
                    onClick = { onEvent(EsignEvent.StartDrawMark(isSignature)) },
                    variant = if (mark == null) ButtonVariant.Primary else ButtonVariant.Secondary,
                    size = ButtonSize.Small,
                )
                if (mark != null) {
                    ZillitButton(
                        text = "Delete",
                        onClick = { onEvent(EsignEvent.DeleteMark(mark.id)) },
                        variant = ButtonVariant.Tertiary,
                        size = ButtonSize.Small,
                    )
                }
            }
        }
        when {
            mark == null -> ZillitText(
                text = "Not set up yet.",
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textSecondary,
            )

            image == null -> ZillitSpinner()

            else -> {
                val bitmap = remember(mark.id, image.size) { decodeImageBitmap(image) }
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap,
                        contentDescription = if (isSignature) "Signature" else "Initials",
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(MARK_PREVIEW_HEIGHT.dp)
                            .background(Color.White),
                    )
                }
            }
        }
    }
}

@Composable
private fun DrawMarkDialog(state: EsignUiState, onEvent: (EsignEvent) -> Unit) {
    val drawing = state.marks.drawing

    ZillitDialogShell(
        title = if (drawing?.isSignature != false) "Draw your signature" else "Draw your initials",
        visible = drawing != null,
        onDismiss = { onEvent(EsignEvent.CancelDrawMark) },
        icon = ZillitIcons.Edit,
        actions = {
            ZillitButton(
                text = "Clear",
                onClick = { onEvent(EsignEvent.ClearMarkStrokes) },
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
            )
            ZillitButton(
                text = "Save",
                onClick = { onEvent(EsignEvent.SaveMark) },
                size = ButtonSize.Small,
                loading = drawing?.saving == true,
            )
        },
    ) {
        if (drawing == null) return@ZillitDialogShell
        MarkPad(drawing.strokes, onEvent)
    }
}

@Composable
private fun MarkPad(
    strokes: List<List<Pair<Float, Float>>>,
    onEvent: (EsignEvent) -> Unit,
) {
    val current = remember { mutableListOf<Pair<Float, Float>>() }
    val padSize = remember { intArrayOf(0, 0) }

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(PAD_ASPECT)
            .background(Color.White)
            .border(1.dp, ZillitTheme.colors.border)
            .onSizeChanged {
                padSize[0] = it.width
                padSize[1] = it.height
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { offset ->
                        current.clear()
                        current += offset.toRaster(padSize)
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        current += change.position.toRaster(padSize)
                    },
                    onDragEnd = {
                        if (current.size > 1) {
                            onEvent(EsignEvent.AddMarkStroke(current.toList()))
                        }
                        current.clear()
                    },
                )
            },
    ) {
        val scaleX = size.width / RASTER_WIDTH
        val scaleY = size.height / RASTER_HEIGHT
        strokes.forEach { stroke ->
            if (stroke.size < 2) return@forEach
            val path = Path()
            path.moveTo(stroke.first().first * scaleX, stroke.first().second * scaleY)
            stroke.drop(1).forEach { (px, py) -> path.lineTo(px * scaleX, py * scaleY) }
            drawPath(path, INK, style = Stroke(width = PEN_WIDTH))
        }
    }
}

private fun Offset.toRaster(padSize: IntArray): Pair<Float, Float> {
    val w = if (padSize[0] == 0) 1 else padSize[0]
    val h = if (padSize[1] == 0) 1 else padSize[1]
    return (x / w * RASTER_WIDTH) to (y / h * RASTER_HEIGHT)
}

private val FIELD_FILL = Color(0x332B6BD8)
private val FIELD_EDGE = Color(0xFF2B6BD8)
private const val DIALOG_WIDTH = 900
private const val SIGNER_SELECT_WIDTH = 240
private const val TYPE_SELECT_WIDTH = 160
private const val MARK_PREVIEW_HEIGHT = 110
private const val PAD_ASPECT = 800f / 300f
private const val RASTER_WIDTH = 800f
private const val RASTER_HEIGHT = 300f
private const val PEN_WIDTH = 3f
private val INK = Color(0xFF162A60)

/** The envelope-level options the phones added: initials on every page, and how often signers are reminded. */
@Composable
private fun EnvelopeOptions(compose: ComposeState, onEvent: (EsignEvent) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
        ZillitCheckbox(
            checked = compose.initialsOnAllPages,
            onCheckedChange = { onEvent(EsignEvent.EditCompose(compose.copy(initialsOnAllPages = it))) },
            label = "Initials on all pages",
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            ZillitText(text = "Remind signers", style = ZillitTheme.typography.bodySmall)
            ZillitSelect(
                value = compose.reminderCadenceDays,
                options = REMINDER_CHOICES,
                onSelect = { onEvent(EsignEvent.EditCompose(compose.copy(reminderCadenceDays = it))) },
                label = { days -> reminderLabel(days) },
                modifier = Modifier.width(TYPE_SELECT_WIDTH.dp),
            )
        }
    }
}

/** Text styling for the typed field about to be placed — the phones' bold/italic/underline/size tab keys. */
@Composable
private fun StyleRow(compose: ComposeState, onEvent: (EsignEvent) -> Unit) {
    val style = compose.activeStyle
    fun set(next: FieldStyle) = onEvent(EsignEvent.EditCompose(compose.copy(activeStyle = next)))
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        ZillitChoiceChip(label = "B", selected = style.bold, onClick = { set(style.copy(bold = !style.bold)) })
        ZillitChoiceChip(label = "I", selected = style.italic, onClick = { set(style.copy(italic = !style.italic)) })
        ZillitChoiceChip(
            label = "U",
            selected = style.underline,
            onClick = { set(style.copy(underline = !style.underline)) },
        )
        ZillitSelect(
            value = style.fontSize,
            options = listOf<Int?>(null) + FieldStyle.FONT_SIZES,
            onSelect = { set(style.copy(fontSize = it)) },
            label = { size -> if (size == null) "Size" else "${size}pt" },
            modifier = Modifier.width(STYLE_SIZE_WIDTH.dp),
        )
    }
}

private val REMINDER_CHOICES: List<Int?> = listOf(null, 1, 2, 3, 7)

private fun reminderLabel(days: Int?): String = when (days) {
    null -> "Server default"
    1 -> "Every day"
    else -> "Every $days days"
}
private const val STYLE_SIZE_WIDTH = 96
