package com.trirrin.xiaoshuo.agent

object NameExtractor {
    private val phraseRegex = Regex("""\b\p{Lu}[\p{L}\p{N}'-]*(?:\s+\p{Lu}[\p{L}\p{N}'-]*)*\b""")
    private val initialsRegex = Regex("""\b[A-Z]{2,}\b""")
    private val ignoredWords = setOf(
        "A", "An", "And", "As", "At", "But", "By", "For", "From", "If", "In", "Into", "It",
        "Of", "On", "Or", "The", "Then", "This", "To", "When", "While", "With",
    )

    fun extract(text: String?): List<String> {
        if (text.isNullOrBlank()) return emptyList()
        return (phraseRegex.findAll(text).map { it.value } + initialsRegex.findAll(text).map { it.value })
            .map { it.trim() }
            .filter { it.length > 1 && it !in ignoredWords }
            .distinctBy { it.normalizedName() }
            .toList()
    }
}

internal fun String.normalizedName(): String {
    return trim().lowercase().replace(Regex("\\s+"), " ")
}
