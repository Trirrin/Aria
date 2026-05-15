package com.trirrin.xiaoshuo.llm

import com.trirrin.xiaoshuo.llm.anthropic.AnthropicLlmClient
import com.trirrin.xiaoshuo.llm.openai.OpenAiLlmClient
import kotlinx.coroutines.flow.Flow

/**
 * Factory for provider-specific clients.
 *
 * Requests normally carry an explicit model because agents can use different
 * models. The configured default is only a fallback for callers that pass a
 * blank model.
 */
data class LlmClientConfig(
    val provider: LlmProvider,
    val apiKey: String,
    val baseUrl: String? = null,
    val defaultModel: String,
)

class LlmClientFactory {
    fun create(config: LlmClientConfig): LlmClient {
        val client = when (config.provider) {
            LlmProvider.ANTHROPIC -> AnthropicLlmClient(
                apiKey = config.apiKey,
                baseUrl = config.baseUrl ?: "https://api.anthropic.com",
            )
            LlmProvider.OPENAI -> OpenAiLlmClient(
                apiKey = config.apiKey,
                baseUrl = config.baseUrl ?: "https://api.openai.com",
            )
        }

        return DefaultModelLlmClient(client, config.defaultModel)
    }
}

private class DefaultModelLlmClient(
    private val delegate: LlmClient,
    private val defaultModel: String,
) : LlmClient {
    override val provider: LlmProvider = delegate.provider

    override suspend fun complete(request: LlmRequest): LlmResponse {
        return delegate.complete(request.withDefaultModel())
    }

    override fun stream(request: LlmRequest): Flow<LlmChunk> {
        return delegate.stream(request.withDefaultModel())
    }

    private fun LlmRequest.withDefaultModel(): LlmRequest {
        if (model.isNotBlank()) return this
        require(defaultModel.isNotBlank()) { "defaultModel must not be blank" }
        return copy(model = defaultModel)
    }
}
