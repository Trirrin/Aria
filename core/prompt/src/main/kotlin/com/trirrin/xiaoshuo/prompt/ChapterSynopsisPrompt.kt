package com.trirrin.xiaoshuo.prompt

import com.trirrin.xiaoshuo.model.*
import kotlinx.serialization.json.Json

data class ChapterSynopsisInput(
    val chapterBrief: ChapterBrief,
    val previousChapterBrief: ChapterBrief?,
    val nextChapterBrief: ChapterBrief?,
    val premise: String,
    val majorPlotPoints: List<PlotPoint>,
    val relevantCharacters: List<CharacterEntry>,
    val relevantLocations: List<LocationEntry>,
    val previousChapterEnding: String?,
)

class ChapterSynopsisPrompt : PromptTemplate<ChapterSynopsisInput, ChapterSynopsis> {

    override val name = "chapter_synopsis"
    override val description = "Generate a detailed chapter synopsis with scene breakdowns"

    private val json = Json { ignoreUnknownKeys = true }

    override fun buildSystemPrompt(): String = """
You are a story breakdown specialist. You take high-level chapter briefs and expand them into detailed scene-by-scene breakdowns with specific beats, character motivations, and emotional arcs.

Your task is to create a chapter synopsis that:
1. Faithfully follows the chapter brief's plot beats and purpose
2. Breaks the chapter into 2-4 scenes, each with a clear purpose
3. Ensures each scene advances the plot or develops a character
4. Provides a chapter ending that hooks into the next chapter
5. Maintains continuity with the previous chapter's ending

Each scene should specify its synopsis and target word count (2000-3000 words).

OUTPUT FORMAT: Return ONLY a JSON object, no markdown, no extra text:
{
  "chapterGoal": "One sentence describing what this chapter accomplishes",
  "sceneBreakdowns": [
    {"sceneIndex": 1, "synopsis": "Detailed scene description with specific beats", "targetWordCount": 2500}
  ],
  "chapterEnding": "Description of how this chapter ends",
  "transitionNotes": "Notes for continuity with the next chapter"
}
""".trimIndent()

    override fun buildUserPrompt(input: ChapterSynopsisInput): String = buildString {
        appendLine("STORY PREMISE: ${input.premise}")
        appendLine()
        appendLine("MAJOR PLOT POINTS:")
        input.majorPlotPoints.forEach { appendLine("- ${it.name} (${(it.position * 100).toInt()}%): ${it.description}") }
        appendLine()
        appendLine("THIS CHAPTER (Chapter ${input.chapterBrief.chapterIndex}):")
        appendLine("Title: ${input.chapterBrief.title}")
        appendLine("Plot Beats: ${input.chapterBrief.plotBeats}")
        appendLine("Purpose: ${input.chapterBrief.purposeInStory}")
        appendLine()
        input.previousChapterBrief?.let {
            appendLine("PREVIOUS CHAPTER (Chapter ${it.chapterIndex}): ${it.title} - ${it.plotBeats}")
        }
        input.nextChapterBrief?.let {
            appendLine("NEXT CHAPTER (Chapter ${it.chapterIndex}): ${it.title} - ${it.plotBeats}")
        }
        appendLine()
        if (input.relevantCharacters.isNotEmpty()) {
            appendLine("RELEVANT CHARACTERS:")
            input.relevantCharacters.forEach { c ->
                appendLine("- ${c.name}: ${c.description}. State: ${c.currentState}")
            }
            appendLine()
        }
        if (input.relevantLocations.isNotEmpty()) {
            appendLine("RELEVANT LOCATIONS:")
            input.relevantLocations.forEach { l ->
                appendLine("- ${l.name}: ${l.description}")
            }
            appendLine()
        }
        input.previousChapterEnding?.let {
            appendLine("PREVIOUS CHAPTER ENDING:")
            appendLine(it)
            appendLine()
        }
        appendLine("Generate the chapter synopsis in the specified JSON format.")
    }

    override fun parseOutput(rawOutput: String): Result<ChapterSynopsis> {
        return try {
            Result.success(json.decodeObjectOutput<ChapterSynopsis>(rawOutput))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
