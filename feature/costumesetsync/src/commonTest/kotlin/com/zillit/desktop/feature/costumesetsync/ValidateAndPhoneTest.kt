package com.zillit.desktop.feature.costumesetsync

import com.zillit.desktop.core.strings.S
import com.zillit.desktop.feature.costumesetsync.domain.DialCode
import com.zillit.desktop.feature.costumesetsync.domain.Phone
import com.zillit.desktop.feature.costumesetsync.domain.Validate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The module's shared field rules and its phone pairs — the web's
 * `lib/validate.js` and `lib/phone.js`.
 */
class ValidateAndPhoneTest {

    @Test
    fun `an empty optional field is fine, and a filled one has to be usable`() {
        // Required-ness is a separate question: a vendor saves with no phone.
        assertNull(Validate.phoneError(""))
        assertNull(Validate.phoneError(null))
        assertNull(Validate.emailError("  "))

        // Length only, and only the digits count, so spacing and brackets are
        // not what makes a number wrong.
        assertNull(Validate.phoneError("98765 43210"))
        assertNull(Validate.phoneError("(020) 7946 0958"))
        assertEquals(S.desktop_csync_err_phone_length, Validate.phoneError("9876"))
        assertEquals(S.desktop_csync_err_phone_length, Validate.phoneError("1".repeat(21)))

        assertNull(Validate.emailError("wardrobe@zillit.com"))
        assertEquals(S.desktop_csync_err_email_format, Validate.emailError("wardrobe@zillit"))
        assertEquals(S.desktop_csync_err_email_format, Validate.emailError("two words@zillit.com"))
    }

    @Test
    fun `zero is a legitimate cost and a negative one is not`() {
        assertNull(Validate.costError(""))
        assertNull(Validate.costError("0"))
        assertNull(Validate.costError("1250.50"))
        assertEquals(S.desktop_csync_err_negative, Validate.costError("-1"))
        assertEquals(S.docusign_field_number_desc, Validate.costError("twelve"))
    }

    @Test
    fun `a same-day rental is allowed and a return before the pickup is not`() {
        assertNull(Validate.dateOrderError("2026-10-23", "2026-10-23"))
        assertNull(Validate.dateOrderError("2026-10-23", "2026-10-30"))
        // Either half missing is not yet a contradiction.
        assertNull(Validate.dateOrderError("", "2026-10-01"))
        assertEquals(
            S.desktop_csync_err_return_before_pickup,
            Validate.dateOrderError("2026-10-23", "2026-10-22"),
        )
    }

    @Test
    fun `noErrors feeds a Save button`() {
        assertTrue(Validate.noErrors(null, null))
        assertFalse(Validate.noErrors(null, S.desktop_csync_err_negative))
    }

    @Test
    fun `a number with no code is shown exactly as it was typed`() {
        // An old record may carry the code inside the number, so re-formatting
        // would either duplicate it or eat a digit.
        assertEquals("+91 98765 43210", Phone.format("98765 43210", "+91"))
        assertEquals("+91 98765 43210", Phone.format("+91 98765 43210", ""))
        assertEquals("", Phone.format("", "+91"))
        assertEquals("+919876543210", Phone.dialable("98765 43210", "+91"))
        assertEquals("919876543210", Phone.whatsapp("98765 43210", "+91"))
    }

    @Test
    fun `an old number is split on the longest matching dial code`() {
        val codes = listOf(
            DialCode("United States", "+1", "US"),
            DialCode("Bahamas", "+1242", "BS"),
            DialCode("India", "+91", "IN"),
        )
        // "+1" and "+1242" both match a Bahamas number; the longer one is the
        // right answer, which is what opens the picker on the right country.
        assertEquals("+1242" to "555 0199", Phone.split("+1242 555 0199", "", codes))
        assertEquals("+91" to "98765 43210", Phone.split("+91 98765 43210", null, codes))
        // A record that already carries a code is already split, list or no list.
        assertEquals("+44" to "7946 0958", Phone.split("7946 0958", "+44"))
        // No plus, or no list to match against: the number is left whole.
        assertEquals("" to "98765 43210", Phone.split("98765 43210", "", codes))
        assertEquals("" to "+358 40 1234567", Phone.split("+358 40 1234567", "", codes))
    }
}
