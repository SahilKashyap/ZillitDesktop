package com.zillit.desktop.feature.crewlist.ui.dialogs

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitSwitch
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTooltip
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.crewlist.domain.HeaderLayout
import com.zillit.desktop.feature.crewlist.domain.LayoutHistory
import com.zillit.desktop.feature.crewlist.domain.LogoAlign
import com.zillit.desktop.feature.crewlist.ui.CanvasDocument
import com.zillit.desktop.feature.crewlist.ui.CanvasMode
import com.zillit.desktop.feature.crewlist.ui.CrewListEvent
import com.zillit.desktop.feature.crewlist.ui.CustomiseState
import com.zillit.desktop.feature.crewlist.ui.components.CrewCopy
import com.zillit.desktop.feature.crewlist.ui.components.CrewIcons
import com.zillit.desktop.feature.crewlist.ui.components.CrewSlider
import com.zillit.desktop.feature.crewlist.ui.components.crewPalette
import com.zillit.desktop.feature.crewlist.ui.components.onBackdropTap
import com.zillit.desktop.feature.crewlist.ui.components.swallowPresses

/**
 * The host's browser pane for a canvas document. [obscured] is true while
 * something is drawn over the pane: the browser is a heavyweight surface that
 * would paint over any dialog, so the host takes it down until it is clear.
 * [placeholder] is drawn in the browser's place until its first page has
 * painted — a starting browser is a blank white box for seconds.
 */
typealias CrewCanvas = @Composable (
    document: CanvasDocument?,
    obscured: Boolean,
    onMessage: (String) -> Unit,
    placeholder: @Composable () -> Unit,
    modifier: Modifier,
) -> Unit

/** What the Customise & Preview dialog needs beyond its own state. */
internal class CustomiseModel(
    val customise: CustomiseState,
    val layout: LayoutHistory,
    val hideInternalLines: Boolean,
    val canDesign: Boolean,
    val isAdmin: Boolean,
    val obscured: Boolean,
    val generating: Boolean,
)

/**
 * Customise & Preview (ZL-19725), laid out as the web's editor: a compact
 * Customise sidebar beside the document canvas. Design is the arranger — the
 * backend's render with grips on its three header sections; Preview is the
 * render the PDF will be. Only those who may shape the document get Design and
 * the sidebar.
 */
@Composable
internal fun CustomiseDialog(
    model: CustomiseModel?,
    copy: CrewCopy,
    canvas: CrewCanvas?,
    onEvent: (CrewListEvent) -> Unit,
    onGenerate: () -> Unit,
) {
    val held = remember { arrayOfNulls<CustomiseModel>(1) }
    model?.let { held[0] = it }
    AnimatedVisibility(visible = model != null, enter = fadeIn(tween(ENTER_MS)), exit = fadeOut(tween(EXIT_MS))) {
        val shown = model ?: held[0] ?: return@AnimatedVisibility
        Box(
            Modifier
                .fillMaxSize()
                .background(ZillitTheme.colors.scrim.copy(alpha = SCRIM))
                .onBackdropTap { onEvent(CrewListEvent.Design.Close) },
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth(WIDTH_SHARE)
                    .fillMaxHeight(HEIGHT_SHARE)
                    .animateEnterExit(
                        enter = scaleIn(initialScale = 0.98f, animationSpec = tween(ENTER_MS)),
                        exit = scaleOut(targetScale = 0.98f, animationSpec = tween(EXIT_MS)),
                    )
                    .shadow(20.dp, RoundedCornerShape(14.dp))
                    .clip(RoundedCornerShape(14.dp))
                    .background(ZillitTheme.colors.surface)
                    .swallowPresses(),
            ) {
                DialogBar(shown, copy, onEvent, onGenerate)
                Box(Modifier.fillMaxWidth().height(1.dp).background(ZillitTheme.colors.border))
                Row(Modifier.fillMaxSize()) {
                    if (shown.canDesign && shown.customise.mode == CanvasMode.Design) {
                        Sidebar(shown, copy, onEvent)
                        Box(Modifier.width(1.dp).fillMaxHeight().background(ZillitTheme.colors.border))
                    }
                    CanvasPane(shown, canvas, onEvent, Modifier.weight(1f).fillMaxHeight())
                }
            }
        }
    }
}

