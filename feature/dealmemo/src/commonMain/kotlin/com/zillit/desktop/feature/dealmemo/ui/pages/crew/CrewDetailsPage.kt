package com.zillit.desktop.feature.dealmemo.ui.pages.crew

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitScrollColumn
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.dealmemo.domain.DealDoc
import com.zillit.desktop.feature.dealmemo.domain.preview.CrewDraft
import com.zillit.desktop.feature.dealmemo.domain.preview.CrewField
import com.zillit.desktop.feature.dealmemo.domain.preview.CrewFormRules
import com.zillit.desktop.feature.dealmemo.domain.preview.CrewRequirements
import com.zillit.desktop.feature.dealmemo.domain.preview.CrewStep
import com.zillit.desktop.feature.dealmemo.domain.preview.isDirtyAgainst
import com.zillit.desktop.feature.dealmemo.domain.preview.withDraft
import com.zillit.desktop.feature.dealmemo.ui.CrewFormEvent
import com.zillit.desktop.feature.dealmemo.ui.DealMemoEvent
import com.zillit.desktop.feature.dealmemo.ui.DealMemoUiState
import com.zillit.desktop.feature.dealmemo.ui.MyDealEvent
import com.zillit.desktop.feature.dealmemo.ui.components.DmButton
import com.zillit.desktop.feature.dealmemo.ui.components.DmButtonStyle
import com.zillit.desktop.feature.dealmemo.ui.components.DmModal
import com.zillit.desktop.feature.dealmemo.ui.components.DmType
import com.zillit.desktop.feature.dealmemo.ui.components.rememberHover
import com.zillit.desktop.feature.dealmemo.ui.pages.EmptyNote
import com.zillit.desktop.feature.dealmemo.ui.pages.LoadingLine
import com.zillit.desktop.feature.dealmemo.ui.preview.CrewFormUi
import com.zillit.desktop.feature.dealmemo.ui.preview.DealPreviewActions

/**
 * "Complete your details" (`/my-deal/complete`, `CrewDetailsEditorPanel.jsx`):
 * the crew member's own details as a stepped form that owns the whole
 * content area — a top bar, the step rail, one scroller, and a footer. The
 * memo follows the draft as it is typed; nothing is saved until Save.
 */
@Composable
fun CrewDetailsPage(state: DealMemoUiState, onEvent: (DealMemoEvent) -> Unit) {
    val preview = state.preview
    val deal = preview?.deal
    val draft = preview?.crewDraft
    val form = preview?.crewForm
    Box(Modifier.fillMaxSize().background(cp.page)) {
        if (deal != null && draft != null && form != null) {
            CrewForm(state, deal, draft, form, onEvent)
        } else {
            Placeholder(state, onEvent)
        }
    }
}

@Composable
private fun Placeholder(state: DealMemoUiState, onEvent: (DealMemoEvent) -> Unit) {
    val mine = state.myDeal
    when {
        mine.failed && mine.deal == null -> Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 64.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ZillitText(text = "Couldn't load your deal memo.", style = DmType.sans(14.sp), color = cp.body)
            Spacer(Modifier.height(12.dp))
            DmButton(
                "Retry",
                onClick = { onEvent(MyDealEvent.Retry) },
                style = DmButtonStyle.SmallSecondary,
                icon = ZillitIcons.Reload,
            )
        }
        mine.loaded && !mine.loading && mine.deal == null -> EmptyNote(
            title = "No deal memo on file",
            body = "You don't have a deal memo for this production yet. The production accountant or the relevant " +
                "HOD will issue one — once it's issued to you, it'll appear here for you to review and send for " +
                "approval.",
        )
        else -> LoadingLine("Loading your deal memo…")
    }
}

