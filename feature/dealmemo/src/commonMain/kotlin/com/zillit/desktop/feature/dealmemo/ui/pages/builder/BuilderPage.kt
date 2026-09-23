package com.zillit.desktop.feature.dealmemo.ui.pages.builder

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitScrollColumn
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.dealmemo.ui.BuilderEvent
import com.zillit.desktop.feature.dealmemo.ui.DealMemoEvent
import com.zillit.desktop.feature.dealmemo.ui.DealMemoUiState
import com.zillit.desktop.feature.dealmemo.ui.builder.AutosaveStatus
import com.zillit.desktop.feature.dealmemo.ui.builder.BuilderSection
import com.zillit.desktop.feature.dealmemo.ui.builder.BuilderState
import com.zillit.desktop.feature.dealmemo.ui.components.DmType
import com.zillit.desktop.feature.dealmemo.ui.components.rememberHover
import kotlin.time.Instant
import kotlinx.coroutines.delay
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * The one-page builder (`DMTemplateBuilderPage.jsx`): a frosted header with
 * the autosave readout and the page's actions, then one memo card holding
 * every section — read-only until its Edit opens it on a deal, always open on
 * a setup.
 */
@Composable
fun BuilderPage(state: DealMemoUiState, onEvent: (DealMemoEvent) -> Unit) {
    val builder = state.builder ?: return
    val mode = builder.mode
    ProvideDealLabels(mode.deal) {
        Box(Modifier.fillMaxSize().background(bp.page)) {
            Column(Modifier.fillMaxSize()) {
                if (!mode.embedded) BuilderHeader(builder, onEvent)
                BuilderBody(state, builder, onEvent, Modifier.weight(1f))
            }
            if (mode.embedded) {
                Box(Modifier.fillMaxWidth().align(Alignment.TopStart)) { BuilderHeader(builder, onEvent) }
            }
            BuilderDialogs(state, builder, onEvent)
        }
    }
}

@Composable
private fun BuilderHeader(builder: BuilderState, onEvent: (DealMemoEvent) -> Unit) {
    val p = bp
    val mode = builder.mode
    Column(Modifier.fillMaxWidth().background(p.header)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (!mode.embedded) {
                BackButton { onEvent(BuilderEvent.Back) }
                Crumb(builder, onEvent)
            }
            Spacer(Modifier.weight(1f))
            AutosaveReadout(builder)
            if (mode.deal) DealActions(builder, onEvent) else SetupAction(builder, onEvent)
        }
        Rule(p.hairline)
    }
}

@Composable
private fun BackButton(onClick: () -> Unit) {
    val p = bp
    val (source, hovered) = rememberHover()
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(shape)
            .background(if (hovered) p.chipHover else p.chipBg)
            .border(1.dp, p.hairline, shape)
            .hoverable(source)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand),
        contentAlignment = Alignment.Center,
    ) { ZillitIcon(ZillitIcons.ChevronLeft, size = 13.dp, tint = p.ink2) }
}

@Composable
private fun Crumb(builder: BuilderState, onEvent: (DealMemoEvent) -> Unit) {
    val p = bp
    val mode = builder.mode
    val (source, hovered) = rememberHover()
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ZillitText(
            text = str(S.dm_hub_eyebrow),
            style = DmType.sans(11.sp, FontWeight.Bold, 0.08.em),
            color = p.cta,
            modifier = Modifier
                .alpha(if (hovered) 0.8f else 1f)
                .hoverable(source)
                .clickable(interactionSource = source, indication = null) { onEvent(BuilderEvent.Back) }
                .pointerHoverIcon(PointerIcon.Hand),
        )
        ZillitText(text = "/", style = DmType.sans(12.5.sp), color = p.placeholder)
        val crumb = when {
            mode.deal -> builder.dealReference ?: str(S.dm_quick_new_title)
            mode.templateId != null -> str(S.desktop_dm_edit_deal_setup)
            else -> str(S.desktop_dm_new_deal_setup)
        }
        ZillitText(
            text = buildAnnotatedString {
                append(crumb)
                if (mode.deal && builder.dealReference != null) {
                    withStyle(SpanStyle(fontSize = 10.5.sp, fontWeight = FontWeight.Normal, color = p.muted)) {
                        append("  · ${builder.form.text("fullLegalName").trim().ifEmpty { "Draft" }}")
                    }
                }
            },
            style = DmType.sans(12.5.sp, FontWeight.SemiBold),
            color = p.ink2,
            maxLines = 1,
        )
    }
}

