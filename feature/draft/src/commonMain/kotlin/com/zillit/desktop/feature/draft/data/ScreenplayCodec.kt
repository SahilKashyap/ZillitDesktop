package com.zillit.desktop.feature.draft.data

import com.zillit.desktop.feature.draft.domain.ElementType
import com.zillit.desktop.feature.draft.domain.Screenplay
import com.zillit.desktop.feature.draft.domain.ScriptElement
import com.zillit.desktop.feature.draft.domain.StoredScript
import com.zillit.desktop.feature.draft.domain.TitlePage
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * The stored body: title page and elements as JSON. Lossless for our own
 * element types, which Fountain is not (a forced heading and a real one
 * read back the same) — the interchange formats are for import and export.
 */
object ScreenplayCodec {

    @Serializable
    private data class Body(val titlePage: PageDto = PageDto(), val elements: List<ElementDto> = emptyList())

    @Serializable
    private data class PageDto(
        val title: String = "",
        val credit: String = "Written by",
        val author: String = "",
        val source: String = "",
        val draftDate: String = "",
        val contact: String = "",
        val notes: String = "",
    )

    @Serializable
    private data class ElementDto(val id: String, val type: String, val text: String)

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun encode(screenplay: Screenplay): StoredScript = StoredScript(
        id = screenplay.id,
        projectId = screenplay.projectId,
        title = screenplay.title,
        body = json.encodeToString(
            Body(
                titlePage = screenplay.titlePage.let {
                    PageDto(it.title, it.credit, it.author, it.source, it.draftDate, it.contact, it.notes)
                },
                elements = screenplay.elements.map { ElementDto(it.id, it.type.name, it.text) },
            ),
        ),
        createdAtMillis = screenplay.createdAtMillis,
        updatedAtMillis = screenplay.updatedAtMillis,
    )

    fun decode(stored: StoredScript): Screenplay {
        val body = runCatching { json.decodeFromString(Body.serializer(), stored.body) }.getOrDefault(Body())
        return Screenplay(
            id = stored.id,
            projectId = stored.projectId,
            title = stored.title,
            titlePage = body.titlePage.let {
                TitlePage(it.title, it.credit, it.author, it.source, it.draftDate, it.contact, it.notes)
            },
            elements = body.elements.map { dto ->
                ScriptElement(dto.id, ElementType.entries.firstOrNull { it.name == dto.type } ?: ElementType.Action,
                    dto.text)
            },
            createdAtMillis = stored.createdAtMillis,
            updatedAtMillis = stored.updatedAtMillis,
        )
    }
}
