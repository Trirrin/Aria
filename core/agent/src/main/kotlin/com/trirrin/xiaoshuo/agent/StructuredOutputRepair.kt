package com.trirrin.xiaoshuo.agent

import com.trirrin.xiaoshuo.llm.LlmClient
import com.trirrin.xiaoshuo.llm.LlmMessage
import com.trirrin.xiaoshuo.llm.LlmRequest
import com.trirrin.xiaoshuo.llm.LlmResponse
import com.trirrin.xiaoshuo.llm.MessageRole

internal suspend fun <T> parseOrRepairJsonOutput(
    rawContent: String,
    llmClient: LlmClient,
    model: String,
    agentName: String,
    parse: (String) -> Result<T>,
): Result<Pair<T, AgentUsage?>> {
    parse(rawContent).getOrNull()?.let { parsed ->
        return Result.success(parsed to null)
    }

    val repairRequest = LlmRequest(
        systemPrompt = "Repair malformed JSON output. Return only one valid JSON object. Do not add markdown or commentary.",
        messages = listOf(
            LlmMessage(
                MessageRole.USER,
                buildString {
                    appendLine("The previous model output could not be parsed as the required JSON object.")
                    appendLine("Repair syntax only. Preserve the same fields and content.")
                    appendLine()
                    appendLine("BROKEN OUTPUT:")
                    appendLine(rawContent)
                },
            ),
        ),
        model = model,
        maxTokens = 8192,
        temperature = 0.0,
    )

    val repairResponse: LlmResponse = try {
        llmClient.complete(repairRequest)
    } catch (error: Exception) {
        return Result.failure(error)
    }

    return parse(repairResponse.content).map { parsed ->
        parsed to repairResponse.toAgentUsage(agentName, model)
    }
}

internal fun AgentUsage.plusRepair(repairUsage: AgentUsage?): AgentUsage {
    if (repairUsage == null) return this
    return AgentUsage(
        agentName = agentName,
        model = model,
        inputTokens = inputTokens + repairUsage.inputTokens,
        outputTokens = outputTokens + repairUsage.outputTokens,
    )
}
