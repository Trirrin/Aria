package com.trirrin.xiaoshuo.agent

import com.trirrin.xiaoshuo.llm.*
import com.trirrin.xiaoshuo.model.CharacterEntry
import com.trirrin.xiaoshuo.prompt.ReviewInput
import com.trirrin.xiaoshuo.prompt.ReviewPrompt
import com.trirrin.xiaoshuo.prompt.ReviewType

class ReviewAgent(
    private val llmClient: LlmClient,
    private val model: String,
) : Agent {

    override val name = "review"
    override val description = "Reviews child output for compliance against parent directive"

    private val prompt = ReviewPrompt()

    suspend fun review(
        parentDirective: String,
        childOutput: String,
        reviewType: ReviewType,
        relevantCharacters: List<CharacterEntry> = emptyList(),
    ): AgentResult {
        val input = ReviewInput(
            parentDirective = parentDirective,
            childOutput = childOutput,
            reviewType = reviewType,
            relevantCharacters = relevantCharacters,
        )

        val request = LlmRequest(
            systemPrompt = prompt.buildSystemPrompt(),
            messages = listOf(LlmMessage(MessageRole.USER, prompt.buildUserPrompt(input))),
            model = model,
            maxTokens = 2048,
            temperature = 0.3,
        )

        val response: LlmResponse = try {
            llmClient.complete(request)
        } catch (e: Exception) {
            return AgentResult.Error("LLM call failed: ${e.message}", e)
        }

        return prompt.parseOutput(response.content).fold(
            onSuccess = {
                AgentResult.ReviewResult(
                    score = it.complianceScore,
                    issues = it.issues,
                    suggestedFixes = it.suggestedFixes,
                    passed = it.passed,
                    usage = response.toAgentUsage(name, model),
                )
            },
            onFailure = {
                AgentResult.Error("Failed to parse review: ${it.message}", it)
            },
        )
    }
}
