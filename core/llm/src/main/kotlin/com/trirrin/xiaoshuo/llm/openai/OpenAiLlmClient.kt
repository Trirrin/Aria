package com.trirrin.xiaoshuo.llm.openai

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

class OpenAiLlmClient(
    private val apiKey: String,
    private val baseUrl: String = "https://api.openai.com",
    private val client: OkHttpClient = OkHttpClient(),
) : LlmClient {

    override val provider = LlmProvider.OPENAI

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

                    val chunk = parseStreamObject(data)
                    val delta = chunk["choices"]?.jsonArray
                        ?.firstOrNull()?.jsonObject
                        ?.get("delta")?.jsonObject
                        ?.get("content")?.jsonPrimitive?.contentOrNull ?: ""

                    if (delta.isNotEmpty()) {
                        emit(LlmChunk(delta = delta))
                    }

                    if (inputTokens == null) {
                        inputTokens = chunk["usage"]?.jsonObject
                            ?.get("prompt_tokens")?.jsonPrimitive?.intOrNull
                    }
                    outputTokens = chunk["usage"]?.jsonObject
                        ?.get("completion_tokens")?.jsonPrimitive?.intOrNull ?: outputTokens
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
            throw LlmError.NetworkError(IllegalStateException("Malformed OpenAI stream event", error))
        }
    }

    private fun buildHttpRequest(request: LlmRequest, stream: Boolean): Request {
        val messagesArray = buildJsonArray {
            add(buildJsonObject {
                put("role", "system")
                put("content", request.systemPrompt)
            })
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
            put("messages", messagesArray)
            put("temperature", request.temperature)
            if (request.stopSequences.isNotEmpty()) {
                put("stop", buildJsonArray {
                    request.stopSequences.forEach { add(it) }
                })
            }
            if (stream) put("stream", true)
        }

        return Request.Builder()
            .url("$baseUrl/v1/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
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
        val choice = jsonBody["choices"]?.jsonArray?.firstOrNull()?.jsonObject
        val content = choice?.get("message")?.jsonObject
            ?.get("content")?.jsonPrimitive?.content ?: ""

        val usage = jsonBody["usage"]?.jsonObject
        val inputTokens = usage?.get("prompt_tokens")?.jsonPrimitive?.intOrNull ?: 0
        val outputTokens = usage?.get("completion_tokens")?.jsonPrimitive?.intOrNull ?: 0
        val model = jsonBody["model"]?.jsonPrimitive?.contentOrNull ?: requestModel

        return LlmResponse(
            content = content,
            inputTokens = inputTokens,
            outputTokens = outputTokens,
            finishReason = choice?.get("finish_reason")?.jsonPrimitive?.contentOrNull,
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
        return "context_length_exceeded" in lower ||
            ("context" in lower && ("long" in lower || "length" in lower || "exceed" in lower))
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