@Composable
private fun DialogBar(model: CustomiseModel, copy: CrewCopy, onEvent: (CrewListEvent) -> Unit, onGenerate: () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(
            modifier = Modifier.align(Alignment.CenterStart),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                Modifier.size(30.dp).clip(RoundedCornerShape(8.dp)).background(ZillitTheme.colors.accentSoft),
                contentAlignment = Alignment.Center,
            ) {
                ZillitIcon(icon = ZillitIcons.Eye, tint = ZillitTheme.colors.accentText, size = 16.dp)
            }
            ZillitText(
                text = copy.t("CustomisePreview", "Customise & Preview"),
                style = ZillitTheme.typography.titleSmall,
                maxLines = 1,
            )
        }
        if (model.canDesign) {
            CanvasSwitch(
                mode = model.customise.mode,
                previewDirty = model.customise.previewDirty,
                copy = copy,
                onSwitch = { onEvent(CrewListEvent.Design.Switch(it)) },
                modifier = Modifier.align(Alignment.Center),
            )
        }
        Row(
            modifier = Modifier.align(Alignment.CenterEnd),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ZillitButton(
                text = copy.t("generate_pdf", "Generate PDF"),
                size = ButtonSize.Small,
                leadingIcon = CrewIcons.Document,
                loading = model.generating,
                onClick = onGenerate,
            )
            ZillitIconButton(
                icon = ZillitIcons.Close,
                contentDescription = copy.t("Close", "Close"),
                onClick = { onEvent(CrewListEvent.Design.Close) },
            )
        }
    }
}

/** Design | Preview on a sunken track; Preview wears an orange dot while its render is out of date. */
@Composable
private fun CanvasSwitch(
    mode: CanvasMode,
    previewDirty: Boolean,
    copy: CrewCopy,
    onSwitch: (CanvasMode) -> Unit,
    modifier: Modifier,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(ZillitTheme.colors.surfaceSunken)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Segment(copy.t("Design", "Design"), ZillitIcons.Edit, mode == CanvasMode.Design, dot = false) {
            onSwitch(CanvasMode.Design)
        }
        Segment(copy.t("Preview", "Preview"), ZillitIcons.Eye, mode == CanvasMode.Preview, dot = previewDirty) {
            onSwitch(CanvasMode.Preview)
        }
    }
}

@Composable
private fun Segment(label: String, icon: ImageVector, picked: Boolean, dot: Boolean, onClick: () -> Unit) {
    val colors = ZillitTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val fill by animateColorAsState(if (picked) colors.surface else Color.Transparent, label = "segment")
    Row(
        modifier = Modifier
            .then(if (picked) Modifier.shadow(2.dp, RoundedCornerShape(8.dp)) else Modifier)
            .clip(RoundedCornerShape(8.dp))
            .background(fill)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val ink = when {
            picked -> colors.accentText
            hovered -> colors.textPrimary
            else -> colors.textSecondary
        }
        ZillitIcon(icon = icon, tint = ink, size = 14.dp)
        ZillitText(
            text = label,
            style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
            color = ink,
            maxLines = 1,
        )
        if (dot) Box(Modifier.size(6.dp).clip(CircleShape).background(crewPalette().grip))
    }
}

