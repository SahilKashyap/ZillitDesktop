@file:Suppress("MagicNumber", "TooManyFunctions") // Flag geometry is proportions; one painter per flag.

package com.zillit.desktop.feature.dealmemo.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * A territory's flag, drawn.
 *
 * The web renders SVG flags rather than emoji because Windows has no flag
 * glyphs (`components/ui/Flag.jsx`); the desktop ships to Windows too, so it
 * draws them. Simplified at the small sizes they appear at — stripes, crosses,
 * discs and stars — but each is recognisably its country. The box is the
 * web's `size × 0.75` with 2 px corners and a hairline.
 */
@Composable
fun TerritoryFlag(territoryId: String?, width: Dp, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(2.dp)
    Canvas(
        modifier = modifier
            .size(width, width * 0.75f)
            .clip(shape)
            .border(1.dp, Color.Black.copy(alpha = 0.06f), shape),
    ) {
        FLAGS[territoryId?.lowercase()]?.invoke(this) ?: unknownFlag()
    }
}

private val FLAGS: Map<String, DrawScope.() -> Unit> = mapOf(
    "uk" to { unionJack(Offset.Zero, size) },
    "ie" to { vBands(IE_GREEN, Color.White, IE_ORANGE) },
    "us" to { usFlag() },
    "ca" to { canadaFlag() },
    "at" to { hBands(AT_RED, Color.White, AT_RED) },
    "pt" to { portugalFlag() },
    "gr" to { greeceFlag() },
    "de" to { hBands(Color.Black, DE_RED, DE_GOLD) },
    "fr" to { vBands(FR_BLUE, Color.White, FR_RED) },
    "es" to { hBandsWeighted(listOf(ES_RED to 1f, ES_YELLOW to 2f, ES_RED to 1f)) },
    "it" to { vBands(IT_GREEN, Color.White, IT_RED) },
    "be" to { vBands(Color.Black, BE_YELLOW, BE_RED) },
    "nl" to { hBands(NL_RED, Color.White, NL_BLUE) },
    "au" to { southernCross(AU_BLUE, Color.White, australia = true) },
    "nz" to { southernCross(AU_BLUE, NZ_RED, australia = false) },
    "jp" to { japanFlag() },
    "in" to { indiaFlag() },
    "sg" to { singaporeFlag() },
    "kr" to { koreaFlag() },
    "ae" to { uaeFlag() },
    "za" to { southAfricaFlag() },
    "ma" to { moroccoFlag() },
    "mx" to { emblemTricolour(MX_GREEN, Color.White, MX_RED, MX_BROWN) },
    "br" to { brazilFlag() },
    "co" to { hBandsWeighted(listOf(CO_YELLOW to 2f, CO_BLUE to 1f, CO_RED to 1f)) },
    "ar" to { argentinaFlag() },
    "dk" to { nordicCross(DK_RED, Color.White) },
    "fi" to { nordicCross(Color.White, FI_BLUE) },
    "no" to { nordicCross(NO_RED, Color.White, NO_BLUE) },
    "se" to { nordicCross(SE_BLUE, SE_YELLOW) },
    "cz" to { czechFlag() },
    "hu" to { hBands(HU_RED, Color.White, HU_GREEN) },
    "pl" to { hBands(Color.White, PL_RED) },
    "ro" to { vBands(RO_BLUE, RO_YELLOW, RO_RED) },
    "bg" to { hBands(Color.White, BG_GREEN, BG_RED) },
    "hr" to { hBandsWithShield(HR_RED, Color.White, HR_BLUE, centre = true) },
    "rs" to { hBandsWithShield(RS_RED, RS_BLUE, Color.White, centre = false) },
    "mt" to { maltaFlag() },
    "lu" to { hBands(LU_RED, Color.White, LU_BLUE) },
    "is" to { nordicCross(IS_BLUE, Color.White, IS_RED) },
    "il" to { israelFlag() },
)

// -- building blocks ------------------------------------------------------------------

private fun DrawScope.hBands(first: Color, second: Color, third: Color? = null) =
    hBandsWeighted(listOfNotNull(first, second, third).map { it to 1f })

private fun DrawScope.hBandsWeighted(bands: List<Pair<Color, Float>>) {
    val total = bands.sumOf { it.second.toDouble() }.toFloat()
    var y = 0f
    bands.forEach { (color, weight) ->
        val h = size.height * weight / total
        drawRect(color, Offset(0f, y), Size(size.width, h + 0.5f))
        y += h
    }
}

