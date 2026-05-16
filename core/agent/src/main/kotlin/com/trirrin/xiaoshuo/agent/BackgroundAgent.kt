package com.trirrin.xiaoshuo.agent

import com.trirrin.xiaoshuo.llm.LlmClient
import com.trirrin.xiaoshuo.llm.LlmMessage
import com.trirrin.xiaoshuo.llm.LlmRequest
import com.trirrin.xiaoshuo.llm.LlmResponse
import com.trirrin.xiaoshuo.llm.MessageRole
import com.trirrin.xiaoshuo.prompt.BackgroundInput
import com.trirrin.xiaoshuo.prompt.BackgroundPrompt

class BackgroundAgent(
    private val llmClient: LlmClient,
    private val model: String,
) : Agent {
    override val name = "background"
    override val description = "Generates novel background proposals from natural language intent"

    private val prompt = BackgroundPrompt()

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
        )

        val response: LlmResponse = try {
            llmClient.complete(request)
        } catch (error: Exception) {
            return AgentResult.Error("LLM call failed: ${error.message}", error)
        }

        val baseUsage = response.toAgentUsage(name, model)
        return parseOrRepairJsonOutput(
            rawContent = response.content,
            llmClient = llmClient,
            model = model,
            agentName = name,
            parse = prompt::parseOutput,
        ).fold(
            onSuccess = { (background, repairUsage) ->
                AgentResult.BackgroundResult(background, baseUsage.plusRepair(repairUsage))
            },
            onFailure = {
                AgentResult.Error("Failed to parse background proposal: ${it.message}", it)
            },
        )
    }
}
