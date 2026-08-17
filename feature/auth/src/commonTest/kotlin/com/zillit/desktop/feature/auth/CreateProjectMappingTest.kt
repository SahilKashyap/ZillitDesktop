package com.zillit.desktop.feature.auth

import com.zillit.desktop.feature.auth.data.toCreateDto
import com.zillit.desktop.feature.auth.domain.NewProductionDraft
import com.zillit.desktop.feature.auth.domain.ProductionType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Draft → create payload.
 *
 * The two rules worth pinning are the ones the server rejects silently: the
 * "add new" sentinel must never travel as a real sub-type, and the phone pair
 * must be whole or absent.
 */
class CreateProjectMappingTest {

    private val other = ProductionType(ProductionType.OTHER, "Other", listOf("Misc"))
    private val feature = ProductionType("feature", "Feature Film", listOf("Studio"))

    private val draft = NewProductionDraft(
        firstName = "  Denis  ",
        lastName = "Villeneuve",
        productionName = "  Dune Part Three  ",
        email = "  denis@production.com ",
        typeId = "feature",
        subType = "Studio",
        languageCode = "en",
        agreedToTerms = true,
    )

    @Test
    fun `the display label travels alongside the id`() {
        val dto = draft.toCreateDto(feature, confirmCode = "CONFIRM", languageName = "English")

        assertEquals("feature", dto.projectTypeId)
        assertEquals("Feature Film", dto.projectType)
        assertEquals("English", dto.projectLanguageDescription)
        assertEquals("en", dto.projectLanguage)
    }

    @Test
    fun `surrounding whitespace is trimmed off every free-text field`() {
        val dto = draft.toCreateDto(feature, "CONFIRM")

        assertEquals("Denis", dto.firstName)
        assertEquals("Dune Part Three", dto.projectName)
        assertEquals("denis@production.com", dto.email)
    }

    @Test
    fun `the add-new sentinel is replaced by the typed value`() {
        val custom = draft.copy(
            typeId = ProductionType.OTHER,
            subType = ProductionType.ADD_NEW_SUB_TYPE,
            customSubType = "Music Video",
        )

        val dto = custom.toCreateDto(other, "CONFIRM")

        assertEquals("Music Video", dto.projectSubType)
    }

    @Test
    fun `an ordinary sub-type is sent as chosen`() {
        assertEquals("Studio", draft.toCreateDto(feature, "CONFIRM").projectSubType)
    }

    @Test
    fun `the phone pair is sent whole or not at all`() {
        val none = draft.toCreateDto(feature, "CONFIRM")
        assertNull(none.countryCode)
        assertNull(none.phone)

        val both = draft.copy(countryCode = "44", phone = "7700900123").toCreateDto(feature, "CONFIRM")
        assertEquals("44", both.countryCode)
        assertEquals("7700900123", both.phone)

        // Half-filled must not reach the wire — the backend rejects a lone half.
        val halfA = draft.copy(phone = "7700900123").toCreateDto(feature, "CONFIRM")
        assertNull(halfA.countryCode)
        assertNull(halfA.phone)

        val halfB = draft.copy(countryCode = "44").toCreateDto(feature, "CONFIRM")
        assertNull(halfB.countryCode)
        assertNull(halfB.phone)
    }

    @Test
    fun `an empty storage code is omitted rather than sent blank`() {
        assertNull(draft.toCreateDto(feature, "CONFIRM").enterpriseClientCode)
        assertEquals("ACME", draft.copy(storageCode = " ACME ").toCreateDto(feature, "CONFIRM").enterpriseClientCode)
    }

    @Test
    fun `the placeholder counts every client sends are preserved`() {
        // `number_of_users` and `project_region` look like stubs and are not —
        // the server assigns the real values and expects 0 here.
        val dto = draft.toCreateDto(feature, "CONFIRM")

        assertEquals(0, dto.numberOfUsers)
        assertEquals(0, dto.projectRegion)
        assertEquals("CONFIRM", dto.confirmCode)
    }
}
