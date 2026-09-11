package com.zillit.desktop.feature.accounthub

import com.zillit.desktop.feature.accounthub.data.NonUnionPayDto
import com.zillit.desktop.feature.accounthub.domain.PayDayKind
import com.zillit.desktop.feature.accounthub.domain.PayRuleKind
import com.zillit.desktop.feature.accounthub.domain.PayRuleTemplate
import com.zillit.desktop.feature.accounthub.domain.PayTrigger
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Which condition a stored pay rule came from.
 *
 * The most dangerous code on this screen: a rule tagged with the wrong
 * template is re-saved as a different condition, and the engine then pays a
 * different amount. Every case here is one the web hit or guarded against.
 */
class PayRuleTemplateTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun triggerFrom(body: String): PayTrigger =
        json.decodeFromString(
            NonUnionPayDto.serializer(),
            """{"overtimes":[{"triggers":[$body]}]}""",
        ).toDomain().overtimes.single().triggers.single()

    /**
     * The server pads every key it knows, most to null or false.
     *
     * Matching against the padded object recognises nothing, and the rule then
     * falls back to the list's default — which is how Broken Turnaround came
     * to be tagged as a Meal Penalty on the web.
     */
    @Test
    fun `padding is not part of the condition`() {
        val padded = triggerFrom(
            """{"less":600,"after":null,"before":null,"meal":false,"clock":false,
               "day_number":null,"consecutive":false,"camera":false}""",
        )

        assertEquals(setOf("less"), padded.meaningfulKeys)
        assertEquals(PayRuleTemplate.BrokenTurnaround, PayRuleTemplate.of(padded))
    }

    /** Zero is a real value, so a rest period of none still matches. */
    @Test
    fun `a zero threshold is a threshold`() {
        val zero = triggerFrom("""{"less":0,"after":null}""")

        assertEquals(setOf("less"), zero.meaningfulKeys)
        assertEquals(PayRuleTemplate.BrokenTurnaround, PayRuleTemplate.of(zero))
    }

    /** The specific day templates win over the plain hours one. */
    @Test
    fun `a day-numbered overtime is not plain overtime`() {
        val sixth = triggerFrom("""{"day_number":6,"consecutive":true,"after":480}""")
        val seventh = triggerFrom("""{"day_number":7,"consecutive":true,"after":480}""")
        val plain = triggerFrom("""{"after":480}""")

        assertEquals(PayRuleTemplate.OvertimeSixthDay, PayRuleTemplate.of(sixth))
        assertEquals(PayRuleTemplate.OvertimeSeventhDay, PayRuleTemplate.of(seventh))
        assertEquals(PayRuleTemplate.Overtime, PayRuleTemplate.of(plain))
    }

    /** With no hours threshold it is the day premium, not the overtime. */
    @Test
    fun `a day without hours is the premium`() {
        assertEquals(
            PayRuleTemplate.SixthDay,
            PayRuleTemplate.of(triggerFrom("""{"day_number":6,"consecutive":true}""")),
        )
        assertEquals(
            PayRuleTemplate.SeventhDay,
            PayRuleTemplate.of(triggerFrom("""{"day_number":7,"consecutive":true}""")),
        )
    }

    /** Before a clock time is a call; after one is night work. */
    @Test
    fun `the clock templates read in the right direction`() {
        assertEquals(PayRuleTemplate.PreDawn, PayRuleTemplate.of(triggerFrom("""{"clock":true,"before":360}""")))
        assertEquals(PayRuleTemplate.NightWork, PayRuleTemplate.of(triggerFrom("""{"clock":true,"after":1320}""")))
    }

    @Test
    fun `the meal templates are told apart`() {
        assertEquals(
            PayRuleTemplate.MealPenalty,
            PayRuleTemplate.of(triggerFrom("""{"meal":true,"after":360}""")),
        )
        assertEquals(
            PayRuleTemplate.MealCurtailed,
            PayRuleTemplate.of(triggerFrom("""{"meal_curtailed":true}""")),
        )
    }

    /** Nothing recognised is null, not the list default — they are different facts. */
    @Test
    fun `an unrecognised condition matches nothing`() {
        assertNull(PayRuleTemplate.of(triggerFrom("""{"weekly":true,"after":2400}""")))
        assertNull(PayRuleTemplate.of(PayTrigger()))
        assertNull(PayRuleTemplate.of(null))
    }

    /** The rule-level gates ride along but never decide what a condition is. */
    @Test
    fun `bdr gates are carried, not matched on`() {
        val withGates = triggerFrom("""{"less":600,"bdr_min":100.0,"bdr_max":900.0}""")

        assertEquals(PayRuleTemplate.BrokenTurnaround, PayRuleTemplate.of(withGates))
        assertEquals(100.0, withGates.bdrMin)

        // And they survive being edited through a template.
        val rebuilt = PayRuleTemplate.BrokenTurnaround.trigger("12", "", emptyList(), withGates)
        assertEquals(100.0, rebuilt.bdrMin)
        assertEquals(900.0, rebuilt.bdrMax)
    }

    // -- units --------------------------------------------------------------

    /** Hours and clock times are both minutes on the wire. */
    @Test
    fun `hours and clock times round-trip through minutes`() {
        assertEquals(480, PayRuleTemplate.hoursToMinutes("8"))
        assertEquals(630, PayRuleTemplate.hoursToMinutes("10.5"))
        assertEquals("8", PayRuleTemplate.asHoursText(480), "a whole number of hours drops the decimal")
        assertEquals("10.5", PayRuleTemplate.asHoursText(630))

        assertEquals(360, PayRuleTemplate.clockToMinutes("06:00"))
        assertEquals(1320, PayRuleTemplate.clockToMinutes("22:00"))
        assertEquals("06:00", PayRuleTemplate.asClockText(360))
        assertEquals(0, PayRuleTemplate.clockToMinutes("nonsense"))
    }

    /** A single day kind is sometimes stored bare rather than in an array. */
    @Test
    fun `a bare day kind still loads`() {
        val bare = triggerFrom("""{"day_kind":"bank_holiday"}""")
        val list = triggerFrom("""{"day_kind":["saturday","sunday"]}""")

        assertEquals(listOf(PayDayKind.BankHoliday), bare.dayKinds)
        assertEquals(listOf(PayDayKind.Saturday, PayDayKind.Sunday), list.dayKinds)
        assertEquals(PayRuleTemplate.BankHoliday, PayRuleTemplate.of(bare))
    }

    /** Each list starts a new rule on the condition it usually needs. */
    @Test
    fun `each list has its own default`() {
        assertEquals(PayRuleTemplate.Overtime, PayRuleTemplate.defaultFor(PayRuleKind.Overtimes))
        assertEquals(PayRuleTemplate.SixthDay, PayRuleTemplate.defaultFor(PayRuleKind.Premiums))
        assertEquals(PayRuleTemplate.MealPenalty, PayRuleTemplate.defaultFor(PayRuleKind.Penalties))
    }

    /**
     * Every template it can recognise, it recognises as itself.
     *
     * The exception is the early unit call, which is the pre-dawn condition
     * with a different rate — it can be written but never read back, and the
     * web has the same overlap.
     */
    @Test
    fun `a template builds a condition it would recognise`() {
        PayRuleTemplate.entries
            .filter { it != PayRuleTemplate.NightWorkEarlyCall }
            .forEach { template ->
                val built = template.trigger(
                    hours = "8",
                    clock = "06:00",
                    dayKinds = listOf(PayDayKind.BankHoliday),
                    carrying = PayTrigger(),
                )
                assertEquals(template, PayRuleTemplate.of(built), "${template.id} must round-trip")
            }
    }

    /** The early unit call reads back as pre-dawn, which is the same condition. */
    @Test
    fun `the early call reads back as pre-dawn`() {
        val built = PayRuleTemplate.NightWorkEarlyCall
            .trigger(hours = "", clock = "05:00", dayKinds = emptyList(), carrying = PayTrigger())

        assertEquals(PayRuleTemplate.PreDawn, PayRuleTemplate.of(built))
        assertTrue(built.clock)
        assertEquals(300, built.beforeMinutes)
    }
}
