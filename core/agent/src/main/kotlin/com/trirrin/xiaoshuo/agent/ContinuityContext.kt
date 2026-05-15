package com.trirrin.xiaoshuo.agent

private const val DEFAULT_CONTINUITY_CONTEXT_WORDS = 200

fun trimContinuityContext(text: String?, maxWords: Int = DEFAULT_CONTINUITY_CONTEXT_WORDS): String? {
    val words = text.orEmpty()
        .trim()
        .split(Regex("\\s+"))
        .filter { it.isNotBlank() }
    if (words.isEmpty()) return null
    return words.takeLast(maxWords.coerceAtLeast(1)).joinToString(" ")
}
