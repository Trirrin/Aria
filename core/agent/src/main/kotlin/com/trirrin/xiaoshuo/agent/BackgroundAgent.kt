package com.trirrin.xiaoshuo.agent

import com.trirrin.xiaoshuo.llm.LlmClient
import com.trirrin.xiaoshuo.llm.LlmMessage
import com.trirrin.xiaoshuo.llm.LlmRequest
import com.trirrin.xiaoshuo.llm.LlmResponse
import com.trirrin.xiaoshuo.llm.LlmToolChoice
import com.trirrin.xiaoshuo.llm.MessageRole
import com.trirrin.xiaoshuo.model.NovelBackgroundProposal
import com.trirrin.xiaoshuo.prompt.BackgroundInput
import com.trirrin.xiaoshuo.prompt.BackgroundPrompt
import kotlinx.serialization.json.Json

class BackgroundAgent(
    private val llmClient: LlmClient,
    private val model: String,
) : Agent {
    override val name = "background"
    override val description = "Generates novel background proposals from natural language intent"

    private val prompt = BackgroundPrompt()
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun generate(input: BackgroundInput): AgentResult {
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
            tools = listOf(novelBackgroundProposalTool),
            toolChoice = LlmToolChoice.Named(SUBMIT_NOVEL_BACKGROUND_PROPOSAL),
        )

        val response: LlmResponse = try {
            llmClient.complete(request)
        } catch (error: Exception) {
            return AgentResult.Error("LLM call failed: ${error.message}", error)
        }

        val baseUsage = response.toAgentUsage(name, model)
        return response.requireSingleToolCallArguments(SUBMIT_NOVEL_BACKGROUND_PROPOSAL, name).fold(
            onSuccess = { argumentsJson ->
                try {
                    AgentResult.BackgroundResult(
                        background = json.decodeFromString(NovelBackgroundProposal.serializer(), argumentsJson),
                        usage = baseUsage,
                    )
                } catch (error: Exception) {
                    AgentResult.Error("Failed to decode background proposal arguments: ${error.message}", error)
                }
            },
            onFailure = { AgentResult.Error(it.message ?: "Invalid background proposal tool call", it) },
        )
    }
}
