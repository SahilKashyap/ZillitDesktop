package com.zillit.desktop.feature.accounthub

import com.zillit.desktop.feature.accounthub.data.NonUnionPayDto
import com.zillit.desktop.feature.accounthub.domain.NonUnionPay
import com.zillit.desktop.feature.accounthub.domain.PayApplyMode
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private val json = Json { ignoreUnknownKeys = true; isLenient = true }

/**
 * Who a non-union production's pay rules actually pay.
 *
 * Section-level scope, and the reason it has its own tests: it is the one
 * field on this screen where getting it wrong pays the wrong people. A
 * production that scoped its overtime to two departments and had that widened
 * to everybody would not see an error — it would see a payroll run.
 */
class PayApplyScopeTest {

    /**
     * The pristine state is "nobody has chosen", and it is not a third
     * behaviour.
     *
     * The engine treats it as everybody; it exists so the screen can show
     * neither choice as picked rather than claim a decision nobody made.
     */
    @Test
    fun `an unset scope is read as unset, not as everyone`() {
        val fresh = json.decodeFromString(
            NonUnionPayDto.serializer(),
            """{"overtimes":[],"premiums":[],"penalties":[]}""",
        ).toDomain()

        assertEquals(PayApplyMode.Unset, fresh.applyMode)
        assertTrue(fresh.departmentIds.isEmpty())
    }

    @Test
    fun `a department scope is read with its departments`() {
        val scoped = json.decodeFromString(
            NonUnionPayDto.serializer(),
            """{"apply_mode":"departments","department_ids":["d1","d2",""]}""",
        ).toDomain()

        assertEquals(PayApplyMode.Departments, scoped.applyMode)
        assertEquals(listOf("d1", "d2"), scoped.departmentIds)
    }

    @Test
    fun `a mode this client does not know reads as unset`() {
        val odd = json.decodeFromString(
            NonUnionPayDto.serializer(),
            """{"apply_mode":"something_new"}""",
        ).toDomain()

        assertEquals(PayApplyMode.Unset, odd.applyMode)
    }

    /**
     * Choosing departments is what sets the mode — never the list being
     * non-empty.
     *
     * A production that picked departments and then removed them all has still
     * chosen "departments", and re-reading that as "everyone" would widen who
     * gets paid.
     */
    @Test
    fun `emptying the department list does not widen the scope`() {
        val scoped = NonUnionPay().appliedTo(listOf("d1", "d2"))

        val emptied = scoped.appliedTo(emptyList())

        assertEquals(PayApplyMode.Departments, emptied.applyMode)
        assertTrue(emptied.appliesToNobody)
    }

    /** Choosing everyone clears the department list rather than keeping it. */
    @Test
    fun `applying to everyone drops the departments`() {
        val everyone = NonUnionPay().appliedTo(listOf("d1")).appliedToEveryone()

        assertEquals(PayApplyMode.All, everyone.applyMode)
        assertTrue(everyone.departmentIds.isEmpty())
        assertFalse(everyone.appliesToNobody)
    }

    @Test
    fun `a department chosen twice is stored once`() {
        val scoped = NonUnionPay().appliedTo(listOf("d1", "d1", "d2"))

        assertEquals(listOf("d1", "d2"), scoped.departmentIds)
    }

    /** Rules applying to everyone reach somebody; a scope with no department does not. */
    @Test
    fun `only an empty department scope pays nobody`() {
        assertFalse(NonUnionPay().appliesToNobody)
        assertFalse(NonUnionPay().appliedToEveryone().appliesToNobody)
        assertTrue(NonUnionPay().appliedTo(emptyList()).appliesToNobody)
    }
}
