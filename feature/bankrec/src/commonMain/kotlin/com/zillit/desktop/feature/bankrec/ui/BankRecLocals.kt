package com.zillit.desktop.feature.bankrec.ui

import androidx.compose.runtime.staticCompositionLocalOf
import com.zillit.desktop.feature.bankrec.domain.BankPeriod
import com.zillit.desktop.feature.bankrec.domain.BankRecDirectory
import com.zillit.desktop.feature.bankrec.domain.BankRecPerson

/**
 * The crew, for the tables that name a signer or an actor.
 *
 * A local rather than state: it is a lookup over the production's directory,
 * which the host keeps current, and copying it into every state emission
 * would re-render every table on every crew change.
 */
val LocalBankRecPeople = staticCompositionLocalOf { BankRecDirectory { null } }

/**
 * Who signed a period off — the server's own resolved name first, then the
 * directory. Null when neither knows: an id is never shown in its place.
 */
fun BankRecDirectory.signer(period: BankPeriod): BankRecPerson? {
    val known = period.signedBy.takeIf { it.isNotBlank() }?.let(::person)
    val name = period.signedByName.ifBlank { known?.name.orEmpty() }
    if (name.isBlank()) return null
    return BankRecPerson(name = name, designation = period.signedByDesignation.ifBlank { known?.designation.orEmpty() })
}
