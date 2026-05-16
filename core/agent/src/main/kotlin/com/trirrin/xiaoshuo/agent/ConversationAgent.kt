package com.trirrin.xiaoshuo.agent

import com.trirrin.xiaoshuo.llm.LlmClient
import com.trirrin.xiaoshuo.llm.LlmMessage
import com.trirrin.xiaoshuo.llm.LlmRequest
import com.trirrin.xiaoshuo.llm.LlmResponse
import com.trirrin.xiaoshuo.llm.LlmTool
import com.trirrin.xiaoshuo.llm.LlmToolChoice
import com.trirrin.xiaoshuo.llm.MessageRole
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class ConversationAgent(
    private val llmClient: LlmClient,
    private val model: String,
) : Agent {
    override val name = "conversation_orchestrator"
    override val description = "Routes natural language requests to safe workflow tools"

    suspend fun plan(input: ConversationPlanInput): AgentResult {
        val response: LlmResponse = try {
            llmClient.complete(
                LlmRequest(
                    systemPrompt = systemPrompt,
                    messages = listOf(
                        LlmMessage(
                            role = MessageRole.USER,
                            content = input.toPrompt(),
                        ),
                    ),
                    model = model,
                    maxTokens = 1024,
                    temperature = 0.0,
                    tools = workflowTools,
                    toolChoice = LlmToolChoice.Required,
                    cacheableSystemPrompt = true,
                ),
            )
        } catch (error: Exception) {
            return AgentResult.Error("Conversation agent failed: ${error.message}", error)
        }

        val toolCalls = response.toolCalls
        if (toolCalls.size != 1) {
            return AgentResult.Error("Conversation agent must return exactly one tool call; got ${toolCalls.size}")
        }

        val toolCall = toolCalls.single()
        if (workflowTools.none { it.name == toolCall.name }) {
            return AgentResult.Error("Conversation agent returned unknown tool call: ${toolCall.name}")
        }

        return AgentResult.ConversationToolResult(
            toolCall = WorkflowToolCall(
                id = toolCall.id,
                name = toolCall.name,
                argumentsJson = toolCall.argumentsJson,
            ),
            usage = response.toAgentUsage(name, model),
        )
    }

    private fun ConversationPlanInput.toPrompt(): String = buildString {
        appendLine("USER MESSAGE:")
        appendLine(userMessage.trim())
        appendLine()
        appendLine("CURRENT STATE:")
        appendLine("selectedNovelTitle: ${selectedNovelTitle ?: "none"}")
        appendLine("hasOutline: $hasOutline")
        appendLine("pendingApprovalType: ${pendingApprovalType ?: "none"}")
    }

    private val systemPrompt = """
You are the only user-facing agent for Xiao Shuo, an AI-assisted long-form novel writing workbench.

You must call exactly one available tool. You are a dispatcher; you do not write persistence, commit canon, or bypass approval.

Use createNovelBackgroundProposal when the author describes a new novel or asks to start a story.
Use generateOutlineProposal when the author asks for an outline, plot, or whole-novel structure for the selected novel.
Use generateChapterSynopsisProposal when the author asks to plan or generate a chapter.
Use generateSceneTextProposal when the author asks to draft or generate a scene.
Use acceptPendingApproval only when the author clearly accepts the pending proposal.
Use rejectPendingApproval only when the author clearly rejects or cancels the pending proposal.
Use revisePendingApproval when the author asks to change the pending proposal before accepting.
Use askClarification when the request is ambiguous or the target chapter or scene cannot be inferred.

Never invent hidden state. Never claim a proposal is saved unless a tool commits it after approval.
""".trimIndent()

    private val workflowTools = listOf(
        LlmTool(
            name = "createNovelBackgroundProposal",
            description = "Create a non-persistent background proposal from the author's natural language novel idea.",
            inputSchema = objectSchema(
                required = listOf("userRequest"),
                properties = mapOf(
                    "userRequest" to stringSchema("The author's full creative request."),
                ),
            ),
        ),
        LlmTool(
            name = "generateOutlineProposal",
            description = "Generate a non-persistent outline proposal for the selected novel.",
            inputSchema = objectSchema(),
        ),
        LlmTool(
            name = "generateChapterSynopsisProposal",
            description = "Generate a non-persistent chapter plan proposal for the selected or specified chapter.",
            inputSchema = objectSchema(
                properties = mapOf(
                    "novelId" to stringSchema("Optional selected novel id."),
                    "chapterIndex" to integerSchema("Optional one-based chapter index."),
                ),
            ),
        ),
        LlmTool(
            name = "generateSceneTextProposal",
            description = "Generate a non-persistent scene draft proposal for the selected or specified scene.",
            inputSchema = objectSchema(
                properties = mapOf(
                    "novelId" to stringSchema("Optional selected novel id."),
                    "chapterIndex" to integerSchema("Optional one-based chapter index."),
                    "sceneIndex" to integerSchema("Optional one-based scene index."),
                ),
            ),
        ),
        LlmTool(
            name = "acceptPendingApproval",
            description = "Accept the currently pending proposal and let the app commit it safely.",
            inputSchema = objectSchema(),
        ),
        LlmTool(
            name = "rejectPendingApproval",
            description = "Reject the currently pending proposal without saving anything.",
            inputSchema = objectSchema(),
        ),
        LlmTool(
            name = "revisePendingApproval",
            description = "Request a revision to the currently pending proposal before it is accepted.",
            inputSchema = objectSchema(
                required = listOf("revisionFeedback"),
                properties = mapOf(
                    "revisionFeedback" to stringSchema("Specific changes the author wants."),
                ),
            ),
        ),
        LlmTool(
            name = "askClarification",
            description = "Ask the author a short clarification question or explain that the requested workflow slice is not available yet.",
            inputSchema = objectSchema(
                required = listOf("message"),
                properties = mapOf(
                    "message" to stringSchema("Brief user-facing clarification message."),
                ),
            ),
        ),
    )
}

data class ConversationPlanInput(
    val userMessage: String,
    val selectedNovelTitle: String?,
    val hasOutline: Boolean,
    val pendingApprovalType: String?,
)

data class WorkflowToolCall(
    val id: String,
    val name: String,
    val argumentsJson: String,
)

private fun objectSchema(
    required: List<String> = emptyList(),
    properties: Map<String, kotlinx.serialization.json.JsonObject> = emptyMap(),
): kotlinx.serialization.json.JsonObject {
    return buildJsonObject {
        put("type", "object")
        put(
            "properties",
            buildJsonObject {
                properties.forEach { (name, schema) -> put(name, schema) }
            },
        )
        put(
            "required",
            buildJsonArray {
                required.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) }
            },
        )
    }
}

private fun stringSchema(description: String): kotlinx.serialization.json.JsonObject {
    return buildJsonObject {
        put("type", "string")
        put("description", description)
    }
}

private fun integerSchema(description: String): kotlinx.serialization.json.JsonObject {
    return buildJsonObject {
        put("type", "integer")
        put("description", description)
    }
}
