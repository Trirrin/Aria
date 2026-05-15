package com.trirrin.xiaoshuo.prompt

import kotlinx.serialization.json.Json

internal inline fun <reified T> Json.decodeObjectOutput(rawOutput: String): T {
    var lastError: Exception? = null
    for (candidate in extractJsonCandidates(rawOutput).distinct()) {
        try {
            return decodeFromString<T>(candidate)
        } catch (error: Exception) {
            lastError = error
        }
    }
    throw lastError ?: IllegalArgumentException("No JSON object found in model output")
}

internal fun extractJson(text: String): String {
    return extractJsonCandidates(text).firstOrNull() ?: text.trim()
}

@PublishedApi
internal fun extractJsonCandidates(text: String): List<String> {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return emptyList()

    val candidates = mutableListOf<String>()
    candidates += fencedBlocks(trimmed)
    if (trimmed.startsWith("{") && balancedObjectEnd(trimmed, 0) == trimmed.lastIndex) {
        candidates += trimmed
    }
    candidates += balancedObjects(trimmed)
    return candidates.map { it.trim() }.filter { it.isNotEmpty() }
}

private fun fencedBlocks(text: String): List<String> {
    val fenceRegex = Regex("```(?:json)?\\s*\\n?([\\s\\S]*?)\\n?```", RegexOption.IGNORE_CASE)
    return fenceRegex.findAll(text).map { it.groupValues[1] }.toList()
}

private fun balancedObjects(text: String): List<String> {
    val objects = mutableListOf<String>()
    for (index in text.indices) {
        if (text[index] != '{') continue
        val end = balancedObjectEnd(text, index)
        if (end >= 0) {
            objects += text.substring(index, end + 1)
        }
    }
    return objects
}

private fun balancedObjectEnd(text: String, start: Int): Int {
    var depth = 0
    var inString = false
    var escaping = false

    for (index in start until text.length) {
        val char = text[index]
        if (inString) {
            when {
                escaping -> escaping = false
                char == '\\' -> escaping = true
                char == '"' -> inString = false
            }
            continue
        }

        when (char) {
            '"' -> inString = true
            '{' -> depth += 1
            '}' -> {
                depth -= 1
                if (depth == 0) return index
                if (depth < 0) return -1
            }
        }
    }
    return -1
}
