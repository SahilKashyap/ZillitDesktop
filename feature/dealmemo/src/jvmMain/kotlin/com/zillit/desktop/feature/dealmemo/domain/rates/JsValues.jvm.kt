package com.zillit.desktop.feature.dealmemo.domain.rates

import java.math.BigDecimal
import java.math.RoundingMode

/** `BigDecimal(double)` is the exact binary value — the number `toFixed` rounds. */
internal actual fun exactToFixed(value: Double, digits: Int): String =
    BigDecimal(value).setScale(digits, RoundingMode.HALF_UP).toPlainString()
