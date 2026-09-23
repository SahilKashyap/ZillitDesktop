@file:Suppress("MatchingDeclarationName") // The code field; CoaCodes is only its reading of the chart.

package com.zillit.desktop.feature.dealmemo.ui.pages.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.dealmemo.ui.DealCoaAccount
import com.zillit.desktop.feature.dealmemo.ui.components.DmType
import com.zillit.desktop.feature.dealmemo.ui.preview.CoaState

/** The chart of accounts as the code pickers read it (`lib/coa.js`). */
internal object CoaCodes {

    /** Nominal and code rows with Posting ticked; an untyped chart offers every row. */
    fun pickable(accounts: List<DealCoaAccount>): List<DealCoaAccount> = accounts.filter { account ->
        account.posting && account.lineType.let { it == null || it == "category" || it == "sub_category" }
    }

    /** `rankRows`: exact code, code prefix, name prefix, code substring, name substring. */
    fun rank(accounts: List<DealCoaAccount>, query: String): List<DealCoaAccount> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return accounts
        return accounts.mapNotNull { account ->
            val code = account.code.lowercase()
            val name = account.name.lowercase()
            val score = when {
                code == q -> EXACT
                code.startsWith(q) -> CODE_PREFIX
                name.startsWith(q) -> NAME_PREFIX
                q in code -> CODE_CONTAINS
                q in name -> NAME_CONTAINS
                else -> 0
            }
            account.takeIf { score > 0 }?.let { it to score }
        }.sortedByDescending { it.second }.map { it.first }
    }

    private const val EXACT = 100
    private const val CODE_PREFIX = 80
    private const val NAME_PREFIX = 60
    private const val CODE_CONTAINS = 40
    private const val NAME_CONTAINS = 20
}

/**
 * `CoaCodeInput`: a code field that suggests from the chart of accounts — the
 * code on the left, its name on the right — and still keeps a code the chart
 * does not have. Closed it shows the code; open, the live query.
 */
@Suppress("CyclomaticComplexMethod", "LongMethod")
@Composable
internal fun CoaCodeField(
    value: String,
    onValueChange: (String) -> Unit,
    coa: CoaState,
    modifier: Modifier = Modifier,
    placeholder: String = str(S.dm_nom_override_hint),
    alignEnd: Boolean = true,
    height: Dp = 34.dp,
    borderless: Boolean = false,
) {
    val focus = remember { MutableInteractionSource() }
    val focused by focus.collectIsFocusedAsState()
    var open by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf(value) }
    var highlight by remember { mutableIntStateOf(0) }
    LaunchedEffect(focused) {
        if (focused) {
            query = value
            open = true
        } else {
            open = false
        }
    }
    val pickable = remember(coa.accounts) { CoaCodes.pickable(coa.accounts) }
    val matches = remember(pickable, query) { CoaCodes.rank(pickable, query) }
    val shape = RoundedCornerShape(8.dp)
    fun commit(code: String) {
        onValueChange(code)
        query = code
        open = false
    }
    Box(modifier = modifier) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(height)
                .clip(shape)
                .background(if (borderless) Color.Transparent else pv.card)
                .then(
                    if (borderless) {
                        Modifier
                    } else {
                        Modifier.border(1.dp, if (focused) Color(0xFFF6D8A8) else pv.chipBorder, shape)
                    },
                )
                .padding(horizontal = 10.dp),
            contentAlignment = if (alignEnd) Alignment.CenterEnd else Alignment.CenterStart,
        ) {
            val shown = if (open) query else value
            if (shown.isEmpty()) {
                ZillitText(
                    text = placeholder,
                    style = DmType.mono(12.sp, FontWeight.SemiBold),
                    color = pv.faint,
                    textAlign = if (alignEnd) TextAlign.End else TextAlign.Start,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            BasicTextField(
                value = shown,
                onValueChange = {
                    query = it
                    highlight = 0
                    open = true
                    onValueChange(it)
                },
                singleLine = true,
                interactionSource = focus,
                textStyle = TextStyle(
                    fontFamily = DmType.mono(12.sp).fontFamily,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = pv.ink,
                    textAlign = if (alignEnd) TextAlign.End else TextAlign.Start,
                ),
                cursorBrush = SolidColor(PreviewInk.Action),
                modifier = Modifier
                    .fillMaxWidth()
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown || !open) return@onPreviewKeyEvent false
                        when (event.key) {
                            Key.DirectionDown -> {
                                highlight = (highlight + 1).coerceAtMost(matches.lastIndex.coerceAtLeast(0)); true
                            }
                            Key.DirectionUp -> { highlight = (highlight - 1).coerceAtLeast(0); true }
                            Key.Enter -> {
                                commit(matches.getOrNull(highlight)?.code ?: query.trim())
                                true
                            }
                            Key.Escape -> { open = false; true }
                            else -> false
                        }
                    },
            )
        }
        if (open) {
            Popup(
                popupPositionProvider = remember { BelowEndPosition(gap = 4) },
                onDismissRequest = { open = false },
                properties = PopupProperties(focusable = false),
            ) {
                CoaDropdown(coa, pickable.size, matches, query, highlight, onPick = ::commit)
            }
        }
    }
}

