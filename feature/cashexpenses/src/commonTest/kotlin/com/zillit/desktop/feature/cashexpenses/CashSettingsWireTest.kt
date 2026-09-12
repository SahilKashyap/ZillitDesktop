package com.zillit.desktop.feature.cashexpenses

import com.zillit.desktop.feature.cashexpenses.data.SettingsDto
import com.zillit.desktop.feature.cashexpenses.domain.RuleProcess
import com.zillit.desktop.feature.cashexpenses.domain.RuleThreshold
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The settings document, as the web writes it.
 *
 * These three sections are new on the desktop, and one of them — quick codes
 * — was being read under field names nothing has ever written.
 */
class CashSettingsWireTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun parse(text: String) = json.decodeFromString(SettingsDto.serializer(), text).toDomain()

    /**
     * The shape the web's Settings page saves.
     *
     * `name` and `nominal_code` for a quick code, and `enable` — not
     * `enabled` — for a rule's on/off flag.
     */
    @Test
    fun `the settings document decodes the web's own shapes`() {
        val settings = parse(
            """
            {
              "float_custodian_account": "1200",
              "bs_code_from": "1200",
              "bs_code_to": "1299",
              "reimburse_to_payroll": true,
              "quick_codes": [
                {"name": "Fuel", "nominal_code": "2400", "keywords": ["fuel", "diesel"], "vat": 20}
              ],
              "deduction_rules": [
                {
                  "id": "fuel_deduction", "type": "fuel_deduction", "title": "Fuel Deduction",
                  "description": "Deduct a share for personal use.",
                  "process_type": "deduct_amount", "threshold_type": "percentage",
                  "threshold_value": 20, "enable": true,
                  "trigger_codes": ["fuel", "petrol"], "system_default": true
                }
              ]
            }
            """.trimIndent(),
        )
        assertTrue(settings.reimburseToPayroll)

        val code = settings.quickCodes.single()
        assertEquals("Fuel", code.name)
        assertEquals("2400", code.nominalCode)
        assertEquals(listOf("fuel", "diesel"), code.keywords)
        assertEquals(20.0, code.vat)

        val rule = settings.deductionRules.single()
        assertEquals("Fuel Deduction", rule.title)
        assertEquals(RuleProcess.DeductAmount, rule.processType)
        assertEquals(RuleThreshold.Percentage, rule.thresholdType)
        assertEquals(20.0, rule.thresholdValue)
        assertTrue(rule.enabled, "the flag is spelled `enable`, not `enabled`")
        assertTrue(rule.systemDefault)
        assertEquals(listOf("fuel", "petrol"), rule.triggerCodes)
    }

    /** An absent section is empty and off, never a crash. */
    @Test
    fun `a document with none of the new sections still decodes`() {
        val settings = parse("""{"float_custodian_account": "1200"}""")
        assertFalse(settings.reimburseToPayroll)
        assertTrue(settings.quickCodes.isEmpty())
        assertTrue(settings.deductionRules.isEmpty())
    }

    /**
     * A rule the server sends with no `enable` is off.
     *
     * All four system rules ship that way, and defaulting them on would start
     * deducting from receipts nobody asked to deduct from.
     */
    @Test
    fun `a rule with no flag is off`() {
        val settings = parse(
            """{"deduction_rules": [{"id": "high_value", "title": "High Value", "process_type": "senior_review"}]}""",
        )
        val rule = settings.deductionRules.single()
        assertFalse(rule.enabled)
        assertEquals(RuleProcess.SeniorReview, rule.processType)
        // An unknown threshold spelling falls back rather than throwing.
        assertEquals(RuleThreshold.Percentage, rule.thresholdType)
    }

    /** The older `code`/`label` spellings still read, in case a row carries them. */
    @Test
    fun `the legacy quick-code spelling still reads`() {
        val settings = parse("""{"quick_codes": [{"code": "2400", "label": "Camera"}]}""")
        val code = settings.quickCodes.single()
        assertEquals("Camera", code.name)
        assertEquals("2400", code.nominalCode)
        assertNull(code.vat)
    }
}
