package com.trirrin.xiaoshuo.agent

import com.trirrin.xiaoshuo.llm.*
import com.trirrin.xiaoshuo.model.NovelBible
import com.trirrin.xiaoshuo.prompt.BibleDiff
import com.trirrin.xiaoshuo.prompt.ContinuityInput
import com.trirrin.xiaoshuo.prompt.ContinuityPrompt
import kotlinx.serialization.json.Json

class ContinuityAgent(
    private val llmClient: LlmClient,
    private val model: String,
) : Agent {

    override val name = "continuity"
    override val description = "Extracts facts from scene text to update the Novel Bible"

    private val prompt = ContinuityPrompt()
    private val json = Json { ignoreUnknownKeys = true }

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
            cacheableSystemPrompt = true,
            tools = listOf(bibleUpdateProposalTool),
            toolChoice = LlmToolChoice.Named(SUBMIT_BIBLE_UPDATE_PROPOSAL),
        )

        val response: LlmResponse = try {
            llmClient.complete(request)
        } catch (e: Exception) {
            return AgentResult.Error("LLM call failed: ${e.message}", e)
        }

        val baseUsage = response.toAgentUsage(name, model)
        return response.requireSingleToolCallArguments(SUBMIT_BIBLE_UPDATE_PROPOSAL, name).fold(
            onSuccess = { argumentsJson ->
                try {
                    AgentResult.ContinuityResult(
                        bibleDiff = json.decodeFromString(BibleDiff.serializer(), argumentsJson),
                        usage = baseUsage,
                    )
                } catch (error: Exception) {
                    AgentResult.Error("Failed to decode Bible update proposal arguments: ${error.message}", error)
                }
            },
            onFailure = { AgentResult.Error(it.message ?: "Invalid Bible update proposal tool call", it) },
        )
    }
}