@Suppress("LongMethod")
@Composable
private fun CrewForm(
    state: DealMemoUiState,
    deal: DealDoc,
    draft: CrewDraft,
    form: CrewFormUi,
    onEvent: (DealMemoEvent) -> Unit,
) {
    val gate = remember(deal, draft) { deal.withDraft(draft) }
    val steps = remember(deal, draft) { CrewFormRules.steps(deal, draft) }
    // Clamped for drawing when a refetch takes a step away from under the user.
    val index = form.step.coerceIn(0, steps.lastIndex)
    val step = steps[index]
    val errors = remember(deal, draft) { CrewFormRules.formatErrors(deal, draft) }
    // The fieldset shows its PAYE error at once; every other error waits for a blur or a refused action.
    val shown = errors
        .filter { it.field != CrewField.PayeRef && (form.submitAttempted || it.field in form.touched) }
        .associate { it.field to it.message }
    val required = remember(deal, draft) { CrewFormRules.requiredPaths(deal, draft) }
    val anyMarked = remember(deal) { CrewRequirements.markedPaths(deal).isNotEmpty() }
    val dirty = remember(deal, draft) { draft.isDirtyAgainst(deal) }
    val stillEmpty = remember(deal, draft) { CrewFormRules.markedGateableMissing(deal, draft) }
    val context = DealPreviewActions.memoContext(state)
    val model = CrewFormModel(
        gate = gate,
        draft = draft,
        context = context,
        countries = state.production.countries,
        required = required,
        shownErrors = shown,
        uploading = form.uploading,
        onEvent = onEvent,
    )
    val scroll = rememberScrollState()
    LaunchedEffect(index) { scroll.scrollTo(0) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(focus)
            .focusable()
            .onPreviewKeyEvent { event ->
                // Escape is the same guarded exit as the back chevron; over the prompt it belongs to the prompt.
                val escape = event.type == KeyEventType.KeyDown && event.key == Key.Escape
                if (escape && !form.discardPrompt) onEvent(CrewFormEvent.Close)
                escape && !form.discardPrompt
            },
    ) {
        TopBar(saving = form.saving, dirty = dirty, onEvent = onEvent)
        Row(
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 28.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            StepRail(steps, index, onEvent, Modifier.width(252.dp).fillMaxHeight().padding(vertical = 20.dp))
            ZillitScrollColumn(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                state = scroll,
                // The end inset keeps the scroll rail clear of the cards' edge.
                contentPadding = PaddingValues(start = 8.dp, end = 18.dp, top = 20.dp, bottom = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(Modifier.widthIn(max = 1200.dp).fillMaxWidth()) {
                    StepHeader(position = index + 1, count = steps.size, step = step)
                    if (anyMarked) InfoLine()
                    CrewStepContent(step, model)
                }
            }
        }
        Footer(
            position = index + 1,
            count = steps.size,
            saving = form.saving,
            blockedLabels = if (form.submitAttempted) errors.map { it.field.label } else emptyList(),
            stillEmpty = stillEmpty,
            onEvent = onEvent,
        )
    }
    DiscardPrompt(visible = form.discardPrompt, saving = form.saving, onEvent = onEvent)
}

/** Back, the "My Deal / Complete your details" breadcrumb, and Save while there is something to save. */
@Composable
private fun TopBar(saving: Boolean, dirty: Boolean, onEvent: (DealMemoEvent) -> Unit) {
    val p = cp
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().background(p.bar).padding(horizontal = 28.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            BackButton(enabled = !saving) { onEvent(CrewFormEvent.Close) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val (source, hovered) = rememberHover()
                ZillitText(
                    text = "MY DEAL",
                    style = DmType.sans(11.sp, FontWeight.Bold, 0.08.em),
                    color = p.amber,
                    modifier = Modifier
                        .alpha(if (hovered && !saving) HOVER_DIM else 1f)
                        .hoverable(source)
                        .then(
                            if (saving) {
                                Modifier
                            } else {
                                Modifier
                                    .clickable(interactionSource = source, indication = null) {
                                        onEvent(CrewFormEvent.Close)
                                    }
                                    .pointerHoverIcon(PointerIcon.Hand)
                            },
                        ),
                )
                ZillitText(text = "/", style = DmType.sans(12.sp), color = p.placeholder)
                ZillitText(
                    text = "Complete your details",
                    style = DmType.sans(12.5.sp, FontWeight.SemiBold),
                    color = p.label,
                )
            }
            Spacer(Modifier.weight(1f))
            AmberButton(
                text = if (saving) "Saving…" else "Save",
                enabled = !saving && dirty,
                loading = saving,
                height = 34,
                horizontal = 16,
                textSize = 12.5f,
            ) { onEvent(CrewFormEvent.Save) }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(p.cardBorder))
    }
}

