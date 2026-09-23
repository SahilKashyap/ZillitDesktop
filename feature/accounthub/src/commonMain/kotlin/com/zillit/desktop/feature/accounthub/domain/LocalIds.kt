package com.zillit.desktop.feature.accounthub.domain

import kotlin.random.Random

/**
 * Ids for rows minted on this client — a new pay rule, allowance, clause or bureau.
 *
 * Never derived from the list's length: delete the second of three rows and
 * add one, and "row-2" is minted twice. Two rows sharing an id is worse than it
 * looks — edits and removals address rows by id, the server keeps whatever id
 * it is given, and the pay engine reads a rule by it. The web mints a random
 * uid for the same reason (`breUid`).
 */
object LocalIds {

    /** A fresh `prefix-xxxxxxxx` that is not in [taken]. */
    fun next(prefix: String, taken: Collection<String> = emptyList()): String =
        generateSequence { "$prefix-${Random.nextLong().toULong().toString(RADIX)}" }
            .first { it !in taken }

    private const val RADIX = 36
}
