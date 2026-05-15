package com.trirrin.xiaoshuo.agent

import com.trirrin.xiaoshuo.llm.LlmClient
import com.trirrin.xiaoshuo.llm.LlmMessage
import com.trirrin.xiaoshuo.llm.LlmRequest
import com.trirrin.xiaoshuo.llm.LlmResponse
import com.trirrin.xiaoshuo.llm.MessageRole
import com.trirrin.xiaoshuo.prompt.RollingSummaryInput
import com.trirrin.xiaoshuo.prompt.RollingSummaryPrompt

class RollingSummaryAgent(
    private val llmClient: LlmClient,
    private val model: String,
) : Agent {
    override val name = "rolling_chapter_summary"
    override val description = "Updates compact chapter tension summary"

    private val prompt = RollingSummaryPrompt()

    suspend fun update(
        previousSummary: String?,
        sceneSynopsis: String,
        sceneText: String,
    ): AgentResult {
        val input = RollingSummaryInput(
            previousSummary = previousSummary,
            sceneSynopsis = sceneSynopsis,
            sceneText = sceneText,
        )
        val request = LlmRequest(
            systemPrompt = prompt.buildSystemPrompt(),
            messages = listOf(LlmMessage(MessageRole.USER, prompt.buildUserPrompt(input))),
            model = model,
            maxTokens = 512,
            temperature = 0.2,
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
            onSuccess = { (summary, repairUsage) ->
                AgentResult.RollingSummaryResult(summary.summary, baseUsage.plusRepair(repairUsage))
            },
            onFailure = { AgentResult.Error("Failed to parse rolling summary: ${it.message}", it) },
        )
    }
}