private fun DrawScope.vBands(first: Color, second: Color, third: Color? = null) {
    val colors = listOfNotNull(first, second, third)
    val w = size.width / colors.size
    colors.forEachIndexed { i, color -> drawRect(color, Offset(w * i, 0f), Size(w + 0.5f, size.height)) }
}

/** A Nordic cross, offset towards the hoist, optionally with an inner cross. */
private fun DrawScope.nordicCross(background: Color, cross: Color, inner: Color? = null) {
    drawRect(background)
    val h = size.height
    val w = size.width
    val arm = h * 0.25f
    val x = w * 0.32f
    drawRect(cross, Offset(x, 0f), Size(arm, h))
    drawRect(cross, Offset(0f, (h - arm) / 2), Size(w, arm))
    if (inner != null) {
        val thin = arm * 0.5f
        drawRect(inner, Offset(x + (arm - thin) / 2, 0f), Size(thin, h))
        drawRect(inner, Offset(0f, (h - thin) / 2), Size(w, thin))
    }
}

/** A five-pointed star centred on [c] with outer radius [r]. */
private fun starPath(c: Offset, r: Float, points: Int = 5, innerRatio: Float = 0.4f): Path = Path().apply {
    for (i in 0 until points * 2) {
        val radius = if (i % 2 == 0) r else r * innerRatio
        val angle = -PI / 2 + i * PI / points
        val p = Offset(c.x + (radius * cos(angle)).toFloat(), c.y + (radius * sin(angle)).toFloat())
        if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
    }
    close()
}

/** The Union Jack filling [size] at [origin]. */
private fun DrawScope.unionJack(origin: Offset, area: Size) {
    clipRect(origin.x, origin.y, origin.x + area.width, origin.y + area.height) {
        drawRect(UK_BLUE, origin, area)
        val w = area.width
        val h = area.height
        val tl = origin
        val br = Offset(origin.x + w, origin.y + h)
        val tr = Offset(origin.x + w, origin.y)
        val bl = Offset(origin.x, origin.y + h)
        drawLine(Color.White, tl, br, strokeWidth = h * 0.2f)
        drawLine(Color.White, tr, bl, strokeWidth = h * 0.2f)
        drawLine(UK_RED, tl, br, strokeWidth = h * 0.066f)
        drawLine(UK_RED, tr, bl, strokeWidth = h * 0.066f)
        val white = h * 0.333f
        val red = h * 0.2f
        drawRect(Color.White, Offset(origin.x + (w - white) / 2, origin.y), Size(white, h))
        drawRect(Color.White, Offset(origin.x, origin.y + (h - white) / 2), Size(w, white))
        drawRect(UK_RED, Offset(origin.x + (w - red) / 2, origin.y), Size(red, h))
        drawRect(UK_RED, Offset(origin.x, origin.y + (h - red) / 2), Size(w, red))
    }
}

// -- the harder ones ------------------------------------------------------------------

private fun DrawScope.usFlag() {
    val stripe = size.height / 13
    for (i in 0 until 13) drawRect(
        if (i % 2 == 0) US_RED else Color.White,
        Offset(0f, stripe * i),
        Size(size.width, stripe + 0.5f),
    )
    val cantonW = size.width * 0.4f
    val cantonH = stripe * 7
    drawRect(US_BLUE, Offset.Zero, Size(cantonW, cantonH))
    val dot = cantonH * 0.045f
    for (row in 0 until 4) for (col in 0 until 5) {
        drawCircle(Color.White, dot, Offset(cantonW * (col + 0.6f) / 5.2f, cantonH * (row + 0.6f) / 4.2f))
    }
}

private fun DrawScope.canadaFlag() {
    val w = size.width
    drawRect(Color.White)
    drawRect(CA_RED, Offset.Zero, Size(w / 4, size.height))
    drawRect(CA_RED, Offset(w * 3 / 4, 0f), Size(w / 4 + 0.5f, size.height))
    drawPath(starPath(Offset(w / 2, size.height * 0.52f), size.height * 0.3f, points = 7, innerRatio = 0.55f), CA_RED)
}

private fun DrawScope.portugalFlag() {
    drawRect(PT_GREEN, Offset.Zero, Size(size.width * 0.4f, size.height))
    drawRect(PT_RED, Offset(size.width * 0.4f, 0f), Size(size.width * 0.6f, size.height))
    drawCircle(PT_YELLOW, size.height * 0.22f, Offset(size.width * 0.4f, size.height / 2))
    drawCircle(PT_RED, size.height * 0.12f, Offset(size.width * 0.4f, size.height / 2))
}

