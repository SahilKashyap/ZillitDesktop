package com.zillit.desktop.core.localization

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.applyMessageElements
import com.zillit.desktop.core.common.withoutUnfilledPlaceholders

/**
 * What to actually put in front of someone when a call fails.
 *
 * `ZillitError.userMessage` is right for every failure the client diagnoses
 * itself — no connection, TLS, a bad decode. It is wrong for the one case the
 * server diagnosed: [ZillitError.Http] carries the backend's `message` field
 * verbatim, and the backend writes message **keys**, not sentences. Left alone,
 * a user is shown `permanent_trip_id_required`.
 *
 * Two steps, in this order:
 *
 *  1. **Translate**, in the `messages` dictionary first — that table exists
 *     precisely to turn those keys into sentences.
 *  2. **Substitute**, because ~320 of those sentences have blanks in them:
 *     `Cannot edit this timecard because it is {{status}}`. The values ride the
 *     same envelope, in `messageElements`. Translating without substituting
 *     trades one unreadable string for another.
 *
 * A key with no entry still comes back readable rather than raw (see
 * [LabelDictionary.translate]), and the envelope-unwrapping in [LabelKey]
 * covers the failures where the network layer put the entire response body in
 * `message`.
 *
 * The client's own wording is never sent through the dictionary: those strings
 * are already English sentences, and looking them up would be a guaranteed miss
 * dressed up as a translation.
 */
fun ZillitError.localised(): String = when (this) {
    is ZillitError.Http -> serverMessage
        ?.takeIf { it.isNotBlank() }
        ?.let { key ->
            applyMessageElements(
                text = key.localisedMessage(),
                elements = messageElements,
                // A replacer is a value — a name, a count, a status — so it is
                // only translated when the dictionary genuinely knows it, and
                // used verbatim otherwise. `exact` says which; `translate`
                // would humanise a miss into wording the server never sent.
                translate = { replacer -> Labels.current.exact(replacer, LabelKind.Messages) },
            ).withoutUnfilledPlaceholders()
        }
        ?: userMessage

    else -> userMessage
}
