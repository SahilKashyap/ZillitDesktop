package com.zillit.desktop.feature.recce

import com.zillit.desktop.feature.recce.domain.Weather
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The weather line is one string on the wire, split into fields for the form
 * and joined back on save. What matters is that an edited recce round-trips
 * the string the web wrote — otherwise every save rewrites a field nobody
 * touched, and the audit trail fills with phantom edits.
 */
class WeatherTest {

    /** The shape the web writes: en dash, spaced, unit on both ends. */
    @Test
    fun `a range round-trips unchanged`() {
        val text = "12°C – 18°C, light cloud"

        val parsed = Weather.parse(text)

        assertEquals("12", parsed.low)
        assertEquals("18", parsed.high)
        assertEquals("C", parsed.unit)
        assertEquals("light cloud", parsed.conditions)
        assertEquals(text, parsed.compose())
    }

    /** A hyphen or the word "to" is read, and normalised on the way back. */
    @Test
    fun `other separators are understood`() {
        assertEquals("12", Weather.parse("12C - 18C, rain").low)
        assertEquals("18", Weather.parse("12 to 18, rain").high)
    }

    /** One temperature is a legitimate reading, not a broken range. */
    @Test
    fun `a single temperature keeps its place`() {
        val parsed = Weather.parse("21°C, clear")

        assertEquals("21", parsed.low)
        assertEquals("", parsed.high)
        assertEquals("21°C, clear", parsed.compose())
    }

    /** Fahrenheit survives the round trip rather than being coerced. */
    @Test
    fun `fahrenheit is kept`() {
        val parsed = Weather.parse("60°F – 75°F, breezy")

        assertEquals("F", parsed.unit)
        assertEquals("60°F – 75°F, breezy", parsed.compose())
    }

    /** Conditions alone — no reading yet, but the note is worth keeping. */
    @Test
    fun `conditions without a temperature survive`() {
        val parsed = Weather.parse("overcast, chance of rain")

        assertEquals("", parsed.low)
        assertEquals("overcast, chance of rain", parsed.conditions)
    }

    /** Nothing in, nothing out — an empty line must not become "°C". */
    @Test
    fun `an empty line composes to nothing`() {
        assertEquals("", Weather().compose())
        assertEquals("", Weather.parse("").compose())
    }

    /** Whitespace is the user's, not the wire's. */
    @Test
    fun `fields are trimmed on the way out`() {
        val weather = Weather(low = " 12 ", high = " 18 ", unit = "C", conditions = "  light cloud  ")

        assertEquals("12°C – 18°C, light cloud", weather.compose())
    }
}
