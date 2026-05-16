package com.trirrin.xiaoshuo.llm.anthropic

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.core.JsonValue
import com.anthropic.models.messages.CacheControlEphemeral
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.TextBlockParam
import com.anthropic.models.messages.Tool
import com.anthropic.models.messages.ToolChoiceAny
import com.anthropic.models.messages.ToolChoiceAuto
import com.anthropic.models.messages.ToolChoiceNone
import com.anthropic.models.messages.ToolChoiceTool
import com.trirrin.xiaoshuo.llm.LlmChunk
import com.trirrin.xiaoshuo.llm.LlmClient
import com.trirrin.xiaoshuo.llm.LlmError
import com.trirrin.xiaoshuo.llm.LlmProvider
import com.trirrin.xiaoshuo.llm.LlmRequest
import com.trirrin.xiaoshuo.llm.LlmTool
import com.trirrin.xiaoshuo.llm.LlmToolCall
import com.trirrin.xiaoshuo.llm.LlmToolChoice
import com.trirrin.xiaoshuo.llm.LlmResponse
import com.trirrin.xiaoshuo.llm.MessageRole
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

class AnthropicLlmClient(
    apiKey: String,
    baseUrl: String = "https://api.anthropic.com",
    private val client: AnthropicClient = AnthropicOkHttpClient.builder()
        .apiKey(apiKey)
        .baseUrl(baseUrl)
        .build(),
) : LlmClient {

    override val provider = LlmProvider.ANTHROPIC

    override suspend fun complete(request: LlmRequest): LlmResponse = withContext(Dispatchers.IO) {
        try {
            val response = client.messages().create(request.toMessageCreateParams())
            LlmResponse(
                content = response.content().textContent(),
                inputTokens = response.usage().inputTokens().toInt(),
                outputTokens = response.usage().outputTokens().toInt(),
                finishReason = response.stopReason().orElse(null)?.asString(),
                model = response.model().asString(),
                toolCalls = response.content().toolCalls(),
            )
        } catch (error: Exception) {
            throw error.toLlmError()
        }
    }

    override fun stream(request: LlmRequest): Flow<LlmChunk> = flow {
        try {
            var inputTokens: Int? = null
            var outputTokens: Int? = null
            client.messages().createStreaming(request.toMessageCreateParams()).use { stream ->
                val iterator = stream.stream().iterator()
                while (iterator.hasNext()) {
                    val event = iterator.next()
                    when {
                        event.isMessageStart() -> {
                            val usage = event.asMessageStart().message().usage()
                            inputTokens = usage.inputTokens().toInt()
                        }
                        event.isContentBlockDelta() -> {
                            val delta = event.asContentBlockDelta().delta()
                            if (delta.isText()) {
                                val text = delta.asText().text()
                                if (text.isNotEmpty()) {
                                    emit(LlmChunk(delta = text))
                                }
                            }
                        }
                        event.isMessageDelta() -> {
                            val usage = event.asMessageDelta().usage()
                            inputTokens = usage.inputTokens().orElse(inputTokens?.toLong() ?: 0L).toInt()
                            outputTokens = usage.outputTokens().toInt()
                        }
                    }
                }
            }
            emit(LlmChunk(delta = "", inputTokens = inputTokens, outputTokens = outputTokens, isComplete = true))
        } catch (error: Exception) {
            throw error.toLlmError()
        }
    }.flowOn(Dispatchers.IO)

    private fun LlmRequest.toMessageCreateParams(): MessageCreateParams {
        val builder = MessageCreateParams.builder()
            .model(model)
            .maxTokens(maxTokens.toLong())

        when {
            systemPrompt.isBlank() -> Unit
            cacheableSystemPrompt -> builder.systemOfTextBlockParams(
                listOf(
                    TextBlockParam.builder()
                        .text(systemPrompt)
                        .cacheControl(CacheControlEphemeral.builder().build())
                        .build(),
                ),
            )
            else -> builder.system(systemPrompt)
        }

        messages.forEach { message ->
            when (message.role) {
                MessageRole.SYSTEM -> builder.addUserMessage(message.content)
                MessageRole.USER -> builder.addUserMessage(message.content)
                MessageRole.ASSISTANT -> builder.addAssistantMessage(message.content)
            }
        }
        if (tools.isNotEmpty()) {
            tools.forEach { tool -> builder.addTool(tool.toAnthropicTool()) }
            when (val choice = toolChoice) {
                LlmToolChoice.Auto -> builder.toolChoice(
                    ToolChoiceAuto.builder().type(JsonValue.from("auto")).disableParallelToolUse(true).build(),
                )
                LlmToolChoice.Required -> builder.toolChoice(
                    ToolChoiceAny.builder().type(JsonValue.from("any")).disableParallelToolUse(true).build(),
                )
                LlmToolChoice.None -> builder.toolChoice(
                    ToolChoiceNone.builder().type(JsonValue.from("none")).build(),
                )
                is LlmToolChoice.Named -> builder.toolChoice(choice.toAnthropicToolChoice())
            }
        }
        if (stopSequences.isNotEmpty()) {
            builder.stopSequences(stopSequences)
        }
        return builder.build()
    }

    private fun List<com.anthropic.models.messages.ContentBlock>.textContent(): String {
        return filter { it.isText() }
            .joinToString(separator = "") { it.asText().text() }
    }

    private fun List<com.anthropic.models.messages.ContentBlock>.toolCalls(): List<LlmToolCall> {
        return filter { it.isToolUse() }
            .map { it.asToolUse() }
            .map { toolUse ->
                LlmToolCall(
                    id = toolUse.id(),
                    name = toolUse.name(),
                    argumentsJson = Json.encodeToString(toolUse._input().convert(Any::class.java).toJsonElement()),
                )
            }
    }

    private fun LlmTool.toAnthropicTool(): Tool {
        val properties = inputSchema["properties"] as? JsonObject ?: JsonObject(emptyMap())
        val required = (inputSchema["required"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.content }
            .orEmpty()
        return Tool.builder()
            .name(name)
            .description(description)
            .inputSchema(
                Tool.InputSchema.builder()
                    .type(JsonValue.from(inputSchema["type"]?.toPlainValue() ?: "object"))
                    .properties(
                        Tool.InputSchema.Properties.builder()
                            .putAllAdditionalProperties(properties.toAnthropicJsonValueMap())
                            .build(),
                    )
                    .required(required)
                    .build(),
            )
            .strict(strict)
            .build()
    }

    private fun LlmToolChoice.Named.toAnthropicToolChoice(): ToolChoiceTool {
        return ToolChoiceTool.builder()
            .type(JsonValue.from("tool"))
            .name(name)
            .disableParallelToolUse(true)
            .build()
    }

    private fun JsonObject.toAnthropicJsonValueMap(): Map<String, JsonValue> {
        return mapValues { (_, value) -> value.toAnthropicJsonValue() }
    }

    private fun JsonElement.toAnthropicJsonValue(): JsonValue {
        return JsonValue.from(toPlainValue())
    }

    private fun JsonElement.toPlainValue(): Any? {
        return when (this) {
            JsonNull -> null
            is JsonObject -> mapValues { (_, value) -> value.toPlainValue() }
            is JsonArray -> map { it.toPlainValue() }
            is JsonPrimitive -> when {
                isString -> content
                booleanOrNull != null -> booleanOrNull
                longOrNull != null -> longOrNull
                doubleOrNull != null -> doubleOrNull
                else -> content
            }
        }
    }

    private fun Any?.toJsonElement(): JsonElement {
        return when (this) {
            null -> JsonNull
            is Map<*, *> -> JsonObject(
                entries.associate { (key, value) -> key.toString() to value.toJsonElement() },
            )
            is List<*> -> JsonArray(map { it.toJsonElement() })
            is Boolean -> JsonPrimitive(this)
            is Number -> JsonPrimitive(this)
            else -> JsonPrimitive(toString())
        }
    }

    private fun Exception.toLlmError(): LlmError {
        return when (this) {
            is LlmError -> this
            is AnthropicServiceException -> toServiceError()
            is AnthropicIoException -> LlmError.NetworkError(this)
            else -> LlmError.NetworkError(this)
        }
    }

    private fun AnthropicServiceException.toServiceError(): LlmError {
        val message = message ?: body().toString()
        return when {
            statusCode() == 401 -> LlmError.AuthFailed(message)
            statusCode() == 429 -> LlmError.RateLimited()
            statusCode() == 400 && message.contains("too long", ignoreCase = true) -> LlmError.ContextTooLong(message = message)
            statusCode() == 400 && message.contains("context", ignoreCase = true) -> LlmError.ContextTooLong(message = message)
            else -> LlmError.ApiError(statusCode(), message)
        }
    }
}
