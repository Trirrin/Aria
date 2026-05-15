package com.trirrin.xiaoshuo.llm

import kotlinx.coroutines.flow.Flow

interface LlmClient {
    val provider: LlmProvider

    suspend fun complete(request: LlmRequest): LlmResponse

    fun stream(request: LlmRequest): Flow<LlmChunk>
}

enum class LlmProvider { ANTHROPIC, OPENAI }

data class LlmRequest(
    val systemPrompt: String,
    val messages: List<LlmMessage>,
    val model: String,
    val maxTokens: Int = 4096,
    val temperature: Double = 0.7,
    val stopSequences: List<String> = emptyList(),
    val cacheableSystemPrompt: Boolean = false,
)

data class LlmMessage(
    val role: MessageRole,
    val content: String,
)

enum class MessageRole { SYSTEM, USER, ASSISTANT }

data class LlmResponse(
    val content: String,
    val inputTokens: Int,
    val outputTokens: Int,
    val finishReason: String?,
    val model: String,
)

data class LlmChunk(
    val delta: String,
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    val isComplete: Boolean = false,
)

sealed class LlmError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class RateLimited(val retryAfter: Long? = null) : LlmError("Rate limited")
    class AuthFailed(message: String) : LlmError(message)
    class ContextTooLong(
        val tokenCount: Int? = null,
        val maxTokens: Int? = null,
        message: String = "Context window exceeded",
    ) : LlmError(message)
    class ApiError(val statusCode: Int, message: String) : LlmError(message)
    class NetworkError(cause: Throwable) : LlmError(cause.message ?: "Network error", cause)
}
