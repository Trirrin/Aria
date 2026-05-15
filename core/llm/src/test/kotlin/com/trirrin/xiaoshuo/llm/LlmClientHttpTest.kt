package com.trirrin.xiaoshuo.llm

import com.trirrin.xiaoshuo.llm.anthropic.AnthropicLlmClient
import com.trirrin.xiaoshuo.llm.openai.OpenAiLlmClient
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test

class LlmClientHttpTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `anthropic complete sends messages api request and parses response`() = runTest {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "content": [{"type": "text", "text": "hello"}],
                      "usage": {"input_tokens": 3, "output_tokens": 2},
                      "model": "claude-test",
                      "stop_reason": "end_turn"
                    }
                    """.trimIndent(),
                ),
        )

        try {
            val client = AnthropicLlmClient(
                apiKey = "anthropic-key",
                baseUrl = server.url("/").toString().trimEnd('/'),
            )
            val response = client.complete(sampleRequest(model = "claude-test"))
            val recorded = server.takeRequest()
            val body = json.parseToJsonElement(recorded.body.readUtf8()).jsonObject

            assertEquals("/v1/messages", recorded.path)
            assertEquals("anthropic-key", recorded.getHeader("x-api-key"))
            assertEquals("system prompt", body["system"]?.jsonPrimitive?.content)
            assertEquals("claude-test", body["model"]?.jsonPrimitive?.content)
            assertEquals("user", body["messages"]?.jsonArray?.single()?.jsonObject?.get("role")?.jsonPrimitive?.content)
            assertEquals("user prompt", body["messages"]?.jsonArray?.single()?.jsonObject?.get("content")?.jsonPrimitive?.content)
            assertEquals("hello", response.content)
            assertEquals(3, response.inputTokens)
            assertEquals(2, response.outputTokens)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `openai complete sends chat completions request and parses response`() = runTest {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "choices": [{"message": {"content": "hello"}, "finish_reason": "stop"}],
                      "usage": {"prompt_tokens": 4, "completion_tokens": 2},
                      "model": "gpt-test"
                    }
                    """.trimIndent(),
                ),
        )

        try {
            val client = OpenAiLlmClient(
                apiKey = "openai-key",
                baseUrl = server.url("/").toString().trimEnd('/'),
            )
            val response = client.complete(sampleRequest(model = "gpt-test"))
            val recorded = server.takeRequest()
            val body = json.parseToJsonElement(recorded.body.readUtf8()).jsonObject
            val messages = body["messages"]?.jsonArray.orEmpty()

            assertEquals("/v1/chat/completions", recorded.path)
            assertEquals("Bearer openai-key", recorded.getHeader("Authorization"))
            assertEquals("gpt-test", body["model"]?.jsonPrimitive?.content)
            assertEquals("system", messages[0].jsonObject["role"]?.jsonPrimitive?.content)
            assertEquals("system prompt", messages[0].jsonObject["content"]?.jsonPrimitive?.content)
            assertEquals("user", messages[1].jsonObject["role"]?.jsonPrimitive?.content)
            assertEquals("user prompt", messages[1].jsonObject["content"]?.jsonPrimitive?.content)
            assertEquals("hello", response.content)
            assertEquals(4, response.inputTokens)
            assertEquals(2, response.outputTokens)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `openai context length error maps to context too long`() = runTest {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(400)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "error": {
                        "type": "context_length_exceeded",
                        "message": "This model's maximum context length was exceeded."
                      }
                    }
                    """.trimIndent(),
                ),
        )

        try {
            val client = OpenAiLlmClient(
                apiKey = "openai-key",
                baseUrl = server.url("/").toString().trimEnd('/'),
            )

            try {
                client.complete(sampleRequest(model = "gpt-test"))
                fail("Expected ContextTooLong")
            } catch (error: LlmError.ContextTooLong) {
                assertTrue(error.message.orEmpty().contains("context length"))
            }
        } finally {
            server.shutdown()
        }
    }

    private fun sampleRequest(model: String): LlmRequest {
        return LlmRequest(
            systemPrompt = "system prompt",
            messages = listOf(LlmMessage(MessageRole.USER, "user prompt")),
            model = model,
            maxTokens = 32,
            temperature = 0.2,
        )
    }
}
