package com.zillit.desktop.feature.draft.domain

/**
 * Fountain — the plain-text screenplay format every screenwriting app reads
 * (fountain.io). What we write is the canonical form; what we read is the
 * common subset: title page, scene headings (INT./EXT./forced `.`), action,
 * character cues (all caps, forced `@`), parentheticals, dialogue,
 * transitions (`… TO:` and forced `>`), centered text, boneyards `/* */`,
 * notes `[[ ]]`, sections `#` and synopses `=` (kept as General so nothing
 * typed is lost).
 */
@Suppress("CyclomaticComplexMethod", "LongMethod", "NestedBlockDepth", "ComplexCondition")
// A format is a list of rules; each `when` arm is one rule of fountain.io, in its order of precedence.
object Fountain {

    data class Parsed(val titlePage: TitlePage, val elements: List<ScriptElement>)

    fun write(screenplay: Screenplay): String = buildString {
        val page = screenplay.titlePage
        val fields = listOfNotNull(
            page.title.ifBlank { screenplay.title }.takeIf { it.isNotBlank() }?.let { "Title" to it },
            page.credit.takeIf { it.isNotBlank() }?.let { "Credit" to it },
            page.author.takeIf { it.isNotBlank() }?.let { "Author" to it },
            page.source.takeIf { it.isNotBlank() }?.let { "Source" to it },
            page.draftDate.takeIf { it.isNotBlank() }?.let { "Draft date" to it },
            page.contact.takeIf { it.isNotBlank() }?.let { "Contact" to it },
            page.notes.takeIf { it.isNotBlank() }?.let { "Notes" to it },
        )
        fields.forEach { (key, value) ->
            val lines = value.split('\n')
            if (lines.size == 1) append(key).append(": ").append(lines[0]).append('\n')
            else {
                append(key).append(":\n")
                lines.forEach { append("    ").append(it).append('\n') }
            }
        }
        if (fields.isNotEmpty()) append('\n')

        var previous: ElementType? = null
        screenplay.elements.forEach { element ->
            val text = element.text.trimEnd()
            if (previous != null && !(element.type == ElementType.Parenthetical || element.type == ElementType
                .Dialogue)) {
                append('\n')
            }
            when (element.type) {
                ElementType.SceneHeading ->
                    append(if (looksLikeHeading(text)) text else ".$text").append('\n')
                ElementType.Character ->
                    append(if (isAllCaps(text) && text.isNotBlank()) text else "@$text").append('\n')
                ElementType.Transition ->
                    append(if (isTransition(text)) text else ">$text").append('\n')
                ElementType.Shot -> append("!").append(text).append('\n')
                ElementType.General -> append("!").append(text).append('\n')
                ElementType.Parenthetical -> append(if (text.startsWith("(")) text else "($text)").append('\n')
                ElementType.Action -> append(if (needsForcedAction(text)) "!$text" else text).append('\n')
                ElementType.Dialogue -> append(text.ifBlank { " " }).append('\n')
            }
            previous = element.type
        }
    }

