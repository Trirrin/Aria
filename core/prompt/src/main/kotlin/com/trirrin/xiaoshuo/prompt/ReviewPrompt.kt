package com.trirrin.xiaoshuo.prompt

import com.trirrin.xiaoshuo.model.CharacterEntry
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

enum class ReviewType {
    OUTLINE_AGAINST_CONCEPT,
    SYNOPSIS_AGAINST_OUTLINE,
    TEXT_AGAINST_SYNOPSIS,
}

data class ReviewInput(
    val parentDirective: String,
    val childOutput: String,
    val reviewType: ReviewType,
    val relevantCharacters: List<CharacterEntry> = emptyList(),
)

@Serializable
data class ReviewOutput(
    val complianceScore: Int,
    val qualityScore: Int = 10,
    val issues: List<String>,
    val qualityIssues: List<String> = emptyList(),
    val suggestedFixes: List<String>,
    val passed: Boolean,
)

class ReviewPrompt : PromptTemplate<ReviewInput, ReviewOutput> {

    override val name = "review"
    override val description = "Review child output for compliance against parent directive"

    private val json = Json { ignoreUnknownKeys = true }

    override fun buildSystemPrompt(): String = """
You are a strict story editor. You compare written output against the directive it was supposed to follow. You identify deviations, missing beats, and inconsistencies.

REVIEW RULES:
1. Score directive compliance on a scale of 1-10. A compliance score of 7 or higher PASSES.
2. Separately score prose quality on a scale of 1-10. Quality is a soft signal; low quality does not fail compliance.
3. For prose, check telling instead of showing, stiff dialogue, repetitive sentence shapes, purple prose, flat sensory detail, and generic AI voice.
4. Check that ALL plot beats from the directive appear in the output.
5. Check that no characters act inconsistently with their provided profiles.
6. Issues must be SPECIFIC and ACTIONABLE. Reference exact moments or elements.
7. Suggested fixes must include both compliance fixes and quality fixes when either is weak.

OUTPUT FORMAT: Return ONLY a JSON object, no markdown, no extra text:
{
  "complianceScore": 8,
  "qualityScore": 6,
  "issues": ["Specific compliance issue 1", "Specific compliance issue 2"],
  "qualityIssues": ["Specific prose issue 1", "Specific prose issue 2"],
  "suggestedFixes": ["Specific fix 1", "Specific fix 2"],
  "passed": true
}
""".trimIndent()

    override fun buildUserPrompt(input: ReviewInput): String = buildString {
        appendLine("REVIEW TYPE: ${input.reviewType.name}")
        appendLine()
        appendLine("PARENT DIRECTIVE (what the output should follow):")
        appendLine(input.parentDirective)
        appendLine()
        appendLine("CHILD OUTPUT (the content to review):")
        appendLine(input.childOutput)
        appendLine()
        if (input.relevantCharacters.isNotEmpty()) {
            appendLine("CHARACTER PROFILES (for fact-checking):")
            input.relevantCharacters.forEach { c ->
                appendLine("- ${c.name}: ${c.personality}. State: ${c.currentState}")
            }
            appendLine()
        }
        appendLine("Evaluate compliance and prose quality, then return the JSON result.")
    }

    override fun parseOutput(rawOutput: String): Result<ReviewOutput> {
        return try {
            Result.success(json.decodeObjectOutput<ReviewOutput>(rawOutput))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
