package com.zillit.desktop.feature.email

import com.zillit.desktop.feature.email.ui.ComposeEvent
import com.zillit.desktop.feature.email.ui.RecipientField

/** A committed recipient on a row — what picking a suggestion or typing a comma produces. */
internal fun recipientsTyped(field: RecipientField, vararg addresses: String, input: String = ""): ComposeEvent =
    ComposeEvent.RecipientsChanged(field, addresses.toList(), input)

/** Text still being typed on a row, before any chip is committed. */
internal fun typingIn(field: RecipientField, text: String): ComposeEvent =
    ComposeEvent.RecipientsChanged(field, emptyList(), text)
