package com.trirrin.xiaoshuo.prompt

import com.trirrin.xiaoshuo.model.*

data class SceneExpansionInput(
    val sceneBreakdown: SceneBreakdown,
    val chapterGoal: String,
    val relevantCharacters: List<CharacterEntry>,
    val relevantLocations: List<LocationEntry>,
    val relevantWorldRules: List<WorldRule>,
    val previousSceneEnding: String?,
    val styleGuide: StyleGuide,
    val rollingChapterSummary: String? = null,
    val referenceProseStyle: String? = null,
)

class SceneExpansionPrompt : PromptTemplate<SceneExpansionInput, String> {

    override val name = "scene_expansion"
    override val description = "Expand a scene synopsis into full prose"

    override fun buildSystemPrompt(): String = """
You are an award-winning fiction writer. You write vivid, immersive prose that brings scenes to life.

WRITING RULES:
1. SHOW, DON'T TELL. Use sensory details, actions, and dialogue to convey emotions and information.
2. Write natural dialogue. Each character should have a distinct voice.
3. Maintain consistent character voices and personalities as described in the character profiles.
4. Use the specified narrative voice and tense.
5. Write exactly the requested word count (within 10%). Do not write significantly more or less.
6. Every scene beat in the synopsis MUST appear in your prose. Do not skip or merge beats.
7. End the scene at a natural breaking point.
8. Do not add meta-commentary, chapter headings, or scene labels. Write ONLY the story text.
9. Match the emotional tone specified in the scene synopsis.
10. Use the world-building rules consistently.

The narrative voice, tense, and prose style are supplied in the user prompt. Follow them exactly.
""".trimIndent()

    override fun buildUserPrompt(input: SceneExpansionInput): String = buildString {
        appendLine("CHAPTER GOAL: ${input.chapterGoal}")
        appendLine()
        appendLine("SCENE TO WRITE (Scene ${input.sceneBreakdown.sceneIndex}):")
        appendLine(input.sceneBreakdown.synopsis)
        appendLine()
        appendLine("TARGET WORD COUNT: ${input.sceneBreakdown.targetWordCount} words")
        appendLine()
        appendLine("NARRATIVE VOICE: ${input.styleGuide.narrativeVoice}")
        appendLine("TENSE: ${input.styleGuide.tense}")
        appendLine("PROSE STYLE: ${input.styleGuide.proseStyle}")
        input.referenceProseStyle?.takeIf { it.isNotBlank() }?.let {
            appendLine()
            appendLine("REFERENCE PROSE STYLE:")
            appendLine(it.limitWords(150))
        }
        if (input.styleGuide.additionalNotes.isNotBlank()) {
            appendLine("ADDITIONAL STYLE NOTES: ${input.styleGuide.additionalNotes}")
        }
        appendLine()
        if (input.relevantCharacters.isNotEmpty()) {
            appendLine("CHARACTERS IN THIS SCENE:")
            input.relevantCharacters.forEach { c ->
                appendLine("- ${c.name}: ${c.description}. Personality: ${c.personality}. Current state: ${c.currentState}")
                if (c.relationships.isNotEmpty()) {
                    appendLine("  Relationships: ${c.relationships.joinToString(", ") { "${it.targetCharacterName} (${it.relationType})" }}")
                }
            }
            appendLine()
        }
        if (input.relevantLocations.isNotEmpty()) {
            appendLine("LOCATIONS IN THIS SCENE:")
            input.relevantLocations.forEach { l ->
                appendLine("- ${l.name}: ${l.description}. Significance: ${l.significance}")
            }
            appendLine()
        }
        if (input.relevantWorldRules.isNotEmpty()) {
            appendLine("WORLD RULES:")
            input.relevantWorldRules.forEach { r ->
                appendLine("- [${r.category}] ${r.rule}: ${r.details}")
            }
            appendLine()
        }
        input.rollingChapterSummary?.takeIf { it.isNotBlank() }?.let {
            appendLine("ROLLING CHAPTER TENSION SUMMARY:")
            appendLine(it)
            appendLine()
        }
        input.previousSceneEnding?.let {
            appendLine("PREVIOUS SCENE ENDING (continue from here):")
            appendLine(it)
            appendLine()
        }
        appendLine("Write the scene prose now. Output ONLY the story text, nothing else.")
    }

    private fun String.limitWords(maxWords: Int): String {
        val words = trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        return words.take(maxWords).joinToString(" ")
    }

    override fun parseOutput(rawOutput: String): Result<String> {
        val cleaned = rawOutput.trim()
        if (cleaned.isEmpty()) return Result.failure(Exception("Empty output from text agent"))
        return Result.success(cleaned)
    }
}
