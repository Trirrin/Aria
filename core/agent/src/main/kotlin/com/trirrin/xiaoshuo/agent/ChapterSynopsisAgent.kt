package com.trirrin.xiaoshuo.agent

import com.trirrin.xiaoshuo.llm.LlmClient
import com.trirrin.xiaoshuo.llm.LlmMessage
import com.trirrin.xiaoshuo.llm.LlmRequest
import com.trirrin.xiaoshuo.llm.LlmResponse
import com.trirrin.xiaoshuo.llm.MessageRole
import com.trirrin.xiaoshuo.model.*
import com.trirrin.xiaoshuo.prompt.ChapterSynopsisInput
import com.trirrin.xiaoshuo.prompt.ChapterSynopsisPrompt

class ChapterSynopsisAgent(
    private val llmClient: LlmClient,
    private val model: String,
    private val bibleFilter: BibleFilter,
) : Agent {

    override val name = "chapter_synopsis"
    override val description = "Generates chapter synopsis with scene breakdowns"

    private val prompt = ChapterSynopsisPrompt()

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
            characterNames = extractCharacterNames(brief.plotBeats),
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
            previousChapterEnding = previousChapterEnding,
        )

        val request = LlmRequest(
            systemPrompt = prompt.buildSystemPrompt(),
            messages = listOf(LlmMessage(MessageRole.USER, prompt.buildUserPrompt(input))),
            model = model,
            maxTokens = 4096,
            temperature = 0.7,
        )

        val response: LlmResponse = try {
            llmClient.complete(request)
        } catch (e: Exception) {
            return AgentResult.Error("LLM call failed: ${e.message}", e)
        }

        return prompt.parseOutput(response.content).fold(
            onSuccess = { AgentResult.SynopsisResult(it) },
            onFailure = {
                AgentResult.Error("Failed to parse synopsis: ${it.message}", it)
            },
        )
    }

    private fun extractCharacterNames(text: String): List<String> {
        return text.split(Regex("[\\s.,!?;:\"'()\\[\\]{}\\-—–]+"))
            .filter { it.length > 2 && it.first().isUpperCase() }
            .distinct()
    }
}
