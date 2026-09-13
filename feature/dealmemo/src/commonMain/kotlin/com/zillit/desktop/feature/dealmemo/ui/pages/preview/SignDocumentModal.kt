package com.zillit.desktop.feature.dealmemo.ui.pages.preview

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.dealmemo.ui.DealMemoEvent
import com.zillit.desktop.feature.dealmemo.ui.DealMemoUiState
import com.zillit.desktop.feature.dealmemo.ui.SignerEvent
import com.zillit.desktop.feature.dealmemo.ui.components.DmIcons
import com.zillit.desktop.feature.dealmemo.ui.components.DmModal
import com.zillit.desktop.feature.dealmemo.ui.components.DmType
import com.zillit.desktop.feature.dealmemo.ui.components.rememberHover
import com.zillit.desktop.feature.dealmemo.ui.documents.DealPlacement
import com.zillit.desktop.feature.dealmemo.ui.preview.CapturedSignature
import com.zillit.desktop.feature.dealmemo.ui.preview.SignerStage
import com.zillit.desktop.feature.dealmemo.ui.preview.SignerState
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** The per-document signer: choose a signature, then place it. */
@Composable
internal fun SignDocumentModal(state: DealMemoUiState, onEvent: (DealMemoEvent) -> Unit) {
    val signer = state.preview?.signer
    SignaturePicker(signer?.takeIf { it.stage == SignerStage.Choose }, onEvent)
    PlacementModal(signer?.takeIf { it.stage == SignerStage.Place }, state.preview?.signature, onEvent)
}

/** Where the live signature sits, in dp from the top-left of the page stack. */
private data class Overlay(val x: Float, val y: Float, val w: Float, val h: Float)

/** The stack's geometry at the current zoom: page width, each page's top and height. */
private class StackLayout(val pageWidth: Float, val tops: List<Float>, val heights: List<Float>) {
    val height: Float get() = if (tops.isEmpty()) 0f else tops.last() + heights.last()

    /** `measurePlacement`: the last page whose top is above the overlay's centre, clamped inside it. */
    fun placement(overlay: Overlay): DealPlacement? {
        if (tops.isEmpty() || pageWidth <= 0f) return null
        val centre = overlay.y + overlay.h / 2
        val index = tops.indexOfLast { it <= centre }.coerceAtLeast(0)
        val pageHeight = heights[index]
        val x = overlay.x.coerceIn(0f, max(0f, pageWidth - overlay.w))
        val y = (overlay.y - tops[index]).coerceIn(0f, max(0f, pageHeight - overlay.h))
        return DealPlacement(
            index,
            (x / pageWidth).toDouble(),
            (y / pageHeight).toDouble(),
            (overlay.w / pageWidth).toDouble(),
        )
    }
}

/**
 * Stage two (`DMSignDocumentModal.jsx:588-831`): the whole document stacked,
 * a signature to drag and resize over it, stamps for more pages, and Sign.
 */
