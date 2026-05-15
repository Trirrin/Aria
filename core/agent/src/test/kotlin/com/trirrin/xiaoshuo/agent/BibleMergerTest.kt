package com.trirrin.xiaoshuo.agent

import com.trirrin.xiaoshuo.model.BibleEntrySource
import com.trirrin.xiaoshuo.model.CharacterEntry
import com.trirrin.xiaoshuo.model.LocationEntry
import com.trirrin.xiaoshuo.model.NovelBible
import com.trirrin.xiaoshuo.prompt.BibleDiff
import com.trirrin.xiaoshuo.prompt.CharacterToAdd
import com.trirrin.xiaoshuo.prompt.CharacterToUpdate
import com.trirrin.xiaoshuo.prompt.LocationToAdd
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BibleMergerTest {
    @Test
    fun `merge preserves user-authored character canon and records conflicts`() {
        val bible = NovelBible(
            characters = listOf(
                CharacterEntry(
                    id = "char-1",
                    name = "Mira",
                    description = "A careful courier.",
                    personality = "guarded",
                    currentState = "unhurt",
                    source = BibleEntrySource.USER,
                ),
            ),
        )
        val diff = BibleDiff(
            charactersToAdd = listOf(
                CharacterToAdd(
                    name = "Mira",
                    description = "A reckless thief.",
                    personality = "reckless",
                    currentState = "wounded",
                ),
            ),
            charactersToUpdate = listOf(CharacterToUpdate(name = "Mira", updatedState = "wounded")),
        )

        val merged = BibleMerger().merge(bible, diff, chapterId = "chapter-1", sceneId = "scene-2")

        val mira = merged.characters.single()
        assertEquals("A careful courier.", mira.description)
        assertEquals("guarded", mira.personality)
        assertEquals("unhurt", mira.currentState)
        assertEquals(BibleEntrySource.USER, mira.source)
        assertEquals(3, merged.conflicts.size)
        assertTrue(merged.conflicts.any { it.field == "description" && it.incomingValue == "A reckless thief." })
        assertTrue(merged.conflicts.any { it.field == "personality" && it.incomingValue == "reckless" })
        assertTrue(merged.conflicts.any { it.field == "currentState" && it.incomingValue == "wounded" })
    }

    @Test
    fun `merge updates extracted character facts in place`() {
        val bible = NovelBible(
            characters = listOf(
                CharacterEntry(
                    id = "char-1",
                    name = "Mira",
                    description = "A courier.",
                    personality = "guarded",
                    currentState = "unhurt",
                    source = BibleEntrySource.EXTRACTED,
                ),
            ),
        )
        val diff = BibleDiff(
            charactersToAdd = listOf(
                CharacterToAdd(
                    name = "Mira",
                    description = "A courier carrying a living map.",
                    personality = "defiant",
                    currentState = "wounded",
                ),
            ),
        )

        val merged = BibleMerger().merge(bible, diff, chapterId = "chapter-1", sceneId = "scene-2")

        val mira = merged.characters.single()
        assertEquals("char-1", mira.id)
        assertEquals("A courier carrying a living map.", mira.description)
        assertEquals("defiant", mira.personality)
        assertEquals("wounded", mira.currentState)
        assertEquals("scene-2", mira.sourceSceneId)
        assertTrue(merged.conflicts.isEmpty())
    }

    @Test
    fun `merge preserves user-authored location canon and records conflicts`() {
        val bible = NovelBible(
            locations = listOf(
                LocationEntry(
                    id = "loc-1",
                    name = "Glass City",
                    description = "A bright city of mirrors.",
                    significance = "Mira's home",
                    source = BibleEntrySource.USER,
                ),
            ),
        )
        val diff = BibleDiff(
            locationsToAdd = listOf(
                LocationToAdd(
                    name = "Glass City",
                    description = "A ruined mirror maze.",
                    significance = "Enemy fortress",
                ),
            ),
        )

        val merged = BibleMerger().merge(bible, diff, chapterId = "chapter-1", sceneId = "scene-2")

        val location = merged.locations.single()
        assertEquals("A bright city of mirrors.", location.description)
        assertEquals("Mira's home", location.significance)
        assertEquals(2, merged.conflicts.size)
        assertTrue(merged.conflicts.any { it.field == "description" && it.existingValue == "A bright city of mirrors." })
        assertTrue(merged.conflicts.any { it.field == "significance" && it.incomingValue == "Enemy fortress" })
    }

    @Test
    fun `merge deduplicates repeated conflict candidates`() {
        val bible = NovelBible(
            characters = listOf(
                CharacterEntry(
                    id = "char-1",
                    name = "Mira",
                    currentState = "unhurt",
                    source = BibleEntrySource.USER,
                ),
            ),
        )
        val diff = BibleDiff(
            charactersToUpdate = listOf(
                CharacterToUpdate(name = "Mira", updatedState = "wounded"),
                CharacterToUpdate(name = "Mira", updatedState = "wounded"),
            ),
        )

        val merged = BibleMerger().merge(bible, diff, chapterId = "chapter-1", sceneId = "scene-2")

        assertEquals(1, merged.conflicts.size)
        assertEquals("currentState", merged.conflicts.single().field)
    }
}