@Composable
private fun CoaDropdown(
    coa: CoaState,
    pickableCount: Int,
    matches: List<DealCoaAccount>,
    query: String,
    highlight: Int,
    onPick: (String) -> Unit,
) {
    val shape = RoundedCornerShape(10.dp)
    val typed = query.trim()
    val message = when {
        coa.failed -> str(S.desktop_dm_couldnt_load_the_chart_of_accounts_reload)
        !coa.loaded -> str(S.desktop_dm_loading_chart_of_accounts)
        coa.accounts.isEmpty() -> str(S.desktop_dm_this_projects_chart_of_accounts_is_empty)
        pickableCount == 0 -> if (coa.accounts.size == 1) {
            str(S.desktop_dm_coa_one_code_not_pickable)
        } else {
            str(S.desktop_dm_coa_n_codes_not_pickable, coa.accounts.size)
        }
        matches.isEmpty() && typed.isEmpty() -> str(S.desktop_dm_type_to_filter)
        else -> null
    }
    // A code the chart doesn't have is still kept — offered as its own row.
    val freeText = typed.isNotEmpty() && matches.none { it.code.equals(typed, ignoreCase = true) }
    val rows = matches.size + if (freeText) 1 else 0
    Column(
        modifier = Modifier
            .width(DROPDOWN_WIDTH.dp)
            .shadowed(shape)
            .clip(shape)
            .background(pv.card)
            .border(1.dp, pv.barBorder, shape),
    ) {
        message?.let {
            ZillitText(text = it, style = DmType.sans(12.sp), color = pv.muted, modifier = Modifier.padding(12.dp))
        }
        if (rows > 0) {
            // A lazy list in a popup needs a fixed height.
            LazyColumn(modifier = Modifier.fillMaxWidth().height((rows.coerceAtMost(MAX_ROWS) * ROW_HEIGHT).dp)) {
                if (freeText) {
                    item {
                        CoaRow(
                            typed,
                            str(S.desktop_dm_use_code_not_in_chart_of_accounts),
                            highlighted = false,
                            onClick = { onPick(typed) },
                        )
                    }
                }
                itemsIndexed(matches) { index, account ->
                    CoaRow(
                        account.code,
                        account.name,
                        highlighted = index == highlight,
                        onClick = { onPick(account.code) },
                    )
                }
            }
        }
    }
}

@Composable
private fun CoaRow(code: String, name: String, highlighted: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(ROW_HEIGHT.dp)
            .background(if (highlighted) Color(0xFFFDF8EE) else Color.Transparent)
            .clickable(onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ZillitText(text = code, style = DmType.mono(12.5.sp, FontWeight.SemiBold), color = pv.ink, maxLines = 1)
        ZillitText(
            text = name,
            style = DmType.sans(12.sp),
            color = pv.muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f),
        )
    }
}

private const val DROPDOWN_WIDTH = 340
private const val ROW_HEIGHT = 34
private const val MAX_ROWS = 7
