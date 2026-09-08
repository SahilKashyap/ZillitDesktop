package com.zillit.desktop.feature.esignature

import com.zillit.desktop.feature.esignature.data.FieldDto
import com.zillit.desktop.feature.esignature.domain.FieldStyle
import com.zillit.desktop.feature.esignature.domain.FieldType
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The phones' newer tab keys — field types, text styling, dropdown options — read off the wire. */
class FieldStyleTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `new field types resolve by their wire names and count as typed where text is entered`() {
        assertEquals(FieldType.Phone, FieldType.fromWire("phone"))
        assertEquals(FieldType.Dropdown, FieldType.fromWire("dropdown"))
        assertEquals(FieldType.Attachment, FieldType.fromWire("attachment"))
        assertTrue(FieldType.Number.isTyped)
        assertTrue(!FieldType.Dropdown.isTyped)
    }

    @Test
    fun `styling and options are parsed, absent keys mean the default`() {
        val wire = """{"_id":"t1","type":"text","page":1,"x":10,"y":20,"font_size":"14","bold":true,
            "underline":false,"font_color":"#112233","options":["A",{"label":"B"},{"option_id":"c"}]}"""
        val dto = json.decodeFromString(FieldDto.serializer(), wire)
        val field = dto.toDomain()!!
        assertEquals(FieldStyle(fontSize = 14, fontColor = "#112233", bold = true), field.style)
        assertEquals(listOf("A", "B", "c"), field.options)
        val bare = """{"_id":"t2","type":"email","page":1}"""
        val plain = json.decodeFromString(FieldDto.serializer(), bare).toDomain()!!
        assertTrue(plain.style.isDefault)
    }
}
