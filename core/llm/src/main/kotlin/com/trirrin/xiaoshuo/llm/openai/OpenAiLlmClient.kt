package com.trirrin.xiaoshuo.llm.openai

import com.openai.client.OpenAIClient
import com.openai.client.okhttp.OpenAIOkHttpClient
import com.openai.errors.OpenAIIoException
import com.openai.errors.OpenAIServiceException
import com.openai.core.JsonValue
import com.openai.models.FunctionDefinition
import com.openai.models.FunctionParameters
import com.openai.models.chat.completions.ChatCompletionCreateParams
import com.openai.models.chat.completions.ChatCompletionStreamOptions
import com.openai.models.chat.completions.ChatCompletionNamedToolChoice
import com.openai.models.chat.completions.ChatCompletionToolChoiceOption
import com.trirrin.xiaoshuo.llm.LlmChunk
import com.trirrin.xiaoshuo.llm.LlmClient
import com.trirrin.xiaoshuo.llm.LlmError
import com.trirrin.xiaoshuo.llm.LlmProvider
import com.trirrin.xiaoshuo.llm.LlmRequest
import com.trirrin.xiaoshuo.llm.LlmTool
import com.trirrin.xiaoshuo.llm.LlmToolCall
import com.trirrin.xiaoshuo.llm.LlmToolCallDelta
import com.trirrin.xiaoshuo.llm.LlmToolChoice
import com.trirrin.xiaoshuo.llm.LlmResponse
import com.trirrin.xiaoshuo.llm.MessageRole
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

private fun String.toOpenAiSdkBaseUrl(): String {
    val trimmed = trimEnd('/')
    return if (trimmed.endsWith("/v1")) trimmed else "$trimmed/v1"
}