/** `Saving…`, `Unsaved changes`, `Not saved — will retry`, `Saved 14:05` — nothing while idle. */
@Composable
private fun AutosaveReadout(builder: BuilderState) {
    val p = bp
    val autosave = builder.autosave
    val text = when (autosave.status) {
        AutosaveStatus.Saving -> str(S.dm_nda_saving)
        AutosaveStatus.Pending -> str(S.dm_nda_unsaved)
        AutosaveStatus.Error -> str(S.desktop_dm_not_saved_will_retry)
        AutosaveStatus.Saved -> autosave.savedAt?.let { at ->
            val time = Instant.fromEpochMilliseconds(at).toLocalDateTime(TimeZone.currentSystemDefault())
            str(
                S.dm_quick_status_saved,
                "${time.hour.toString().padStart(2, '0')}:${time.minute.toString().padStart(2, '0')}",
            )
        }
        AutosaveStatus.Idle -> null
    } ?: return
    ZillitText(
        text = text,
        style = DmType.sans(11.5.sp, FontWeight.SemiBold),
        color = if (autosave.status == AutosaveStatus.Error) p.autosaveError else p.muted,
        maxLines = 1,
        modifier = Modifier.padding(end = 4.dp),
    )
}

@Composable
private fun DealActions(builder: BuilderState, onEvent: (DealMemoEvent) -> Unit) {
    val p = bp
    HeaderButton(
        text = when {
            builder.saving -> str(S.dm_nda_saving)
            builder.dealId != null -> str(S.dm_save)
            else -> str(S.dm_quick_save_draft)
        },
        background = p.chipBg,
        hover = p.chipHover,
        ink = p.ink2,
        border = p.hairline,
        enabled = !builder.saving && !builder.submitting,
        onClick = { onEvent(BuilderEvent.Save) },
    )
    HeaderButton(
        text = if (builder.submitting) str(S.dm_quick_issuing) else str(S.dm_quick_issue),
        background = p.green,
        hover = p.greenHover,
        ink = Color.White,
        enabled = !builder.submitting && !builder.saving,
        onClick = { onEvent(BuilderEvent.Issue) },
    )
}

/** Save Setup on a new setup; Update Setup on a saved one, once something changed. */
@Composable
private fun SetupAction(builder: BuilderState, onEvent: (DealMemoEvent) -> Unit) {
    val p = bp
    val editing = builder.mode.templateId != null
    if (editing && builder.dirtyTick == 0 && !builder.savingTemplate) return
    HeaderButton(
        text = when {
            editing && builder.savingTemplate -> str(S.desktop_dm_updating)
            editing -> str(S.dm_builder_update)
            builder.savingTemplate -> str(S.dm_nda_saving)
            else -> str(S.dm_builder_save)
        },
        background = p.cta,
        hover = p.ctaHover,
        ink = Color.White,
        enabled = !builder.savingTemplate,
        onClick = { onEvent(BuilderEvent.SaveSetup) },
    )
}

@Composable
private fun HeaderButton(
    text: String,
    background: Color,
    hover: Color,
    ink: Color,
    enabled: Boolean,
    onClick: () -> Unit,
    border: Color? = null,
) {
    val (source, hovered) = rememberHover()
    val shape = RoundedCornerShape(6.dp)
    Box(
        modifier = Modifier
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .height(32.dp)
            .clip(shape)
            .background(if (hovered && enabled) hover else background)
            .then(if (border != null) Modifier.border(1.dp, border, shape) else Modifier)
            .hoverable(source)
            .then(
                if (enabled) {
                    Modifier.clickable(interactionSource = source, indication = null, onClick = onClick)
                } else {
                    Modifier
                },
            )
            .pointerHoverIcon(if (enabled) PointerIcon.Hand else PointerIcon.Default)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) { ZillitText(text = text, style = DmType.sans(12.5.sp, FontWeight.SemiBold), color = ink, maxLines = 1) }
}