private fun DrawScope.greeceFlag() {
    val stripe = size.height / 9
    for (i in 0 until 9) drawRect(
        if (i % 2 == 0) GR_BLUE else Color.White,
        Offset(0f, stripe * i),
        Size(size.width, stripe + 0.5f),
    )
    val canton = stripe * 5
    drawRect(GR_BLUE, Offset.Zero, Size(canton, canton))
    drawRect(Color.White, Offset(stripe * 2, 0f), Size(stripe, canton))
    drawRect(Color.White, Offset(0f, stripe * 2), Size(canton, stripe))
}

private fun DrawScope.southernCross(background: Color, starColor: Color, australia: Boolean) {
    drawRect(background)
    unionJack(Offset.Zero, Size(size.width / 2, size.height / 2))
    val h = size.height
    val w = size.width
    val stars = listOf(
        Offset(w * 0.75f, h * 0.2f),
        Offset(w * 0.62f, h * 0.45f),
        Offset(w * 0.86f, h * 0.4f),
        Offset(w * 0.75f, h * 0.8f),
    )
    stars.forEach { c ->
        if (!australia) drawPath(starPath(c, h * 0.085f), Color.White)
        drawPath(starPath(c, h * 0.065f), starColor)
    }
    if (australia) drawPath(starPath(Offset(w * 0.25f, h * 0.75f), h * 0.12f, points = 7), Color.White)
}

private fun DrawScope.japanFlag() {
    drawRect(Color.White)
    drawCircle(JP_RED, size.height * 0.3f, center)
}

private fun DrawScope.indiaFlag() {
    hBands(IN_SAFFRON, Color.White, IN_GREEN)
    drawCircle(IN_NAVY, size.height * 0.13f, center, style = Stroke(width = size.height * 0.03f))
}

private fun DrawScope.singaporeFlag() {
    hBands(SG_RED, Color.White)
    val h = size.height
    drawCircle(Color.White, h * 0.2f, Offset(size.width * 0.2f, h * 0.25f))
    drawCircle(SG_RED, h * 0.18f, Offset(size.width * 0.25f, h * 0.25f))
    listOf(0.33f to 0.14f, 0.42f to 0.2f, 0.4f to 0.32f, 0.27f to 0.32f, 0.25f to 0.2f).forEach { (x, y) ->
        drawPath(starPath(Offset(size.width * x + h * 0.02f, h * y + h * 0.01f), h * 0.035f), Color.White)
    }
}

private fun DrawScope.koreaFlag() {
    drawRect(Color.White)
    val r = size.height * 0.24f
    drawArc(KR_RED, 180f, 180f, useCenter = true, topLeft = center - Offset(r, r), size = Size(r * 2, r * 2))
    drawArc(KR_BLUE, 0f, 180f, useCenter = true, topLeft = center - Offset(r, r), size = Size(r * 2, r * 2))
    val bar = Size(size.height * 0.16f, size.height * 0.035f)
    val corners = listOf(Offset(0.2f, 0.22f), Offset(0.8f, 0.22f), Offset(0.2f, 0.78f), Offset(0.8f, 0.78f))
    corners.forEach { c ->
        for (i in -1..1) {
            drawRect(
                Color.Black,
                Offset(size.width * c.x - bar.width / 2, size.height * c.y + i * bar.height * 1.8f - bar.height / 2),
                bar,
            )
        }
    }
}

private fun DrawScope.uaeFlag() {
    val hoist = size.width * 0.25f
    val band = size.height / 3
    drawRect(AE_GREEN, Offset(hoist, 0f), Size(size.width - hoist, band + 0.5f))
    drawRect(Color.White, Offset(hoist, band), Size(size.width - hoist, band + 0.5f))
    drawRect(Color.Black, Offset(hoist, band * 2), Size(size.width - hoist, band + 0.5f))
    drawRect(AE_RED, Offset.Zero, Size(hoist, size.height))
}