    fun parse(source: String, newId: () -> String): Parsed {
        val text = source.replace("\r\n", "\n").replace('\r', '\n')
        val (titlePage, bodyStart) = parseTitlePage(text)
        val body = stripBoneyard(text.substring(bodyStart))
        val lines = body.split('\n')
        val elements = mutableListOf<ScriptElement>()
        var i = 0
        var previous: ElementType? = null
        while (i < lines.size) {
            val raw = lines[i]
            val line = raw.trimEnd()
            val trimmed = line.trim()
            if (trimmed.isEmpty()) {
                previous = null
                i++
                continue
            }
            val blankBefore = i == 0 || lines[i - 1].isBlank()
            val nextNonBlank = i + 1 < lines.size && lines[i + 1].isNotBlank()
            val type: ElementType
            val content: String
            when {
                trimmed.startsWith("[[") && trimmed.endsWith("]]") -> {
                    type = ElementType.General
                    content = trimmed.removePrefix("[[").removeSuffix("]]").trim()
                }
                trimmed.startsWith("#") -> {
                    type = ElementType.General
                    content = trimmed.trimStart('#').trim()
                }
                trimmed.startsWith("=") && !trimmed.startsWith("===") -> {
                    type = ElementType.General
                    content = trimmed.trimStart('=').trim()
                }
                trimmed.startsWith("!") -> {
                    type = ElementType.Action
                    content = trimmed.drop(1)
                }
                trimmed.startsWith(".") && !trimmed.startsWith("..") -> {
                    type = ElementType.SceneHeading
                    content = trimmed.drop(1).trim()
                }
                trimmed.startsWith("@") -> {
                    type = ElementType.Character
                    content = trimmed.drop(1).trim()
                }
                trimmed.startsWith(">") && trimmed.endsWith("<") -> {
                    type = ElementType.Action
                    content = trimmed.removePrefix(">").removeSuffix("<").trim()
                }
                trimmed.startsWith(">") -> {
                    type = ElementType.Transition
                    content = trimmed.drop(1).trim()
                }
                blankBefore && looksLikeHeading(trimmed) -> {
                    type = ElementType.SceneHeading
                    content = trimmed
                }
                blankBefore && isTransition(trimmed) -> {
                    type = ElementType.Transition
                    content = trimmed
                }
                blankBefore && isAllCaps(trimmed) && nextNonBlank -> {
                    type = ElementType.Character
                    content = trimmed
                }
                previous?.isSpeech == true && trimmed.startsWith("(") && trimmed.endsWith(")") -> {
                    type = ElementType.Parenthetical
                    content = trimmed
                }
                previous?.isSpeech == true -> {
                    type = ElementType.Dialogue
                    content = trimmed
                }
                else -> {
                    type = ElementType.Action
                    content = line.trimStart()
                }
            }
            // Dialogue and action may run several lines; gather them.
            val gathered = StringBuilder(content)
            if (type == ElementType.Dialogue || type == ElementType.Action) {
                while (i + 1 < lines.size && lines[i + 1].isNotBlank() &&
                    !(type == ElementType.Dialogue && lines[i + 1].trim().let { it.startsWith("(") && it
                        .endsWith(")") })
                ) {
                    i++
                    gathered.append('\n').append(lines[i].trimEnd().let { if (type == ElementType.Action) it
                        .trimStart() else it.trim() })
                }
            }
            elements += ScriptElement(newId(), type, gathered.toString())
            previous = type
            i++
        }
        return Parsed(titlePage, elements)
    }

    /** `Key: value` lines at the top, until the first blank line. */
    private fun parseTitlePage(text: String): Pair<TitlePage, Int> {
        val lines = text.split('\n')
        if (lines.isEmpty() || !TITLE_KEY.containsMatchIn(lines[0])) return TitlePage() to 0
        val fields = mutableMapOf<String, StringBuilder>()
        var key: String? = null
        var consumed = 0
        for ((index, line) in lines.withIndex()) {
            if (line.isBlank()) {
                consumed = index + 1
                break
            }
            val m = TITLE_KEY.find(line)
            val current = key
            if (m != null && !line.startsWith(" ") && !line.startsWith("\t")) {
                key = m.groupValues[1].trim().lowercase()
                fields[key] = StringBuilder(m.groupValues[2].trim())
            } else if (current != null) {
                val builder = fields.getValue(current)
                if (builder.isNotEmpty()) builder.append('\n')
                builder.append(line.trim())
            }
            consumed = index + 1
        }
        fun field(vararg names: String) = names.firstNotNullOfOrNull { fields[it]?.toString() }.orEmpty()
        val page = TitlePage(
            title = field("title"),
            credit = field("credit").ifBlank { "Written by" },
            author = field("author", "authors"),
            source = field("source"),
            draftDate = field("draft date", "draft_date", "date"),
            contact = field("contact", "contact info"),
            notes = field("notes"),
        )
        val offset = lines.take(consumed).sumOf { it.length + 1 }.coerceAtMost(text.length)
        return page to offset
    }

    private fun stripBoneyard(text: String): String = BONEYARD.replace(text, "")

    internal fun looksLikeHeading(line: String): Boolean {
        val upper = line.uppercase()
        return HEADING_PREFIXES.any { upper.startsWith(it) }
    }

    internal fun isTransition(line: String): Boolean =
        isAllCaps(line) && (line.trim().endsWith("TO:") || line.trim() in FIXED_TRANSITIONS)

    internal fun isAllCaps(line: String): Boolean =
        line.any { it.isLetter() } && line.none { it.isLetter() && it.isLowerCase() }

    /** Action that would otherwise be read as a heading, cue or transition. */
    private fun needsForcedAction(text: String): Boolean {
        val first = text.substringBefore('\n').trim()
        return looksLikeHeading(first) || isTransition(first) || (isAllCaps(first) && first.isNotBlank()) ||
            first.startsWith("(") || first.startsWith("~")
    }

    private val TITLE_KEY = Regex("^([A-Za-z][A-Za-z _]*):(.*)$")
    private val BONEYARD = Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL)
    private val HEADING_PREFIXES = listOf("INT.", "EXT.", "EST.", "INT./EXT.", "INT/EXT.", "I/E.", "INT ", "EXT ")
    private val FIXED_TRANSITIONS = setOf("FADE OUT.", "FADE IN:", "FADE TO BLACK.", "CUT TO BLACK.", "THE END")
}
