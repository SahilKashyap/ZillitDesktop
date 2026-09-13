@file:Suppress("MatchingDeclarationName") // The calculator field; CalcState is only its parse.

package com.zillit.desktop.feature.dealmemo.ui.pages.builder

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.feature.dealmemo.domain.authoring.PayloadParts
import com.zillit.desktop.feature.dealmemo.domain.preview.DealCalc
import com.zillit.desktop.feature.dealmemo.domain.rates.Js
import com.zillit.desktop.feature.dealmemo.ui.components.DmType
import com.zillit.desktop.feature.dealmemo.ui.pages.crew.GroupedDigits
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * `CalcInput`'s behaviour, apart from its look: a plain number commits as it
 * is typed, an expression (`2*2`) resolves to two places when the field is
 * left, and a committed figure reads grouped to two places. A cleared field
 * commits `""`; zero reads as the placeholder.
 */
@Stable
internal class CalcState(private val commit: (JsonElement) -> Unit) {

    /** What is being typed; null while the field shows the committed value. */
    var draft by mutableStateOf<String?>(null)
        private set

    val expression: String? get() = draft?.takeIf(DealCalc::isExpression)

    fun shown(value: JsonElement?): String =
        draft ?: Js.parseFloat(value)?.takeIf { it != 0.0 }?.let(DealCalc::grouped).orEmpty()

    val visualTransformation: VisualTransformation
        get() = if (draft != null && expression == null) GroupedDigits else VisualTransformation.None

    fun type(typed: String) {
        val next = typed.replace(",", "")
        if (!DealCalc.isAllowedInput(next)) return
        draft = next
        if (!DealCalc.isExpression(next)) {
            commit(DealCalc.plainValue(next)?.let(PayloadParts::number) ?: JsonPrimitive(""))
        }
    }

    fun leave() {
        val text = draft ?: return
        if (DealCalc.isExpression(text)) {
            DealCalc.evaluate(text)?.let { commit(PayloadParts.number(DealCalc.round2(it))) }
        }
        draft = null
    }
}

@Composable
internal fun rememberCalcState(onCommit: (JsonElement) -> Unit): CalcState {
    val latest by rememberUpdatedState(onCommit)
    return remember { CalcState { latest(it) } }
}

/** A money field in the builder skin. */
@Composable
internal fun CalcInput(
    value: JsonElement?,
    onCommit: (JsonElement) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "0.00",
    prefix: String? = null,
    enabled: Boolean = true,
    height: Dp = CONTROL_HEIGHT,
    textSize: Float = 14f,
    mono: Boolean = false,
) {
    val calc = rememberCalcState(onCommit)
    val focus = LocalFocusManager.current
    Column(modifier) {
        BuilderInput(
            value = calc.shown(value),
            onValueChange = calc::type,
            placeholder = placeholder,
            prefix = prefix,
            enabled = enabled,
            height = height,
            textSize = textSize,
            mono = mono,
            visualTransformation = calc.visualTransformation,
            onBlur = calc::leave,
            onEnter = { focus.clearFocus() },
        )
        CalcPreview(calc)
    }
}

/** `= 4.00` under a field holding an expression; `= —` while it doesn't resolve. */
@Composable
internal fun CalcPreview(calc: CalcState) {
    val expression = calc.expression ?: return
    val result = DealCalc.evaluate(expression)
    ZillitText(
        text = result?.let { "= ${DealCalc.grouped(it)}" } ?: "= —",
        style = DmType.mono(11.sp, FontWeight.Bold),
        color = if (result == null) bp.red else bp.teal,
        textAlign = TextAlign.End,
        modifier = Modifier.fillMaxWidth().padding(top = 3.dp),
    )
}
