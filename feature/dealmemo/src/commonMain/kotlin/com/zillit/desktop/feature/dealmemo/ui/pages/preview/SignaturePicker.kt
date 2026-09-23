package com.zillit.desktop.feature.dealmemo.ui.pages.preview

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.dealmemo.ui.DealMemoEvent
import com.zillit.desktop.feature.dealmemo.ui.SignerEvent
import com.zillit.desktop.feature.dealmemo.ui.components.DmConfirm
import com.zillit.desktop.feature.dealmemo.ui.components.DmConfirmKind
import com.zillit.desktop.feature.dealmemo.ui.components.DmModal
import com.zillit.desktop.feature.dealmemo.ui.components.DmType
import com.zillit.desktop.feature.dealmemo.ui.components.rememberHover
import com.zillit.desktop.feature.dealmemo.ui.documents.SignatureFont
import com.zillit.desktop.feature.dealmemo.ui.documents.platformPdfWork
import com.zillit.desktop.feature.dealmemo.ui.preview.NewSignatureStep
import com.zillit.desktop.feature.dealmemo.ui.preview.SignatureLibrary
import com.zillit.desktop.feature.dealmemo.ui.preview.SignerState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Stage one of signing — E-Signature's `SavedSignaturePicker` in
 * single-document mode: pick a saved signature, or type or draw a new one,
 * which is used at once.
 */
@Suppress("LongMethod")
@Composable
internal fun SignaturePicker(signer: SignerState?, onEvent: (DealMemoEvent) -> Unit) {
    val shown = remember { mutableStateOf(signer) }
    if (signer != null) shown.value = signer
    val current = shown.value
    val library = current?.library ?: SignatureLibrary()
    DmModal(
        visible = signer != null,
        title = str(S.docusign_picker_title),
        onDismiss = { onEvent(SignerEvent.Cancel) },
        maxWidth = 840.dp,
        closeOnBackdrop = false,
        showClose = false,
        titleContent = {
            Column(Modifier.weight(1f)) {
                ZillitText(
                    text = str(S.docusign_picker_before_signing),
                    style = DmType.sans(10.sp, FontWeight.Bold, 0.12.em),
                    color = PreviewInk.Action,
                )
                ZillitText(
                    text = str(S.docusign_picker_title),
                    style = DmType.display(16.sp, FontWeight.Bold),
                    color = pv.ink,
                )
            }
        },
        headerActions = {
            OutlineButton(
                text = str(S.desktop_dm_back_to_review),
                onClick = { onEvent(SignerEvent.Cancel) },
                height = 30.dp,
                radius = 15.dp,
                textSize = 12f,
            )
        },
        footer = if (library.creating == null) {
            {
                if (library.selectedId == null) {
                    ZillitText(
                        text = str(S.docusign_presign_continue_hint),
                        style = DmType.sans(12.sp),
                        color = pv.faint,
                        modifier = Modifier.weight(1f),
                    )
                }
                SolidButton(
                    text = str(S.desktop_dm_continue_to_signing),
                    onClick = { onEvent(SignerEvent.ContinueToSigning) },
                    enabled = library.selectedId != null,
                    height = 36.dp,
                    radius = 10.dp,
                )
            }
        } else {
            null
        },
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 20.dp)) {
            when (library.creating) {
                null -> LibraryStrip(library, onEvent)
                NewSignatureStep.Method -> MethodStep(onEvent)
                NewSignatureStep.Type -> TypeStep(library, onEvent)
                NewSignatureStep.Draw -> DrawStep(library, onEvent)
            }
        }
    }
    DmConfirm(
        visible = library.pendingDelete != null,
        title = str(S.delete_signature),
        message = str(S.desktop_dm_delete_this_saved_signature),
        confirmLabel = str(S.dm_nda_delete),
        onConfirm = { onEvent(SignerEvent.ConfirmDeleteSaved) },
        onCancel = { onEvent(SignerEvent.CancelDeleteSaved) },
        kind = DmConfirmKind.Danger,
    )
}