// -- body ----------------------------------------------------------------------------------------

@Composable
private fun BuilderBody(
    state: DealMemoUiState,
    builder: BuilderState,
    onEvent: (DealMemoEvent) -> Unit,
    modifier: Modifier,
) {
    val mode = builder.mode
    val scroll = rememberScrollState()
    val sectionCoordinates = remember(builder.visit) { mutableStateMapOf<Int, LayoutCoordinates>() }
    var viewport by remember { mutableStateOf<LayoutCoordinates?>(null) }
    ScrollToSection(
        builder,
        scroll,
        { id -> viewport?.let { v -> sectionCoordinates[id]?.let { v.localPositionOf(it, Offset.Zero).y } } },
        onEvent,
    )
    val skeleton =
        (mode.setup && (builder.editLoading || !state.projectSettings.loaded)) || (mode.deal && builder.editLoading)
    ZillitScrollColumn(
        modifier = modifier.fillMaxWidth().onGloballyPositioned { viewport = it },
        state = scroll,
        contentPadding = PaddingValues(
            start = 36.dp,
            end = 36.dp,
            top = if (mode.embedded) 77.dp else 20.dp,
            bottom = 32.dp,
        ),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(Modifier.widthIn(max = CONTENT_MAX).fillMaxWidth()) {
            if (skeleton) {
                BuilderSkeleton(intro = mode.setup && mode.templateId == null)
            } else {
                if (mode.deal && mode.dealGroup != null) SetupPickerBanner(state, builder, onEvent)
                MemoCard(state, builder, { id, coordinates -> sectionCoordinates[id] = coordinates }, onEvent)
            }
        }
    }
}

/** Issue and "Now" open a section; the page scrolls to it once the layout has settled. */
@Composable
private fun ScrollToSection(
    builder: BuilderState,
    scroll: ScrollState,
    offsetOf: (Int) -> Float?,
    onEvent: (DealMemoEvent) -> Unit,
) {
    val request = builder.scroll ?: return
    LaunchedEffect(request.nonce) {
        delay(SCROLL_SETTLE_MILLIS)
        offsetOf(request.sectionId)?.let { relative ->
            scroll.animateScrollTo((scroll.value + relative.toInt() - SCROLL_MARGIN).coerceIn(0, scroll.maxValue))
        }
        onEvent(BuilderEvent.ScrollDone)
    }
}

@Composable
private fun MemoCard(
    state: DealMemoUiState,
    builder: BuilderState,
    onPositioned: (Int, LayoutCoordinates) -> Unit,
    onEvent: (DealMemoEvent) -> Unit,
) {
    val p = bp
    val mode = builder.mode
    val shape = RoundedCornerShape(12.dp)
    val sections = builder.sections(state.viewer.isAccountant)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(p.card)
            .border(1.dp, p.cardBorder, shape)
            .padding(vertical = 20.dp),
    ) {
        if (mode.setup && mode.templateId == null) IntroBand()
        sections.forEachIndexed { index, section ->
            val firstOfGroup =
                section.group != null && sections.firstOrNull { it.group == section.group }?.id == section.id
            if (firstOfGroup) GroupBand(section.group.orEmpty())
            SectionFrame(
                state = state,
                builder = builder,
                section = section,
                divided = index > 0 && !firstOfGroup,
                onPositioned = { onPositioned(section.id, it) },
                onEvent = onEvent,
            )
        }
    }
}

/** The create-setup page's explanation, full bleed at the top of the card. */
@Composable
private fun IntroBand() {
    val p = bp
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 20.dp)
            .background(p.page)
            .padding(horizontal = 24.dp, vertical = 16.dp),
    ) {
        ZillitText(
            text = str(S.dm_builder_intro_title),
            style = DmType.sans(14.sp, FontWeight.Bold),
            color = p.title,
        )
        ZillitText(
            text = str(S.dm_builder_intro_body),
            style = DmType.sans(14.sp).copy(lineHeight = 22.sp),
            color = p.ink2,
            modifier = Modifier.padding(top = 4.dp),
        )
        Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Bullet(
                buildAnnotatedString {
                    append(str(S.desktop_dm_keep_one_for) + " ")
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = p.title)) {
                        append(str(S.dm_label_union))
                    }
                    append(" " + str(S.desktop_dm_and_one_for) + " ")
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = p.title)) {
                        append(str(S.dm_create_non_union))
                    }
                    append(str(S.desktop_dm_setup_intro_tail))
                },
            )
            Bullet(
                buildAnnotatedString {
                    append(
                        str(S.dm_builder_intro_2),
                    )
                },
            )
        }
    }
}

