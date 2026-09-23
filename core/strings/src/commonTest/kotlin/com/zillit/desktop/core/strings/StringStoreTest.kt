package com.zillit.desktop.core.strings

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class StringStoreTest {

    private val loads = mutableListOf<String>()

    private val source = CatalogSource { language ->
        loads += language.code
        when (language.code) {
            "en" -> StringCatalog(language, mapOf("hello" to "Hello", "only_en" to "English only"))
            "fr" -> StringCatalog(language, mapOf("hello" to "Bonjour"))
            else -> null
        }
    }

    @AfterTest
    fun tearDown() = Strings.reset()

    private fun store() = StringStore(source, io = UnconfinedTestDispatcher(), main = UnconfinedTestDispatcher())

    @Test
    fun `a language is laid over English per key`() = runTest {
        store().load(AppLanguage.byCode("fr")!!)

        assertEquals("Bonjour", str("hello"))
        assertEquals("English only", str("only_en"))
        assertEquals("fr", Strings.language.code)
    }

    @Test
    fun `a language with no file falls back to English and says so`() = runTest {
        store().load(AppLanguage.byCode("th")!!)

        assertEquals("Hello", str("hello"))
        assertEquals("en", Strings.language.code)
    }

    @Test
    fun `following the preference loads each distinct language once and English base once`() = runTest {
        val language = MutableStateFlow("fr")
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { store().follow(language) }

        language.value = "fr-CA"
        language.value = "de"
        language.value = "en"
        job.cancel()

        assertEquals(listOf("en", "fr", "de"), loads)
        assertEquals("Hello", str("hello"))
    }
}
