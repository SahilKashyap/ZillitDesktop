package com.zillit.desktop.core.strings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppLanguageTest {

    @Test
    fun `codes resolve with region tags and old ISO aliases`() {
        assertEquals("en", AppLanguage.byCode("en_GB")?.code)
        assertEquals("pt", AppLanguage.byCode("pt-BR")?.code)
        assertEquals("zh", AppLanguage.byCode("zh-Hant-TW")?.code)
        assertEquals("he", AppLanguage.byCode("iw")?.code)
        assertEquals("id", AppLanguage.byCode("in")?.code)
        assertEquals("fil", AppLanguage.byCode("tl")?.code)
        assertEquals("no", AppLanguage.byCode("nb_NO")?.code)
        assertNull(AppLanguage.byCode("xx"))
        assertNull(AppLanguage.byCode(""))
        assertNull(AppLanguage.byCode(null))
    }

    @Test
    fun `resolve prefers the choice, then the OS, then English`() {
        assertEquals("fr", AppLanguage.resolve("fr", "de").code)
        assertEquals("de", AppLanguage.resolve("", "de").code)
        assertEquals("en", AppLanguage.resolve("", "xx").code)
        assertEquals("en", AppLanguage.resolve(null, null).code)
    }

    @Test
    fun `right-to-left scripts are marked`() {
        assertTrue(AppLanguage.byCode("ar")!!.rtl)
        assertTrue(AppLanguage.byCode("he")!!.rtl)
        assertTrue(AppLanguage.all.filter { it.rtl }.map { it.code }.toSet() == setOf("ar", "he"))
    }

    @Test
    fun `codes are unique and English leads`() {
        assertEquals(AppLanguage.all.size, AppLanguage.all.map { it.code }.toSet().size)
        assertEquals(AppLanguage.English, AppLanguage.all.first())
    }
}