@Composable
private fun Bullet(text: AnnotatedString) {
    val p = bp
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ZillitText(text = "•", style = DmType.sans(14.sp, FontWeight.Bold), color = Color(0xFFEA7A0E))
        ZillitText(
            text = text,
            style = DmType.sans(14.sp).copy(lineHeight = 22.sp),
            color = p.ink2,
            modifier = Modifier.weight(1f),
        )
    }
}

/** A group masthead: the tinted full-bleed rule, the amber title and its note. */
@Composable
private fun GroupBand(group: String) {
    val p = bp
    val (title, note) = when (group) {
        "nonunion" ->
            str(S.dm_builder_band_nonunion) to str(S.dm_builder_band_nonunion_note)
        else -> str(S.dm_builder_band_global) to str(S.desktop_dm_the_sections_below_are_the_projects_global)
    }
    Spacer(Modifier.height(40.dp))
    Box(Modifier.fillMaxWidth().height(7.dp).background(p.band)) {
        Rule(p.divider)
        Rule(p.divider, Modifier.align(Alignment.BottomStart))
    }
    Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 4.dp)) {
        ZillitText(
            text = title.uppercase(),
            style = DmType.display(13.5.sp, FontWeight.Bold, 0.12.em),
            color = p.bandTitle,
        )
        val shape = RoundedCornerShape(10.dp)
        Row(
            modifier = Modifier
                .padding(top = 12.dp)
                .fillMaxWidth()
                .clip(shape)
                .background(p.noteBg)
                .border(1.dp, p.noteBorder, shape)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ZillitText(
                text = str(S.note),
                style = DmType.sans(14.sp, FontWeight.Bold).copy(lineHeight = 21.sp),
                color = p.noteInk,
            )
            ZillitText(
                text = note,
                style = DmType.sans(14.sp).copy(lineHeight = 21.sp),
                color = p.noteInk,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * One section: its label with Show/Hide and Edit/Done on a deal, the
 * Required banner while Issue has it flagged, then its read-only view or its
 * editor. A flag only washes the background — the geometry never moves.
 */
@Suppress("CyclomaticComplexMethod", "LongMethod")
@Composable
private fun SectionFrame(
    state: DealMemoUiState,
    builder: BuilderState,
    section: BuilderSection,
    divided: Boolean,
    onPositioned: (LayoutCoordinates) -> Unit,
    onEvent: (DealMemoEvent) -> Unit,
) {
    val p = bp
    val mode = builder.mode
    val flagged = if (mode.deal) builder.issueErrors[section.id] else null
    val editing = builder.editingSection == section.id
    val collapsed = mode.deal && section.collapsible && section.id !in builder.expanded
    val wash by animateColorAsState(if (flagged != null) p.flagWash else Color.Transparent, tween(WASH_MILLIS))
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .onGloballyPositioned(onPositioned)
            .background(wash),
    ) {
        if (divided) Rule(p.divider)
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ZillitText(
                    text = section.name.uppercase(),
                    style = DmType.display(11.sp, FontWeight.Bold, 0.05.em),
                    color = if (flagged != null) p.flagLabel else p.sectionLabel,
                    modifier = Modifier.weight(1f).padding(bottom = 8.dp),
                )
                if (mode.deal && section.collapsible) {
                    EditChip(if (collapsed) str(S.dm_quick_show) else str(S.dm_quick_hide), ChipTone.Plain) {
                        onEvent(BuilderEvent.ToggleCollapse(section.id))
                    }
                }
                if (mode.deal && !section.noEdit) {
                    EditChip(
                        text = if (editing) str(S.dm_quick_done) else str(S.dm_nda_edit),
                        tone = when {
                            editing -> ChipTone.Editing
                            flagged != null -> ChipTone.Flagged
                            else -> ChipTone.Plain
                        },
                    ) { onEvent(BuilderEvent.ToggleEdit(section.id)) }
                }
            }
            AnimatedVisibility(
                visible = flagged != null,
                enter = fadeIn(tween(BANNER_MILLIS)),
                exit = fadeOut(tween(BANNER_MILLIS)),
            ) {
                RequiredBanner(flagged.orEmpty())
            }
            if (mode.deal && !editing && !collapsed) section.note?.let { note ->
                ZillitText(
                    text = note,
                    style = DmType.sans(13.sp, FontWeight.SemiBold).copy(lineHeight = 19.sp),
                    color = p.noteRed,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
            }
            when {
                collapsed -> Unit
                mode.deal && !editing -> BuilderReadOnlyView(state, builder, section.id)
                else -> BuilderSectionEditor(state, builder, section.id, onEvent)
            }
        }
    }
}

private enum class ChipTone { Plain, Editing, Flagged }

@Composable
private fun EditChip(text: String, tone: ChipTone, onClick: () -> Unit) {
    val p = bp
    val (source, hovered) = rememberHover()
    val shape = RoundedCornerShape(7.dp)
    val (background, ink) = when (tone) {
        ChipTone.Editing -> (if (hovered) p.greenHover else p.green) to Color.White
        ChipTone.Flagged -> (if (hovered) p.redHover else p.red) to Color.White
        ChipTone.Plain -> (if (hovered) p.chipHover else p.chipBg) to p.chipInk
    }
    Box(
        modifier = Modifier
            .padding(bottom = 8.dp)
            .clip(shape)
            .background(background)
            .then(if (tone == ChipTone.Plain) Modifier.border(1.dp, p.chipBorder, shape) else Modifier)
            .hoverable(source)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = if (tone == ChipTone.Plain) 8.dp else 10.dp, vertical = 2.dp),
    ) { ZillitText(text = text, style = DmType.sans(11.sp, FontWeight.Bold), color = ink, maxLines = 1) }
}

