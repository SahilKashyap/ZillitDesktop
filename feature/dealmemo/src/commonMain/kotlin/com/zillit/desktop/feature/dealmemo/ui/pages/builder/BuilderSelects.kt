package com.zillit.desktop.feature.dealmemo.ui.pages.builder

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.dealmemo.domain.authoring.PayloadParts
import com.zillit.desktop.feature.dealmemo.domain.preview.DealAddress
import com.zillit.desktop.feature.dealmemo.domain.preview.DealCountry
import com.zillit.desktop.feature.dealmemo.ui.components.DmType
import com.zillit.desktop.feature.dealmemo.ui.components.rememberHover
import com.zillit.desktop.feature.dealmemo.ui.pages.crew.CountryPicker
import com.zillit.desktop.feature.dealmemo.ui.pages.crew.CountryRowStyle
import com.zillit.desktop.feature.dealmemo.ui.pages.crew.DateInput
import com.zillit.desktop.feature.dealmemo.ui.pages.crew.isoFromStoredCode
import com.zillit.desktop.feature.dealmemo.ui.pages.preview.shadowed
import com.zillit.desktop.feature.dealmemo.ui.pages.rules.BelowStartPosition
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.JsonPrimitive

/** One choice of a picker: its key, what it reads, an optional second line, what search matches. */
internal data class PickOption(
    val key: String,
    val label: String,
    val sub: String? = null,
    val search: String = listOfNotNull(label, sub).joinToString(" "),
    val disabled: Boolean = false,
    /** A chip beside the label — `Pending`, `System`. */
    val badge: String? = null,
)

/**
 * `RichSelect`: a trigger showing the chosen option, and a searchable,
 * keyboard-driven list under it — a fixed-height lazy list, since a lazy list
 * in a popup crashes on its intrinsics otherwise.
 */
