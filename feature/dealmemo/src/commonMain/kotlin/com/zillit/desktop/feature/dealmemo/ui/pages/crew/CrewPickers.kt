package com.zillit.desktop.feature.dealmemo.ui.pages.crew

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import com.zillit.desktop.feature.dealmemo.domain.preview.DealAddress
import com.zillit.desktop.feature.dealmemo.domain.preview.DealCountry
import com.zillit.desktop.feature.dealmemo.ui.components.DmType
import com.zillit.desktop.feature.dealmemo.ui.components.rememberHover
import com.zillit.desktop.feature.dealmemo.ui.pages.preview.shadowed
import com.zillit.desktop.feature.dealmemo.ui.pages.rules.BelowStartPosition

/** How a country row reads: the name alone, a phone code first, or a name over its code. */
internal enum class CountryRowStyle { Name, Phone, Country }

/**
 * `RichSelect` over the ISD list: a searchable, keyboard-driven picker keyed on
 * the ISO code — dial codes collide — with a fixed-height lazy list, since a
 * lazy list in a popup crashes on its intrinsics otherwise.
 */
@Suppress("LongMethod")
@Composable
internal fun CountryPicker(
    countries: List<DealCountry>,
    selectedCode: String?,
    triggerText: String?,
    placeholder: String,
    onPick: (DealCountry?) -> Unit,
    rowStyle: CountryRowStyle,
    modifier: Modifier = Modifier,
    dropdownWidth: Dp = 320.dp,
    height: Dp = 44.dp,
    textSize: Float = 12.5f,
    radius: Dp = 8.dp,
    enabled: Boolean = true,
) {
    val p = cp
    var open by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(radius)
    Box(modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .alpha(if (enabled) 1f else DISABLED_ALPHA)
                .focusRing(open, radius, p.amber)
                .height(height)
                .clip(shape)
                .background(p.inputBg)
                .border(1.dp, if (open) p.focusBorder else p.pickerBorder, shape)
                .then(if (enabled) Modifier.clickable { open = !open }.pointerHoverIcon(PointerIcon.Hand) else Modifier)
                .padding(start = 10.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ZillitText(
                text = triggerText ?: placeholder,
                style = DmType.sans(textSize.sp, FontWeight.Medium),
                color = if (triggerText == null) p.placeholder else p.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (triggerText != null && enabled) {
                Box(
                    modifier = Modifier
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(p.hover)
                        .clickable { onPick(null) }
                        .pointerHoverIcon(PointerIcon.Hand),
                    contentAlignment = Alignment.Center,
                ) { ZillitIcon(ZillitIcons.Close, size = 9.dp, tint = p.muted) }
            }
            ZillitIcon(ZillitIcons.ChevronDown, size = 11.dp, tint = p.muted)
        }
        if (open) {
            Popup(
                popupPositionProvider = remember { BelowStartPosition(gap = 8) },
                onDismissRequest = { open = false },
                properties = PopupProperties(focusable = true),
            ) {
                CountryPanel(
                    countries = countries,
                    selectedCode = selectedCode,
                    rowStyle = rowStyle,
                    width = dropdownWidth,
                    onPick = {
                        open = false
                        onPick(it)
                    },
                    onClose = { open = false },
                )
            }
        }
    }
}

@Suppress("CyclomaticComplexMethod", "LongMethod")
@Composable
private fun CountryPanel(
    countries: List<DealCountry>,
    selectedCode: String?,
    rowStyle: CountryRowStyle,
    width: Dp,
    onPick: (DealCountry) -> Unit,
    onClose: () -> Unit,
) {
    val p = cp
    var query by remember { mutableStateOf("") }
    val matches = remember(countries, query, rowStyle) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) {
            countries
        } else {
            countries.filter { country ->
                val haystack = if (rowStyle == CountryRowStyle.Name) {
                    "${country.name} ${country.code}"
                } else {
                    "${country.name} ${country.code} ${country.dialCode}"
                }
                q in haystack.lowercase()
            }
        }
    }
    var highlight by remember(matches) {
        mutableIntStateOf(matches.indexOfFirst { it.code == selectedCode }.coerceAtLeast(0))
    }
    val listState = rememberLazyListState()
    LaunchedEffect(highlight, matches) {
        if (matches.isNotEmpty()) listState.animateScrollToItem((highlight - 2).coerceAtLeast(0))
    }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val shape = RoundedCornerShape(15.dp)
    val rowHeight = if (rowStyle == CountryRowStyle.Name) NAME_ROW else DETAIL_ROW
    Column(
        modifier = Modifier
            .width(width)
            .shadowed(shape)
            .clip(shape)
            .background(p.menu)
            .border(1.dp, p.pickerBorder, shape),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
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
                    cursorBrush = SolidColor(p.amber),
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
                                    matches.getOrNull(highlight)?.let(onPick)
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
            if (query.isNotEmpty()) {
                Box(
                    Modifier.size(18.dp).clip(CircleShape).background(p.hover).clickable { query = "" },
                    contentAlignment = Alignment.Center,
                ) { ZillitIcon(ZillitIcons.Close, size = 9.dp, tint = p.muted) }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(p.menuDivider))
        if (matches.isEmpty()) {
            ZillitText(
                text = "No results for \"$query\"",
                style = DmType.sans(13.sp),
                color = p.muted,
                modifier = Modifier.fillMaxWidth().padding(vertical = 18.dp).padding(horizontal = 16.dp),
            )
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth().height((matches.size.coerceAtMost(MAX_VISIBLE) * rowHeight).dp),
            ) {
                itemsIndexed(matches, key = { _, country -> country.code }) { index, country ->
                    CountryRow(
                        country = country,
                        style = rowStyle,
                        height = rowHeight.dp,
                        selected = country.code == selectedCode,
                        highlighted = index == highlight,
                        onClick = { onPick(country) },
                    )
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(p.menuDivider))
        Row(
            modifier = Modifier.fillMaxWidth().background(p.hover).padding(horizontal = 18.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ZillitText(text = "${matches.size}", style = DmType.mono(11.sp, FontWeight.Bold), color = p.label)
            ZillitText(
                text = if (matches.size == 1) " option" else " options",
                style = DmType.sans(11.sp),
                color = p.muted,
                modifier = Modifier.weight(1f),
            )
            KeyHint("↑ ↓")
            ZillitText(text = " navigate  ", style = DmType.sans(10.5.sp), color = p.muted)
            KeyHint("↵")
            ZillitText(text = " select", style = DmType.sans(10.5.sp), color = p.muted)
        }
    }
}

@Composable
private fun KeyHint(text: String) {
    val p = cp
    Box(
        Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(p.menu)
            .border(1.dp, p.pickerBorder, RoundedCornerShape(4.dp))
            .padding(horizontal = 5.dp, vertical = 1.dp),
    ) { ZillitText(text = text, style = DmType.mono(10.sp), color = p.label) }
}

@Composable
private fun CountryRow(
    country: DealCountry,
    style: CountryRowStyle,
    height: Dp,
    selected: Boolean,
    highlighted: Boolean,
    onClick: () -> Unit,
) {
    val p = cp
    val (source, hovered) = rememberHover()
    Column(Modifier.fillMaxWidth().height(height)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(
                    when {
                        selected -> p.selected
                        highlighted || hovered -> p.hover
                        else -> Color.Transparent
                    },
                )
                .hoverable(source)
                .clickable(interactionSource = source, indication = null, onClick = onClick)
                .pointerHoverIcon(PointerIcon.Hand),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.width(3.dp).fillMaxHeight().background(if (selected) p.amber else Color.Transparent))
            Row(
                modifier = Modifier.weight(1f).padding(start = 11.dp, end = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (style != CountryRowStyle.Name) Monogram(country.code)
                CountryText(country, style, Modifier.weight(1f))
                if (selected) {
                    Box(
                        Modifier.size(24.dp).clip(CircleShape).background(p.amber),
                        contentAlignment = Alignment.Center,
                    ) {
                        ZillitIcon(ZillitIcons.Check, size = 12.dp, tint = Color.White)
                    }
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(p.menuDivider))
    }
}

@Composable
private fun CountryText(country: DealCountry, style: CountryRowStyle, modifier: Modifier) {
    val p = cp
    Column(modifier) {
        when (style) {
            CountryRowStyle.Name -> ZillitText(
                text = country.name,
                style = DmType.sans(13.5.sp, FontWeight.Medium),
                color = p.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            CountryRowStyle.Phone -> {
                ZillitText(
                    text = country.dialCode,
                    style = DmType.mono(13.5.sp, FontWeight.Bold),
                    color = p.ink,
                    maxLines = 1,
                )
                ZillitText(
                    text = country.name,
                    style = DmType.sans(12.sp),
                    color = p.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            CountryRowStyle.Country -> {
                ZillitText(
                    text = country.name,
                    style = DmType.sans(13.5.sp, FontWeight.SemiBold),
                    color = p.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                ZillitText(
                    text = "${country.dialCode} · ${country.code}",
                    style = DmType.mono(12.sp),
                    color = p.muted,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun Monogram(code: String) {
    val p = cp
    Box(
        Modifier.size(28.dp).clip(RoundedCornerShape(7.dp)).background(p.tile),
        contentAlignment = Alignment.Center,
    ) { ZillitText(text = code.take(2).uppercase(), style = DmType.mono(10.sp, FontWeight.Bold), color = p.label) }
}

/**
 * `AddressFields`: line 1 and line 2 across, city beside county, postcode
 * beside the country picker, each under its own label. An emptied box stores
 * null, never `""`, so a typed-then-deleted line still equals an untouched
 * one; the country is stored as its name.
 */
@Composable
internal fun AddressBlock(
    label: String,
    address: DealAddress,
    countries: List<DealCountry>,
    onChange: (DealAddress) -> Unit,
    required: Boolean = false,
) {
    Column {
        RowLabel(label, required)
        FormGrid {
            wide {
                AddressLine("Address line 1", "12 Baker Street", address.line1) { onChange(address.copy(line1 = it)) }
            }
            wide { AddressLine("Address line 2", "Flat 4", address.line2) { onChange(address.copy(line2 = it)) } }
            half { AddressLine("City", "London", address.city) { onChange(address.copy(city = it)) } }
            half {
                AddressLine("County / State", "Greater London", address.state) { onChange(address.copy(state = it)) }
            }
            half {
                AddressLine("Postal code / ZIP", "NW1 6XE", address.postalCode, mono = true) {
                    onChange(address.copy(postalCode = it))
                }
            }
            half {
                Column {
                    WizLabel("Country")
                    val selected = countries.firstOrNull { it.name == address.country }
                    CountryPicker(
                        countries = countries,
                        selectedCode = selected?.code,
                        triggerText = selected?.name,
                        placeholder = "Select country…",
                        onPick = { onChange(address.copy(country = it?.name)) },
                        rowStyle = CountryRowStyle.Name,
                    )
                }
            }
        }
    }
}

@Composable
private fun AddressLine(
    label: String,
    placeholder: String,
    value: String?,
    mono: Boolean = false,
    onChange: (String?) -> Unit,
) {
    Column {
        WizLabel(label)
        FormInput(
            value = value.orEmpty(),
            onValueChange = { onChange(it.ifEmpty { null }) },
            placeholder = placeholder,
            mono = mono,
        )
    }
}

/** Where a phone row's code comes from, and what it stores. */
internal enum class DialMode {
    /** The two contacts: the ISO code is stored, the dial shown. */
    Iso,

    /** The loan-out company: the dial string itself is stored and shown. */
    Dial,
}

/** A phone number beside its code picker; the number keeps digits only. */
@Composable
internal fun PhoneRow(
    label: String,
    countries: List<DealCountry>,
    storedCode: String,
    number: String,
    mode: DialMode,
    onCode: (String) -> Unit,
    onNumber: (String) -> Unit,
    onBlur: () -> Unit,
    error: String?,
    required: Boolean = false,
) {
    val iso = isoFromStoredCode(countries, storedCode)
    val trigger = when (mode) {
        DialMode.Iso -> countries.firstOrNull { it.code == iso }?.dialCode
        DialMode.Dial -> storedCode.trim().ifEmpty { null }
    }
    Column {
        RowLabel(label, required)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CountryPicker(
                countries = countries,
                selectedCode = iso.ifEmpty { null },
                triggerText = trigger,
                placeholder = if (mode == DialMode.Iso) "Code" else "Country code",
                onPick = { country ->
                    onCode(
                        when {
                            country == null -> ""
                            mode == DialMode.Iso -> country.code
                            else -> country.dialCode
                        },
                    )
                },
                rowStyle = if (mode == DialMode.Iso) CountryRowStyle.Phone else CountryRowStyle.Country,
                modifier = Modifier.width(130.dp),
                dropdownWidth = if (mode == DialMode.Iso) 260.dp else 320.dp,
            )
            FormInput(
                value = number,
                onValueChange = onNumber,
                placeholder = "7700 900000",
                modifier = Modifier.weight(1f),
                error = error != null,
                onBlur = onBlur,
            )
        }
        InlineError(error)
    }
}

/**
 * `isoFromStoredCode`: an ISO code as it is; otherwise a dial code (`+`
 * added when missing) resolves to the first country that has it — so `+44`
 * reads as Guernsey. For display only; the stored value is never rewritten.
 */
internal fun isoFromStoredCode(countries: List<DealCountry>, stored: String?): String {
    val value = stored?.trim().orEmpty()
    if (value.isEmpty()) return ""
    countries.firstOrNull { it.code.equals(value, ignoreCase = true) }?.let { return it.code }
    val dial = if (value.startsWith("+")) value else "+$value"
    return countries.firstOrNull { it.dialCode == dial }?.code.orEmpty()
}

private const val MAX_VISIBLE = 7
private const val NAME_ROW = 44
private const val DETAIL_ROW = 54
private const val DISABLED_ALPHA = 0.5f
