package com.trirrin.xiaoshuo.agent

import com.trirrin.xiaoshuo.llm.*
import com.trirrin.xiaoshuo.model.CharacterEntry
import com.trirrin.xiaoshuo.prompt.ReviewInput
import com.trirrin.xiaoshuo.prompt.ReviewOutput
import com.trirrin.xiaoshuo.prompt.ReviewPrompt
import com.trirrin.xiaoshuo.prompt.ReviewType
import kotlinx.serialization.json.Json

class ReviewAgent(
    private val llmClient: LlmClient,
    private val model: String,
) : Agent {

    override val name = "review"
    override val description = "Reviews child output for compliance against parent directive"

    private val prompt = ReviewPrompt()
    private val json = Json { ignoreUnknownKeys = true }

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
            cacheableSystemPrompt = true,
            tools = listOf(sceneReviewTool),
            toolChoice = LlmToolChoice.Named(SUBMIT_SCENE_REVIEW),
        )

        val response: LlmResponse = try {
            llmClient.complete(request)
        } catch (e: Exception) {
            return AgentResult.Error("LLM call failed: ${e.message}", e)
        }

        val baseUsage = response.toAgentUsage(name, model)
        return response.requireSingleToolCallArguments(SUBMIT_SCENE_REVIEW, name).fold(
            onSuccess = { argumentsJson ->
                try {
                    val review = json.decodeFromString(ReviewOutput.serializer(), argumentsJson)
                    AgentResult.ReviewResult(
                        score = review.complianceScore,
                        issues = review.issues,
                        suggestedFixes = review.suggestedFixes,
                        passed = review.passed,
                        qualityScore = review.qualityScore,
                        qualityIssues = review.qualityIssues,
                        usage = baseUsage,
                    )
                } catch (error: Exception) {
                    AgentResult.Error("Failed to decode scene review arguments: ${error.message}", error)
                }
            },
            onFailure = { AgentResult.Error(it.message ?: "Invalid scene review tool call", it) },
        )
    }
}