@Suppress("CyclomaticComplexMethod", "LongMethod")
@Composable
private fun PlacementModal(signer: SignerState?, signature: CapturedSignature?, onEvent: (DealMemoEvent) -> Unit) {
    val shown = remember { mutableStateOf(signer) }
    if (signer != null) shown.value = signer
    val current = shown.value
    val busy = signer?.busy == true
    var overlay by remember { mutableStateOf<Overlay?>(null) }
    var layout by remember { mutableStateOf<StackLayout?>(null) }
    DmModal(
        visible = signer != null,
        title = "Sign: ${current?.surface?.label.orEmpty()}",
        onDismiss = { onEvent(SignerEvent.Cancel) },
        maxWidth = Dp.Infinity,
        fullHeight = true,
        dismissible = !busy,
        closeOnBackdrop = false,
        footer = {
            val stamps = current?.stamps?.size ?: 0
            if (stamps > 0) {
                ZillitText(
                    text = "$stamps signature${if (stamps == 1) "" else "s"} placed — the current one signs with the " +
                        "document.",
                    style = DmType.sans(12.sp),
                    color = pv.muted,
                    modifier = Modifier.weight(1f),
                )
            }
            OutlineButton(
                text = "Cancel",
                onClick = { onEvent(SignerEvent.Cancel) },
                enabled = !busy,
                height = 36.dp,
                radius = 10.dp,
                ink = pv.muted,
            )
            val ready = current?.ready == true && signature != null
            OutlineButton(
                text = "Add another signature",
                onClick = {
                    val box = overlay
                    val placement = box?.let { layout?.placement(it) }
                    if (box != null && placement != null) {
                        onEvent(SignerEvent.AddAnother(placement))
                        overlay = box.copy(y = max(EDGE_GAP, box.y - box.h - STAMP_GAP))
                    }
                },
                enabled = ready && !busy,
                ink = PreviewInk.Action,
                border = PreviewInk.Action.copy(alpha = 0.5f),
                hoverBg = Color(0xFFFFF4EA),
                height = 36.dp,
                radius = 10.dp,
            )
            SolidButton(
                text = when {
                    busy -> "Signing…"
                    stamps > 0 -> "Sign Document (${stamps + 1} signatures)"
                    else -> "Sign Document"
                },
                onClick = {
                    val placement = overlay?.let { layout?.placement(it) }
                    if (placement != null) onEvent(SignerEvent.Sign(placement))
                },
                enabled = ready && !busy,
                loading = busy,
                height = 36.dp,
                radius = 10.dp,
            )
        },
    ) {
        if (current == null) return@DmModal
        Column(modifier = Modifier.fillMaxSize()) {
            Toolbar(current, onEvent)
            Box(modifier = Modifier.fillMaxWidth().weight(1f).background(Color(0xFFECEAE4))) {
                when {
                    current.loading -> DocumentMessage {
                        ZillitSpinner(size = 16.dp, color = PreviewInk.Action)
                        ZillitText(text = "Preparing document…", style = DmType.sans(13.sp), color = pv.muted)
                    }
                    current.failed -> DocumentMessage {
                        ZillitText(
                            text = "Couldn’t load this document for signing.",
                            style = DmType.sans(13.sp),
                            color = pv.muted,
                        )
                        OutlineButton(
                            text = "Retry",
                            onClick = { onEvent(SignerEvent.Retry) },
                            icon = ZillitIcons.Reload,
                            height = 32.dp,
                            radius = 8.dp,
                        )
                    }
                    else -> PageStack(
                        signer = current,
                        signature = signature,
                        overlay = overlay,
                        onOverlay = { overlay = it },
                        onLayout = { layout = it },
                        onEvent = onEvent,
                    )
                }
            }
        }
    }
}

@Composable
private fun Toolbar(signer: SignerState, onEvent: (DealMemoEvent) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ZillitText(
            text = "Drag your signature to where you want to sign — the whole document scrolls below. Use “Add " +
                "another signature” to sign on more pages.",
            style = DmType.sans(12.sp),
            color = pv.muted,
            modifier = Modifier.weight(1f),
        )
        if (signer.pages.isNotEmpty()) {
            ZillitText(
                text = "${signer.pages.size} page${if (signer.pages.size == 1) "" else "s"}",
                style = DmType.sans(12.sp, FontWeight.SemiBold),
                color = pv.muted,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            ZoomButton(DmIcons.Minus, enabled = !signer.busy && signer.zoom > SignerState.ZOOM_MIN) {
                onEvent(SignerEvent.Zoom(-SignerState.ZOOM_STEP))
            }
            ZillitText(
                text = "${(signer.zoom * PERCENT).roundToInt()}%",
                style = DmType.sans(11.5.sp, FontWeight.SemiBold),
                color = pv.body,
                modifier = Modifier.width(40.dp),
                textAlign = TextAlign.Center,
            )
            ZoomButton(ZillitIcons.Add, enabled = !signer.busy && signer.zoom < SignerState.ZOOM_MAX) {
                onEvent(SignerEvent.Zoom(SignerState.ZOOM_STEP))
            }
        }
        Row(
            modifier = Modifier.clickable(enabled = !signer.busy) { onEvent(SignerEvent.ChangeSignature) }
                .pointerHoverIcon(PointerIcon.Hand),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            ZillitIcon(ZillitIcons.Edit, size = 10.dp, tint = PreviewInk.Action)
            ZillitText(
                text = "Change signature",
                style = DmType.sans(12.sp, FontWeight.SemiBold),
                color = PreviewInk.Action,
            )
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(pv.chipBorder))
}

@Composable
private fun ZoomButton(icon: ImageVector, enabled: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(7.dp)
    Box(
        modifier = Modifier
            .alpha(if (enabled) 1f else DISABLED)
            .size(24.dp)
            .clip(shape)
            .border(1.dp, pv.chipBorder, shape)
            .clickable(enabled = enabled, onClick = onClick)
            .pointerHoverIcon(if (enabled) PointerIcon.Hand else PointerIcon.Default),
        contentAlignment = Alignment.Center,
    ) {
        ZillitIcon(icon, size = 12.dp, tint = pv.body)
    }
}

@Composable
private fun DocumentMessage(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) { content() }
}

