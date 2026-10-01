package com.zillit.desktop.feature.costumesetsync

import com.zillit.desktop.feature.costumesetsync.domain.ImportRow
import com.zillit.desktop.feature.costumesetsync.domain.aliveRows
import com.zillit.desktop.feature.costumesetsync.domain.buildCharacterImport
import com.zillit.desktop.feature.costumesetsync.domain.castNumberOf
import com.zillit.desktop.feature.costumesetsync.domain.initialRows
import com.zillit.desktop.feature.costumesetsync.domain.manualCharacters
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CharacterImportTest {
    private val detected = listOf(
        sceneRec("""{"name":"MEERA","scenes":4,"lines":12}"""),
        sceneRec("""{"name":"RADIO VOICE","scenes":1,"lines":1}"""),
    )
    private val existing = listOf(sceneRec("""{"_id":"e1","name":"Meera","cast_number":3}"""))

    @Test
    fun prefillsTheNumberACharacterAlreadyHasMatchingNamesCaseInsensitively() {
        val rows = initialRows(detected, existing)
        assertEquals(ImportRow("MEERA", "3"), rows[0])
        assertEquals("", rows[1].castNumber)
    }

    @Test
    fun resolvesADetectedNameOntoTheExistingCharacter() {
        assertEquals("Meera", buildCharacterImport(initialRows(detected, existing), existing).characterMap["MEERA"])
    }

    @Test
    fun mapsADeletedRowToNull() {
        val rows = listOf(ImportRow("RADIO VOICE", deleted = true))
        val map = buildCharacterImport(rows, existing).characterMap
        assertTrue(map.containsKey("RADIO VOICE"))
        assertNull(map["RADIO VOICE"])
    }

    @Test
    fun carriesTheTypedCastNumberUnderTheResolvedName() {
        assertEquals(mapOf("Meera" to 7L), buildCharacterImport(listOf(ImportRow("MEERA", "7")), existing).castNumbers)
    }

    @Test
    fun dropsACastNumberThatIsNotWhole() {
        assertTrue(buildCharacterImport(listOf(ImportRow("NEW", "1.5")), existing).castNumbers.isEmpty())
        assertNull(castNumberOf(ImportRow("x", "1.5")))
    }

    @Test
    fun ignoresABlankName() {
        assertTrue(buildCharacterImport(listOf(ImportRow("  ")), emptyList()).characterMap.isEmpty())
    }

    @Test
    fun manualCharactersAreOnlyHandTypedPeopleNoSceneMentions() {
        val rows = listOf(ImportRow("MEERA"), ImportRow("Stunt double", "9", manual = true))
        assertEquals(
            listOf("Stunt double" to 9L),
            manualCharacters(rows, existing, detected).map { it.name to it.castNumber },
        )
    }

    @Test
    fun manualCharactersSkipKnownAndDeletedNames() {
        val known = listOf(
            ImportRow("MEERA", manual = true),
            ImportRow("RADIO VOICE", manual = true),
            ImportRow("Extra", deleted = true, manual = true),
        )
        assertTrue(manualCharacters(known, existing, detected).isEmpty())
    }

    @Test
    fun aliveRowsCountWhatTheImportWillCreate() {
        assertEquals(2, aliveRows(listOf(ImportRow("a"), ImportRow("b", deleted = true), ImportRow("c"))).size)
    }
}