@Composable
private fun BackButton(enabled: Boolean, onClick: () -> Unit) {
    val p = cp
    val (source, hovered) = rememberHover()
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier = Modifier
            .size(36.dp)
            .alpha(if (enabled) 1f else HALF)
            .clip(shape)
            .background(if (hovered && enabled) p.hover else p.card)
            .border(1.dp, p.cardBorder, shape)
            .hoverable(source)
            .then(
                if (enabled) {
                    Modifier.clickable(interactionSource = source, indication = null, onClick = onClick)
                        .pointerHoverIcon(PointerIcon.Hand)
                } else {
                    Modifier
                },
            ),
        contentAlignment = Alignment.Center,
    ) { ZillitIcon(ZillitIcons.ChevronLeft, size = 13.dp, tint = p.label, contentDescription = "Back to deal memo") }
}

/** The step rail: positional bullets — done before, active here, upcoming after — every item clickable. */
@Composable
private fun StepRail(steps: List<CrewStep>, index: Int, onEvent: (DealMemoEvent) -> Unit, modifier: Modifier) {
    val p = cp
    val shape = RoundedCornerShape(16.dp)
    Box(modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .shadow(1.dp, shape, ambientColor = RAIL_SHADOW, spotColor = RAIL_SHADOW)
                .clip(shape)
                .background(p.card)
                .border(1.dp, p.cardBorder, shape)
                .padding(horizontal = 8.dp, vertical = 18.dp),
        ) {
            ZillitText(
                text = "YOUR DETAILS — ${steps.size} STEPS",
                style = DmType.sans(10.5.sp, FontWeight.Bold, 0.12.em),
                color = p.muted,
                modifier = Modifier.padding(start = 14.dp, bottom = 14.dp),
            )
            Column(
                // The connector runs behind the bullets, from the first to the last.
                modifier = Modifier.drawBehind {
                    val x = RAIL_LINE_X.dp.toPx()
                    val inset = RAIL_LINE_INSET.dp.toPx()
                    drawLine(p.railLine, Offset(x, inset), Offset(x, size.height - inset), strokeWidth = 2.dp.toPx())
                },
            ) {
                steps.forEachIndexed { i, step ->
                    RailItem(step, i, index) { onEvent(CrewFormEvent.GoToStep(i)) }
                }
            }
        }
    }
}

@Composable
private fun RailItem(step: CrewStep, position: Int, current: Int, onClick: () -> Unit) {
    val p = cp
    val (source, hovered) = rememberHover()
    val active = position == current
    val done = position < current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(
                when {
                    active -> p.railActive
                    hovered -> p.railHover
                    else -> Color.Transparent
                },
            )
            .hoverable(source)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        Bullet(number = position + 1, active = active, done = done)
        Column(Modifier.weight(1f)) {
            ZillitText(
                text = step.label,
                style = DmType.sans(12.5.sp, if (active || done) FontWeight.Bold else FontWeight.SemiBold),
                color = when {
                    active -> Color(0xFFE8861A)
                    done -> p.ink
                    else -> p.label
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            ZillitText(
                text = step.sub,
                style = DmType.sans(10.5.sp),
                color = p.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 1.dp),
            )
        }
    }
}