@Composable
private fun LibraryStrip(library: SignatureLibrary, onEvent: (DealMemoEvent) -> Unit) {
    ZillitText(
        text = str(S.docusign_picker_subtitle),
        style = DmType.sans(14.sp, FontWeight.Bold),
        color = pv.ink,
    )
    Spacer(Modifier.height(2.dp))
    ZillitText(
        text = str(S.docusign_picker_then_tap),
        style = DmType.sans(12.5.sp),
        color = pv.muted,
    )
    Spacer(Modifier.height(16.dp))
    if (library.loading) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ZillitSpinner(size = 14.dp, color = PreviewInk.Action)
            ZillitText(text = str(S.desktop_dm_loading_your_signatures), style = DmType.sans(12.5.sp), color = pv.muted)
        }
        return
    }
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        library.entries.forEach { entry ->
            SavedSignatureCard(
                image = entry.image,
                selected = entry.id == library.selectedId,
                onSelect = { onEvent(SignerEvent.SelectSaved(entry.id)) },
                onDelete = { onEvent(SignerEvent.AskDeleteSaved(entry.id)) },
            )
        }
        AddNewTile(onClick = { onEvent(SignerEvent.NewSignature) })
    }
}

@Composable
private fun SavedSignatureCard(image: ImageBitmap?, selected: Boolean, onSelect: () -> Unit, onDelete: () -> Unit) {
    val (source, hovered) = rememberHover()
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier = Modifier
            .size(width = 200.dp, height = 96.dp)
            .clip(shape)
            .background(Color.White)
            .border(if (selected) 2.dp else 1.dp, if (selected) PreviewInk.Action else pv.chipBorder, shape)
            .hoverable(source)
            .clickable(interactionSource = source, indication = null, onClick = onSelect)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(12.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = str(S.txt_signature_list_header),
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            ZillitText(text = str(S.dm_doc_unavailable), style = DmType.sans(12.sp), color = pv.faint)
        }
        if (hovered || selected) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(Color.White)
                    .border(1.dp, pv.chipBorder, CircleShape)
                    .clickable(onClick = onDelete)
                    .pointerHoverIcon(PointerIcon.Hand),
                contentAlignment = Alignment.Center,
            ) { ZillitIcon(ZillitIcons.Trash, size = 11.dp, tint = PreviewInk.RedHover) }
        }
        if (selected) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(PreviewInk.Action),
                contentAlignment = Alignment.Center,
            ) { ZillitIcon(ZillitIcons.Check, size = 11.dp, tint = Color.White) }
        }
    }
}

@Composable
private fun AddNewTile(onClick: () -> Unit) {
    val (source, hovered) = rememberHover()
    Box(
        modifier = Modifier
            .size(width = 160.dp, height = 96.dp)
            .dashedBorder(if (hovered) PreviewInk.Action else Color(0xFFD6D4CC), 12.dp)
            .hoverable(source)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            ZillitIcon(ZillitIcons.Add, size = 18.dp, tint = if (hovered) PreviewInk.Action else pv.muted)
            Spacer(Modifier.height(4.dp))
            ZillitText(
                text = str(S.desktop_dm_add_new),
                style = DmType.sans(12.5.sp, FontWeight.Bold),
                color = if (hovered) PreviewInk.Action else pv.muted,
            )
        }
    }
}

@Composable
private fun StepHeader(step: String, title: String, sub: String, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlineButton(
            text = str(S.desktop_dm_back_arrow),
            onClick = onBack,
            height = 28.dp,
            radius = 14.dp,
            textSize = 11.5f,
            horizontal = 10.dp,
        )
        Column {
            ZillitText(text = step, style = DmType.sans(10.5.sp, FontWeight.Bold, 0.08.em), color = pv.faint)
            ZillitText(text = title, style = DmType.sans(15.sp, FontWeight.Bold), color = pv.ink)
        }
    }
    Spacer(Modifier.height(4.dp))
    ZillitText(text = sub, style = DmType.sans(12.5.sp), color = pv.muted)
    Spacer(Modifier.height(16.dp))
}