private fun DrawScope.southAfricaFlag() {
    val w = size.width
    val h = size.height
    drawRect(ZA_RED, Offset.Zero, Size(w, h / 2))
    drawRect(ZA_BLUE, Offset(0f, h / 2), Size(w, h / 2))
    val y = Path().apply {
        moveTo(0f, 0f); lineTo(w * 0.42f, h * 0.38f); lineTo(w, h * 0.38f); lineTo(w, h * 0.62f)
        lineTo(w * 0.42f, h * 0.62f); lineTo(0f, h); close()
    }
    drawPath(y, Color.White)
    val green = Path().apply {
        moveTo(0f, h * 0.1f); lineTo(w * 0.4f, h * 0.44f); lineTo(w, h * 0.44f); lineTo(w, h * 0.56f)
        lineTo(w * 0.4f, h * 0.56f); lineTo(0f, h * 0.9f); close()
    }
    drawPath(green, ZA_GREEN)
    val triangle = Path().apply { moveTo(0f, h * 0.22f); lineTo(w * 0.3f, h / 2); lineTo(0f, h * 0.78f); close() }
    drawPath(triangle, ZA_YELLOW)
    val inner = Path().apply { moveTo(0f, h * 0.3f); lineTo(w * 0.22f, h / 2); lineTo(0f, h * 0.7f); close() }
    drawPath(inner, Color.Black)
}

private fun DrawScope.moroccoFlag() {
    drawRect(MA_RED)
    drawPath(
        starPath(center, size.height * 0.26f, innerRatio = 0.38f),
        MA_GREEN,
        style = Stroke(width = size.height * 0.05f),
    )
}

private fun DrawScope.emblemTricolour(left: Color, middle: Color, right: Color, emblem: Color) {
    vBands(left, middle, right)
    drawCircle(emblem, size.height * 0.12f, center)
}

private fun DrawScope.brazilFlag() {
    drawRect(BR_GREEN)
    val w = size.width
    val h = size.height
    val rhombus = Path().apply {
        moveTo(w * 0.08f, h / 2); lineTo(w / 2, h * 0.1f); lineTo(w * 0.92f, h / 2); lineTo(w / 2, h * 0.9f); close()
    }
    drawPath(rhombus, BR_YELLOW)
    drawCircle(BR_BLUE, h * 0.25f, center)
}

private fun DrawScope.argentinaFlag() {
    hBands(AR_BLUE, Color.White, AR_BLUE)
    drawCircle(AR_SUN, size.height * 0.1f, center)
}

private fun DrawScope.czechFlag() {
    hBands(Color.White, CZ_RED)
    val triangle = Path().apply {
        moveTo(0f, 0f)
        lineTo(size.width * 0.5f, size.height / 2)
        lineTo(0f, size.height)
        close()
    }
    drawPath(triangle, CZ_BLUE)
}

private fun DrawScope.hBandsWithShield(top: Color, middle: Color, bottom: Color, centre: Boolean) {
    hBands(top, middle, bottom)
    val s = size.height * 0.34f
    val x = if (centre) (size.width - s) / 2 else size.width * 0.28f - s / 2
    val y = (size.height - s) / 2
    drawRect(Color.White, Offset(x, y), Size(s, s))
    val cell = s / 3
    for (row in 0 until 3) for (col in 0 until 3) {
        if ((row + col) % 2 == 0) drawRect(HR_RED, Offset(x + col * cell, y + row * cell), Size(cell, cell))
    }
}

private fun DrawScope.maltaFlag() {
    vBands(Color.White, MT_RED)
    val s = size.height * 0.16f
    drawRect(MT_GREY, Offset(size.width * 0.12f, size.height * 0.12f), Size(s, s * 0.34f))
    drawRect(MT_GREY, Offset(size.width * 0.12f + s * 0.33f, size.height * 0.12f - s * 0.33f), Size(s * 0.34f, s))
}

private fun DrawScope.israelFlag() {
    drawRect(Color.White)
    val h = size.height
    drawRect(IL_BLUE, Offset(0f, h * 0.12f), Size(size.width, h * 0.12f))
    drawRect(IL_BLUE, Offset(0f, h * 0.76f), Size(size.width, h * 0.12f))
    val r = h * 0.18f
    val up = Path().apply {
        moveTo(center.x, center.y - r)
        lineTo(center.x + r * 0.87f, center.y + r / 2)
        lineTo(center.x - r * 0.87f, center.y + r / 2)
        close()
    }
    val down = Path().apply {
        moveTo(center.x, center.y + r)
        lineTo(center.x + r * 0.87f, center.y - r / 2)
        lineTo(center.x - r * 0.87f, center.y - r / 2)
        close()
    }
    val stroke = Stroke(width = h * 0.035f)
    drawPath(up, IL_BLUE, style = stroke)
    drawPath(down, IL_BLUE, style = stroke)
}

/** A territory the catalogue does not draw: a neutral tile. */
private fun DrawScope.unknownFlag() {
    drawRect(Color(0xFFE5E7EB))
}

