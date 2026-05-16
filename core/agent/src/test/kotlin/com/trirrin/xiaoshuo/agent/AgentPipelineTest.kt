package com.trirrin.xiaoshuo.agent

import com.trirrin.xiaoshuo.llm.LlmChunk
import com.trirrin.xiaoshuo.llm.LlmClient
import com.trirrin.xiaoshuo.llm.LlmProvider
import com.trirrin.xiaoshuo.llm.LlmRequest
import com.trirrin.xiaoshuo.llm.LlmResponse
import com.trirrin.xiaoshuo.llm.LlmToolCall
import com.trirrin.xiaoshuo.llm.LlmToolChoice
import com.trirrin.xiaoshuo.model.Chapter
import com.trirrin.xiaoshuo.model.ChapterSynopsis
import com.trirrin.xiaoshuo.model.Genre
import com.trirrin.xiaoshuo.model.Novel
import com.trirrin.xiaoshuo.model.NovelOutline
import com.trirrin.xiaoshuo.model.PlotPoint
import com.trirrin.xiaoshuo.model.Scene
import com.trirrin.xiaoshuo.model.SceneBreakdown
import com.trirrin.xiaoshuo.prompt.BackgroundInput
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
    fun `background agent requires background proposal tool call`() = runTest {
        val llm = QueueLlmClient(toolResponse(SUBMIT_NOVEL_BACKGROUND_PROPOSAL, backgroundArguments()))
        val agent = BackgroundAgent(llmClient = llm, model = "creative-model")

        val result = agent.generate(
            BackgroundInput(userRequest = "A courier steals a living map."),
        )

        val background = result as AgentResult.BackgroundResult
        val request = llm.requests.single()
        assertEquals("Ash Map", background.background.titleOptions.single())
        assertEquals(listOf(SUBMIT_NOVEL_BACKGROUND_PROPOSAL), request.tools.map { it.name })
        assertEquals(LlmToolChoice.Named(SUBMIT_NOVEL_BACKGROUND_PROPOSAL), request.toolChoice)
        assertTrue(request.cacheableSystemPrompt)
    }

    @Test
    fun `outline agent requires outline proposal tool call`() = runTest {
        val llm = QueueLlmClient(toolResponse(SUBMIT_NOVEL_OUTLINE_PROPOSAL, outlineArguments()))
        val agent = OutlineAgent(llmClient = llm, model = "creative-model")

        val result = agent.generate(
            OutlineInput(
                concept = "A courier steals a living map.",
                genre = Genre.FANTASY,
                themes = listOf("trust"),
                styleGuide = com.trirrin.xiaoshuo.model.StyleGuide(),
            ),
        )

        val outline = result as AgentResult.OutlineResult
        val request = llm.requests.single()
        assertEquals("The Theft", outline.outline.chapterBriefs.single().title)
        assertFalse(request.systemPrompt.contains("Fantasy"))
        assertFalse(request.systemPrompt.contains("$" + "GENRE"))
        assertTrue(request.messages.single().content.contains("GENRE: Fantasy"))
        assertEquals(listOf(SUBMIT_NOVEL_OUTLINE_PROPOSAL), request.tools.map { it.name })
        assertEquals(LlmToolChoice.Named(SUBMIT_NOVEL_OUTLINE_PROPOSAL), request.toolChoice)
        assertTrue(request.cacheableSystemPrompt)
    }

    @Test
    fun `outline agent rejects assistant text json instead of repairing it`() = runTest {
        val llm = QueueLlmClient(outlineArguments())
        val agent = OutlineAgent(llmClient = llm, model = "creative-model")

        val result = agent.generate(
            OutlineInput(
                concept = "A courier steals a living map.",
                genre = Genre.FANTASY,
                themes = listOf("trust"),
                styleGuide = com.trirrin.xiaoshuo.model.StyleGuide(),
            ),
        )

        val error = result as AgentResult.Error
        assertTrue(error.message.contains("exactly one tool call"))
        assertEquals(1, llm.requests.size)
    }

    @Test
    fun `pipeline generates scene reviews it and updates bible`() = runTest {
        val llm = QueueLlmClient(
            textResponse("Mira crossed the Ash Gate with the living map pressed to her wounded side."),
            toolResponse(SUBMIT_SCENE_REVIEW, passedReviewArguments()),
            toolResponse(SUBMIT_BIBLE_UPDATE_PROPOSAL, bibleDiffArguments()),
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

    @Test
    fun `pipeline streams scene deltas and records usage`() = runTest {
        val llm = QueueLlmClient("Mira crossed the Ash Gate.")
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
        )
        val chapter = Chapter(
            novelId = novel.id,
            order = 1,
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

        val events = pipeline.streamSceneEvents(novel, chapter, scene).toList()

        assertEquals("Mira crossed the Ash Gate.", events.filterIsInstance<PipelineEvent.SceneTextDelta>().single().delta)
        val usage = events.filterIsInstance<PipelineEvent.UsageRecorded>().single().usage
        assertEquals("scene_expansion", usage.agentName)
        assertEquals("creative-model", usage.model)
        assertEquals(10, usage.inputTokens)
        assertEquals(20, usage.outputTokens)
    }

    @Test
    fun `pipeline trims previous scene ending before streaming`() = runTest {
        val llm = QueueLlmClient("Mira crossed the Ash Gate.")
        val filter = BibleFilter(tokenBudget = 500)
        val pipeline = AgentPipeline(
            outlineAgent = OutlineAgent(llm, "creative-model"),
            chapterSynopsisAgent = ChapterSynopsisAgent(llm, "creative-model", filter),
            sceneExpansionAgent = SceneExpansionAgent(llm, "creative-model", filter),
            reviewAgent = ReviewAgent(llm, "review-model"),
            continuityAgent = ContinuityAgent(llm, "cheap-model"),
            bibleMerger = BibleMerger(),
        )
        val novel = Novel(title = "The Glass Map", genre = Genre.FANTASY, concept = "A courier steals a living map.")
        val chapter = Chapter(
            novelId = novel.id,
            order = 1,
            synopsis = ChapterSynopsis(
                chapterGoal = "Mira escapes the city.",
                sceneBreakdowns = listOf(SceneBreakdown(1, "Mira crosses the Ash Gate.", 50)),
                chapterEnding = "Mira reaches the road.",
            ),
        )
        val scene = Scene(chapterId = chapter.id, novelId = novel.id, order = 1)
        val previousEnding = (1..250).joinToString(" ") { "word$it" }

        pipeline.streamSceneEvents(novel, chapter, scene, previousEnding).toList()

        val prompt = llm.requests.single().messages.single().content
        assertFalse(prompt.contains("word50"))
        assertTrue(prompt.contains("word51"))
        assertTrue(prompt.contains("word250"))
    }

    @Test
    fun `pipeline finalizes streamed scene without regenerating prose`() = runTest {
        val llm = QueueLlmClient(
            toolResponse(SUBMIT_SCENE_REVIEW, passedReviewArguments(score = 9)),
            toolResponse(
                SUBMIT_BIBLE_UPDATE_PROPOSAL,
                bibleDiffArguments(
                    currentState = "safe",
                    timelineEvent = "Mira reached the road after crossing the Ash Gate.",
                ),
            ),
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
        )
        val chapter = Chapter(
            novelId = novel.id,
            order = 1,
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

        val events = pipeline.finalizeSceneText(
            novel = novel,
            chapter = chapter,
            scene = scene,
            text = "Mira crossed the Ash Gate and reached the road.",
        ).toList()

        assertTrue(events.filterIsInstance<PipelineEvent.ReviewComplete>().single().result.passed)
        assertEquals("Mira", events.filterIsInstance<PipelineEvent.BibleUpdated>().single().bible.characters.single().name)
        assertEquals(listOf("review-model", "cheap-model"), llm.requests.map { it.model })
    }

    @Test
    fun `pipeline does not update bible when scene review fails`() = runTest {
        val llm = QueueLlmClient(
            textResponse("Mira talks about leaving but never reaches the Ash Gate."),
            toolResponse(
                SUBMIT_SCENE_REVIEW,
                failedReviewArguments(),
            ),
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
        )
        val chapter = Chapter(
            novelId = novel.id,
            order = 1,
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

        assertFalse(events.filterIsInstance<PipelineEvent.ReviewComplete>().single().result.passed)
        assertTrue(events.filterIsInstance<PipelineEvent.BibleUpdated>().isEmpty())
        assertEquals(listOf("creative-model", "review-model"), llm.requests.map { it.model })
    }
}

private fun textResponse(content: String): LlmResponse = LlmResponse(
    content = content,
    inputTokens = 10,
    outputTokens = 20,
    finishReason = "stop",
    model = "test-model",
)

private fun toolResponse(functionName: String, argumentsJson: String): LlmResponse {
    return LlmResponse(
        content = "",
        inputTokens = 10,
        outputTokens = 20,
        finishReason = "tool_calls",
        model = "test-model",
        toolCalls = listOf(
            LlmToolCall(
                id = "tool-call-1",
                name = functionName,
                argumentsJson = argumentsJson,
            ),
        ),
    )
}

private fun outlineArguments(): String = """
{
  "premise": "A courier steals a living map.",
  "majorPlotPoints": [{"name": "Inciting Incident", "description": "Mira steals the map.", "position": 0.12}],
  "characterArcs": [],
  "thematicStructure": "Trust against control.",
  "chapterCount": 1,
  "chapterBriefs": [{"chapterIndex": 1, "title": "The Theft", "plotBeats": "Mira steals the map.", "purposeInStory": "Launch the story."}]
}
""".trimIndent()

private fun backgroundArguments(): String = """
{
  "titleOptions": ["Ash Map"],
  "genre": "FANTASY",
  "tone": "urgent and mythic",
  "premise": "A courier steals a living map.",
  "worldSetup": "A city built over old roads that still remember their dead.",
  "protagonistSeed": "Mira is a courier who trusts routes more than people.",
  "coreCastSeeds": ["A cartographer who can hear roads."],
  "majorConflict": "Mira must decide whether to sell the living map or follow it.",
  "themes": ["trust"],
  "styleGuide": {
    "narrativeVoice": "THIRD_PERSON_LIMITED",
    "tense": "PAST",
    "proseStyle": "LITERARY",
    "targetSceneWordCountMin": 2000,
    "targetSceneWordCountMax": 3000,
    "additionalNotes": "Keep images concrete."
  },
  "initialBibleCandidates": {
    "characters": [{"name": "Mira", "description": "A courier", "personality": "defiant", "currentState": "carrying the map"}],
    "locations": [],
    "timelineEvents": [],
    "worldRules": [],
    "themes": [],
    "conflicts": []
  }
}
""".trimIndent()

private fun passedReviewArguments(score: Int = 8): String = """
{
  "complianceScore": $score,
  "qualityScore": 8,
  "issues": [],
  "qualityIssues": [],
  "suggestedFixes": [],
  "passed": true
}
""".trimIndent()

private fun failedReviewArguments(): String = """
{
  "complianceScore": 4,
  "qualityScore": 8,
  "issues": ["The scene never crosses the Ash Gate."],
  "qualityIssues": [],
  "suggestedFixes": ["Add the crossing beat."],
  "passed": false
}
""".trimIndent()

private fun bibleDiffArguments(
    currentState: String = "wounded",
    timelineEvent: String = "Mira crossed the Ash Gate.",
): String = """
{
  "charactersToAdd": [
    {"name": "Mira", "description": "A courier", "personality": "defiant", "currentState": "$currentState", "relationships": []}
  ],
  "charactersToUpdate": [],
  "locationsToAdd": [
    {"name": "Ash Gate", "description": "The ruined eastern gate", "significance": "Escape route"}
  ],
  "timelineEventsToAdd": [
    {"description": "$timelineEvent"}
  ],
  "worldRulesToAdd": []
}
""".trimIndent()

private class QueueLlmClient : LlmClient {
    private val queuedResponses: ArrayDeque<LlmResponse>
    val requests = mutableListOf<LlmRequest>()

    constructor(vararg responses: String) {
        queuedResponses = ArrayDeque(
            responses.map { content ->
                LlmResponse(
                    content = content,
                    inputTokens = 10,
                    outputTokens = 20,
                    finishReason = "stop",
                    model = "test-model",
                )
            },
        )
    }

    constructor(vararg responses: LlmResponse) {
        queuedResponses = ArrayDeque(responses.toList())
    }

    override val provider: LlmProvider = LlmProvider.OPENAI

    override suspend fun complete(request: LlmRequest): LlmResponse {
        requests.add(request)
        return queuedResponses.removeFirst().copy(model = request.model)
    }

    override fun stream(request: LlmRequest): Flow<LlmChunk> {
        requests.add(request)
        return flowOf(
            LlmChunk(
                delta = queuedResponses.removeFirst().content,
                inputTokens = 10,
                outputTokens = 20,
                isComplete = true,
            ),
        )
    }
}