@Composable
private fun Bullet(number: Int, active: Boolean, done: Boolean) {
    val p = cp
    val (fill, border, ink) = when {
        active -> Triple(Color(0xFFE8861A), Color(0xFFE8861A), Color.White)
        done -> Triple(Color(0xFF1AA463), Color(0xFF1AA463), Color.White)
        else -> Triple(p.card, p.railLine, p.muted)
    }
    Box(
        modifier = Modifier
            .size(20.dp)
            .then(
                if (active) {
                    Modifier.drawBehind {
                        drawCircle(
                            Color(0xFFE8861A).copy(alpha = RING_ALPHA),
                            radius = size.minDimension / 2 + 4.dp.toPx(),
                        )
                    }
                } else {
                    Modifier
                },
            )
            .clip(CircleShape)
            .background(fill)
            .border(1.5.dp, border, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (done) {
            ZillitIcon(ZillitIcons.Check, size = 11.dp, tint = ink)
        } else {
            ZillitText(text = number.toString(), style = DmType.mono(10.5.sp, FontWeight.Bold), color = ink)
        }
    }
}

/** `PageHeader`: "Step N of M" with its hairline, the step's Syne title and its description. */
@Composable
private fun StepHeader(position: Int, count: Int, step: CrewStep) {
    val p = cp
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ZillitText(
            text = "STEP $position OF $count",
            style = DmType.sans(12.sp, FontWeight.SemiBold, 0.2.em),
            color = p.brand,
            maxLines = 1,
        )
        Box(Modifier.weight(1f).height(1.dp).background(p.brand.copy(alpha = HAIRLINE_ALPHA)))
    }
    ZillitText(
        text = step.label,
        style = DmType.display(30.sp, FontWeight.Bold, (-0.025).em).copy(lineHeight = 36.sp),
        color = p.title,
        maxLines = 1,
    )
    ZillitText(
        text = step.sub,
        style = DmType.sans(14.sp),
        color = p.body,
        modifier = Modifier.fillMaxWidth(DESCRIPTION_WIDTH).padding(top = 4.dp, bottom = 20.dp),
    )
}

/** Shown only when the sender marked something, although the floor's asterisks always show. */
@Composable
private fun InfoLine() {
    val p = cp
    Row(Modifier.padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        ZillitIcon(ZillitIcons.Info, size = 11.dp, tint = p.brand, modifier = Modifier.padding(top = 2.dp))
        ZillitText(
            text = buildAnnotatedString {
                append("Greyed values are managed by your production accountant. Fields marked ")
                withStyle(SpanStyle(color = Color(0xFFEF4444))) { append("*") }
                append(" are required.")
            },
            style = DmType.sans(11.sp).copy(lineHeight = 17.sp),
            color = p.body,
        )
    }
}

/** The two notes, then Back · progress · Continue (or Save & Finish on the last step). */
@Composable
private fun Footer(
    position: Int,
    count: Int,
    saving: Boolean,
    blockedLabels: List<String>,
    stillEmpty: Boolean,
    onEvent: (DealMemoEvent) -> Unit,
) {
    val p = cp
    val last = position >= count
    Column(Modifier.fillMaxWidth().background(p.footer)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(p.cardBorder))
        if (blockedLabels.isNotEmpty()) {
            ZillitText(
                text = "Fix ${blockedLabels.joinToString(", ")} before saving or continuing.",
                style = DmType.sans(11.sp, FontWeight.SemiBold),
                color = p.error,
                modifier = Modifier.padding(start = 28.dp, end = 28.dp, top = 10.dp),
            )
        }
        if (stillEmpty) {
            ZillitText(
                text = "Some required fields are still empty — you can save now and finish later, but they're needed " +
                    "before you can send this for approval.",
                style = DmType.sans(11.sp),
                color = p.warn,
                modifier = Modifier.padding(start = 28.dp, end = 28.dp, top = 10.dp),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            FooterBack(enabled = position > 1) { onEvent(CrewFormEvent.StepBack) }
            Progress(position, count, Modifier.weight(1f))
            if (last) {
                AmberButton(text = if (saving) "Saving…" else "Save & Finish", enabled = !saving, loading = saving) {
                    onEvent(CrewFormEvent.Finish)
                }
            } else {
                AmberButton(text = "Continue", enabled = true, trailingArrow = true) { onEvent(CrewFormEvent.Continue) }
            }
        }
    }
}

