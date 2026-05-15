package com.trirrin.xiaoshuo.llm.anthropic

import com.trirrin.xiaoshuo.llm.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

class AnthropicLlmClient(
    private val apiKey: String,
    private val baseUrl: String = "https://api.anthropic.com",
    private val client: OkHttpClient = OkHttpClient(),
) : LlmClient {

    override val provider = LlmProvider.ANTHROPIC

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun complete(request: LlmRequest): LlmResponse {
        val httpRequest = buildHttpRequest(request, stream = false)
        val response = client.newCall(httpRequest).execute()
        return handleResponse(response, request.model)
    }

    override fun stream(request: LlmRequest): Flow<LlmChunk> = flow {
        val httpRequest = buildHttpRequest(request, stream = true)
        val response = client.newCall(httpRequest).execute()
        if (!response.isSuccessful) {
            handleResponse(response, request.model)
            return@flow
        }
        val reader = response.body?.byteStream()?.bufferedReader()
            ?: throw LlmError.NetworkError(Exception("Empty response body"))

        try {
            var inputTokens: Int? = null
            var outputTokens: Int? = null
            var completed = false
            var line = reader.readLine()

            while (line != null) {
                if (line.startsWith("data: ")) {
                    val data = line.removePrefix("data: ").trim()
                    if (data == "[DONE]") {
                        completed = true
                        break
                    }

                    val event = parseStreamObject(data)
                    val type = event["type"]?.jsonPrimitive?.content

                    when (type) {
                        "content_block_delta" -> {
                            val delta = event["delta"]?.jsonObject
                                ?.get("text")?.jsonPrimitive?.content ?: ""
                            if (delta.isNotEmpty()) {
                                emit(LlmChunk(delta = delta))
                            }
                        }
                        "message_start" -> {
                            inputTokens = event["message"]?.jsonObject
                                ?.get("usage")?.jsonObject
                                ?.get("input_tokens")?.jsonPrimitive?.intOrNull
                        }
                        "message_delta" -> {
                            outputTokens = event["usage"]?.jsonObject
                                ?.get("output_tokens")?.jsonPrimitive?.intOrNull
                            emit(LlmChunk(
                                delta = "",
                                inputTokens = inputTokens,
                                outputTokens = outputTokens,
                            ))
                        }
                    }
                }
                line = reader.readLine()
            }

            if (!completed || inputTokens != null || outputTokens != null) {
                emit(LlmChunk(
                    delta = "",
                    inputTokens = inputTokens,
                    outputTokens = outputTokens,
                    isComplete = true,
                ))
            } else {
                emit(LlmChunk(delta = "", isComplete = true))
            }
        } finally {
            reader.close()
            response.close()
        }
    }.flowOn(Dispatchers.IO)

    private fun parseStreamObject(data: String): JsonObject {
        return try {
            json.parseToJsonElement(data).jsonObject
        } catch (error: Exception) {
            throw LlmError.NetworkError(IllegalStateException("Malformed Anthropic stream event", error))
        }
    }

    private fun buildHttpRequest(request: LlmRequest, stream: Boolean): Request {
        val messagesArray = buildJsonArray {
            for (msg in request.messages) {
                add(buildJsonObject {
                    put("role", msg.role.name.lowercase())
                    put("content", msg.content)
                })
            }
        }

        val body = buildJsonObject {
            put("model", request.model)
            put("max_tokens", request.maxTokens)
            put("system", request.systemPrompt)
            put("messages", messagesArray)
            put("temperature", request.temperature)
            if (request.stopSequences.isNotEmpty()) {
                put("stop_sequences", buildJsonArray {
                    request.stopSequences.forEach { add(it) }
                })
            }
            if (stream) put("stream", true)
        }

        return Request.Builder()
            .url("$baseUrl/v1/messages")
            .addHeader("x-api-key", apiKey)
            .addHeader("anthropic-version", "2023-06-01")
            .addHeader("content-type", "application/json")
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
    }

    private fun handleResponse(response: Response, requestModel: String): LlmResponse {
        val body = response.body?.string()
            ?: throw LlmError.NetworkError(Exception("Empty response body"))

        when (response.code) {
            401 -> throw LlmError.AuthFailed("Invalid API key")
            429 -> {
                val retryAfter = response.header("retry-after")?.toLongOrNull()
                throw LlmError.RateLimited(retryAfter)
            }
        }

        if (!response.isSuccessful) {
            val (type, message) = parseError(body)
            if (type == "context_length_exceeded" || message.isContextTooLong()) {
                throw LlmError.ContextTooLong(message = message)
            }
            throw LlmError.ApiError(response.code, message)
        }

        val jsonBody = json.parseToJsonElement(body).jsonObject
        val content = jsonBody["content"]?.jsonArray
            ?.filterIsInstance<JsonObject>()
            ?.joinToString("") { it["text"]?.jsonPrimitive?.content ?: "" }
            ?: ""

        val usage = jsonBody["usage"]?.jsonObject
        val inputTokens = usage?.get("input_tokens")?.jsonPrimitive?.intOrNull ?: 0
        val outputTokens = usage?.get("output_tokens")?.jsonPrimitive?.intOrNull ?: 0
        val model = jsonBody["model"]?.jsonPrimitive?.contentOrNull ?: requestModel

        return LlmResponse(
            content = content,
            inputTokens = inputTokens,
            outputTokens = outputTokens,
            finishReason = jsonBody["stop_reason"]?.jsonPrimitive?.contentOrNull,
            model = model,
        )
    }

    private fun parseError(body: String): Pair<String?, String> {
        return try {
            val error = json.parseToJsonElement(body).jsonObject["error"]?.jsonObject
            val type = error?.get("type")?.jsonPrimitive?.contentOrNull
            val message = error?.get("message")?.jsonPrimitive?.contentOrNull ?: body
            type to message
        } catch (_: Exception) {
            null to body
        }
    }

    private fun String.isContextTooLong(): Boolean {
        val lower = lowercase()
        return ("context" in lower && ("long" in lower || "length" in lower || "exceed" in lower)) ||
            "prompt is too long" in lower
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
