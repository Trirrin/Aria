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
            systemPrompt = prompt.buildSystemPrompt().replace("$" + "GENRE", input.genre.label),
            messages = listOf(LlmMessage(
                com.trirrin.xiaoshuo.llm.MessageRole.USER,
                prompt.buildUserPrompt(input),
            )),
            model = model,
            maxTokens = 8192,
            temperature = 0.8,
        )

        val response: LlmResponse = try {
            llmClient.complete(request)
        } catch (e: Exception) {
            return AgentResult.Error("LLM call failed: ${e.message}", e)
        }

        return prompt.parseOutput(response.content).fold(
            onSuccess = { AgentResult.OutlineResult(it) },
            onFailure = {
                AgentResult.Error("Failed to parse outline: ${it.message}", it)
            },
        )
    }
}
