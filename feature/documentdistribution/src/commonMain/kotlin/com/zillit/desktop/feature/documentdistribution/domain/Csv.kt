package com.zillit.desktop.feature.documentdistribution.domain

/** A parsed CSV row for the distribution-list importer. */
data class CsvContact(
    val name: String,
    val email: String,
    val job: String = "",
    val valid: Boolean = isValidEmail(email),
    /** Already in the list, or repeated within the file. */
    val duplicate: Boolean = false,
) {
    fun toRecipient(): Recipient = Recipient(email = email, name = name, jobTitle = job)
}

/**
 * The CSV importer and exporters, ported from `utils/csv.js` and the two
 * client-side exports in History and the Address Book.
 */
object Csv {

    /**
     * Minimal RFC-4180 parser: BOM, quoted fields with embedded commas and
     * newlines, doubled-quote escaping. Blank rows are dropped.
     */
    @Suppress("CyclomaticComplexMethod") // A character-class state machine; each branch is one CSV rule.
    fun parse(text: String): List<List<String>> {
        val stripped = text.removePrefix("﻿")
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var inQuotes = false
        var i = 0

        fun pushField() {
            row.add(field.toString())
            field.setLength(0)
        }

        fun pushRow() {
            if (row.isEmpty() && field.isEmpty()) return
            pushField()
            if (row.any { it.isNotEmpty() }) rows.add(row)
            row = mutableListOf()
        }

        while (i < stripped.length) {
            val c = stripped[i]
            when {
                inQuotes && c == '"' && stripped.getOrNull(i + 1) == '"' -> {
                    field.append('"')
                    i += 2
                    continue
                }
                inQuotes && c == '"' -> inQuotes = false
                inQuotes -> field.append(c)
                c == '"' -> inQuotes = true
                c == ',' -> pushField()
                c == '\n' || c == '\r' -> {
                    pushRow()
                    if (c == '\r' && stripped.getOrNull(i + 1) == '\n') i++
                }
                else -> field.append(c)
            }
            i++
        }
        pushRow()
        return rows
    }

    /**
     * Rows as contacts. Google/Outlook contact exports (split name columns,
     * several email columns) take priority; otherwise a header row is
     * detected, else 1 column → email, 2 → name,email, 3+ → name,email,job.
     */
    fun toContacts(text: String, existingEmails: Collection<String> = emptyList()): List<CsvContact> {
        val rows = parse(text)
        if (rows.isEmpty()) return emptyList()
        val header = rows.first().map { it.lowercase().trim() }
        val parsed = contactExport(rows, header) ?: simple(rows, header)
        val existing = existingEmails.map { it.lowercase() }.toHashSet()
        val seen = HashSet<String>()
        return parsed.filter { it.email.isNotEmpty() }.map { contact ->
            val duplicate = contact.email in existing || !seen.add(contact.email)
            contact.copy(duplicate = duplicate)
        }
    }

    private fun contactExport(rows: List<List<String>>, header: List<String>): List<CsvContact>? {
        val nameIdx = EXPORT_NAME_HEADERS.map { header.indexOf(it) }.filter { it >= 0 }
        val emailIdx = EXPORT_EMAIL_HEADERS.map { header.indexOf(it) }.filter { it >= 0 }
        if (nameIdx.isEmpty() || emailIdx.isEmpty()) return null
        return rows.drop(1).flatMap { row ->
            val name = nameIdx.map { row.getOrNull(it).orEmpty().trim() }.filter { it.isNotEmpty() }.joinToString(" ")
            emailIdx.mapNotNull { idx ->
                val email = row.getOrNull(idx).orEmpty().trim().lowercase()
                if (email.isEmpty()) null else CsvContact(name = name, email = email)
            }
        }
    }

