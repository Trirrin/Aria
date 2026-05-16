package com.trirrin.xiaoshuo.agent

import com.trirrin.xiaoshuo.llm.LlmClient
import com.trirrin.xiaoshuo.llm.LlmMessage
import com.trirrin.xiaoshuo.llm.LlmRequest
import com.trirrin.xiaoshuo.llm.LlmResponse
import com.trirrin.xiaoshuo.llm.LlmToolChoice
import com.trirrin.xiaoshuo.llm.MessageRole
import com.trirrin.xiaoshuo.model.*
import com.trirrin.xiaoshuo.prompt.ChapterSynopsisInput
import com.trirrin.xiaoshuo.prompt.ChapterSynopsisPrompt
import kotlinx.serialization.json.Json

class ChapterSynopsisAgent(
    internal val llmClient: LlmClient,
    internal val model: String,
    private val bibleFilter: BibleFilter,
) : Agent {

    override val name = "chapter_synopsis"
    override val description = "Generates chapter synopsis with scene breakdowns"

    private val prompt = ChapterSynopsisPrompt()
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun generate(
        novel: Novel,
        chapter: Chapter,
        chapters: List<Chapter>,
        previousChapterEnding: String? = null,
    ): AgentResult {
        val outline = novel.outline ?: return AgentResult.Error("Novel has no outline")
        val brief = outline.chapterBriefs.find { it.chapterIndex == chapter.order }
            ?: return AgentResult.Error("No brief found for chapter ${chapter.order}")

        val prevBrief = outline.chapterBriefs.find { it.chapterIndex == chapter.order - 1 }
        val nextBrief = outline.chapterBriefs.find { it.chapterIndex == chapter.order + 1 }

        val filterContext = BibleFilterContext(
            chapterSynopsis = brief.plotBeats,
            characterNames = NameExtractor.extract(brief.plotBeats),
        )
        val relevantBible = bibleFilter.filterRelevant(novel.bible, filterContext)

        val input = ChapterSynopsisInput(
            chapterBrief = brief,
            previousChapterBrief = prevBrief,
            nextChapterBrief = nextBrief,
            premise = outline.premise,
            majorPlotPoints = outline.majorPlotPoints,
            relevantCharacters = relevantBible.characters,
            relevantLocations = relevantBible.locations,
            previousChapterEnding = trimContinuityContext(previousChapterEnding),
        )

        val request = LlmRequest(
            systemPrompt = prompt.buildSystemPrompt(),
            messages = listOf(LlmMessage(MessageRole.USER, prompt.buildUserPrompt(input))),
            model = model,
            maxTokens = 4096,
            temperature = 0.7,
            cacheableSystemPrompt = true,
            tools = listOf(chapterSynopsisProposalTool),
            toolChoice = LlmToolChoice.Named(SUBMIT_CHAPTER_SYNOPSIS_PROPOSAL),
        )

        val response: LlmResponse = try {
            llmClient.complete(request)
        } catch (e: Exception) {
            return AgentResult.Error("LLM call failed: ${e.message}", e)
        }

        val baseUsage = response.toAgentUsage(name, model)
        return response.requireSingleToolCallArguments(SUBMIT_CHAPTER_SYNOPSIS_PROPOSAL, name).fold(
            onSuccess = { argumentsJson ->
                try {
                    AgentResult.SynopsisResult(
                        synopsis = json.decodeFromString(ChapterSynopsis.serializer(), argumentsJson),
                        usage = baseUsage,
                    )
                } catch (error: Exception) {
                    AgentResult.Error("Failed to decode chapter synopsis proposal arguments: ${error.message}", error)
                }
            },
            onFailure = { AgentResult.Error(it.message ?: "Invalid chapter synopsis proposal tool call", it) },
        )
    }
}