@Composable
private fun MethodStep(onEvent: (DealMemoEvent) -> Unit) {
    StepHeader(
        step = str(S.desktop_dm_step_1_of_2),
        title = str(S.desktop_dm_how_would_you_like_to_make_your),
        sub = str(S.docusign_create_sig_choose_hint),
        onBack = { onEvent(SignerEvent.StepBack) },
    )
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        MethodCard(
            title = str(S.desktop_dm_type_your_signature),
            tag = str(S.docusign_create_sig_card_type_tag),
            body = str(S.docusign_create_sig_card_type_blurb),
            onClick = { onEvent(SignerEvent.Method(NewSignatureStep.Type)) },
            modifier = Modifier.weight(1f),
        )
        MethodCard(
            title = str(S.desktop_dm_draw_your_signature),
            tag = null,
            body = str(S.docusign_create_sig_card_draw_blurb),
            onClick = { onEvent(SignerEvent.Method(NewSignatureStep.Draw)) },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun MethodCard(title: String, tag: String?, body: String, onClick: () -> Unit, modifier: Modifier) {
    val (source, hovered) = rememberHover()
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier = modifier
            .clip(shape)
            .background(if (hovered) Color(0xFFFFFAF1) else pv.card)
            .border(1.dp, if (hovered) PreviewInk.Action else pv.chipBorder, shape)
            .hoverable(source)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ZillitText(text = title, style = DmType.sans(14.sp, FontWeight.Bold), color = pv.ink)
            tag?.let {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xFFE6F7EE))
                        .padding(horizontal = 6.dp, vertical = 1.dp),
                ) {
                    ZillitText(text = it, style = DmType.sans(10.5.sp, FontWeight.Bold), color = Color(0xFF0C6A3F))
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        ZillitText(text = body, style = DmType.sans(12.5.sp), color = pv.muted)
    }
}

/** Typing a name: the previews are the very images that get stamped. */
@Composable
private fun TypeStep(library: SignatureLibrary, onEvent: (DealMemoEvent) -> Unit) {
    StepHeader(
        step = str(S.desktop_dm_step_2_of_2),
        title = str(S.desktop_dm_type_your_signature),
        sub = str(S.docusign_create_sig_type_hint),
        onBack = { onEvent(SignerEvent.StepBack) },
    )
    val fieldShape = RoundedCornerShape(10.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(fieldShape)
            .background(pv.card)
            .border(1.dp, pv.chipBorder, fieldShape)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (library.typedText.isEmpty()) {
            ZillitText(text = str(S.desktop_sa_your_full_name), style = DmType.sans(13.5.sp), color = pv.faint)
        }
        BasicTextField(
            value = library.typedText,
            onValueChange = { onEvent(SignerEvent.TypedText(it)) },
            singleLine = true,
            textStyle = DmType.sans(13.5.sp, FontWeight.Medium).copy(color = pv.ink),
            cursorBrush = SolidColor(PreviewInk.Action),
            modifier = Modifier.fillMaxWidth(),
        )
    }
    Spacer(Modifier.height(14.dp))
    val text = library.typedText.trim()
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        SignatureFont.entries.take(3).forEach { font ->
            TypedTile(
                text,
                font,
                selected = library.typedFont == font,
                onClick = { onEvent(SignerEvent.TypedFont(font)) },
                Modifier.weight(1f),
            )
        }
    }
    Spacer(Modifier.height(10.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        SignatureFont.entries.drop(3).forEach { font ->
            TypedTile(
                text,
                font,
                selected = library.typedFont == font,
                onClick = { onEvent(SignerEvent.TypedFont(font)) },
                Modifier.weight(1f),
            )
        }
    }
    Spacer(Modifier.height(16.dp))
    UseRow(library, enabled = text.isNotEmpty(), onUse = { onEvent(SignerEvent.UseTyped) }, onEvent = onEvent)
}