    private fun simple(rows: List<List<String>>, header: List<String>): List<CsvContact> {
        val hasHeaders = header.any { HEADER_WORDS.matches(it) }
        val nameIdx: Int
        val emailIdx: Int
        var jobIdx = -1
        var dataStart = 0
        when {
            hasHeaders -> {
                nameIdx = header.indexOfFirst { NAME_HEADER.matches(it) }
                emailIdx = header.indexOfFirst { EMAIL_HEADER.matches(it) }
                jobIdx = header.indexOfFirst { JOB_HEADER.matches(it) }
                dataStart = 1
            }
            rows.first().size == 1 -> { nameIdx = -1; emailIdx = 0 }
            rows.first().size == 2 -> { nameIdx = 0; emailIdx = 1 }
            else -> { nameIdx = 0; emailIdx = 1; jobIdx = 2 }
        }
        if (emailIdx < 0) return emptyList()
        return rows.drop(dataStart).map { row ->
            CsvContact(
                name = if (nameIdx >= 0) row.getOrNull(nameIdx).orEmpty().trim() else "",
                email = row.getOrNull(emailIdx).orEmpty().trim().lowercase(),
                job = if (jobIdx >= 0) row.getOrNull(jobIdx).orEmpty().trim() else "",
            )
        }
    }

    /** The importer's example file — so users know exactly which columns it expects. */
    fun template(): String = render(
        listOf(
            listOf("name", "email", "job"),
            listOf("Jane Doe", "jane@example.com", "Director"),
            listOf("John Smith", "john@example.com", "Producer"),
            listOf("Alex, Jr.", "alex@example.com", ""),
        ),
    )

    /** A list's members, `name,email,job` — re-imports cleanly. */
    fun recipients(recipients: List<Recipient>): String = render(
        listOf(listOf("name", "email", "job")) +
            recipients.map { listOf(it.name, it.email, it.jobTitle) },
    )

    /** The whole address book, with a trailing `lists` column the importer ignores. */
    fun contacts(contacts: List<Contact>): String = render(
        listOf(listOf("name", "email", "job", "lists")) +
            contacts.map { c -> listOf(c.name, c.email, c.jobTitle, c.lists.joinToString("; ") { it.name }) },
    )

    /**
     * A send's recipients with delivery, for History's export. Leads with a
     * UTF-8 BOM so Excel opens accented names correctly.
     */
    fun deliveries(distribution: Distribution, openedAt: (Long?) -> String): String {
        val rows = distribution.recipients.map { row ->
            listOf(
                row.recipient.name,
                row.recipient.email,
                row.kind.label,
                row.status.wire,
                openedAt(row.openedAt),
            )
        }
        return "﻿" + render(listOf(listOf("Name", "Email", "Type", "Status", "Opened At")) + rows, "\r\n")
    }

    private fun render(rows: List<List<String>>, newline: String = "\n"): String =
        rows.joinToString(newline) { row -> row.joinToString(",") { escape(it) } }

    private fun escape(cell: String): String =
        if (cell.any { it in QUOTED_CHARS }) {
            "\"" + cell.replace("\"", "\"\"") + "\""
        } else {
            cell
        }

    private val QUOTED_CHARS = setOf('"', ',', '\n', '\r')
    private val EXPORT_NAME_HEADERS = listOf("first name", "middle name", "last name")
    private val EXPORT_EMAIL_HEADERS = listOf(
        "e-mail address", "e-mail 2 address", "e-mail 3 address", "e-mail 1 - value", "e-mail 2 - value",
    )
    private val HEADER_WORDS = Regex(
        "^(name|first[_ ]?name|full[_ ]?name|email|e-?mail|address|job|role|title)$",
        RegexOption.IGNORE_CASE,
    )
    private val NAME_HEADER = Regex("^(name|first[_ ]?name|full[_ ]?name)$", RegexOption.IGNORE_CASE)
    private val EMAIL_HEADER = Regex("^(email|e-?mail|address)$", RegexOption.IGNORE_CASE)
    private val JOB_HEADER = Regex("^(job|role|title|position)$", RegexOption.IGNORE_CASE)
}