@Composable
private fun FooterBack(enabled: Boolean, onClick: () -> Unit) {
    val p = cp
    val (source, hovered) = rememberHover()
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier = Modifier
            .alpha(if (enabled) 1f else BACK_DISABLED)
            .height(38.dp)
            .clip(shape)
            .background(if (hovered && enabled) p.hover else p.card)
            .border(1.dp, p.cardBorder, shape)
            .hoverable(source)
            .then(
                if (enabled) {
                    Modifier.clickable(interactionSource = source, indication = null, onClick = onClick)
                        .pointerHoverIcon(PointerIcon.Hand)
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        ZillitIcon(ZillitIcons.ArrowLeft, size = 12.dp, tint = p.teal)
        ZillitText(text = "Back", style = DmType.sans(13.sp, FontWeight.Bold), color = p.teal)
    }
}

@Composable
private fun Progress(position: Int, count: Int, modifier: Modifier) {
    val p = cp
    val target = if (count == 0) 0f else position.toFloat() / count
    val fraction by animateFloatAsState(target, tween(PROGRESS_MS), label = "crew-progress")
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.weight(1f).height(5.dp).clip(CircleShape).background(p.track)) {
            Box(
                Modifier
                    .fillMaxWidth(fraction.coerceIn(0f, 1f))
                    .fillMaxHeight()
                    .clip(CircleShape)
                    .background(Brush.horizontalGradient(listOf(Color(0xFFE8861A), Color(0xFF1AA463)))),
            )
        }
        ZillitText(
            text = "Step $position of $count",
            style = DmType.mono(11.5.sp, FontWeight.Bold, 0.04.em),
            color = p.muted,
            textAlign = TextAlign.End,
            maxLines = 1,
            modifier = Modifier.widthIn(min = 72.dp),
        )
    }
}

/** The form's amber action: Save in the bar, Continue and Save & Finish in the footer. */
@Composable
private fun AmberButton(
    text: String,
    enabled: Boolean,
    loading: Boolean = false,
    trailingArrow: Boolean = false,
    height: Int = 38,
    horizontal: Int = 22,
    textSize: Float = 13f,
    onClick: () -> Unit,
) {
    val (source, hovered) = rememberHover()
    val shape = RoundedCornerShape(10.dp)
    val active = enabled && !loading
    Row(
        modifier = Modifier
            .alpha(if (enabled || loading) 1f else HALF)
            .shadow(if (active) 6.dp else 0.dp, shape, ambientColor = GLOW, spotColor = GLOW)
            .height(height.dp)
            .clip(shape)
            .background(if (hovered && active) Color(0xFFD97712) else Color(0xFFE8861A))
            .hoverable(source)
            .then(
                if (active) {
                    Modifier.clickable(interactionSource = source, indication = null, onClick = onClick)
                        .pointerHoverIcon(PointerIcon.Hand)
                } else {
                    Modifier
                },
            )
            .padding(horizontal = horizontal.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        if (loading) ZillitSpinner(size = 12.dp, color = Color.White)
        ZillitText(text = text, style = DmType.sans(textSize.sp, FontWeight.Bold), color = Color.White, maxLines = 1)
        if (trailingArrow) ZillitIcon(ZillitIcons.ArrowRight, size = 12.dp, tint = Color.White)
    }
}

/** "Discard changes?" — keep editing, throw the draft away, or save it. */
@Composable
private fun DiscardPrompt(visible: Boolean, saving: Boolean, onEvent: (DealMemoEvent) -> Unit) {
    DmModal(
        visible = visible,
        title = "Discard changes?",
        onDismiss = { onEvent(CrewFormEvent.KeepEditing) },
        maxWidth = 460.dp,
        footer = {
            DmButton(
                "Keep Editing",
                onClick = { onEvent(CrewFormEvent.KeepEditing) },
                style = DmButtonStyle.ModalNeutral,
            )
            DmButton(
                "Discard Changes",
                onClick = { onEvent(CrewFormEvent.DiscardChanges) },
                style = DmButtonStyle.ModalDangerText,
                enabled = !saving,
            )
            DmButton(
                text = if (saving) "Saving…" else "Save Changes",
                onClick = { onEvent(CrewFormEvent.SaveChanges) },
                style = DmButtonStyle.ModalPrimary,
                loading = saving,
            )
        },
    ) {
        ZillitText(
            text = "You have unsaved changes to your details. Discarding puts every field back to what's currently " +
                "saved on the deal memo.",
            style = DmType.sans(12.sp).copy(lineHeight = 19.sp),
            color = cp.body,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp),
        )
    }
}

private const val HALF = 0.5f
private const val BACK_DISABLED = 0.4f
private const val HOVER_DIM = 0.8f
private const val HAIRLINE_ALPHA = 0.2f
private const val RING_ALPHA = 0.14f
private const val DESCRIPTION_WIDTH = 0.6f
private const val PROGRESS_MS = 300
private const val RAIL_LINE_X = 20
private const val RAIL_LINE_INSET = 14
private val GLOW = Color(0x47E8861A)
private val RAIL_SHADOW = Color(0x0F0F1115)
