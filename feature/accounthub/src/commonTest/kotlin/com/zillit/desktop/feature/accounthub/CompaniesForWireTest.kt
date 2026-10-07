package com.zillit.desktop.feature.accounthub

import com.zillit.desktop.feature.accounthub.domain.BankAccount
import com.zillit.desktop.feature.accounthub.domain.Companies
import com.zillit.desktop.feature.accounthub.domain.Company
import kotlin.test.Test
import kotlin.test.assertEquals

/** The companies PATCH replaces the whole list, so what it does with bank links must never be a guess. */
class CompaniesForWireTest {

    private val company = Company(id = "co-1", name = "Zillit Films", bankIds = listOf("b-1", "b-gone"))

    @Test
    fun `with the banks read, a link to a bank that no longer exists is dropped`() {
        val bank = BankAccount(id = "b-1")
        assertEquals(listOf("b-1"), Companies.forWire(listOf(company), listOf(bank)).single().bankIds)
    }

    @Test
    fun `with the banks unread, every link is kept rather than erased`() {
        assertEquals(listOf("b-1", "b-gone"), Companies.forWire(listOf(company), null).single().bankIds)
    }
}
