package com.zillit.desktop.core.strings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AndroidStringsXmlTest {

    private fun parse(xml: String) =
        AndroidStringsXml.parse(AppLanguage.English, xml.byteInputStream())

    @Test
    fun `reads strings plurals and arrays`() {
        val catalog = parse(
            """
            <resources>
                <string name="a">Hello</string>
                <plurals name="p"><item quantity="one">%d day</item><item quantity="other">%d days</item></plurals>
                <string-array name="arr"><item>x</item><item>y</item></string-array>
                <color name="ignored">#fff</color>
            </resources>
            """.trimIndent(),
        )
        assertEquals("Hello", catalog["a"])
        assertEquals("%d day", catalog.plural("p", 1))
        assertEquals(listOf("x", "y"), catalog.array("arr"))
        assertEquals(3, catalog.size)
    }

    @Test
    fun `applies android escaping rules`() {
        assertEquals("It's here", AndroidStringsXml.unescape("It\\'s here"))
        assertEquals("line\nnext", AndroidStringsXml.unescape("line\\nnext"))
        assertEquals("a b", AndroidStringsXml.unescape("  a \n\t  b  "))
        assertEquals(" kept  spaces ", AndroidStringsXml.unescape("\" kept  spaces \""))
        assertEquals("say J'accepte now", AndroidStringsXml.unescape("say \"J\\'accepte\" now"))
        assertEquals("@home?", AndroidStringsXml.unescape("\\@home\\?"))
        assertEquals("back\\slash", AndroidStringsXml.unescape("back\\\\slash"))
        assertEquals("é", AndroidStringsXml.unescape("\\u00e9"))
        assertEquals("100%", AndroidStringsXml.unescape("100%"))
    }

    @Test
    fun `entities and cdata come through the parser`() {
        val catalog = parse(
            """
            <resources>
                <string name="amp">C&amp;C</string>
                <string name="cd"><![CDATA[C&C <b>bold</b>]]></string>
            </resources>
            """.trimIndent(),
        )
        assertEquals("C&C", catalog["amp"])
        assertEquals("C&C <b>bold</b>", catalog["cd"])
    }

    @Test
    fun `every bundled language parses and carries the app name`() {
        val source = BundledCatalogSource()
        AppLanguage.all.forEach { language ->
            val catalog = assertNotNull(source.load(language), "no file for ${language.code}")
            assertTrue(catalog.size > 1000, "${language.code} has only ${catalog.size} keys")
            assertNotNull(catalog[S.app_name], "${language.code} lacks app_name")
        }
    }

    @Test
    fun `desktop-only keys are in the English catalogue and android wins on overlap`() {
        val english = assertNotNull(BundledCatalogSource().load(AppLanguage.English))
        assertEquals("Language", english[S.desktop_language])
        assertEquals("Start Project", english[S.start_project])
    }

    @Test
    fun `every generated key exists in the English catalogue`() {
        val english = assertNotNull(BundledCatalogSource().load(AppLanguage.English))
        val missing = S::class.java.declaredFields
            .filter { java.lang.reflect.Modifier.isStatic(it.modifiers) && it.type == String::class.java }
            .map { it.get(null) as String }
            .filter { english[it] == null && english.plural(it, 1) == null && english.array(it).isEmpty() }
        assertTrue(missing.isEmpty(), "keys in S with no English text: ${missing.take(20)}")
    }

    @Test
    fun `desktop translations only name keys that desktop-en has and reach the language`() {
        val source = BundledCatalogSource()
        val englishKeys = S::class.java.declaredFields
            .filter { java.lang.reflect.Modifier.isStatic(it.modifiers) && it.type == String::class.java }
            .map { it.get(null) as String }
            .filter { it.startsWith("desktop_") }
            .toSet()
        AppLanguage.all.filter { it != AppLanguage.English }.forEach { language ->
            val stream = BundledCatalogSource::class.java.classLoader
                .getResourceAsStream("i18n/desktop-${language.code}.xml") ?: return@forEach
            val desktop = stream.use { AndroidStringsXml.parse(language, it) }
            // A translated key that English lacks is unreachable from `S` — a typo in the file.
            val unknown = desktopKeysOf(language).filterNot { it in englishKeys }
            assertTrue(unknown.isEmpty(), "${language.code}: unknown keys $unknown")
            assertNotNull(desktop[S.desktop_sign_out_title], "${language.code} lacks the sign-out title")
            assertNotNull(source.load(language)?.get(S.desktop_sign_out_title))
        }
    }

    @Test
    fun `a separator keeps the space it needs`() {
        // Android trims a value's ends unless it is quoted, so every catalogue
        // entry that opens or closes with a space must be written `" · x"`.
        // Unquoted, the separator silently disappears and two words run together.
        val english = assertNotNull(BundledCatalogSource().load(AppLanguage.English))
        val source = BundledCatalogSource::class.java.classLoader
            .getResourceAsStream("i18n/desktop-en.xml")!!.bufferedReader().readText()
        val unquoted = Regex("""<string name="([^"]+)">(?!")([^<]*)</string>""")
            .findAll(source)
            .filter { it.groupValues[2] != it.groupValues[2].trim() }
            .map { it.groupValues[1] }
            .toList()
        assertTrue(unquoted.isEmpty(), "edge whitespace needs quoting: $unquoted")
        assertEquals(" · %1\u0024d line", english["desktop_cr_lines_one"])
    }

    @Test
    fun `every desktop value with a placeholder formats in every language`() {
        // str(S.key, args) runs the value through String.format. A bare `%` next to
        // a placeholder throws, and the app then shows the raw template instead of
        // the sentence — "Tax rate must be between %1$s% and %2$s%." did exactly that.
        val placeholder = Regex("""%(\d+\$)?[sdf]""")
        val broken = AppLanguage.all.flatMap { language ->
            val stream = BundledCatalogSource::class.java.classLoader
                .getResourceAsStream("i18n/desktop-${language.code}.xml") ?: return@flatMap emptyList()
            val catalog = stream.use { AndroidStringsXml.parse(language, it) }
            desktopKeysOf(language).mapNotNull { key ->
                val value = catalog[key] ?: return@mapNotNull null
                if (!placeholder.containsMatchIn(value)) return@mapNotNull null
                val args = Array<Any?>(ARG_SLOTS) { "x" }
                // Every dummy argument is a string, so read integer conversions as %s.
                val template = value.replace(Regex("%(\\d+\\$)?d"), "%$1s")
                runCatching { String.format(java.util.Locale.ROOT, template, *args) }
                    .exceptionOrNull()?.let { "${language.code}:$key" }
            }
        }
        assertTrue(broken.isEmpty(), "values that fail to format: ${broken.take(20)}")
    }

    private fun desktopKeysOf(language: AppLanguage): List<String> {
        val stream = BundledCatalogSource::class.java.classLoader
            .getResourceAsStream("i18n/desktop-${language.code}.xml") ?: return emptyList()
        val document = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(stream)
        val nodes = document.documentElement.getElementsByTagName("string")
        return (0 until nodes.length).map { (nodes.item(it) as org.w3c.dom.Element).getAttribute("name") }
    }

    private companion object {
        /** Enough arguments for any value in the catalogue; unused ones are ignored. */
        const val ARG_SLOTS = 6
    }
}