@Suppress("CyclomaticComplexMethod", "LongMethod")
@Composable
internal fun RichSelect(
    options: List<PickOption>,
    selectedKey: String?,
    onPick: (String?) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    clearable: Boolean = true,
    error: Boolean = false,
    dropdownWidth: Dp = 320.dp,
    height: Dp = CONTROL_HEIGHT,
    triggerText: String? = null,
    leading: (@Composable () -> Unit)? = null,
    rowLeading: (@Composable (PickOption) -> Unit)? = null,
) {
    val p = bp
    var open by remember { mutableStateOf(false) }
    val selected = options.firstOrNull { it.key == selectedKey }
    val shown = triggerText ?: selected?.label
    val shape = RoundedCornerShape(RADIUS)
    Box(modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .alpha(if (enabled) 1f else DISABLED)
                .focusRingBuilder(open, p.cta)
                .height(height)
                .clip(shape)
                .background(p.inputBg)
                .border(
                    1.dp,
                    when {
                        error -> p.red
                        open -> p.focusBorder
                        else -> p.inputBorder
                    },
                    shape,
                )
                .then(if (enabled) Modifier.clickable { open = !open }.pointerHoverIcon(PointerIcon.Hand) else Modifier)
                .padding(start = 10.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            if (shown != null) leading?.invoke()
            ZillitText(
                text = shown ?: placeholder,
                style = DmType.sans(14.sp, FontWeight.Normal),
                color = if (shown == null) p.placeholder else p.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (clearable && shown != null && enabled) {
                Box(
                    modifier = Modifier.size(18.dp).clip(CircleShape).background(p.menuHover).clickable {
                        onPick(null)
                    },
                    contentAlignment = Alignment.Center,
                ) { ZillitIcon(ZillitIcons.Close, size = 9.dp, tint = p.muted) }
            }
            ZillitIcon(
                ZillitIcons.ChevronDown,
                size = 11.dp,
                tint = p.muted,
                modifier = Modifier.rotate(if (open) HALF_TURN else 0f),
            )
        }
        if (open) {
            Popup(
                popupPositionProvider = remember { BelowStartPosition(gap = 8) },
                onDismissRequest = { open = false },
                properties = PopupProperties(focusable = true),
            ) {
                PickPanel(
                    options = options,
                    selectedKey = selectedKey,
                    width = dropdownWidth,
                    onPick = {
                        open = false
                        onPick(it.key)
                    },
                    onClose = { open = false },
                    rowLeading = rowLeading,
                )
            }
        }
    }
}

@Suppress("LongMethod")
@Composable
private fun PickPanel(
    options: List<PickOption>,
    selectedKey: String?,
    width: Dp,
    onPick: (PickOption) -> Unit,
    onClose: () -> Unit,
    rowLeading: (@Composable (PickOption) -> Unit)?,
) {
    val p = bp
    var query by remember { mutableStateOf("") }
    val matches = remember(options, query) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) options else options.filter { q in it.search.lowercase() }
    }
    var highlight by remember(matches) {
        mutableIntStateOf(matches.indexOfFirst { it.key == selectedKey }.coerceAtLeast(0))
    }
    val listState = rememberLazyListState()
    LaunchedEffect(highlight, matches) {
        if (matches.isNotEmpty()) listState.animateScrollToItem((highlight - 2).coerceAtLeast(0))
    }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val shape = RoundedCornerShape(15.dp)
    val rowHeight = if (options.any { it.sub != null }) RICH_ROW else PLAIN_ROW
    Column(
        modifier = Modifier
            .width(width)
            .shadowed(shape)
            .clip(shape)
            .background(p.menu)
            .border(1.dp, p.menuBorder, shape),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ZillitIcon(ZillitIcons.Search, size = 15.dp, tint = p.muted)
            Spacer(Modifier.width(10.dp))
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) ZillitText(text = "Search…", style = DmType.sans(13.5.sp), color = p.placeholder)
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    textStyle = DmType.sans(13.5.sp, FontWeight.Medium).copy(color = p.ink),
                    cursorBrush = SolidColor(p.cta),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focus)
                        .onPreviewKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            when (event.key) {
                                Key.DirectionDown -> {
                                    highlight = (highlight + 1).coerceAtMost(matches.lastIndex.coerceAtLeast(0))
                                    true
                                }
                                Key.DirectionUp -> {
                                    highlight = (highlight - 1).coerceAtLeast(0)
                                    true
                                }
                                Key.Enter, Key.NumPadEnter -> {
                                    matches.getOrNull(highlight)?.takeUnless { it.disabled }?.let(onPick)
                                    true
                                }
                                Key.Escape -> {
                                    onClose()
                                    true
                                }
                                else -> false
                            }
                        },
                )
            }
        }
        Rule(p.menuDivider)
        if (matches.isEmpty()) {
            ZillitText(
                text = "No results for \"$query\"",
                style = DmType.sans(13.sp),
                color = p.muted,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 28.dp),
            )
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth().height((matches.size.coerceAtMost(MAX_VISIBLE) * rowHeight).dp),
            ) {
                itemsIndexed(matches, key = { _, option -> option.key }) { index, option ->
                    PickRow(
                        option = option,
                        height = rowHeight.dp,
                        selected = option.key == selectedKey,
                        highlighted = index == highlight,
                        onClick = { if (!option.disabled) onPick(option) },
                        leading = rowLeading,
                    )
                }
            }
        }
        Rule(p.menuDivider)
        Row(
            modifier = Modifier.fillMaxWidth().background(p.menuHover).padding(horizontal = 18.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ZillitText(text = "${matches.size}", style = DmType.mono(11.sp, FontWeight.Bold), color = p.ink2)
            ZillitText(
                text = if (matches.size == 1) " option" else " options",
                style = DmType.sans(11.sp),
                color = p.muted,
                modifier = Modifier.weight(1f),
            )
            ZillitText(text = "↑ ↓ navigate  ↵ select", style = DmType.sans(10.5.sp), color = p.muted)
        }
    }
}