@Composable
private fun Sidebar(model: CustomiseModel, copy: CrewCopy, onEvent: (CrewListEvent) -> Unit) {
    val colors = ZillitTheme.colors
    Column(
        modifier = Modifier
            .width(SIDEBAR_WIDTH)
            .fillMaxHeight()
            .background(colors.surfaceSunken)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        ZillitText(
            text = copy.t("Customise", "Customise").uppercase(),
            style = ZillitTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.1.em),
            color = colors.textMuted,
        )
        SidebarGroup(copy.t("HeaderLayout", "Header layout")) {
            HistoryButtons(model.layout, copy, onEvent)
        }
        if (model.isAdmin) {
            SidebarGroup(copy.t("CompanyDetails", "Company details")) {
                CompanyCard(copy) { onEvent(CrewListEvent.Admin.OpenCompany) }
            }
        }
        if (model.layout.current.logoHasOwnCell) {
            SidebarGroup(copy.t("LogoPosition", "Logo position")) {
                LogoAlignment(model.layout.current.logo, copy) { onEvent(CrewListEvent.Design.AlignLogo(it)) }
            }
        }
        SidebarGroup("${copy.t("LogoSize", "Logo size")}: ${model.customise.logoSizeDraft}px") {
            LogoSizeSlider(model.customise.logoSizeDraft, onEvent)
        }
        SidebarGroup(copy.t("TableLines", "Table lines")) {
            ZillitSwitch(
                checked = !model.hideInternalLines,
                onCheckedChange = { onEvent(CrewListEvent.Design.ShowInternalLines(it)) },
                label = copy.t("ShowInternalLines", "Show internal lines"),
            )
        }
        SidebarGroup(copy.t("SectionOrder", "Section order")) {
            SectionSteps(copy)
        }
    }
}

/** Undo · Redo · Reset over the letterhead's history. */
@Composable
private fun HistoryButtons(layout: LayoutHistory, copy: CrewCopy, onEvent: (CrewListEvent) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        ZillitTooltip(copy.t("Undo", "Undo")) {
            ZillitIconButton(
                icon = CrewIcons.Undo,
                contentDescription = copy.t("Undo", "Undo"),
                enabled = layout.canUndo,
                onClick = { onEvent(CrewListEvent.Design.Undo) },
            )
        }
        ZillitTooltip(copy.t("Redo", "Redo")) {
            ZillitIconButton(
                icon = CrewIcons.Redo,
                contentDescription = copy.t("Redo", "Redo"),
                enabled = layout.canRedo,
                onClick = { onEvent(CrewListEvent.Design.Redo) },
            )
        }
        ZillitTooltip(copy.t("ResetLayout", "Reset to default")) {
            ZillitButton(
                text = copy.t("Reset", "Reset"),
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
                leadingIcon = ZillitIcons.Reload,
                enabled = layout.current.isCustomised,
                onClick = { onEvent(CrewListEvent.Design.Reset) },
            )
        }
    }
}

@Composable
private fun SidebarGroup(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ZillitText(
            text = title,
            style = ZillitTheme.typography.label.copy(fontSize = 12.sp),
            color = ZillitTheme.colors.textSecondary,
            maxLines = 1,
        )
        content()
    }
}

/** The web's `.cl-company-card`: a full-width row that warms to the accent on hover. */
@Composable
private fun CompanyCard(copy: CrewCopy, onClick: () -> Unit) {
    val colors = ZillitTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val border by animateColorAsState(if (hovered) colors.accent else colors.border, label = "companyCard")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (hovered) colors.accentSoft.copy(alpha = 0.45f) else colors.surface)
            .border(1.dp, border, RoundedCornerShape(10.dp))
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier.size(34.dp).clip(RoundedCornerShape(9.dp)).background(crewPalette().accentTile),
            contentAlignment = Alignment.Center,
        ) {
            ZillitIcon(icon = ZillitIcons.Bank, tint = crewPalette().grip, size = 17.dp)
        }
        Column(Modifier.weight(1f)) {
            ZillitText(
                text = copy.t("EditCompanyDetails", "Edit details"),
                style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 1,
            )
            ZillitText(
                text = copy.t("CompanyDetailsHint", "Logo, name, address & contact"),
                style = ZillitTheme.typography.labelSmall,
                color = colors.textMuted,
                maxLines = 1,
            )
        }
        val chevron = if (hovered) crewPalette().grip else colors.textMuted
        ZillitIcon(icon = ZillitIcons.ChevronRight, tint = chevron, size = 13.dp)
    }
}

