package com.trirrin.xiaoshuo.prompt

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private const val MAX_SUMMARY_WORDS = 80

data class RollingSummaryInput(
    val previousSummary: String?,
    val sceneSynopsis: String,
    val sceneText: String,
)

@Serializable
data class RollingSummaryOutput(
    val summary: String,
)

class RollingSummaryPrompt : PromptTemplate<RollingSummaryInput, RollingSummaryOutput> {
    override val name = "rolling_chapter_summary"
    override val description = "Summarize unresolved chapter tension for the next scene"

    private val json = Json { ignoreUnknownKeys = true }

    override fun buildSystemPrompt(): String = """
You track chapter-level narrative tension. Produce a compact continuity summary of the emotional trajectory, unresolved conflicts, promises, wounds, suspicions, and open scene beats that should affect the next scene.

Rules:
1. Maximum $MAX_SUMMARY_WORDS words.
2. Do not list stable facts better suited for the Bible.
3. Preserve emotional cause and effect.
4. Return only JSON.

OUTPUT FORMAT:
{
  "summary": "<=80 words of unresolved chapter tension"
}
""".trimIndent()

    override fun buildUserPrompt(input: RollingSummaryInput): String = buildString {
        input.previousSummary?.takeIf { it.isNotBlank() }?.let {
            appendLine("PREVIOUS CHAPTER TENSION SUMMARY:")
            appendLine(it)
            appendLine()
        }
        appendLine("SCENE SYNOPSIS:")
        appendLine(input.sceneSynopsis)
        appendLine()
        appendLine("SCENE TEXT:")
        appendLine(input.sceneText)
        appendLine()
        appendLine("Update the rolling chapter tension summary in the required JSON format.")
    }

    override fun parseOutput(rawOutput: String): Result<RollingSummaryOutput> {
        return try {
            val parsed = json.decodeObjectOutput<RollingSummaryOutput>(rawOutput)
            Result.success(parsed.copy(summary = parsed.summary.limitWords(MAX_SUMMARY_WORDS)))
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    private fun String.limitWords(maxWords: Int): String {
        val words = trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        return words.take(maxWords).joinToString(" ")
    }
}
