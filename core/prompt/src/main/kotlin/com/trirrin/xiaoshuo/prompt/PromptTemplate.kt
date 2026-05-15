package com.trirrin.xiaoshuo.prompt

interface PromptTemplate<TInput, TOutput> {
    val name: String
    val description: String

    fun buildSystemPrompt(): String
    fun buildUserPrompt(input: TInput): String
    fun parseOutput(rawOutput: String): Result<TOutput>
}
