package com.trirrin.xiaoshuo.prompt

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PromptParsingTest {
    @Test
    fun `outline parser accepts fenced json`() {
        val raw = """
            Here is the outline:
            ```json
            {
              "premise": "A courier steals a living map.",
              "majorPlotPoints": [
                {"name": "Inciting Incident", "description": "Mira steals the map.", "position": 0.12}
              ],
              "characterArcs": ["Mira learns to trust others."],
              "thematicStructure": "Trust against control.",
              "chapterCount": 1,
              "chapterBriefs": [
                {"chapterIndex": 1, "title": "The Theft", "plotBeats": "Mira steals the map.", "purposeInStory": "Launch the conflict."}
              ]
            }
            ```
        """.trimIndent()

        val outline = OutlinePrompt().parseOutput(raw).getOrThrow()

        assertEquals("A courier steals a living map.", outline.premise)
        assertEquals(1, outline.chapterCount)
        assertEquals("The Theft", outline.chapterBriefs.single().title)
    }

    @Test
    fun `review parser returns structured compliance result`() {
        val raw = """
            {
              "complianceScore": 8,
              "issues": ["Missing the final beat."],
              "suggestedFixes": ["Add the confrontation."],
              "passed": true
            }
        """.trimIndent()

        val review = ReviewPrompt().parseOutput(raw).getOrThrow()

        assertEquals(8, review.complianceScore)
        assertTrue(review.passed)
        assertEquals("Add the confrontation.", review.suggestedFixes.single())
    }

    @Test
    fun `continuity parser returns bible diff`() {
        val raw = """
            {
              "charactersToAdd": [
                {"name": "Mira", "description": "A courier", "personality": "defiant", "currentState": "wounded", "relationships": []}
              ],
              "charactersToUpdate": [],
              "locationsToAdd": [
                {"name": "Glass City", "description": "A city of mirrors", "significance": "Mira's home"}
              ],
              "timelineEventsToAdd": [
                {"description": "Mira stole the map."}
              ],
              "worldRulesToAdd": []
            }
        """.trimIndent()

        val diff = ContinuityPrompt().parseOutput(raw).getOrThrow()

        assertEquals("Mira", diff.charactersToAdd.single().name)
        assertEquals("Glass City", diff.locationsToAdd.single().name)
        assertEquals("Mira stole the map.", diff.timelineEventsToAdd.single().description)
    }
}
