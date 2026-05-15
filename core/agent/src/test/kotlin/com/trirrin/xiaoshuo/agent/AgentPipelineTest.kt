package com.trirrin.xiaoshuo.agent

import com.trirrin.xiaoshuo.llm.LlmChunk
import com.trirrin.xiaoshuo.llm.LlmClient
import com.trirrin.xiaoshuo.llm.LlmProvider
import com.trirrin.xiaoshuo.llm.LlmRequest
import com.trirrin.xiaoshuo.llm.LlmResponse
import com.trirrin.xiaoshuo.model.Chapter
import com.trirrin.xiaoshuo.model.ChapterSynopsis
import com.trirrin.xiaoshuo.model.Genre
import com.trirrin.xiaoshuo.model.Novel
import com.trirrin.xiaoshuo.model.NovelOutline
import com.trirrin.xiaoshuo.model.PlotPoint
import com.trirrin.xiaoshuo.model.Scene
import com.trirrin.xiaoshuo.model.SceneBreakdown
import com.trirrin.xiaoshuo.prompt.OutlineInput
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AgentPipelineTest {
    @Test
    fun `outline agent replaces genre placeholder before sending request`() = runTest {
        val llm = QueueLlmClient(
            """
            {
              "premise": "A courier steals a living map.",
              "majorPlotPoints": [{"name": "Inciting Incident", "description": "Mira steals the map.", "position": 0.12}],
              "characterArcs": [],
              "thematicStructure": "Trust against control.",
              "chapterCount": 1,
              "chapterBriefs": [{"chapterIndex": 1, "title": "The Theft", "plotBeats": "Mira steals the map.", "purposeInStory": "Launch the story."}]
            }
            """.trimIndent(),
        )
        val agent = OutlineAgent(llmClient = llm, model = "creative-model")

        val result = agent.generate(
            OutlineInput(
                concept = "A courier steals a living map.",
                genre = Genre.FANTASY,
                themes = listOf("trust"),
                styleGuide = com.trirrin.xiaoshuo.model.StyleGuide(),
            ),
        )

        assertTrue(result is AgentResult.OutlineResult)
        assertTrue(llm.requests.single().systemPrompt.contains("Fantasy"))
        assertFalse(llm.requests.single().systemPrompt.contains("$" + "GENRE"))
    }

    @Test
    fun `pipeline generates scene reviews it and updates bible`() = runTest {
        val llm = QueueLlmClient(
            "Mira crossed the Ash Gate with the living map pressed to her wounded side.",
            """
            {
              "complianceScore": 8,
              "issues": [],
              "suggestedFixes": [],
              "passed": true
            }
            """.trimIndent(),
            """
            {
              "charactersToAdd": [
                {"name": "Mira", "description": "A courier", "personality": "defiant", "currentState": "wounded", "relationships": []}
              ],
              "charactersToUpdate": [],
              "locationsToAdd": [
                {"name": "Ash Gate", "description": "The ruined eastern gate", "significance": "Escape route"}
              ],
              "timelineEventsToAdd": [
                {"description": "Mira crossed the Ash Gate."}
              ],
              "worldRulesToAdd": []
            }
            """.trimIndent(),
        )
        val filter = BibleFilter(tokenBudget = 500)
        val pipeline = AgentPipeline(
            outlineAgent = OutlineAgent(llm, "creative-model"),
            chapterSynopsisAgent = ChapterSynopsisAgent(llm, "creative-model", filter),
            sceneExpansionAgent = SceneExpansionAgent(llm, "creative-model", filter),
            reviewAgent = ReviewAgent(llm, "review-model"),
            continuityAgent = ContinuityAgent(llm, "cheap-model"),
            bibleMerger = BibleMerger(),
        )
        val novel = Novel(
            title = "The Glass Map",
            genre = Genre.FANTASY,
            concept = "A courier steals a living map.",
            outline = NovelOutline(
                premise = "A courier steals a living map.",
                majorPlotPoints = listOf(PlotPoint("Inciting Incident", "Mira steals the map.", 0.12f)),
                chapterCount = 1,
                chapterBriefs = emptyList(),
            ),
        )
        val chapter = Chapter(
            novelId = novel.id,
            order = 1,
            title = "The Ash Gate",
            synopsis = ChapterSynopsis(
                chapterGoal = "Mira escapes the city.",
                sceneBreakdowns = listOf(
                    SceneBreakdown(
                        sceneIndex = 1,
                        synopsis = "Mira crosses the Ash Gate with the living map.",
                        targetWordCount = 50,
                    ),
                ),
                chapterEnding = "Mira reaches the road.",
            ),
        )
        val scene = Scene(
            chapterId = chapter.id,
            novelId = novel.id,
            order = 1,
        )

        val events = pipeline.generateScene(novel, chapter, scene).toList()
        val textComplete = events.filterIsInstance<PipelineEvent.SceneTextComplete>().single()
        val review = events.filterIsInstance<PipelineEvent.ReviewComplete>().single().result
        val bible = events.filterIsInstance<PipelineEvent.BibleUpdated>().single().bible

        assertEquals(14, textComplete.wordCount)
        assertTrue(review.passed)
        assertTrue(bible.characters.any { it.name == "Mira" && it.currentState == "wounded" })
        assertTrue(bible.locations.any { it.name == "Ash Gate" })
        assertEquals("Mira crossed the Ash Gate.", bible.timelineEvents.single().description)
        assertEquals(listOf("creative-model", "review-model", "cheap-model"), llm.requests.map { it.model })
    }
}

private class QueueLlmClient(vararg responses: String) : LlmClient {
    private val queuedResponses = ArrayDeque(responses.toList())
    val requests = mutableListOf<LlmRequest>()

    override val provider: LlmProvider = LlmProvider.OPENAI

    override suspend fun complete(request: LlmRequest): LlmResponse {
        requests.add(request)
        return LlmResponse(
            content = queuedResponses.removeFirst(),
            inputTokens = 10,
            outputTokens = 20,
            finishReason = "stop",
            model = request.model,
        )
    }

    override fun stream(request: LlmRequest): Flow<LlmChunk> {
        requests.add(request)
        return flowOf(LlmChunk(delta = queuedResponses.removeFirst(), isComplete = true))
    }
}
