package com.zillit.desktop.feature.dealmemo.ui.pages.builder

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitInitialsTile
import com.zillit.desktop.core.designsystem.component.ZillitOptionPopup
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.dealmemo.domain.authoring.PayloadParts
import com.zillit.desktop.feature.dealmemo.domain.preview.DealAddress
import com.zillit.desktop.feature.dealmemo.domain.preview.DealCountry
import com.zillit.desktop.feature.dealmemo.ui.components.DmSelectField
import com.zillit.desktop.feature.dealmemo.ui.components.DmType
import com.zillit.desktop.feature.dealmemo.ui.pages.crew.CountryPicker
import com.zillit.desktop.feature.dealmemo.ui.pages.crew.CountryRowStyle
import com.zillit.desktop.feature.dealmemo.ui.pages.crew.DateInput
import com.zillit.desktop.feature.dealmemo.ui.pages.crew.isoFromStoredCode
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
 * `RichSelect`: a field showing the chosen option — or [triggerText], which
 * the field reads in its place, even for a key the options no longer hold —
 * over the app's one searchable select list. [leading] marks the field while
 * something is shown; [rowLeading] replaces each row's initials tile; a
 * disabled option is greyed and cannot be picked. A list with any [PickOption.badge]
 * draws its own rows so the chip sits beside the label.
 */
@Suppress("LongParameterList")
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
    val shown = triggerText ?: options.firstOrNull { it.key == selectedKey }?.label
    val badged = options.any { it.badge != null }
    DmSelectField(
        shown = shown,
        placeholder = placeholder,
        modifier = modifier,
        enabled = enabled,
        error = error,
        height = height,
        minListWidth = dropdownWidth,
        onClear = if (clearable) ({ onPick(null) }) else null,
        leading = leading,
    ) { width, close ->
        ZillitOptionPopup(
            onDismiss = close,
            options = options,
            isSelected = { it.key == selectedKey },
            onPick = {
                close()
                onPick(it.key)
            },
            label = { it.label },
            width = width,
            searchable = true,
            searchPlaceholder = str(S.dm_picker_search_hint),
            searchText = { it.search },
            subtitle = { it.sub },
            optionLeading = rowLeading,
            renderOption = if (badged) ({ option, selected -> BadgedRow(option, selected, rowLeading) }) else null,
            isEnabled = { !it.disabled },
        )
    }
}

/**
 * A row of a list that carries badges: the shared row's own parts — the
 * leading tile, the label, the muted second line — with the chip beside the label.
 */
@Composable
private fun BadgedRow(option: PickOption, selected: Boolean, leading: (@Composable (PickOption) -> Unit)?) {
    val colors = ZillitTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (leading != null) leading(option) else ZillitInitialsTile(option.label)
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ZillitText(
                    text = option.label,
                    style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = if (selected) colors.accentText else colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                option.badge?.let { PickBadge(it) }
            }
            option.sub?.takeIf { it.isNotBlank() }?.let {
                ZillitText(
                    text = it,
                    style = ZillitTheme.typography.labelSmall,
                    color = colors.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
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

/**
 * `W.sel`: a native select — a first "none" row ([placeholder], key `""`),
 * then the options; retired options show, greyed and unpickable. A value no
 * option holds is shown as it is stored.
 */
@Suppress("LongParameterList")
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
    val rows = listOfNotNull(placeholder?.let { PickOption("", it) }) + options
    val label = rows.firstOrNull { it.key == value }?.label ?: value
    DmSelectField(
        shown = if (value.isEmpty() && placeholder != null) null else label,
        placeholder = placeholder.orEmpty(),
        modifier = modifier,
        enabled = enabled,
        error = error,
        height = height,
        textSize = textSize,
        minListWidth = menuWidth ?: MENU_MIN,
    ) { width, close ->
        ZillitOptionPopup(
            onDismiss = close,
            options = rows,
            // The "none" row is never ticked, as the native select's own.
            isSelected = { it.key.isNotEmpty() && it.key == value },
            onPick = {
                close()
                onPick(it.key)
            },
            label = { it.label },
            width = width,
            isEnabled = { !it.disabled },
            // The "none" row keeps the tile's space but draws none.
            optionLeading = { ZillitInitialsTile(if (it.key.isEmpty()) "" else it.label) },
        )
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
            AddressLine(str(S.dm_step2_address_line1), "12 Baker Street", address.line1, enabled) {
                onChange(address.copy(line1 = it))
            }
        }
        cell(span = 2) {
            AddressLine(
                str(S.dm_step2_address_line2),
                "Flat 4",
                address.line2,
                enabled,
            ) { onChange(address.copy(line2 = it)) }
        }
        cell { AddressLine(str(S.city), "London", address.city, enabled) { onChange(address.copy(city = it)) } }
        cell {
            AddressLine(str(S.dm_step2_address_state), "Greater London", address.state, enabled) {
                onChange(address.copy(state = it))
            }
        }
        cell {
            AddressLine(str(S.desktop_dm_postal_code_zip), "NW1 6XE", address.postalCode, enabled, mono = true) {
                onChange(address.copy(postalCode = it))
            }
        }
        cell {
            Field(str(S.dm_step2_address_country)) {
                val selected = countries.firstOrNull { it.name == address.country }
                CountryPicker(
                    countries = countries,
                    selectedCode = selected?.code,
                    triggerText = selected?.name ?: address.country,
                    placeholder = str(S.desktop_dm_select_country),
                    onPick = { onChange(address.copy(country = it?.name)) },
                    rowStyle = CountryRowStyle.Name,
                    height = CONTROL_HEIGHT,
                    textSize = 14f,
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
                placeholder = str(S.dm_loanout_country_code),
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
            )
            BuilderInput(
                value = number,
                onValueChange = { typed -> onNumber(typed.filter { it.isDigit() }) },
                placeholder = str(S.dm_step2_representative_phone_hint),
                modifier = Modifier.weight(1f),
                error = error != null,
            )
        }
        error?.let { ErrorText(it) }
    }
}

private val MENU_MIN = 240.dp