@Composable
private fun LogoAlignment(selected: LogoAlign, copy: CrewCopy, onPick: (LogoAlign) -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(8.dp)).border(1.dp, ZillitTheme.colors.border, RoundedCornerShape(8.dp)),
    ) {
        LogoAlign.entries.forEach { align ->
            val picked = align == selected
            ZillitText(
                text = copy.t(align.label, align.label),
                style = ZillitTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                color = if (picked) ZillitTheme.colors.textOnAccent else ZillitTheme.colors.textSecondary,
                maxLines = 1,
                modifier = Modifier
                    .background(if (picked) ZillitTheme.colors.accent else ZillitTheme.colors.surface)
                    .clickable { onPick(align) }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

/** 60–400 in steps of 5; the label follows the thumb, the layout takes the value on release. */
@Composable
private fun LogoSizeSlider(draft: Int, onEvent: (CrewListEvent) -> Unit) {
    CrewSlider(
        value = draft,
        min = HeaderLayout.MIN_LOGO_SIZE,
        max = HeaderLayout.MAX_LOGO_SIZE,
        step = HeaderLayout.LOGO_SIZE_STEP,
        onChange = { onEvent(CrewListEvent.Design.DragLogoSize(it)) },
        onCommit = { onEvent(CrewListEvent.Design.CommitLogoSize(it)) },
    )
}

/** The three gestures, each beside a picture of what to grab — the grip mirrors the page's. */
@Composable
private fun SectionSteps(copy: CrewCopy) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ZillitText(
            text = copy.t("SectionDragIntro", "Rearrange Title, Logo and Company details however you like:"),
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textMuted,
        )
        Step(
            chip = { Chip(crewPalette().grip) { ZillitIcon(CrewIcons.Grip, tint = Color.White, size = 13.dp) } },
            text = copy.t("SectionDragStepGrab", "Grab a section by this orange grip handle in the preview."),
        )
        Step(
            chip = {
                Chip(ZillitTheme.colors.border) {
                    ZillitIcon(CrewIcons.Swap, tint = ZillitTheme.colors.textSecondary, size = 12.dp)
                }
            },
            text = copy.t(
                "SectionDragStepSwap",
                "Drop it ONTO another section to swap their places (or sit side-by-side).",
            ),
        )
        Step(
            chip = {
                Chip(ZillitTheme.colors.border) {
                    Box(
                        Modifier
                            .width(12.dp)
                            .height(3.dp)
                            .clip(RoundedCornerShape(1.dp))
                            .background(ZillitTheme.colors.textMuted),
                    )
                }
            },
            text = copy.t("SectionDragStepRow", "Drop it in the gap between rows to give it its own full-width row."),
        )
    }
}

@Composable
private fun Step(chip: @Composable () -> Unit, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        chip()
        ZillitText(text = text, style = ZillitTheme.typography.bodySmall, color = ZillitTheme.colors.textMuted)
    }
}

