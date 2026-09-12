package com.zillit.desktop.feature.taxfiling.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitScrollColumn
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.taxfiling.domain.TaxFilingRoute
import com.zillit.desktop.feature.taxfiling.domain.VatBox
import com.zillit.desktop.feature.taxfiling.ui.components.MtdToast
import com.zillit.desktop.feature.taxfiling.ui.components.mtdPalette
import com.zillit.desktop.feature.taxfiling.ui.components.mtdText
import com.zillit.desktop.feature.taxfiling.ui.components.rememberLast
import com.zillit.desktop.feature.taxfiling.ui.pages.AuthorityWarning
import com.zillit.desktop.feature.taxfiling.ui.pages.CatalogPage
import com.zillit.desktop.feature.taxfiling.ui.pages.FilingUnavailablePage
import com.zillit.desktop.feature.taxfiling.ui.pages.LayersDialog
import com.zillit.desktop.feature.taxfiling.ui.pages.RegisterDialog
import com.zillit.desktop.feature.taxfiling.ui.pages.RegistrationsPage
import com.zillit.desktop.feature.taxfiling.ui.pages.RemoveDialog
import com.zillit.desktop.feature.taxfiling.ui.pages.ReturnPage
import com.zillit.desktop.feature.taxfiling.ui.pages.SubmitDialog

/**
 * Tax filing — the web's filing hub, its MTD VAT module and one company's
 * VAT return, behind one header.
 *
 * The header is the web's page chrome: a back chip and a breadcrumb that name
 * where you are and take you up a level. Below it the body scrolls, centred
 * to the web's reading width everywhere but the return, which uses the whole
 * pane for its boxes and summary rail.
 */
@Composable
fun TaxFilingScreen(
    state: TaxFilingUiState,
    onEvent: (TaxFilingEvent) -> Unit,
    toast: TaxToast? = null,
    onToastDismiss: () -> Unit = {},
) {
    val palette = mtdPalette()
    val scroll = rememberScrollState()
    var viewportTop by remember { mutableFloatStateOf(0f) }
    val sticky = remember(scroll) { StickyViewport(scroll = scroll, viewportTop = { viewportTop }) }
    // The box whose layers are being picked. The dialog lives here, over the
    // whole surface, rather than inside the scrolling boxes it edits.
    var layersBox by remember { mutableStateOf<VatBox?>(null) }
    val surface = Surface.of(state)
    // A new surface opens at its top, as the web scrolls to it.
    LaunchedEffect(surface, state.returnState.registration?.id) { scroll.scrollTo(0) }

    Box(Modifier.fillMaxSize().background(palette.bg)) {
        Column(Modifier.fillMaxSize()) {
            TopBar(state, onEvent)
            ZillitScrollColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .onGloballyPositioned { viewportTop = it.positionInWindow().y },
                state = scroll,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                SurfaceBody(state, onEvent, surface, sticky, onOpenLayers = { layersBox = it })
            }
        }

        RegisterDialog(state, onEvent)
        RemoveDialog(state, onEvent)
        SubmitDialog(state, onEvent)
        BoxLayersDialog(
            state = state,
            onEvent = onEvent,
            box = layersBox?.takeIf { surface == Surface.Return },
            onClose = { layersBox = null },
        )
        MtdToast(toast = toast, onDismiss = onToastDismiss)
    }
}

/** The surface under the header, centred to the reading width everywhere but the return. */
@Composable
private fun SurfaceBody(
    state: TaxFilingUiState,
    onEvent: (TaxFilingEvent) -> Unit,
    surface: Surface,
    sticky: StickyViewport,
    onOpenLayers: (VatBox) -> Unit,
) {
    Column(
        modifier = Modifier
            .then(if (surface == Surface.Return) Modifier else Modifier.widthIn(max = READING_WIDTH))
            .fillMaxWidth()
            .padding(start = 22.dp, end = 22.dp, bottom = 96.dp),
    ) {
        AuthorityWarning(state)
        AnimatedContent(
            targetState = surface,
            transitionSpec = { fadeIn(tween(FADE_MS)) togetherWith fadeOut(tween(FADE_MS)) },
            label = "taxFilingSurface",
        ) { shown ->
            Column(Modifier.fillMaxWidth()) {
                when (shown) {
                    Surface.Catalog -> CatalogPage(state.catalog, onEvent)
                    Surface.Unavailable -> FilingUnavailablePage(onEvent)
                    Surface.Registrations -> RegistrationsPage(state, onEvent)
                    Surface.Return -> ReturnPage(state, onEvent, sticky, onOpenLayers)
                }
            }
        }
    }
}

