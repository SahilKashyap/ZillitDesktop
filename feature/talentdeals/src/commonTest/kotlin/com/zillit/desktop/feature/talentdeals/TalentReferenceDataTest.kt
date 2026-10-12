package com.zillit.desktop.feature.talentdeals

import com.zillit.desktop.feature.talentdeals.domain.TalentCountry
import com.zillit.desktop.feature.talentdeals.domain.TalentCountryCurrency
import com.zillit.desktop.feature.talentdeals.domain.TalentCurrencies
import com.zillit.desktop.feature.talentdeals.domain.TalentCurrency
import com.zillit.desktop.feature.talentdeals.domain.TalentRoles
import com.zillit.desktop.feature.talentdeals.domain.TalentTerritories
import com.zillit.desktop.feature.talentdeals.domain.TalentUnion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The unions a role always sees, the territory list, and the currency picker's rows. */
class TalentReferenceDataTest {

    // -- unions ------------------------------------------------------------------------

    @Test
    fun `DGA for directors, SAG-AFTRA for cast, PGA for producers, nothing for a typed role`() {
        assertEquals(listOf("dga"), TalentRoles.baseUnionsFor("Director").map { it.identifier })
        assertEquals(listOf("sag-aftra"), TalentRoles.baseUnionsFor("cast").map { it.identifier })
        assertEquals(listOf("pga"), TalentRoles.baseUnionsFor("Producer").map { it.identifier })
        assertEquals(emptyList(), TalentRoles.baseUnionsFor("Dialect coach"))
    }

    @Test
    fun `a guild reads as ABBREVIATION - full name`() {
        assertEquals("DGA - Directors Guild of America", TalentRoles.baseUnionsFor("Director").single().name)
        assertEquals("PGA - Producers Guild of America", TalentRoles.baseUnionName("pga"))
        assertNull(TalentRoles.baseUnionName("equity-uk"))
    }

    @Test
    fun `the lookup merges on top without duplicates - its id, the baseline's name`() {
        val merged = TalentRoles.mergeUnions(
            TalentRoles.baseUnionsFor("Cast"),
            listOf(
                TalentUnion("6ab0", "sag-aftra", "Screen Actors Guild - AFTRA"),
                TalentUnion("2", "equity-uk", "Equity"),
                TalentUnion("3", "", "No slug"),
            ),
        )
        assertEquals(listOf("sag-aftra", "equity-uk"), merged.map { it.identifier })
        assertEquals(TalentUnion("6ab0", "sag-aftra", "SAG - Screen Actors Guild - AFTRA"), merged[0])
    }

    @Test
    fun `a stored union no list holds stays selectable`() {
        val options = TalentRoles.unionOptions("Cast", emptyList(), "old-guild")
        assertEquals(listOf("sag-aftra", "old-guild"), options.map { it.identifier })
        assertEquals(listOf("sag-aftra"), TalentRoles.unionOptions("Cast", emptyList(), "").map { it.identifier })
    }

    @Test
    fun `the union is required only when there is something to pick`() {
        assertFalse(TalentRoles.unionRequired(TalentRoles.unionOptions("Dialect coach", emptyList(), "")))
        assertTrue(TalentRoles.unionRequired(TalentRoles.unionOptions("Producer", emptyList(), "")))
    }

    @Test
    fun `the three crew types are asked for by name, anything else asks for all`() {
        assertEquals("cast", TalentRoles.crewTypeFor("Cast"))
        assertEquals("director", TalentRoles.crewTypeFor(" director "))
        assertEquals("producer", TalentRoles.crewTypeFor("PRODUCER"))
        assertEquals("all", TalentRoles.crewTypeFor("Dialect coach"))
        assertEquals("all", TalentRoles.crewTypeFor(null))
    }

    @Test
    fun `Exhibit G is SAG cast only, whatever the slug spelling`() {
        assertTrue(TalentRoles.exhibitGApplies("Cast", "sag"))
        assertTrue(TalentRoles.exhibitGApplies("Cast", "sag-aftra"))
        assertTrue(TalentRoles.exhibitGApplies("Cast", "SAG"))
        assertFalse(TalentRoles.exhibitGApplies("Director", "sag"))
        assertFalse(TalentRoles.exhibitGApplies("Cast", "equity-uk"))
        assertFalse(TalentRoles.exhibitGApplies("Cast", ""))
        assertFalse(TalentRoles.exhibitGApplies("Cast", null))
    }