@Composable
private fun Chip(fill: Color, content: @Composable () -> Unit) {
    Box(
        Modifier.padding(top = 1.dp).size(20.dp).clip(RoundedCornerShape(5.dp)).background(fill),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun CanvasPane(
    model: CustomiseModel,
    canvas: CrewCanvas?,
    onEvent: (CrewListEvent) -> Unit,
    modifier: Modifier,
) {
    val customise = model.customise
    val document = if (customise.mode == CanvasMode.Design) customise.design else customise.preview
    val loading = customise.isCanvasLoading
    Box(modifier.background(ZillitTheme.colors.surfaceSunken)) {
        when {
            canvas == null -> CanvasNotice(
                "The preview needs the embedded browser, which is not available here.",
                action = null,
            )
            customise.failure != null && document == null -> CanvasNotice(customise.failure) {
                ZillitButton(
                    text = "Try again",
                    variant = ButtonVariant.Secondary,
                    size = ButtonSize.Small,
                    leadingIcon = ZillitIcons.Reload,
                    onClick = { onEvent(CrewListEvent.Design.Retry) },
                )
            }
            else -> {
                canvas(
                    document.takeUnless { loading },
                    model.obscured || loading,
                    { text -> onEvent(CrewListEvent.Design.CanvasMessage(text)) },
                    { DocumentSkeleton() },
                    Modifier.fillMaxSize(),
                )
                // While loading, a document-shaped wait; while covered, a quiet sheet stands in.
                if (loading) DocumentSkeleton() else if (model.obscured) PaperCover()
            }
        }
    }
}

@Composable
private fun PaperCover() {
    Box(Modifier.fillMaxSize().background(crewPalette().paper))
}

/** The shown canvas has nothing to draw yet. */
private val CustomiseState.isCanvasLoading: Boolean
    get() = if (mode == CanvasMode.Design) designLoading || design == null else previewLoading

@Composable
private fun CanvasNotice(text: String, action: (@Composable RowScope.() -> Unit)?) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
    ) {
        ZillitIcon(icon = ZillitIcons.Warning, tint = ZillitTheme.colors.textMuted, size = 22.dp)
        ZillitText(text = text, style = ZillitTheme.typography.bodyMedium, color = ZillitTheme.colors.textSecondary)
        action?.let { Row(content = it) }
    }
}

/**
 * A document-shaped wait — header (logo and company lines), a centred title,
 * then crew rows — so the load reads as "building your document", not a bare
 * spinner, as the web's skeleton does. The page is white whatever the app's
 * theme, so the bars pulse in the page's own grey, not the theme's.
 */
@Composable
private fun DocumentSkeleton() {
    val transition = rememberInfiniteTransition(label = "documentSkeleton")
    val alpha by transition.animateFloat(
        initialValue = SKELETON_MIN_ALPHA,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(SKELETON_PULSE_MS), RepeatMode.Reverse),
        label = "documentSkeletonAlpha",
    )
    val bar: @Composable (Modifier, Dp) -> Unit = { modifier, height ->
        Box(modifier.height(height).alpha(alpha).clip(RoundedCornerShape(4.dp)).background(PAPER_SKELETON))
    }
    Box(Modifier.fillMaxSize().background(crewPalette().paper), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 760.dp).fillMaxWidth().padding(horizontal = 40.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                bar(Modifier.width(150.dp), 110.dp)
                Column(Modifier.weight(1f).padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    listOf(0.72f, 0.58f, 0.64f, 0.5f, 0.6f, 0.46f).forEach { share ->
                        bar(Modifier.fillMaxWidth(share), 12.dp)
                    }
                }
            }
            Box(Modifier.fillMaxWidth().padding(vertical = 18.dp), contentAlignment = Alignment.Center) {
                bar(Modifier.width(200.dp), 30.dp)
            }
            repeat(SKELETON_ROWS) { bar(Modifier.fillMaxWidth(), 16.dp) }
        }
    }
}

/** The web skeleton's grey on its white page. */
private val PAPER_SKELETON = Color(0xFFE6E8EC)
private const val SKELETON_MIN_ALPHA = 0.45f
private const val SKELETON_PULSE_MS = 900
private const val SKELETON_ROWS = 7

private val SIDEBAR_WIDTH = 250.dp
private const val WIDTH_SHARE = 0.94f
private const val HEIGHT_SHARE = 0.92f
private const val SCRIM = 0.5f
private const val ENTER_MS = 220
private const val EXIT_MS = 160