private val UK_BLUE = Color(0xFF012169)
private val UK_RED = Color(0xFFC8102E)
private val IE_GREEN = Color(0xFF169B62)
private val IE_ORANGE = Color(0xFFFF883E)
private val US_RED = Color(0xFFB22234)
private val US_BLUE = Color(0xFF3C3B6E)
private val CA_RED = Color(0xFFD52B1E)
private val AT_RED = Color(0xFFC8102E)
private val PT_GREEN = Color(0xFF046A38)
private val PT_RED = Color(0xFFDA291C)
private val PT_YELLOW = Color(0xFFFFE900)
private val GR_BLUE = Color(0xFF0D5EAF)
private val DE_RED = Color(0xFFDD0000)
private val DE_GOLD = Color(0xFFFFCE00)
private val FR_BLUE = Color(0xFF002395)
private val FR_RED = Color(0xFFED2939)
private val ES_RED = Color(0xFFAA151B)
private val ES_YELLOW = Color(0xFFF1BF00)
private val IT_GREEN = Color(0xFF009246)
private val IT_RED = Color(0xFFCE2B37)
private val BE_YELLOW = Color(0xFFFDDA24)
private val BE_RED = Color(0xFFEF3340)
private val NL_RED = Color(0xFFAE1C28)
private val NL_BLUE = Color(0xFF21468B)
private val AU_BLUE = Color(0xFF012169)
private val NZ_RED = Color(0xFFC8102E)
private val JP_RED = Color(0xFFBC002D)
private val IN_SAFFRON = Color(0xFFFF9933)
private val IN_GREEN = Color(0xFF138808)
private val IN_NAVY = Color(0xFF000080)
private val SG_RED = Color(0xFFEF3340)
private val KR_RED = Color(0xFFCD2E3A)
private val KR_BLUE = Color(0xFF0047A0)
private val AE_GREEN = Color(0xFF00732F)
private val AE_RED = Color(0xFFFF0000)
private val ZA_RED = Color(0xFFE03C31)
private val ZA_BLUE = Color(0xFF001489)
private val ZA_GREEN = Color(0xFF007749)
private val ZA_YELLOW = Color(0xFFFFB81C)
private val MA_RED = Color(0xFFC1272D)
private val MA_GREEN = Color(0xFF006233)
private val MX_GREEN = Color(0xFF006847)
private val MX_RED = Color(0xFFCE1126)
private val MX_BROWN = Color(0xFF8C5A2B)
private val BR_GREEN = Color(0xFF009C3B)
private val BR_YELLOW = Color(0xFFFFDF00)
private val BR_BLUE = Color(0xFF002776)
private val CO_YELLOW = Color(0xFFFCD116)
private val CO_BLUE = Color(0xFF003893)
private val CO_RED = Color(0xFFCE1126)
private val AR_BLUE = Color(0xFF74ACDF)
private val AR_SUN = Color(0xFFF6B40E)
private val DK_RED = Color(0xFFC8102E)
private val FI_BLUE = Color(0xFF002F6C)
private val NO_RED = Color(0xFFBA0C2F)
private val NO_BLUE = Color(0xFF00205B)
private val SE_BLUE = Color(0xFF006AA7)
private val SE_YELLOW = Color(0xFFFECC02)
private val CZ_RED = Color(0xFFD7141A)
private val CZ_BLUE = Color(0xFF11457E)
private val HU_RED = Color(0xFFCD2A3E)
private val HU_GREEN = Color(0xFF436F4D)
private val PL_RED = Color(0xFFDC143C)
private val RO_BLUE = Color(0xFF002B7F)
private val RO_YELLOW = Color(0xFFFCD116)
private val RO_RED = Color(0xFFCE1126)
private val BG_GREEN = Color(0xFF00966E)
private val BG_RED = Color(0xFFD62612)
private val HR_RED = Color(0xFFFF0000)
private val HR_BLUE = Color(0xFF171796)
private val RS_RED = Color(0xFFC6363C)
private val RS_BLUE = Color(0xFF0C4076)
private val MT_RED = Color(0xFFCF142B)
private val MT_GREY = Color(0xFF999999)
private val LU_RED = Color(0xFFEF3340)
private val LU_BLUE = Color(0xFF00A3E0)
private val IS_BLUE = Color(0xFF02529C)
private val IS_RED = Color(0xFFDC1E35)
private val IL_BLUE = Color(0xFF0038B8)
