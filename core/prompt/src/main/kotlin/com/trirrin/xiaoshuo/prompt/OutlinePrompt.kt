package com.trirrin.xiaoshuo.prompt

import com.trirrin.xiaoshuo.model.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

data class OutlineInput(
    val concept: String,
    val genre: Genre,
    val themes: List<String>,
    val styleGuide: StyleGuide,
    val chapterCount: IntRange = 15..25,
    val revisionFeedback: String? = null,
    val previousOutline: NovelOutline? = null,
)

class OutlinePrompt : PromptTemplate<OutlineInput, NovelOutline> {

    override val name = "outline"
    override val description = "Generate a detailed novel outline from concept"

    private val json = Json { ignoreUnknownKeys = true }
    private val outputJson = Json { encodeDefaults = true }

    override fun buildSystemPrompt(): String = """
You are a master novelist and plot architect with deep expertise in fiction structure.

Your task is to create a detailed novel outline from a given concept. The outline must include:

1. **Premise**: A clear one-paragraph statement of the story's core conflict and hook.
2. **Major Plot Points**: Key turning points mapped to their position in the story (0.0 = beginning, 1.0 = end).
   - Inciting incident at ~0.10-0.15
   - First turning point at ~0.25
   - Midpoint reversal at ~0.50
   - Dark moment / all-is-lost at ~0.75
   - Climax at ~0.85-0.90
   - Resolution at ~0.95-1.00
3. **Character Arcs**: One sentence each describing the major characters' internal journeys.
4. **Thematic Structure**: How themes weave through the narrative.
5. **Chapter Briefs**: For each chapter, provide:
   - Title
   - Plot beats (what happens)
   - Purpose in the overall story

Call submitNovelOutlineProposal exactly once with the structured outline arguments. Do not put a free-standing JSON object in assistant text.
""".trimIndent()

    override fun buildUserPrompt(input: OutlineInput): String = buildString {
        appendLine("NOVEL CONCEPT:")
        appendLine(input.concept)
        appendLine()
        appendLine("GENRE: ${input.genre.label}")
        appendLine()
        appendLine("THEMES: ${input.themes.joinToString(", ")}")
        appendLine()
        appendLine("NARRATIVE STYLE: ${input.styleGuide.narrativeVoice}, ${input.styleGuide.tense} tense, ${input.styleGuide.proseStyle} prose")
        appendLine()
        appendLine("TARGET CHAPTER COUNT: ${input.chapterCount.first}-${input.chapterCount.last}")
        if (!input.revisionFeedback.isNullOrBlank()) {
            appendLine()
            appendLine("REVISION REQUEST:")
            appendLine(input.revisionFeedback.trim())
        }
        if (input.previousOutline != null) {
            appendLine()
            appendLine("CURRENT OUTLINE TO REVISE OR REPLACE:")
            appendLine(outputJson.encodeToString(input.previousOutline))
        }
        appendLine()
        appendLine("Create a complete novel outline by calling the required outline proposal tool.")
    }

    override fun parseOutput(rawOutput: String): Result<NovelOutline> {
        return try {
            Result.success(json.decodeObjectOutput<NovelOutline>(rawOutput))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
