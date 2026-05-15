package com.trirrin.xiaoshuo.llm

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

class RealProviderSmokeTest {
    @Test
    fun `local real provider complete smoke test`() = runTest {
        assumeTrue(System.getenv("XIAOSHUO_REAL_PROVIDER_SMOKE") == "true")
        val provider = System.getenv("XIAOSHUO_LLM_PROVIDER").orEmpty().uppercase()
        val apiKey = System.getenv("XIAOSHUO_LLM_API_KEY").orEmpty()
        val model = System.getenv("XIAOSHUO_LLM_MODEL").orEmpty()
        val baseUrl = System.getenv("XIAOSHUO_LLM_BASE_URL").orEmpty().ifBlank { null }

        assumeTrue(provider in setOf("ANTHROPIC", "OPENAI"))
        assumeFalse(apiKey.isBlank())
        assumeFalse(model.isBlank())

        val client = LlmClientFactory().create(
            LlmClientConfig(
                provider = LlmProvider.valueOf(provider),
                apiKey = apiKey,
                baseUrl = baseUrl,
                defaultModel = model,
            ),
        )

        val response = client.complete(
            LlmRequest(
                systemPrompt = "You are a smoke test. Reply with exactly: ok",
                messages = listOf(LlmMessage(MessageRole.USER, "Return ok.")),
                model = model,
                maxTokens = 8,
                temperature = 0.0,
            ),
        )

        check(response.content.isNotBlank())
    }
}