    // -- territories -------------------------------------------------------------------

    @Test
    fun `the wizard offers 41 territories, the UK first with its pound`() {
        assertEquals(41, TalentTerritories.wizard.size)
        val uk = TalentTerritories.wizard.first()
        assertEquals("uk", uk.id)
        assertEquals("GB", uk.iso)
        assertEquals("GBP", uk.currency)
        assertEquals("EUR", TalentTerritories.wizard.single { it.id == "de" }.currency)
        assertEquals(TalentTerritories.wizard.size, TalentTerritories.wizard.map { it.id }.toSet().size)
    }

    @Test
    fun `the rest of the world follows, by name, never repeating the wizard's countries`() {
        val list = TalentTerritories.build(
            countries = listOf(
                TalentCountry("GB", "United Kingdom"),
                TalentCountry("KE", "Kenya"),
                TalentCountry("CH", "Switzerland"),
                TalentCountry("", "Nowhere"),
            ),
            currencyOf = { name -> mapOf("Kenya" to "KES", "Switzerland" to "CHF")[name] },
        )
        val tail = list.drop(41)
        assertEquals(listOf("ke", "ch"), tail.map { it.id })
        assertEquals("KES", tail[0].currency)
        assertEquals("CHF", tail[1].currency)
        assertEquals(1, list.count { it.label == "United Kingdom" })
    }

    // -- currencies --------------------------------------------------------------------

    private val catalogue = listOf(
        TalentCurrency("GBP", "Pound Sterling", "£", "United Kingdom"),
        TalentCurrency("USD", "US Dollar", "$", "United States"),
        TalentCurrency("EUR", "Euro", "€", "Eurozone"),
        TalentCurrency("JPY", "Yen", "¥", "Japan"),
    )

    @Test
    fun `the project's currencies come first, tagged, then the rest of the catalogue`() {
        val options = TalentCurrencies(selected = listOf("GBP", "USD"), defaultCode = "GBP",
            catalogue = catalogue).options()
        assertEquals(listOf("GBP", "USD", "EUR", "JPY"), options.map { it.code })
        assertEquals(listOf(true, true, false, false), options.map { it.isProject })
    }

    @Test
    fun `with nothing selected the whole catalogue shows and none of it is tagged Project`() {
        val options = TalentCurrencies(catalogue = catalogue).options()
        assertEquals(4, options.size)
        assertTrue(options.none { it.isProject })
    }

    @Test
    fun `the default is always present, and a saved currency stays selectable`() {
        val currencies = TalentCurrencies(selected = listOf("USD"), defaultCode = "GBP", catalogue = catalogue)
        assertEquals(listOf("GBP", "USD", "EUR", "JPY"), currencies.options().map { it.code })
        assertEquals(listOf("JPY", "GBP", "USD", "EUR"), currencies.options(ensure = "jpy").map { it.code })
    }

    @Test
    fun `a code the catalogue does not know stands for itself`() {
        val currencies = TalentCurrencies(catalogue = catalogue)
        assertEquals("XYZ", currencies.get("XYZ").symbol)
        assertEquals("£", currencies.get("gbp").symbol)
    }

    @Test
    fun `a currency's label reads CODE symbol - Country`() {
        assertEquals("GBP £ — United Kingdom", catalogue[0].label)
        assertEquals("XYZ", TalentCurrency("XYZ").label)
    }

    // -- country → currency ------------------------------------------------------------

    @Test
    fun `most countries self-derive, the multi-country currencies and de facto adopters are explicit`() {
        val lookup = TalentCountryCurrency.lookup(
            catalogue + TalentCurrency("CHF", "Franc", "Fr", "Switzerland") +
                TalentCurrency("XAF", "CFA", "F", "Cameroon, Chad"),
        )
        assertEquals("JPY", lookup("Japan"))
        assertEquals("EUR", lookup("Germany"))
        assertEquals("USD", lookup("Ecuador"))
        assertEquals("CHF", lookup("Liechtenstein"))
        // Qualified, multi-country strings never self-derive, but the explicit list covers them.
        assertEquals("XAF", lookup("Cameroon"))
        assertNull(lookup("Atlantis"))
    }
}
