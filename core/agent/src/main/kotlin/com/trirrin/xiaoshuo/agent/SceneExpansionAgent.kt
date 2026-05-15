package com.trirrin.xiaoshuo.agent

import com.trirrin.xiaoshuo.llm.*
import com.trirrin.xiaoshuo.model.*
import com.trirrin.xiaoshuo.prompt.SceneExpansionInput
import com.trirrin.xiaoshuo.prompt.SceneExpansionPrompt
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class SceneExpansionAgent(
    internal val llmClient: LlmClient,
    internal val model: String,
    private val bibleFilter: BibleFilter,
) : Agent {

    override val name = "scene_expansion"
    override val description = "Expands scene synopsis into full prose"

    private val prompt = SceneExpansionPrompt()

    suspend fun generate(
        novel: Novel,
        chapter: Chapter,
        scene: Scene,
        previousSceneEnding: String? = null,
    ): AgentResult {
        val synopsis = chapter.synopsis ?: return AgentResult.Error("Chapter has no synopsis")
        val breakdown = synopsis.sceneBreakdowns.find { it.sceneIndex == scene.order }
            ?: return AgentResult.Error("No breakdown found for scene ${scene.order}")

        val filterContext = BibleFilterContext(
            sceneSynopsis = breakdown.synopsis,
            characterNames = extractNames(breakdown.synopsis),
        )
        val relevantBible = bibleFilter.filterRelevant(novel.bible, filterContext)

        val input = SceneExpansionInput(
            sceneBreakdown = breakdown,
            chapterGoal = synopsis.chapterGoal,
            relevantCharacters = relevantBible.characters,
            relevantLocations = relevantBible.locations,
            relevantWorldRules = relevantBible.worldRules,
            previousSceneEnding = trimContinuityContext(previousSceneEnding),
            styleGuide = novel.styleGuide,
        )

        val request = LlmRequest(
            systemPrompt = prompt.buildSystemPrompt(),
            messages = listOf(LlmMessage(MessageRole.USER, prompt.buildUserPrompt(input))),
            model = model,
            maxTokens = breakdown.targetWordCount * 2,
            temperature = 0.85,
        )

        return try {
            val response = llmClient.complete(request)
            prompt.parseOutput(response.content).fold(
                onSuccess = {
                    AgentResult.SceneTextResult(
                        text = it,
                        wordCount = it.split(Regex("\\s+")).count { word -> word.isNotBlank() },
                        usage = response.toAgentUsage(name, model),
                    )
                },
                onFailure = {
                    AgentResult.Error("Failed to process scene text: ${it.message}", it)
                },
            )
        } catch (e: Exception) {
            AgentResult.Error("LLM call failed: ${e.message}", e)
        }
    }

    suspend fun stream(
        novel: Novel,
        chapter: Chapter,
        scene: Scene,
        previousSceneEnding: String? = null,
    ): Flow<String> {
        return streamChunks(novel, chapter, scene, previousSceneEnding).map { it.delta }
    }

    suspend fun streamChunks(
        novel: Novel,
        chapter: Chapter,
        scene: Scene,
        previousSceneEnding: String? = null,
    ): Flow<LlmChunk> {
        val synopsis = chapter.synopsis ?: throw IllegalStateException("Chapter has no synopsis")
        val breakdown = synopsis.sceneBreakdowns.find { it.sceneIndex == scene.order }
            ?: throw IllegalStateException("No breakdown for scene ${scene.order}")

        val filterContext = BibleFilterContext(
            sceneSynopsis = breakdown.synopsis,
            characterNames = extractNames(breakdown.synopsis),
        )
        val relevantBible = bibleFilter.filterRelevant(novel.bible, filterContext)

        val input = SceneExpansionInput(
            sceneBreakdown = breakdown,
            chapterGoal = synopsis.chapterGoal,
            relevantCharacters = relevantBible.characters,
            relevantLocations = relevantBible.locations,
            relevantWorldRules = relevantBible.worldRules,
            previousSceneEnding = trimContinuityContext(previousSceneEnding),
            styleGuide = novel.styleGuide,
        )

        val request = LlmRequest(
            systemPrompt = prompt.buildSystemPrompt(),
            messages = listOf(LlmMessage(MessageRole.USER, prompt.buildUserPrompt(input))),
            model = model,
            maxTokens = breakdown.targetWordCount * 2,
            temperature = 0.85,
        )

        return llmClient.stream(request)
    }

    private fun extractNames(text: String): List<String> {
        return text.split(Regex("[\\s.,!?;:\"'()\\[\\]{}\\-—–]+"))
            .filter { it.length > 2 && it.first().isUpperCase() }
            .distinct()
    }
}
