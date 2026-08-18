package com.zillit.desktop.feature.draft.domain

/**
 * Final Draft's `.fdx` — the XML every other screenwriting tool imports and
 * exports, so a script written here opens in Final Draft and vice versa.
 *
 * Read with a tolerant, purpose-built scanner rather than an XML parser:
 * there is none in common Kotlin, and the shape is fixed — `<Paragraph
 * Type="…">` holding one or more `<Text>` runs. Styles (bold, underline)
 * are dropped on read; a run's text is what matters. Written canonically,
 * with a title page block when there is one.
 */
object FinalDraftXml {

    data class Parsed(val titlePage: TitlePage, val elements: List<ScriptElement>)

    fun write(screenplay: Screenplay): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"no\"?>\n")
        append("<FinalDraft DocumentType=\"Script\" Template=\"No\" Version=\"5\">\n")
        append("  <Content>\n")
        screenplay.elements.forEach { element ->
            append("    <Paragraph Type=\"").append(element.type.fdxName).append("\">\n")
            append("      <Text>").append(escape(element.text)).append("</Text>\n")
            append("    </Paragraph>\n")
        }
        append("  </Content>\n")
        val page = screenplay.titlePage
        val lines = titlePageLines(page.copy(title = page.title.ifBlank { screenplay.title }))
        if (lines.isNotEmpty()) {
            append("  <TitlePage>\n    <Content>\n")
            lines.forEach { (alignment, text) ->
                append("      <Paragraph Alignment=\"").append(alignment).append("\">\n")
                append("        <Text>").append(escape(text)).append("</Text>\n")
                append("      </Paragraph>\n")
            }
            append("    </Content>\n  </TitlePage>\n")
        }
        append("</FinalDraft>\n")
    }

    fun parse(xml: String, newId: () -> String): Parsed {
        val content = CONTENT.find(xml)?.groupValues?.get(1) ?: xml
        val elements = PARAGRAPH.findAll(content).mapNotNull { match ->
            val attributes = match.groupValues[1]
            val type = TYPE_ATTR.find(attributes)?.groupValues?.get(1)?.let(ElementType::fromFdx) ?: ElementType.Action
            val text = TEXT.findAll(match.groupValues[2]).joinToString("") { unescape(it.groupValues[1]) }
            ScriptElement(newId(), type, text.replace("\r", ""))
        }.toList()
        val titlePage = TITLE_PAGE.find(xml)?.groupValues?.get(1)?.let(::parseTitlePage) ?: TitlePage()
        return Parsed(titlePage, elements)
    }

    /** The title page as Final Draft lays it out: centred block, contact bottom-left. */
    private fun titlePageLines(page: TitlePage): List<Pair<String, String>> = buildList {
        if (page.title.isNotBlank()) add("Center" to page.title)
        if (page.author.isNotBlank()) {
            add("Center" to "")
            add("Center" to page.credit.ifBlank { "Written by" })
            add("Center" to "")
            add("Center" to page.author)
        }
        if (page.source.isNotBlank()) {
            add("Center" to "")
            add("Center" to page.source)
        }
        if (page.draftDate.isNotBlank()) {
            add("Center" to "")
            add("Center" to page.draftDate)
        }
        if (page.contact.isNotBlank()) {
            add("Left" to "")
            page.contact.split('\n').forEach { add("Left" to it) }
        }
        if (page.notes.isNotBlank()) page.notes.split('\n').forEach { add("Left" to it) }
    }

    /**
     * Reads a title page back by position: the first centred line is the
     * title, "written by"-ish lines are the credit, the line after them the
     * author; left-aligned lines at the end are the contact block.
     */
    private fun parseTitlePage(block: String): TitlePage {
        val paragraphs = PARAGRAPH.findAll(block).map { match ->
            val alignment = ALIGN_ATTR.find(match.groupValues[1])?.groupValues?.get(1) ?: "Left"
            val text = TEXT.findAll(match.groupValues[2]).joinToString("") { unescape(it.groupValues[1]) }
            alignment to text.trim()
        }.filter { it.second.isNotBlank() }.toList()
        val centred = paragraphs.filter { it.first.equals("Center", ignoreCase = true) }.map { it.second }
        val left = paragraphs.filter { !it.first.equals("Center", ignoreCase = true) }.map { it.second }
        val title = centred.firstOrNull().orEmpty()
        val creditIndex = centred.indexOfFirst { CREDIT.containsMatchIn(it) }
        val credit = if (creditIndex >= 0) centred[creditIndex] else "Written by"
        val author = if (creditIndex >= 0) centred.getOrNull(creditIndex + 1).orEmpty() else centred.getOrNull(1)
            .orEmpty()
        val rest = centred.drop(maxOf(2, creditIndex + 2))
        return TitlePage(
            title = title,
            credit = credit,
            author = author,
            source = rest.firstOrNull { it.startsWith("Based on", ignoreCase = true) }.orEmpty(),
            draftDate = rest.firstOrNull { DATE_LIKE.containsMatchIn(it) }.orEmpty(),
            contact = left.joinToString("\n"),
        )
    }

    private fun escape(text: String): String = text
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun unescape(text: String): String = text
        .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'")
        .replace("&#39;", "'").replace("&#10;", "\n").replace("&#xD;", "").replace("&#xA;", "\n")
        .replace("&amp;", "&")

    private val CONTENT = Regex("<Content>(.*?)</Content>", RegexOption.DOT_MATCHES_ALL)
    private val TITLE_PAGE = Regex("<TitlePage>(.*?)</TitlePage>", RegexOption.DOT_MATCHES_ALL)
    private val PARAGRAPH = Regex("<Paragraph([^>]*)>(.*?)</Paragraph>", RegexOption.DOT_MATCHES_ALL)
    private val TYPE_ATTR = Regex("Type=\"([^\"]*)\"")
    private val ALIGN_ATTR = Regex("Alignment=\"([^\"]*)\"")
    private val TEXT = Regex("<Text[^>]*>(.*?)</Text>", RegexOption.DOT_MATCHES_ALL)
    private val CREDIT = Regex("^(written|screenplay|story|by)\\b", RegexOption.IGNORE_CASE)
    private val DATE_LIKE = Regex("\\d{4}|draft", RegexOption.IGNORE_CASE)
}
