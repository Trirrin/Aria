package com.trirrin.xiaoshuo.llm

import com.trirrin.xiaoshuo.llm.anthropic.AnthropicLlmClient
import com.trirrin.xiaoshuo.llm.openai.OpenAiLlmClient
import kotlinx.coroutines.flow.toList
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
    fun `anthropic complete sends cacheable system prompt blocks when enabled`() = runTest {
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
            client.complete(sampleRequest(model = "claude-test", cacheableSystemPrompt = true))
            val recorded = server.takeRequest()
            val body = json.parseToJsonElement(recorded.body.readUtf8()).jsonObject
            val systemBlock = body["system"]?.jsonArray?.single()?.jsonObject

            assertEquals("text", systemBlock?.get("type")?.jsonPrimitive?.content)
            assertEquals("system prompt", systemBlock?.get("text")?.jsonPrimitive?.content)
            assertEquals("ephemeral", systemBlock?.get("cache_control")?.jsonObject?.get("type")?.jsonPrimitive?.content)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `anthropic stream sends cacheable system prompt blocks when enabled`() = runTest {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    """
                    event: message_start
                    data: {"type":"message_start","message":{"usage":{"input_tokens":7}}}

                    event: message_stop
                    data: {"type":"message_stop"}

                    """.trimIndent(),
                ),
        )

        try {
            val client = AnthropicLlmClient(
                apiKey = "anthropic-key",
                baseUrl = server.url("/").toString().trimEnd('/'),
            )
            client.stream(sampleRequest(model = "claude-test", cacheableSystemPrompt = true)).toList()
            val recorded = server.takeRequest()
            val body = json.parseToJsonElement(recorded.body.readUtf8()).jsonObject
            val systemBlock = body["system"]?.jsonArray?.single()?.jsonObject

            assertEquals("ephemeral", systemBlock?.get("cache_control")?.jsonObject?.get("type")?.jsonPrimitive?.content)
            assertEquals("true", body["stream"]?.jsonPrimitive?.content)
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
    fun `openai stream sends streaming request and emits one completion chunk`() = runTest {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    """
                    data: {"choices":[{"delta":{"content":"hel"}}]}

                    data: {"choices":[{"delta":{"content":"lo"}}],"usage":{"prompt_tokens":5,"completion_tokens":2}}

                    data: [DONE]

                    """.trimIndent(),
                ),
        )

        try {
            val client = OpenAiLlmClient(
                apiKey = "openai-key",
                baseUrl = server.url("/").toString().trimEnd('/'),
            )
            val chunks = client.stream(sampleRequest(model = "gpt-test")).toList()
            val recorded = server.takeRequest()
            val body = json.parseToJsonElement(recorded.body.readUtf8()).jsonObject

            assertEquals("/v1/chat/completions", recorded.path)
            assertEquals("Bearer openai-key", recorded.getHeader("Authorization"))
            assertEquals("true", body["stream"]?.jsonPrimitive?.content)
            assertEquals(listOf("hel", "lo"), chunks.filter { it.delta.isNotEmpty() }.map { it.delta })
            assertEquals(1, chunks.count { it.isComplete })
            assertEquals(5, chunks.last().inputTokens)
            assertEquals(2, chunks.last().outputTokens)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `anthropic stream sends streaming request and parses message events`() = runTest {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    """
                    event: message_start
                    data: {"type":"message_start","message":{"usage":{"input_tokens":7}}}

                    event: content_block_delta
                    data: {"type":"content_block_delta","delta":{"type":"text_delta","text":"hel"}}

                    event: content_block_delta
                    data: {"type":"content_block_delta","delta":{"type":"text_delta","text":"lo"}}

                    event: message_delta
                    data: {"type":"message_delta","usage":{"output_tokens":2}}

                    event: message_stop
                    data: {"type":"message_stop"}

                    """.trimIndent(),
                ),
        )

        try {
            val client = AnthropicLlmClient(
                apiKey = "anthropic-key",
                baseUrl = server.url("/").toString().trimEnd('/'),
            )
            val chunks = client.stream(sampleRequest(model = "claude-test")).toList()
            val recorded = server.takeRequest()
            val body = json.parseToJsonElement(recorded.body.readUtf8()).jsonObject

            assertEquals("/v1/messages", recorded.path)
            assertEquals("anthropic-key", recorded.getHeader("x-api-key"))
            assertEquals("true", body["stream"]?.jsonPrimitive?.content)
            assertEquals(listOf("hel", "lo"), chunks.filter { it.delta.isNotEmpty() }.map { it.delta })
            val usageChunk = chunks.first { it.inputTokens != null || it.outputTokens != null }
            assertEquals(7, usageChunk.inputTokens)
            assertEquals(2, usageChunk.outputTokens)
            assertEquals(1, chunks.count { it.isComplete })
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `openai malformed stream event maps to network error`() = runTest {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: {not-json}\n\n"),
        )

        try {
            val client = OpenAiLlmClient(
                apiKey = "openai-key",
                baseUrl = server.url("/").toString().trimEnd('/'),
            )

            try {
                client.stream(sampleRequest(model = "gpt-test")).toList()
                fail("Expected NetworkError")
            } catch (error: LlmError.NetworkError) {
                assertTrue(error.message.orEmpty().contains("Malformed OpenAI stream event"))
            }
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `anthropic malformed stream event maps to network error`() = runTest {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: {not-json}\n\n"),
        )

        try {
            val client = AnthropicLlmClient(
                apiKey = "anthropic-key",
                baseUrl = server.url("/").toString().trimEnd('/'),
            )

            try {
                client.stream(sampleRequest(model = "claude-test")).toList()
                fail("Expected NetworkError")
            } catch (error: LlmError.NetworkError) {
                assertTrue(error.message.orEmpty().contains("Malformed Anthropic stream event"))
            }
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `factory uses default model only when request model is blank`() = runTest {
        val server = MockWebServer()
        repeat(2) {
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(
                        """
                        {
                          "choices": [{"message": {"content": "hello"}, "finish_reason": "stop"}],
                          "usage": {"prompt_tokens": 1, "completion_tokens": 1}
                        }
                        """.trimIndent(),
                    ),
            )
        }

        try {
            val client = LlmClientFactory().create(
                LlmClientConfig(
                    provider = LlmProvider.OPENAI,
                    apiKey = "openai-key",
                    baseUrl = server.url("/").toString().trimEnd('/'),
                    defaultModel = "fallback-model",
                ),
            )

            client.complete(sampleRequest(model = ""))
            val fallbackBody = json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
            assertEquals("fallback-model", fallbackBody["model"]?.jsonPrimitive?.content)

            client.complete(sampleRequest(model = "explicit-model"))
            val explicitBody = json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
            assertEquals("explicit-model", explicitBody["model"]?.jsonPrimitive?.content)
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

    @Test
    fun `anthropic prompt length error maps to context too long`() = runTest {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(400)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "error": {
                        "type": "invalid_request_error",
                        "message": "prompt is too long for this model"
                      }
                    }
                    """.trimIndent(),
                ),
        )

        try {
            val client = AnthropicLlmClient(
                apiKey = "anthropic-key",
                baseUrl = server.url("/").toString().trimEnd('/'),
            )

            try {
                client.complete(sampleRequest(model = "claude-test"))
                fail("Expected ContextTooLong")
            } catch (error: LlmError.ContextTooLong) {
                assertTrue(error.message.orEmpty().contains("prompt is too long"))
            }
        } finally {
            server.shutdown()
        }
    }

    private fun sampleRequest(model: String, cacheableSystemPrompt: Boolean = false): LlmRequest {
        return LlmRequest(
            systemPrompt = "system prompt",
            messages = listOf(LlmMessage(MessageRole.USER, "user prompt")),
            model = model,
            maxTokens = 32,
            temperature = 0.2,
            cacheableSystemPrompt = cacheableSystemPrompt,
        )
    }
}
