package com.trirrin.xiaoshuo.agent

import com.trirrin.xiaoshuo.llm.LlmClient
import com.trirrin.xiaoshuo.llm.LlmMessage
import com.trirrin.xiaoshuo.llm.LlmRequest
import com.trirrin.xiaoshuo.llm.LlmResponse
import com.trirrin.xiaoshuo.llm.LlmToolChoice
import com.trirrin.xiaoshuo.llm.MessageRole
import com.trirrin.xiaoshuo.model.NovelOutline
import com.trirrin.xiaoshuo.prompt.OutlineInput
import com.trirrin.xiaoshuo.prompt.OutlinePrompt
import kotlinx.serialization.json.Json

class OutlineAgent(
    private val llmClient: LlmClient,
    private val model: String,
) : Agent {

    override val name = "outline"
    override val description = "Generates novel outline from concept"

    private val prompt = OutlinePrompt()
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun generate(input: OutlineInput): AgentResult {
        val request = LlmRequest(
            systemPrompt = prompt.buildSystemPrompt(),
            messages = listOf(
                LlmMessage(
                    role = MessageRole.USER,
                    content = prompt.buildUserPrompt(input),
                ),
            ),
            model = model,
            maxTokens = 8192,
            temperature = 0.8,
            cacheableSystemPrompt = true,
            tools = listOf(novelOutlineProposalTool),
            toolChoice = LlmToolChoice.Named(SUBMIT_NOVEL_OUTLINE_PROPOSAL),
        )

        val response: LlmResponse = try {
            llmClient.complete(request)
        } catch (e: Exception) {
            return AgentResult.Error("LLM call failed: ${e.message}", e)
        }

        val baseUsage = response.toAgentUsage(name, model)
        return response.requireSingleToolCallArguments(SUBMIT_NOVEL_OUTLINE_PROPOSAL, name).fold(
            onSuccess = { argumentsJson ->
                try {
                    AgentResult.OutlineResult(
                        outline = json.decodeFromString(NovelOutline.serializer(), argumentsJson),
                        usage = baseUsage,
                    )
                } catch (error: Exception) {
                    AgentResult.Error("Failed to decode outline proposal arguments: ${error.message}", error)
                }
            },
            onFailure = { AgentResult.Error(it.message ?: "Invalid outline proposal tool call", it) },
        )
    }
}