/**
 * The document, every page stacked at the zoomed width (the base is frozen
 * when the signer opens), the stamps already placed, and the live signature.
 */
@Composable
private fun PageStack(
    signer: SignerState,
    signature: CapturedSignature?,
    overlay: Overlay?,
    onOverlay: (Overlay?) -> Unit,
    onLayout: (StackLayout) -> Unit,
    onEvent: (DealMemoEvent) -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val available = maxWidth.value
        val base = remember { max(BASE_MIN, min(BASE_MAX, available - BASE_INSET)) }
        val pageWidth = max(PAGE_MIN, (base * signer.zoom).roundToInt().toFloat())
        val heights = signer.pages.map { pageWidth * it.aspect }
        val tops = heights.runningFold(0f) { top, height -> top + height + PAGE_GAP }.dropLast(1)
        val layout = remember(pageWidth, signer.pages) { StackLayout(pageWidth, tops, heights) }
        LaunchedEffect(layout) { onLayout(layout) }
        val vertical = rememberScrollState()
        var lastWidth by remember { mutableFloatStateOf(pageWidth) }
        // Zoom rescales the live signature with the pages under it.
        LaunchedEffect(pageWidth) {
            val ratio = pageWidth / lastWidth
            if (ratio != 1f) overlay?.let { onOverlay(Overlay(it.x * ratio, it.y * ratio, it.w * ratio, it.h * ratio)) }
            lastWidth = pageWidth
        }
        // A fresh signature starts at the end of the last page, and the view follows it there.
        LaunchedEffect(signer.placementEpoch, signature, signer.pages) {
            if (signature != null && signer.pages.isNotEmpty()) {
                val height = max(MIN_OVERLAY_HEIGHT, (START_WIDTH * signature.aspect).roundToInt().toFloat())
                onOverlay(Overlay(START_X, max(EDGE_GAP, layout.height - height - END_GAP), START_WIDTH, height))
                vertical.scrollTo(vertical.maxValue)
            }
        }
        val viewport = maxWidth
        Box(modifier = Modifier.fillMaxSize().verticalScroll(vertical).horizontalScroll(rememberScrollState())) {
            Box(modifier = Modifier.widthIn(min = viewport).padding(16.dp), contentAlignment = Alignment.TopCenter) {
            Box(modifier = Modifier.width(pageWidth.dp).height(layout.height.dp)) {
                signer.pages.forEachIndexed { index, page ->
                    Image(
                        bitmap = page.image,
                        contentDescription = null,
                        contentScale = ContentScale.FillBounds,
                        modifier = Modifier
                            .offset(y = tops[index].dp)
                            .size(pageWidth.dp, heights[index].dp)
                            .shadow(6.dp, RoundedCornerShape(1.dp))
                            .background(Color.White),
                    )
                }
                if (signature != null) {
                    signer.stamps.forEachIndexed { index, stamp ->
                        StampView(
                            stamp,
                            layout,
                            signature,
                            enabled = !signer.busy,
                            onRemove = { onEvent(SignerEvent.RemoveStamp(index)) },
                        )
                    }
                    overlay?.let { box ->
                        LiveSignature(box, layout, signature, enabled = !signer.busy, onChange = onOverlay)
                    }
                }
            }
            }
        }
    }
}

