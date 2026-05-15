package com.trirrin.xiaoshuo.agent

import com.trirrin.xiaoshuo.llm.LlmClient
import com.trirrin.xiaoshuo.llm.LlmMessage
import com.trirrin.xiaoshuo.llm.LlmRequest
import com.trirrin.xiaoshuo.llm.LlmResponse
import com.trirrin.xiaoshuo.model.NovelOutline
import com.trirrin.xiaoshuo.prompt.OutlineInput
import com.trirrin.xiaoshuo.prompt.OutlinePrompt

class OutlineAgent(
    private val llmClient: LlmClient,
    private val model: String,
) : Agent {

    override val name = "outline"
    override val description = "Generates novel outline from concept"

    private val prompt = OutlinePrompt()

    suspend fun generate(input: OutlineInput): AgentResult {
        val request = LlmRequest(
            systemPrompt = prompt.buildSystemPrompt(),
            messages = listOf(LlmMessage(
                com.trirrin.xiaoshuo.llm.MessageRole.USER,
                prompt.buildUserPrompt(input),
            )),
            model = model,
            maxTokens = 8192,
            temperature = 0.8,
            cacheableSystemPrompt = true,
        )

        val response: LlmResponse = try {
            llmClient.complete(request)
        } catch (e: Exception) {
            return AgentResult.Error("LLM call failed: ${e.message}", e)
        }

        val baseUsage = response.toAgentUsage(name, model)
        return parseOrRepairJsonOutput(
            rawContent = response.content,
            llmClient = llmClient,
            model = model,
            agentName = name,
            parse = prompt::parseOutput,
        ).fold(
            onSuccess = { (outline, repairUsage) ->
                AgentResult.OutlineResult(outline, baseUsage.plusRepair(repairUsage))
            },
            onFailure = {
                AgentResult.Error("Failed to parse outline: ${it.message}", it)
            },
        )
    }
}
