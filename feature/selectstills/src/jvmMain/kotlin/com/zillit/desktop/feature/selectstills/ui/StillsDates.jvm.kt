package com.zillit.desktop.feature.selectstills.ui

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * A stored stamp in the reader's own locale and zone — the web's
 * `toLocaleDateString()`.
 *
 * The service sends seconds in some places and milliseconds in others, so a
 * value too small to be a recent millisecond stamp is read as seconds.
 */
internal actual fun formatDay(millis: Long): String = runCatching {
    val at = Instant.ofEpochMilli(if (millis < SECONDS_CEILING) millis * MILLIS_IN_SECOND else millis)
    DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withZone(ZoneId.systemDefault()).format(at)
}.getOrDefault("")

/** A millisecond stamp from this century is far above this; a second stamp is below it. */
private const val SECONDS_CEILING = 100_000_000_000L

private const val MILLIS_IN_SECOND = 1000L

/** A stamp with its time, in the reader's own locale and zone. */
internal actual fun formatStamp(millis: Long): String = runCatching {
    val at = Instant.ofEpochMilli(if (millis < SECONDS_CEILING) millis * MILLIS_IN_SECOND else millis)
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
        .withZone(ZoneId.systemDefault())
        .format(at)
}.getOrDefault("")