class OpenAiLlmClient(
    apiKey: String,
    baseUrl: String = "https://api.openai.com/v1",
    private val client: OpenAIClient = OpenAIOkHttpClient.builder()
        .apiKey(apiKey)
        .baseUrl(baseUrl.toOpenAiSdkBaseUrl())
        .build(),
) : LlmClient {

    override val provider = LlmProvider.OPENAI

    override suspend fun complete(request: LlmRequest): LlmResponse = withContext(Dispatchers.IO) {
        try {
            val response = client.chat().completions().create(request.toChatCompletionParams(stream = false))
            val choice = response.choices().firstOrNull()
            val usage = response.usage().orElse(null)
            val message = choice?.message()
            LlmResponse(
                content = message?.content()?.orElse("").orEmpty(),
                inputTokens = usage?.promptTokens()?.toInt() ?: 0,
                outputTokens = usage?.completionTokens()?.toInt() ?: 0,
                finishReason = choice?.finishReason()?.asString(),
                model = response.model(),
                toolCalls = message?.toolCalls()?.orElse(emptyList()).orEmpty().mapNotNull { call ->
                    if (!call.isFunction()) return@mapNotNull null
                    val function = call.asFunction()
                    LlmToolCall(
                        id = function.id(),
                        name = function.function().name(),
                        argumentsJson = function.function().arguments(),
                    )
                },
            )
        } catch (error: Exception) {
            throw error.toLlmError()
        }
    }

    override fun stream(request: LlmRequest): Flow<LlmChunk> = flow {
        try {
            var inputTokens: Int? = null
            var outputTokens: Int? = null
            client.chat().completions().createStreaming(request.toChatCompletionParams(stream = true)).use { stream ->
                val iterator = stream.stream().iterator()
                while (iterator.hasNext()) {
                    val chunk = iterator.next()
                    val choice = chunk.choices().firstOrNull()
                    val delta = choice?.delta()?.content()?.orElse("").orEmpty()
                    if (delta.isNotEmpty()) {
                        emit(LlmChunk(delta = delta))
                    }
                    choice?.delta()?.toolCalls()?.orElse(emptyList()).orEmpty().forEach { toolDelta ->
                        val function = toolDelta.function().orElse(null)
                        emit(
                            LlmChunk(
                                delta = "",
                                toolCallDelta = LlmToolCallDelta(
                                    id = toolDelta.id().orElse(null),
                                    name = function?.name()?.orElse(null),
                                    argumentsDelta = function?.arguments()?.orElse("").orEmpty(),
                                    index = toolDelta.index().toInt(),
                                ),
                            ),
                        )
                    }
                    val usage = chunk.usage().orElse(null)
                    if (usage != null) {
                        inputTokens = usage.promptTokens().toInt()
                        outputTokens = usage.completionTokens().toInt()
                    }
                }
            }
            emit(LlmChunk(delta = "", inputTokens = inputTokens, outputTokens = outputTokens, isComplete = true))
        } catch (error: Exception) {
            throw error.toLlmError()
        }
    }.flowOn(Dispatchers.IO)

    private fun LlmRequest.toChatCompletionParams(stream: Boolean): ChatCompletionCreateParams {
        val builder = ChatCompletionCreateParams.builder()
            .model(model)
            .maxCompletionTokens(maxTokens.toLong())
            .temperature(temperature)

        if (systemPrompt.isNotBlank()) {
            builder.addSystemMessage(systemPrompt)
        }
        messages.forEach { message ->
            when (message.role) {
                MessageRole.SYSTEM -> builder.addSystemMessage(message.content)
                MessageRole.USER -> builder.addUserMessage(message.content)
                MessageRole.ASSISTANT -> builder.addAssistantMessage(message.content)
            }
        }
        if (tools.isNotEmpty()) {
            tools.forEach { tool -> builder.addFunctionTool(tool.toOpenAiFunctionDefinition()) }
            builder.parallelToolCalls(false)
            when (val choice = toolChoice) {
                LlmToolChoice.Auto -> builder.toolChoice(ChatCompletionToolChoiceOption.Auto.AUTO)
                LlmToolChoice.Required -> builder.toolChoice(ChatCompletionToolChoiceOption.Auto.REQUIRED)
                LlmToolChoice.None -> builder.toolChoice(ChatCompletionToolChoiceOption.Auto.NONE)
                is LlmToolChoice.Named -> builder.toolChoice(choice.toOpenAiNamedToolChoice())
            }
        }
        if (stopSequences.isNotEmpty()) {
            builder.stopOfStrings(stopSequences)
        }
        if (stream) {
            builder.streamOptions(ChatCompletionStreamOptions.builder().includeUsage(true).build())
        }
        return builder.build()
    }

    private fun LlmTool.toOpenAiFunctionDefinition(): FunctionDefinition {
        return FunctionDefinition.builder()
            .name(name)
            .description(description)
            .parameters(
                FunctionParameters.builder()
                    .putAllAdditionalProperties(inputSchema.toOpenAiJsonValueMap())
                    .build(),
            )
            .strict(strict)
            .build()
    }

    private fun LlmToolChoice.Named.toOpenAiNamedToolChoice(): ChatCompletionNamedToolChoice {
        return ChatCompletionNamedToolChoice.builder()
            .type(JsonValue.from("function"))
            .function(
                ChatCompletionNamedToolChoice.Function.builder()
                    .name(name)
                    .build(),
            )
            .build()
    }

    private fun JsonObject.toOpenAiJsonValueMap(): Map<String, JsonValue> {
        return mapValues { (_, value) -> value.toOpenAiJsonValue() }
    }

    private fun JsonElement.toOpenAiJsonValue(): JsonValue {
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

    private fun Exception.toLlmError(): LlmError {
        return when (this) {
            is LlmError -> this
            is OpenAIServiceException -> toServiceError()
            is OpenAIIoException -> LlmError.NetworkError(this)
            else -> LlmError.NetworkError(this)
        }
    }

    private fun OpenAIServiceException.toServiceError(): LlmError {
        val message = message ?: body().toString()
        val type = type().orElse("")
        return when {
            statusCode() == 401 -> LlmError.AuthFailed(message)
            statusCode() == 429 -> LlmError.RateLimited()
            statusCode() == 400 && (type.contains("context", ignoreCase = true) || message.contains("context", ignoreCase = true)) -> {
                LlmError.ContextTooLong(message = message)
            }
            else -> LlmError.ApiError(statusCode(), message)
        }
    }
}