/** The layers picker for [box], saving straight into that box's mapping. */
@Composable
private fun BoxLayersDialog(
    state: TaxFilingUiState,
    onEvent: (TaxFilingEvent) -> Unit,
    box: VatBox?,
    onClose: () -> Unit,
) {
    // The box stays named through the closing fade, so the pickers do not blank as it goes.
    val shownBox = rememberLast(box)
    LayersDialog(
        visible = box != null,
        boxNumber = shownBox?.number,
        sets = state.lookups.layerSets,
        value = shownBox?.let { state.returnState.mappingFor(it).layers }.orEmpty(),
        onSave = { layers ->
            box?.let { onEvent(TaxFilingEvent.EditMapping(state.returnState.mappingFor(it).copy(layers = layers))) }
            onClose()
        },
        onDismiss = onClose,
    )
}

/** Which of the four surfaces is showing. */
private enum class Surface {
    Catalog, Unavailable, Registrations, Return;

    companion object {
        fun of(state: TaxFilingUiState): Surface = when (val route = state.route) {
            TaxFilingRoute.Catalog -> Catalog
            is TaxFilingRoute.Filing -> when {
                route.supported == null -> Unavailable
                state.inReturn -> Return
                else -> Registrations
            }
        }
    }
}

/**
 * The web's sticky page header: the back chip, then the breadcrumb.
 *
 * "Management / Tax Filing" on the catalogue; "Tax Filing / MTD VAT" in a
 * filing; "Tax Filing / MTD VAT / Company" in a return — every crumb but the
 * last one a way back to it.
 */
@Composable
private fun TopBar(state: TaxFilingUiState, onEvent: (TaxFilingEvent) -> Unit) {
    val palette = mtdPalette()
    val route = state.route
    val filing = (route as? TaxFilingRoute.Filing)?.supported
    Column(Modifier.fillMaxWidth().background(palette.bg)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            BackChip(
                description = when {
                    state.inReturn -> "Back to companies"
                    route is TaxFilingRoute.Filing -> "Back to Tax Filing"
                    else -> "Back to Account Hub"
                },
                onClick = { onEvent(TaxFilingEvent.Back) },
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                when (route) {
                    TaxFilingRoute.Catalog -> {
                        RootCrumb("Management") { onEvent(TaxFilingEvent.LeaveToAccountHub) }
                        CrumbSlash()
                        LeafCrumb("Tax Filing")
                    }
                    is TaxFilingRoute.Filing -> {
                        RootCrumb("Tax Filing") { onEvent(TaxFilingEvent.ShowCatalog) }
                        CrumbSlash()
                        val title = filing?.title ?: "Not available"
                        if (state.inReturn) {
                            MiddleCrumb(title) { onEvent(TaxFilingEvent.BackToRegistrations) }
                            CrumbSlash()
                            LeafCrumb(state.returnState.registration?.companyName.orEmpty())
                        } else {
                            LeafCrumb(title)
                        }
                    }
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(palette.border))
    }
}

@Composable
private fun BackChip(description: String, onClick: () -> Unit) {
    val palette = mtdPalette()
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val background by animateColorAsState(if (hovered) palette.surface3 else palette.surface, label = "backChip")
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(shape)
            .background(background)
            .border(1.dp, palette.border2, shape)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClickLabel = description,
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        ZillitIcon(icon = ZillitIcons.ChevronLeft, contentDescription = description, tint = palette.ink2, size = 16.dp)
    }
}

@Composable
private fun RootCrumb(text: String, onClick: () -> Unit) {
    val palette = mtdPalette()
    ZillitText(
        text = text.uppercase(),
        style = mtdText(13.sp, FontWeight.ExtraBold, tracking = 0.03.em),
        color = palette.accentText,
        modifier = Modifier.clickable(role = Role.Button, onClick = onClick),
    )
}

@Composable
private fun MiddleCrumb(text: String, onClick: () -> Unit) {
    ZillitText(
        text = text,
        style = mtdText(14.5.sp, FontWeight.Bold, tracking = (-0.01).em),
        color = mtdPalette().ink3,
        modifier = Modifier.clickable(role = Role.Button, onClick = onClick),
    )
}

@Composable
private fun LeafCrumb(text: String) {
    ZillitText(
        text = text,
        style = mtdText(14.5.sp, FontWeight.Bold, tracking = (-0.01).em),
        color = mtdPalette().ink,
        maxLines = 1,
    )
}

@Composable
private fun CrumbSlash() {
    ZillitText(text = "/", style = mtdText(15.sp), color = mtdPalette().faint)
}

private val READING_WIDTH = 1180.dp
private const val FADE_MS = 160
