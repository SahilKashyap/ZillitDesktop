package com.zillit.desktop.feature.selectstills.domain

/**
 * A write's answer: what came back, and the service's own words about it.
 *
 * The message is carried because this app never authors success copy — the
 * toast after "member removed" is the service's `message` key, translated
 * through the messages dictionary, exactly as the web's `apiToastMessage`
 * does it. A refusal's words already ride on `ZillitError`; this is the other
 * half.
 *
 * [message] is null when the service had nothing to announce, and the screen
 * then stays silent rather than inventing a sentence.
 */
data class Wrote<out T>(val value: T, val message: String? = null)
