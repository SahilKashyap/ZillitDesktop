package com.zillit.desktop.feature.crewlist.ui

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.localization.localisedMessage

/**
 * A failure in words. A `{status: 0, message}` refusal arrives as a
 * [ZillitError.Validation] carrying the server's message KEY, which
 * [localised] leaves verbatim — so it goes through the messages dictionary
 * here. The client's own sentences pass through untouched: a string with a
 * space is never humanised.
 */
internal fun ZillitError.readable(): String = when (this) {
    is ZillitError.Validation -> userMessage.localisedMessage()
    else -> localised()
}
