package com.zillit.desktop.feature.dealmemo.ui.pages.crew

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.component.ZillitOptionPopup
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.dealmemo.domain.preview.DealAddress
import com.zillit.desktop.feature.dealmemo.domain.preview.DealCountry
import com.zillit.desktop.feature.dealmemo.ui.components.DmSelectField
import com.zillit.desktop.feature.dealmemo.ui.components.DmType

/** How a country row reads: the name alone, a phone code first, or a name over its code. */
internal enum class CountryRowStyle { Name, Phone, Country }

/**
 * `RichSelect` over the ISD list, keyed on the ISO code — dial codes collide.
 * The field reads [triggerText] (what the caller stores, even when no country
 * holds it); the list is the app's one searchable select list, its rows in
 * [rowStyle]: a name alone, or — led by the ISO-code tile — a dial code over
 * its country, or a country over its dial and ISO codes.
 */
@Suppress("LongParameterList")
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
    enabled: Boolean = true,
) {
    DmSelectField(
        shown = triggerText,
        placeholder = placeholder,
        modifier = modifier,
        enabled = enabled,
        height = height,
        textSize = textSize,
        minListWidth = dropdownWidth,
        onClear = { onPick(null) },
    ) { width, close ->
        ZillitOptionPopup(
            onDismiss = close,
            options = countries,
            isSelected = { it.code == selectedCode },
            onPick = {
                close()
                onPick(it)
            },
            label = { if (rowStyle == CountryRowStyle.Phone) it.dialCode else it.name },
            width = width,
            searchable = true,
            searchPlaceholder = str(S.dm_picker_search_hint),
            searchText = { country ->
                if (rowStyle == CountryRowStyle.Name) {
                    "${country.name} ${country.code}"
                } else {
                    "${country.name} ${country.code} ${country.dialCode}"
                }
            },
            subtitle = when (rowStyle) {
                CountryRowStyle.Name -> null
                CountryRowStyle.Phone -> ({ it.name })
                CountryRowStyle.Country -> ({ "${it.dialCode} · ${it.code}" })
            },
            optionLeading = if (rowStyle == CountryRowStyle.Name) null else ({ Monogram(it.code) }),
        )
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
                AddressLine(
                    str(S.dm_step2_address_line1),
                    "12 Baker Street",
                    address.line1,
                ) { onChange(address.copy(line1 = it)) }
            }
            wide {
                AddressLine(str(S.dm_step2_address_line2), "Flat 4", address.line2) {
                    onChange(address.copy(line2 = it))
                }
            }
            half { AddressLine(str(S.city), "London", address.city) { onChange(address.copy(city = it)) } }
            half {
                AddressLine(
                    str(S.dm_step2_address_state),
                    "Greater London",
                    address.state,
                ) { onChange(address.copy(state = it)) }
            }
            half {
                AddressLine(str(S.desktop_dm_postal_code_zip), "NW1 6XE", address.postalCode, mono = true) {
                    onChange(address.copy(postalCode = it))
                }
            }
            half {
                Column {
                    WizLabel(str(S.dm_step2_address_country))
                    val selected = countries.firstOrNull { it.name == address.country }
                    CountryPicker(
                        countries = countries,
                        selectedCode = selected?.code,
                        triggerText = selected?.name,
                        placeholder = str(S.desktop_dm_select_country),
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
                placeholder = if (mode == DialMode.Iso) str(S.dm_phone_code_hint) else str(S.dm_loanout_country_code),
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
