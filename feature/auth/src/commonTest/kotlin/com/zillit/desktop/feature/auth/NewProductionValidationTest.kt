package com.zillit.desktop.feature.auth

import com.zillit.desktop.feature.auth.domain.NewProductionDraft
import com.zillit.desktop.feature.auth.domain.ProductionField
import com.zillit.desktop.feature.auth.domain.ProductionType
import com.zillit.desktop.feature.auth.domain.resolvedSubType
import com.zillit.desktop.feature.auth.domain.validate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The create-production form's rules.
 *
 * Pure, so every branch is reachable without a UI. The web spreads the same
 * rules across ten `Form.Item` validators plus a ref-based phone check, none of
 * which a test can touch.
 */
class NewProductionValidationTest {

    private val feature = ProductionType("feature", "Feature Film", listOf("Studio", "Indie"))
    private val other = ProductionType(ProductionType.OTHER, "Other", listOf("Misc"))
    private val noSubTypes = ProductionType("doc", "Documentary")

    private val valid = NewProductionDraft(
        firstName = "Denis",
        lastName = "Villeneuve",
        productionName = "Dune Part Three",
        email = "denis@production.com",
        typeId = "feature",
        subType = "Studio",
        languageCode = "en",
        agreedToTerms = true,
    )

    @Test
    fun `a complete draft has no errors`() {
        assertTrue(valid.validate(feature).isEmpty())
    }

    @Test
    fun `every required field is reported at once`() {
        // One message per field, not one banner for whichever failed first —
        // otherwise the user fixes and resubmits five times.
        val errors = NewProductionDraft().validate(null)

        assertEquals(
            setOf(
                ProductionField.FirstName,
                ProductionField.LastName,
                ProductionField.ProductionName,
                ProductionField.Email,
                ProductionField.Type,
                ProductionField.Language,
                ProductionField.Terms,
            ),
            errors.keys,
        )
    }

    @Test
    fun `blank is not the same as absent for names`() {
        val errors = valid.copy(firstName = "   ").validate(feature)

        assertTrue(ProductionField.FirstName in errors)
    }

    @Test
    fun `email format is checked, permissively`() {
        listOf("nope", "no@domain", "@production.com", "a b@c.com", "a@b .com")
            .forEach { bad ->
                assertTrue(
                    ProductionField.Email in valid.copy(email = bad).validate(feature),
                    "should have rejected: $bad",
                )
            }
        listOf("a@b.co", "first.last+tag@sub.domain.com")
            .forEach { good ->
                assertTrue(
                    ProductionField.Email !in valid.copy(email = good).validate(feature),
                    "should have accepted: $good",
                )
            }
    }

    @Test
    fun `a sub-type is required only when the type offers any`() {
        // Demanding one unconditionally blocks types that have none.
        val withNone = valid.copy(typeId = "doc", subType = null)
        assertTrue(ProductionField.SubType !in withNone.validate(noSubTypes))

        val withSome = valid.copy(subType = null)
        assertTrue(ProductionField.SubType in withSome.validate(feature))
    }

    @Test
    fun `choosing add-new demands a name for it`() {
        val draft = valid.copy(
            typeId = ProductionType.OTHER,
            subType = ProductionType.ADD_NEW_SUB_TYPE,
            customSubType = "",
        )

        assertTrue(ProductionField.CustomSubType in draft.validate(other))
        assertTrue(ProductionField.CustomSubType !in draft.copy(customSubType = "Music Video").validate(other))
    }

    @Test
    fun `add-new is only offered by the other type`() {
        // The sentinel on a normal type is not a real sub-type, but it is also
        // not something the UI can produce — so it must not demand a custom name.
        val draft = valid.copy(subType = ProductionType.ADD_NEW_SUB_TYPE)

        assertTrue(ProductionField.CustomSubType !in draft.validate(feature))
    }

    @Test
    fun `the sentinel never reaches the server`() {
        val draft = valid.copy(
            typeId = ProductionType.OTHER,
            subType = ProductionType.ADD_NEW_SUB_TYPE,
            customSubType = "  Music Video  ",
        )

        assertEquals("Music Video", draft.resolvedSubType(other))
    }

    @Test
    fun `an ordinary sub-type passes through untouched`() {
        assertEquals("Studio", valid.resolvedSubType(feature))
        assertNull(valid.copy(subType = null).resolvedSubType(feature))
    }

    @Test
    fun `phone is optional but must be complete`() {
        assertTrue(ProductionField.Phone !in valid.validate(feature))
        assertTrue(ProductionField.Phone !in valid.copy(countryCode = "44", phone = "7700900123").validate(feature))

        // Half-filled is the mistake a user makes by tabbing past the country
        // code, and it is what the backend rejects.
        assertTrue(ProductionField.Phone in valid.copy(phone = "7700900123").validate(feature))
        assertTrue(ProductionField.Phone in valid.copy(countryCode = "44").validate(feature))
    }

    @Test
    fun `the terms must be accepted`() {
        assertTrue(ProductionField.Terms in valid.copy(agreedToTerms = false).validate(feature))
    }
}
