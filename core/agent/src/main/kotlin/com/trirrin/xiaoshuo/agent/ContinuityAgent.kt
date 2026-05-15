package com.trirrin.xiaoshuo.agent

import com.trirrin.xiaoshuo.llm.*
import com.trirrin.xiaoshuo.model.NovelBible
import com.trirrin.xiaoshuo.prompt.ContinuityInput
import com.trirrin.xiaoshuo.prompt.ContinuityPrompt

class ContinuityAgent(
    private val llmClient: LlmClient,
    private val model: String,
) : Agent {

    override val name = "continuity"
    override val description = "Extracts facts from scene text to update the Novel Bible"

    private val prompt = ContinuityPrompt()

    suspend fun extract(
        sceneText: String,
        existingBible: NovelBible,
        chapterId: String,
        sceneId: String,
    ): AgentResult {
        val input = ContinuityInput(
            sceneText = sceneText,
            existingBible = existingBible,
            chapterId = chapterId,
            sceneId = sceneId,
        )

        val request = LlmRequest(
            systemPrompt = prompt.buildSystemPrompt(),
            messages = listOf(LlmMessage(MessageRole.USER, prompt.buildUserPrompt(input))),
            model = model,
            maxTokens = 4096,
            temperature = 0.2,
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
            onSuccess = { (diff, repairUsage) ->
                AgentResult.ContinuityResult(diff, baseUsage.plusRepair(repairUsage))
            },
            onFailure = {
                AgentResult.Error("Failed to parse continuity output: ${it.message}", it)
            },
        )
    }
}