@Composable
private fun StampView(
    stamp: DealPlacement,
    layout: StackLayout,
    signature: CapturedSignature,
    enabled: Boolean,
    onRemove: () -> Unit,
) {
    val top = layout.tops.getOrNull(stamp.pageIndex) ?: return
    val pageHeight = layout.heights[stamp.pageIndex]
    val width = (stamp.fw * layout.pageWidth).toFloat()
    val height = width * signature.aspect
    val (source, hovered) = rememberHover()
    Box(
        modifier = Modifier
            .offset(x = (stamp.fx * layout.pageWidth).toFloat().dp, y = (top + stamp.fy * pageHeight).toFloat().dp)
            .size(width.dp, height.dp)
            .hoverable(source),
    ) {
        Image(
            bitmap = signature.image,
            contentDescription = "Placed signature",
            contentScale = ContentScale.FillBounds,
            modifier = Modifier.fillMaxSize(),
        )
        if (hovered && enabled) {
            MaybeTooltip("Remove this signature") {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 8.dp, y = (-8).dp)
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(PreviewInk.RedHover)
                        .clickable(onClick = onRemove)
                        .pointerHoverIcon(PointerIcon.Hand),
                    contentAlignment = Alignment.Center,
                ) { ZillitIcon(ZillitIcons.Close, size = 10.dp, tint = Color.White) }
            }
        }
    }
}

/** The signature being placed: drag to move, the corner to resize — its aspect never changes. */
@Composable
private fun LiveSignature(
    box: Overlay,
    layout: StackLayout,
    signature: CapturedSignature,
    enabled: Boolean,
    onChange: (Overlay) -> Unit,
) {
    val density = LocalDensity.current.density
    val latest = remember { mutableStateOf(box) }
    latest.value = box
    Box(
        modifier = Modifier
            .offset(x = box.x.dp, y = box.y.dp)
            .size(box.w.dp, box.h.dp)
            .background(PreviewInk.Action.copy(alpha = 0.06f))
            .dashedBorder(PreviewInk.Action, 2.dp)
            .pointerHoverIcon(PointerIcon.Crosshair)
            .pointerInput(enabled, layout) {
                if (!enabled) return@pointerInput
                detectDragGestures { change, drag ->
                    change.consume()
                    val current = latest.value
                    val next = current.copy(
                        x = (current.x + drag.x / density).coerceIn(0f, max(0f, layout.pageWidth - current.w)),
                        y = (current.y + drag.y / density).coerceIn(0f, max(0f, layout.height - current.h)),
                    )
                    latest.value = next
                    onChange(next)
                }
            },
    ) {
        Image(
            bitmap = signature.image,
            contentDescription = "Your signature",
            contentScale = ContentScale.FillBounds,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .offset(x = 5.dp, y = 5.dp)
                .size(12.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(PreviewInk.Action)
                .pointerHoverIcon(PointerIcon.Hand)
                .pointerInput(enabled, layout) {
                    if (!enabled) return@pointerInput
                    detectDragGestures { change, drag ->
                        change.consume()
                        val current = latest.value
                        val width = (current.w + drag.x / density)
                            .coerceIn(MIN_WIDTH, max(MIN_WIDTH, layout.pageWidth - current.x))
                        val next = current.copy(w = width, h = width * signature.aspect)
                        latest.value = next
                        onChange(next)
                    }
                },
        )
    }
}

private const val BASE_MIN = 640f
private const val BASE_MAX = 1000f
private const val BASE_INSET = 240f
private const val PAGE_MIN = 320f
private const val PAGE_GAP = 12f
private const val START_WIDTH = 200f
private const val START_X = 40f
private const val MIN_OVERLAY_HEIGHT = 24f
private const val EDGE_GAP = 16f
private const val END_GAP = 24f
private const val STAMP_GAP = 12f
private const val MIN_WIDTH = 60f
private const val PERCENT = 100
private const val DISABLED = 0.45f
