package com.zillit.desktop.feature.costumesetsync.domain

/**
 * One country the code picker offers — a row of `GET /v2/preset/isd-codes`
 * (`{ name, dial_code, code }`), the list every dial-code picker in the app
 * reads.
 */
data class DialCode(val name: String, val dialCode: String, val code: String = "") {
    /** What the open list shows; the closed field shows the dial code alone. */
    val label: String get() = "$name ($dialCode)"
}

/**
 * Phone numbers with a country code — the web's `syncOnset/lib/phone.js`
 * (ZL-21942).
 *
 * The service added a code field beside each phone on 2026-10-22:
 * `country_code` on a vendor and a contact, and `country_code` /
 * `phone2_country_code` / `talent_rep_country_code` on an actor. The dial code
 * goes in the code field and the number without it in the phone field, so
 * "+91 98765 43210" is stored as `{ country_code: "+91", phone: "98765 43210" }`.
 *
 * ⚠ Records saved before that have no code, and many of them already have one
 * typed INSIDE the number. So nothing here may assume the two are separate:
 * [format] leaves an old number exactly as it was typed, and [split] guesses
 * the code out of the number only when the record carries none — which is what
 * opens the picker on the right country when somebody edits an old record.
 */
object Phone {

    /**
     * What to show: "+91 98765 43210".
     *
     * With no code the number is shown exactly as it is: an old record may
     * already carry the code inside it, and re-formatting would either
     * duplicate it or eat a digit.
     */
    fun format(phone: String?, code: String?): String {
        val n = phone.orEmpty().trim()
        if (n.isEmpty()) return ""
        val c = code.orEmpty().trim()
        return if (c.isNotEmpty()) "$c $n" else n
    }

    /** The digits a dialler or a wa.me link takes — the full number, one leading plus. */
    fun dialable(phone: String?, code: String?): String {
        val full = format(phone, code)
        return full.filterIndexed { index, char -> char.isDigit() || (char == '+' && index == 0) }
    }

    /** Just the digits, for a WhatsApp address (`wa.me` takes no plus). */
    fun whatsapp(phone: String?, code: String?): String = format(phone, code).filter { it.isDigit() }

    /**
     * Splits a stored pair back into code and number, for editing.
     *
     * A record with a code is already split. One without may have the code
     * typed into the number, so the LONGEST matching dial code wins — "+1" and
     * "+1242" both match a Bahamas number, and the longer one is the right
     * answer. With no list (a test, or the preset call not back yet) the
     * number is simply left whole.
     */
    fun split(phone: String?, code: String?, dialCodes: List<DialCode> = emptyList()): Pair<String, String> {
        val n = phone.orEmpty().trim()
        val c = code.orEmpty().trim()
        if (c.isNotEmpty()) return c to n
        if (!n.startsWith("+")) return "" to n
        val best = dialCodes
            .map { it.dialCode }
            .filter { it.isNotEmpty() && n.startsWith(it) }
            .maxByOrNull { it.length }
            ?: return "" to n
        return best to n.removePrefix(best).trim()
    }
}
