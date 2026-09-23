package com.zillit.desktop.core.strings

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StringCatalogTest {

    @AfterTest
    fun tearDown() = Strings.reset()

    @Test
    fun `a translated catalogue over English fills its gaps per key`() {
        val english = StringCatalog(AppLanguage.English, mapOf("a" to "A", "b" to "B"))
        val french = StringCatalog(AppLanguage.byCode("fr")!!, mapOf("a" to "Á"))

        val merged = french.over(english)

        assertEquals("Á", merged["a"])
        assertEquals("B", merged["b"])
        assertEquals("fr", merged.language.code)
    }

    @Test
    fun `blank text counts as missing`() {
        val catalog = StringCatalog(AppLanguage.English, mapOf("empty" to "  "))
        assertNull(catalog["empty"])
    }

    @Test
    fun `plurals pick one and other and fall back to other`() {
        val catalog = StringCatalog(
            AppLanguage.English,
            plurals = mapOf("days" to mapOf("one" to "%d day", "other" to "%d days")),
        )
        assertEquals("%d day", catalog.plural("days", 1))
        assertEquals("%d days", catalog.plural("days", 0))
        assertEquals("%d days", catalog.plural("days", 7))
    }

    @Test
    fun `an uninstalled reader answers in bundled English and humanises what no file has`() {
        assertEquals("Start Project", str("start_project"))
        assertEquals("Sign out?", str("desktop_sign_out_title"))
        assertEquals("Nobody Has This", str("nobody_has_this"))
    }

    @Test
    fun `an installed catalogue answers and formats`() {
        Strings.install(
            StringCatalog(
                AppLanguage.English,
                mapOf("use_my_name" to "Use my name (%1\$s)", "count" to "%d items"),
                plurals = mapOf("days" to mapOf("one" to "%d day", "other" to "%d days")),
            ),
        )
        assertEquals("Use my name (Ana)", str("use_my_name", "Ana"))
        assertEquals("3 items", str("count", 3))
        assertEquals("1 day", plural("days", 1))
        assertEquals("4 days", plural("days", 4))
    }

    @Test
    fun `a broken placeholder shows the template rather than throwing`() {
        Strings.install(StringCatalog(AppLanguage.English, mapOf("bad" to "%1 \$s left")))
        assertEquals("%1 \$s left", str("bad", "x"))
    }
}