@Composable
private fun TypedTile(text: String, font: SignatureFont, selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val work = remember { platformPdfWork() }
    val preview by produceState<ImageBitmap?>(null, text, font) {
        delay(TYPED_DEBOUNCE_MS)
        value = if (text.isEmpty()) {
            null
        } else {
            withContext(Dispatchers.Default) { work.typed(text, font)?.let(work::image) }
        }
    }
    val shape = RoundedCornerShape(10.dp)
    Column(
        modifier = modifier
            .clip(shape)
            .background(Color.White)
            .border(if (selected) 2.dp else 1.dp, if (selected) PreviewInk.Action else pv.chipBorder, shape)
            .clickable(onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(8.dp),
    ) {
        Box(Modifier.fillMaxWidth().height(58.dp), contentAlignment = Alignment.Center) {
            val image = preview
            if (image != null) {
                Image(
                    bitmap = image,
                    contentDescription = font.label,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                ZillitText(text = "Aa", style = DmType.sans(18.sp), color = pv.faint)
            }
        }
        ZillitText(
            text = font.label,
            style = DmType.sans(11.sp, FontWeight.SemiBold),
            color = if (selected) PreviewInk.Action else pv.muted,
        )
    }
}

/** Drawing with the pointer: strokes in the pad's own space, rasterised when used. */
@Suppress("LongMethod")
@Composable
private fun DrawStep(library: SignatureLibrary, onEvent: (DealMemoEvent) -> Unit) {
    StepHeader(
        step = str(S.desktop_dm_step_2_of_2),
        title = str(S.desktop_dm_draw_your_signature),
        sub = str(S.docusign_create_sig_card_draw_blurb),
        onBack = { onEvent(SignerEvent.StepBack) },
    )
    val strokes = remember { mutableStateListOf<List<Offset>>() }
    var live by remember { mutableStateOf<List<Offset>>(emptyList()) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(PAD_HEIGHT.dp)
            .clip(shape)
            .background(Color.White)
            .border(1.dp, pv.chipBorder, shape)
            .onSizeChanged { size = it }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { live = listOf(it) },
                    onDrag = { change, _ -> live = live + change.position },
                    onDragEnd = {
                        if (live.size > 1) strokes.add(live)
                        live = emptyList()
                    },
                )
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            (strokes + listOf(live)).forEach { stroke ->
                stroke.zipWithNext().forEach { (from, to) ->
                    drawLine(Color(0xFF101828), from, to, strokeWidth = 2.5.dp.toPx(), cap = StrokeCap.Round)
                }
            }
            drawLine(
                Color(0xFFE5E7EB),
                Offset(24.dp.toPx(), this.size.height - 28.dp.toPx()),
                Offset(this.size.width - 24.dp.toPx(), this.size.height - 28.dp.toPx()),
                strokeWidth = 1.dp.toPx(),
            )
        }
        if (strokes.isEmpty() && live.isEmpty()) {
            ZillitText(
                text = str(S.docusign_signing_sign_here),
                style = DmType.sans(13.sp),
                color = pv.faint,
                modifier = Modifier.align(Alignment.Center),
            )
        }
        if (strokes.isNotEmpty()) {
            ZillitText(
                text = str(S.dm_sign_clear),
                style = DmType.sans(12.sp, FontWeight.SemiBold),
                color = PreviewInk.Action,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(10.dp)
                    .clickable { strokes.clear() }
                    .pointerHoverIcon(PointerIcon.Hand),
            )
        }
    }
    Spacer(Modifier.height(16.dp))
    UseRow(
        library,
        enabled = strokes.isNotEmpty(),
        onUse = { onEvent(SignerEvent.UseDrawn(strokes.toList(), size.width, size.height)) },
        onEvent = onEvent,
    )
}

@Composable
private fun UseRow(library: SignatureLibrary, enabled: Boolean, onUse: () -> Unit, onEvent: (DealMemoEvent) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Row(
            modifier = Modifier
                .weight(1f)
                .clickable { onEvent(SignerEvent.ToggleSaveForNextTime) }
                .pointerHoverIcon(PointerIcon.Hand),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val boxShape = RoundedCornerShape(4.dp)
            Box(
                modifier = Modifier
                    .size(16.dp)
                    .clip(boxShape)
                    .background(if (library.saveForNextTime) PreviewInk.Action else Color.White)
                    .border(1.5.dp, if (library.saveForNextTime) PreviewInk.Action else Color(0xFFD1D5DB), boxShape),
                contentAlignment = Alignment.Center,
            ) {
                if (library.saveForNextTime) ZillitIcon(ZillitIcons.Check, size = 10.dp, tint = Color.White)
            }
            ZillitText(text = str(S.docusign_create_sig_save_next_time), style = DmType.sans(12.5.sp), color = pv.body)
        }
        SolidButton(
            text = str(S.docusign_create_sig_use_signature),
            onClick = onUse,
            enabled = enabled && !library.working,
            loading = library.working,
            height = 36.dp,
            radius = 10.dp,
        )
    }
}

/** A dashed outline — the web's `border-dashed`. */
internal fun Modifier.dashedBorder(color: Color, radius: Dp): Modifier = drawBehind {
    val stroke =
        Stroke(width = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx())))
    drawRoundRect(color = color, style = stroke, cornerRadius = CornerRadius(radius.toPx()))
}

private const val TYPED_DEBOUNCE_MS = 120L
private const val PAD_HEIGHT = 200
