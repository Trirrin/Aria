package com.trirrin.xiaoshuo.agent

import com.trirrin.xiaoshuo.model.CharacterEntry
import com.trirrin.xiaoshuo.model.LocationEntry
import com.trirrin.xiaoshuo.model.NovelBible
import com.trirrin.xiaoshuo.model.Relationship
import com.trirrin.xiaoshuo.model.TimelineEvent
import com.trirrin.xiaoshuo.model.WorldRule
import com.trirrin.xiaoshuo.prompt.BibleDiff
import com.trirrin.xiaoshuo.prompt.CharacterToAdd
import com.trirrin.xiaoshuo.prompt.CharacterToUpdate
import com.trirrin.xiaoshuo.prompt.LocationToAdd
import com.trirrin.xiaoshuo.prompt.RelationshipToAdd
import com.trirrin.xiaoshuo.prompt.TimelineEventToAdd
import com.trirrin.xiaoshuo.prompt.WorldRuleToAdd
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BibleFilterTest {
    @Test
    fun `filter includes direct and related characters`() {
        val bible = NovelBible(
            characters = listOf(
                CharacterEntry(
                    name = "Mira",
                    description = "A courier from the glass city",
                    relationships = listOf(Relationship("Jon", "brother")),
                ),
                CharacterEntry(name = "Jon", description = "A fugitive scholar"),
                CharacterEntry(name = "Sable", description = "A remote antagonist"),
            ),
            locations = listOf(LocationEntry(name = "Glass City", description = "A city of mirrors")),
            timelineEvents = listOf(TimelineEvent(description = "Mira stole the prism")),
            worldRules = listOf(WorldRule(category = "Magic", rule = "Prisms remember spoken promises")),
        )

        val filtered = BibleFilter(tokenBudget = 500).filterRelevant(
            bible = bible,
            context = BibleFilterContext(sceneSynopsis = "Mira returns to the Glass City with the prism."),
        )

        assertEquals(listOf("Mira", "Jon"), filtered.characters.map { it.name })
        assertEquals(listOf("Glass City"), filtered.locations.map { it.name })
        assertEquals(listOf("Mira stole the prism"), filtered.timelineEvents.map { it.description })
        assertEquals(listOf("Prisms remember spoken promises"), filtered.worldRules.map { it.rule })
    }

    @Test
    fun `filter respects token budget`() {
        val bible = NovelBible(
            characters = listOf(
                CharacterEntry(name = "Mira", description = "x".repeat(400)),
                CharacterEntry(name = "Jon", description = "short"),
            ),
        )

        val filtered = BibleFilter(tokenBudget = 10).filterRelevant(
            bible = bible,
            context = BibleFilterContext(sceneSynopsis = "Mira and Jon argue."),
        )

        assertEquals(listOf("Jon"), filtered.characters.map { it.name })
    }

    @Test
    fun `continuity context keeps only trailing words`() {
        val context = (1..250).joinToString(" ") { "word$it" }

        val trimmed = trimContinuityContext(context, maxWords = 200)

        val words = trimmed.orEmpty().split(" ")
        assertEquals(200, words.size)
        assertEquals("word51", words.first())
        assertEquals("word250", words.last())
    }

    @Test
    fun `merger updates existing facts and appends new facts`() {
        val existing = NovelBible(
            characters = listOf(CharacterEntry(name = "Mira", currentState = "unhurt")),
            locations = listOf(LocationEntry(name = "Glass City")),
            timelineEvents = listOf(TimelineEvent(description = "Opening event", chronologicalOrder = 0)),
            worldRules = listOf(WorldRule(category = "Magic", rule = "Old rule")),
        )
        val diff = BibleDiff(
            charactersToAdd = listOf(
                CharacterToAdd(
                    name = "Jon",
                    description = "A fugitive scholar",
                    relationships = listOf(RelationshipToAdd("Mira", "sister")),
                ),
            ),
            charactersToUpdate = listOf(CharacterToUpdate(name = "Mira", updatedState = "wounded")),
            locationsToAdd = listOf(
                LocationToAdd(name = "Glass City"),
                LocationToAdd(name = "Ash Gate", description = "The ruined eastern gate"),
            ),
            timelineEventsToAdd = listOf(
                TimelineEventToAdd("Mira crossed the Ash Gate"),
                TimelineEventToAdd("Jon found the map"),
            ),
            worldRulesToAdd = listOf(
                WorldRuleToAdd(category = "Magic", rule = "Old rule"),
                WorldRuleToAdd(category = "Magic", rule = "Maps burn when they lie"),
            ),
        )

        val merged = BibleMerger().merge(existing, diff, chapterId = "chapter-1", sceneId = "scene-2")

        assertEquals("wounded", merged.characters.first { it.name == "Mira" }.currentState)
        assertTrue(merged.characters.any { it.name == "Jon" && it.firstAppearanceChapterId == "chapter-1" })
        assertEquals(listOf("Glass City", "Ash Gate"), merged.locations.map { it.name })
        assertEquals(listOf(0, 1, 2), merged.timelineEvents.map { it.chronologicalOrder })
        assertEquals(listOf("Old rule", "Maps burn when they lie"), merged.worldRules.map { it.rule })
    }
}
