package com.zillit.desktop.feature.crewlist

import com.zillit.desktop.feature.crewlist.domain.CompanyCustomField
import com.zillit.desktop.feature.crewlist.domain.CompanyDetails
import com.zillit.desktop.feature.crewlist.domain.CompanyField
import com.zillit.desktop.feature.crewlist.domain.CompanyRules
import com.zillit.desktop.feature.crewlist.domain.CrewDepartment
import com.zillit.desktop.feature.crewlist.domain.CrewMember
import com.zillit.desktop.feature.crewlist.domain.CrewMemberRules
import com.zillit.desktop.feature.crewlist.domain.CrewUnit
import com.zillit.desktop.feature.crewlist.domain.DepartmentOrder
import com.zillit.desktop.feature.crewlist.domain.MemberOverride
import com.zillit.desktop.feature.crewlist.domain.OrderedDepartment
import com.zillit.desktop.feature.crewlist.domain.PhoneField
import com.zillit.desktop.feature.crewlist.domain.PhoneRules
import com.zillit.desktop.feature.crewlist.domain.search
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The member cell rules the web's `CrewListCustomUserCard` tests pin down, the
 * phone checks (ZL-20023), the roster search, and the two admin editors' rules.
 */
class CrewMemberRulesTest {

    private val member = CrewMember(
        userId = "u1",
        fullName = "Randeep Oppo",
        designationName = "first_ac_label",
        countryCode = "+91",
        phone = "9820041123",
        primaryEmail = "randeep.oppo@gmail.com",
        email = "randeepoppo.ztaccounthubtest@zillit.org",
    )
    private val text: (String, String) -> String = { _, fallback -> fallback }

    @Test
    fun `PROFILE is primary_email and PROJECT is the mailbox`() {
        val cells = CrewMemberRules.cells(member, null)
        assertEquals("randeep.oppo@gmail.com", cells.profileEmail)
        assertEquals("randeepoppo.ztaccounthubtest@zillit.org", cells.projectEmail)
        assertEquals("+919820041123", cells.phoneDisplay)
    }

    @Test
    fun `external contacts keep their one address under PROFILE and never a mailbox`() {
        val external = member.copy(primaryEmail = "", email = "vendor@acme.com", isExternal = true)
        val cells = CrewMemberRules.cells(external, null)
        assertEquals("vendor@acme.com", cells.profileEmail)
        assertEquals("", cells.projectEmail)
    }

    @Test
    fun `read mode shows the override, not the roster`() {
        val edited = MemberOverride(phone = "77777", countryCode = "+44", email = "new@x.y")
        val cells = CrewMemberRules.cells(member, edited)
        assertEquals("+4477777", cells.phoneDisplay)
        assertEquals("new@x.y", cells.profileEmail)
        assertEquals("", CrewMemberRules.cells(member, MemberOverride(phone = "")).phoneDisplay, "a blanked number")
    }

    @Test
    fun `an edit that returns to the roster value leaves no override`() {
        val edited = CrewMemberRules.merge(member, null, MemberOverride(phone = "12345"))
        assertEquals(MemberOverride(phone = "12345"), edited)
        assertNull(CrewMemberRules.merge(member, edited, MemberOverride(phone = "9820041123")))
        assertEquals(
            MemberOverride(email = ""),
            CrewMemberRules.merge(member, null, MemberOverride(email = "")),
            "blanking is an edit",
        )
    }

    @Test
    fun `phone and dial code are required together and 5 to 25 digits`() {
        assertNull(PhoneRules.validate("", "", text))
        assertEquals(PhoneField.CountryCode, PhoneRules.validate("12345", "", text)?.field)
        assertEquals(PhoneField.Number, PhoneRules.validate("", "+44", text)?.field)
        assertEquals(PhoneField.Number, PhoneRules.validate("1234", "+44", text)?.field)
        assertNull(PhoneRules.validate("1".repeat(25), "+44", text))
        assertEquals(PhoneField.Number, PhoneRules.validate("1".repeat(26), "+44", text)?.field)
        assertEquals("98200", CrewMemberRules.digitsOnly("98-2 0(0)"))
    }

    @Test
    fun `search matches name or translated designation and prunes empty groups`() {
        val units = listOf(
            CrewUnit(
                "main_unit_label",
                listOf(
                    CrewDepartment("camera_label", listOf(member, member.copy(userId = "u2", fullName = "Ravi"))),
                    CrewDepartment(
                        "sound_label",
                        listOf(member.copy(userId = "u3", fullName = "Zed", designationName = "mixer")),
                    ),
                ),
            ),
        )
        val translate = { key: String -> if (key == "first_ac_label") "First AC" else key }
        val byDesignation = units.search("first ac", translate)
        assertEquals(listOf("u1", "u2"), byDesignation.single().departments.single().members.map { it.userId })
        assertEquals("u3", units.search("ZED", translate).single().departments.single().members.single().userId)
        assertTrue(units.search("nobody", translate).isEmpty())
    }

    @Test
    fun `department order moves, refuses a bad priority, and resets by id`() {
        val loaded =
            listOf(OrderedDepartment("c", "Camera"), OrderedDepartment("a", "Art"), OrderedDepartment("b", "Sound"))
        val order = DepartmentOrder(loaded)
        val moved = order.move(from = 0, to = 2)
        assertEquals(listOf("a", "b", "c"), moved.current.map { it.id })
        assertTrue(moved.isChanged)
        assertNull(order.moveToPosition(index = 0, position = 4))
        assertEquals(listOf("a", "c", "b"), order.moveToPosition(index = 1, position = 1)?.current?.map { it.id })
        assertEquals(listOf("a", "b", "c"), order.reset().current.map { it.id })
    }

    @Test
    fun `company details need a label on every custom row and a whole phone`() {
        val problems = CompanyRules.validate(
            CompanyDetails(
                email = "office@",
                phone = "1234",
                countryCode = "+44",
                customFields = listOf(CompanyCustomField(label = "", value = "")),
            ),
            text,
        )
        assertEquals(
            listOf(CompanyField.Email, CompanyField.CustomLabel, CompanyField.Phone),
            problems.map { it.field },
        )
        assertTrue(CompanyRules.validate(CompanyDetails(email = "office@company.com"), text).isEmpty())
        assertEquals("IT", CompanyDetails(name = "india take one").initials)
    }
}