@Composable
private fun RequiredBanner(fields: List<String>) {
    val p = bp
    val shape = RoundedCornerShape(6.dp)
    Row(
        modifier = Modifier
            .padding(bottom = 12.dp)
            .fillMaxWidth()
            .clip(shape)
            .background(p.redSoft)
            .border(1.dp, p.redBorder, shape)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ZillitText(
            text = str(S.desktop_dm_required_colon),
            style = DmType.sans(12.sp, FontWeight.Bold).copy(lineHeight = 18.sp),
            color = p.redInk,
        )
        ZillitText(
            text = if (fields.isEmpty()) str(S.desktop_dm_complete_this_section) else fields.joinToString(", "),
            style = DmType.sans(12.sp).copy(lineHeight = 18.sp),
            color = p.redInk,
            modifier = Modifier.weight(1f),
        )
    }
}

/** The page's pulsing placeholder while a saved deal or setup loads. */
@Composable
private fun BuilderSkeleton(intro: Boolean) {
    val p = bp
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(p.card)
            .border(1.dp, p.cardBorder, shape)
            .padding(vertical = 20.dp),
    ) {
        if (intro) {
            Column(
                Modifier.fillMaxWidth().background(p.page).padding(horizontal = 24.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SkeletonBar(0.42f, 13)
                SkeletonBar(0.72f, 10)
                SkeletonBar(0.64f, 10)
            }
        }
        repeat(3) { index ->
            if (index > 0) Rule(p.hairline)
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                SkeletonBar(0.2f, 10)
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    repeat(2) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            SkeletonBar(0.38f, 9)
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .height(36.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(p.tile),
                            )
                        }
                    }
                }
            }
        }
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { ZillitSpinner(size = 16.dp, color = p.cta) }
    }
}

@Composable
private fun SkeletonBar(fraction: Float, height: Int) {
    Box(Modifier.fillMaxWidth(fraction).height(height.dp).clip(RoundedCornerShape(4.dp)).background(bp.skeleton))
}

internal val CONTENT_MAX = 896.dp
private const val DISABLED_ALPHA = 0.5f
private const val WASH_MILLIS = 300
private const val BANNER_MILLIS = 250
private const val SCROLL_SETTLE_MILLIS = 120L
private const val SCROLL_MARGIN = 12
