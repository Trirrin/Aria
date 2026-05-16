package com.trirrin.xiaoshuo.prompt

import com.trirrin.xiaoshuo.model.Genre
import com.trirrin.xiaoshuo.model.NovelBackgroundProposal
import kotlinx.serialization.json.Json

data class BackgroundInput(
    val userRequest: String,
    val revisionFeedback: String? = null,
    val previousProposal: NovelBackgroundProposal? = null,
)

class BackgroundPrompt : PromptTemplate<BackgroundInput, NovelBackgroundProposal> {
    override val name = "background"
    override val description = "Generate a novel background proposal from natural language intent"

    private val json = Json { ignoreUnknownKeys = true }

    override fun buildSystemPrompt(): String = """
You are a fiction development editor for an AI-assisted long-form novel workbench.

Create a background proposal that a human author can approve before anything is committed.
The proposal must be specific enough to initialize a structured novel project, but it is not canon until accepted.

Rules:
- Call submitNovelBackgroundProposal exactly once with the structured proposal arguments.
- Do not put a free-standing JSON object in assistant text.
- Use exactly one Genre enum name: ${Genre.entries.joinToString(", ") { it.name }}.
- Keep prose concise and concrete.
- Treat initial Bible entries as candidates, not facts beyond the accepted background.
- Do not create outline chapters here.
""".trimIndent()

    override fun buildUserPrompt(input: BackgroundInput): String = buildString {
        appendLine("AUTHOR REQUEST:")
        appendLine(input.userRequest.trim())
        if (!input.revisionFeedback.isNullOrBlank()) {
            appendLine()
            appendLine("REVISION REQUEST:")
            appendLine(input.revisionFeedback.trim())
        }
        if (input.previousProposal != null) {
            appendLine()
            appendLine("PREVIOUS PROPOSAL TO REVISE:")
            appendLine(Json.encodeToString(NovelBackgroundProposal.serializer(), input.previousProposal))
        }
    }

    override fun parseOutput(rawOutput: String): Result<NovelBackgroundProposal> {
        return try {
            Result.success(json.decodeObjectOutput<NovelBackgroundProposal>(rawOutput))
        } catch (error: Exception) {
            Result.failure(error)
        }
    }
}