@Suppress("LongMethod")
@Composable
private fun PickRow(
    option: PickOption,
    height: Dp,
    selected: Boolean,
    highlighted: Boolean,
    onClick: () -> Unit,
    leading: (@Composable (PickOption) -> Unit)?,
) {
    val p = bp
    val (source, hovered) = rememberHover()
    Column(Modifier.fillMaxWidth().height(height)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .alpha(if (option.disabled) DISABLED else 1f)
                .background(
                    when {
                        selected -> p.menuSelected
                        highlighted || hovered -> p.menuHover
                        else -> Color.Transparent
                    },
                )
                .hoverable(source)
                .clickable(interactionSource = source, indication = null, onClick = onClick)
                .pointerHoverIcon(if (option.disabled) PointerIcon.Default else PointerIcon.Hand),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.width(3.dp).fillMaxHeight().background(if (selected) p.cta else Color.Transparent))
            Row(
                modifier = Modifier.weight(1f).padding(start = 11.dp, end = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                leading?.invoke(option)
                Column(Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        ZillitText(
                            text = option.label,
                            style = DmType.sans(13.5.sp, FontWeight.Bold),
                            color = p.ink,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        option.badge?.let { PickBadge(it) }
                    }
                    option.sub?.let {
                        ZillitText(
                            text = it,
                            style = DmType.sans(12.sp),
                            color = p.muted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (selected) {
                    Box(Modifier.size(24.dp).clip(CircleShape).background(p.cta), contentAlignment = Alignment.Center) {
                        ZillitIcon(ZillitIcons.Check, size = 12.dp, tint = Color.White)
                    }
                }
            }
        }
        Rule(p.menuDivider)
    }
}

@Composable
private fun PickBadge(text: String) {
    val p = bp
    Box(
        Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(p.amberSoft)
            .border(1.dp, p.amberRing, RoundedCornerShape(4.dp))
            .padding(horizontal = 5.dp, vertical = 1.dp),
    ) { ZillitText(text = text.uppercase(), style = DmType.sans(9.5.sp, FontWeight.Bold), color = p.cta, maxLines = 1) }
}

/** `W.sel`: a native select — a first "none" row, then the options; retired options show, disabled. */
@Suppress("LongMethod")
@Composable
internal fun NativeSelect(
    value: String,
    options: List<PickOption>,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    enabled: Boolean = true,
    error: Boolean = false,
    height: Dp = CONTROL_HEIGHT,
    menuWidth: Dp? = null,
    textSize: Float = 14f,
) {
    val p = bp
    var open by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(RADIUS)
    val rows = listOfNotNull(placeholder?.let { PickOption("", it) }) + options
    val shown = rows.firstOrNull { it.key == value }?.label ?: value
    Box(modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .alpha(if (enabled) 1f else DISABLED)
                .focusRingBuilder(open, p.cta)
                .height(height)
                .clip(shape)
                .background(p.inputBg)
                .border(
                    1.dp,
                    when {
                        error -> p.red
                        open -> p.focusBorder
                        else -> p.inputBorder
                    },
                    shape,
                )
                .then(if (enabled) Modifier.clickable { open = !open }.pointerHoverIcon(PointerIcon.Hand) else Modifier)
                .padding(start = 12.dp, end = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ZillitText(
                text = shown,
                style = DmType.sans(textSize.sp, FontWeight.SemiBold),
                color = if (value.isEmpty() && placeholder != null) p.muted else p.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            ZillitIcon(ZillitIcons.ChevronDown, size = 11.dp, tint = p.muted)
        }
        if (open) {
            Popup(
                popupPositionProvider = remember { BelowStartPosition(gap = 6) },
                onDismissRequest = { open = false },
                properties = PopupProperties(focusable = true),
            ) {
                val menuShape = RoundedCornerShape(12.dp)
                Column(
                    modifier = Modifier
                        .then(if (menuWidth != null) Modifier.width(menuWidth) else Modifier.width(MENU_MIN))
                        .heightIn(max = MENU_MAX)
                        .shadowed(menuShape)
                        .clip(menuShape)
                        .background(p.menu)
                        .border(1.dp, p.menuBorder, menuShape)
                        .verticalScroll(rememberScrollState())
                        .padding(5.dp),
                ) {
                    rows.forEach { option ->
                        MenuRow(option, option.key == value) {
                            if (!option.disabled) {
                                open = false
                                onPick(option.key)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MenuRow(option: PickOption, selected: Boolean, onClick: () -> Unit) {
    val p = bp
    val (source, hovered) = rememberHover()
    val muted = option.key.isEmpty() || option.disabled
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(
                when {
                    selected && option.key.isNotEmpty() -> p.menuSelected
                    hovered && !option.disabled -> p.menuHover
                    else -> Color.Transparent
                },
            )
            .hoverable(source)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .pointerHoverIcon(if (option.disabled) PointerIcon.Default else PointerIcon.Hand)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitText(
            text = option.label,
            style = DmType.sans(13.sp, if (selected && !muted) FontWeight.SemiBold else FontWeight.Medium),
            color = if (muted) p.muted else p.ink,
            modifier = Modifier.weight(1f),
        )
        if (selected && option.key.isNotEmpty()) ZillitIcon(ZillitIcons.Check, size = 12.dp, tint = p.cta)
    }
}

/**
 * `<input type="date">` over a `YYYY-MM-DD` form value: the UTC calendar day,
 * so a deal's dates never shift with the machine's zone. [min] and [max]
 * bound the calendar.
 */
@Composable
internal fun IsoDateInput(
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    min: String? = null,
    max: String? = null,
    enabled: Boolean = true,
    error: Boolean = false,
) {
    fun localOf(date: String?): LocalDate? = date?.takeIf { it.isNotEmpty() }?.let {
        runCatching { LocalDate.parse(it) }.getOrNull()
    }
    DateInput(
        millis = PayloadParts.toEpoch(value),
        onChange = { millis -> onChange(millis?.let { PayloadParts.fromEpoch(JsonPrimitive(it)) }.orEmpty()) },
        zone = TimeZone.UTC,
        modifier = modifier,
        min = localOf(min),
        max = localOf(max),
        strictMax = false,
        height = CONTROL_HEIGHT,
        textSize = 14f,
        enabled = enabled,
        error = error,
    )
}

/**
 * `AddressFields` in the builder: line 1 and line 2 across, city beside
 * county, postcode beside the country picker; an emptied box stores null.
 */
@Composable
internal fun AddressFields(
    address: DealAddress,
    countries: List<DealCountry>,
    onChange: (DealAddress) -> Unit,
    enabled: Boolean = true,
) {
    BuilderGrid(columns = 2) {
        cell(span = 2) {
            AddressLine("Address line 1", "12 Baker Street", address.line1, enabled) {
                onChange(address.copy(line1 = it))
            }
        }
        cell(span = 2) {
            AddressLine("Address line 2", "Flat 4", address.line2, enabled) { onChange(address.copy(line2 = it)) }
        }
        cell { AddressLine("City", "London", address.city, enabled) { onChange(address.copy(city = it)) } }
        cell {
            AddressLine("County / State", "Greater London", address.state, enabled) {
                onChange(address.copy(state = it))
            }
        }
        cell {
            AddressLine("Postal code / ZIP", "NW1 6XE", address.postalCode, enabled, mono = true) {
                onChange(address.copy(postalCode = it))
            }
        }
        cell {
            Field("Country") {
                val selected = countries.firstOrNull { it.name == address.country }
                CountryPicker(
                    countries = countries,
                    selectedCode = selected?.code,
                    triggerText = selected?.name ?: address.country,
                    placeholder = "Select country…",
                    onPick = { onChange(address.copy(country = it?.name)) },
                    rowStyle = CountryRowStyle.Name,
                    height = CONTROL_HEIGHT,
                    textSize = 14f,
                    radius = RADIUS,
                    enabled = enabled,
                )
            }
        }
    }
}

@Composable
private fun AddressLine(
    label: String,
    placeholder: String,
    value: String?,
    enabled: Boolean,
    mono: Boolean = false,
    onChange: (String?) -> Unit,
) {
    Field(label) {
        BuilderInput(
            value = value.orEmpty(),
            onValueChange = { onChange(it.ifEmpty { null }) },
            placeholder = placeholder,
            mono = mono,
            enabled = enabled,
        )
    }
}

/** How a phone row's code is kept — the contacts store the ISO code, the loan-out company the dial string. */
internal enum class CodeMode { Iso, Dial }

/**
 * `CountryCodeSelect` + a phone number: the 130 px picker and a digits-only
 * box, with the phone rule's inline error.
 */
@Composable
internal fun PhoneFields(
    countries: List<DealCountry>,
    storedCode: String,
    number: String,
    mode: CodeMode,
    onCode: (String) -> Unit,
    onNumber: (String) -> Unit,
    error: String?,
) {
    val iso = isoFromStoredCode(countries, storedCode)
    val country = countries.firstOrNull { it.code == iso }
    val trigger = when (mode) {
        CodeMode.Iso -> country?.let { "${it.name} (${it.dialCode})" }
        CodeMode.Dial -> storedCode.trim().ifEmpty { null }
    }
    Column {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CountryPicker(
                countries = countries,
                selectedCode = iso.ifEmpty { null },
                triggerText = trigger,
                placeholder = "Country code",
                onPick = { picked ->
                    onCode(
                        when {
                            picked == null -> ""
                            mode == CodeMode.Iso -> picked.code
                            else -> picked.dialCode
                        },
                    )
                },
                rowStyle = CountryRowStyle.Country,
                modifier = Modifier.width(130.dp),
                height = CONTROL_HEIGHT,
                textSize = 13f,
                radius = RADIUS,
            )
            BuilderInput(
                value = number,
                onValueChange = { typed -> onNumber(typed.filter { it.isDigit() }) },
                placeholder = "Phone number",
                modifier = Modifier.weight(1f),
                error = error != null,
            )
        }
        error?.let { ErrorText(it) }
    }
}

private const val DISABLED = 0.5f
private const val HALF_TURN = 180f
private const val MAX_VISIBLE = 7
private const val PLAIN_ROW = 44
private const val RICH_ROW = 56
private val MENU_MIN = 240.dp
private val MENU_MAX = 320.dp
