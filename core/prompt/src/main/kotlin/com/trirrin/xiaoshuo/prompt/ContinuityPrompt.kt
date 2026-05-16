package com.trirrin.xiaoshuo.prompt

import com.trirrin.xiaoshuo.model.NovelBible
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

data class ContinuityInput(
    val sceneText: String,
    val existingBible: NovelBible,
    val chapterId: String,
    val sceneId: String,
)

@Serializable
data class BibleDiff(
    val charactersToAdd: List<CharacterToAdd> = emptyList(),
    val charactersToUpdate: List<CharacterToUpdate> = emptyList(),
    val locationsToAdd: List<LocationToAdd> = emptyList(),
    val timelineEventsToAdd: List<TimelineEventToAdd> = emptyList(),
    val worldRulesToAdd: List<WorldRuleToAdd> = emptyList(),
)

@Serializable
data class CharacterToAdd(
    val name: String,
    val description: String = "",
    val personality: String = "",
    val relationships: List<RelationshipToAdd> = emptyList(),
    val currentState: String = "",
)

@Serializable
data class RelationshipToAdd(
    val targetCharacterName: String,
    val relationType: String,
)

@Serializable
data class CharacterToUpdate(
    val name: String,
    val updatedState: String,
    val reason: String = "",
)

@Serializable
data class LocationToAdd(
    val name: String,
    val description: String = "",
    val significance: String = "",
)

@Serializable
data class TimelineEventToAdd(
    val description: String,
)

@Serializable
data class WorldRuleToAdd(
    val category: String,
    val rule: String,
    val details: String = "",
)

class ContinuityPrompt : PromptTemplate<ContinuityInput, BibleDiff> {

    override val name = "continuity"
    override val description = "Extract facts from scene text and produce Bible updates"

    private val json = Json { ignoreUnknownKeys = true }

    override fun buildSystemPrompt(): String = """
You are a meticulous story continuity editor. You extract factual information from written prose and produce structured updates to the story's knowledge base.

EXTRACTION RULES:
1. Extract EVERY new character mentioned by name (not pronouns). Include any description or personality hints from the text.
2. Update ANY character whose state changes (injury, emotional shift, relationship change, location change, death).
3. Record new locations that are described in detail.
4. Log significant timeline events (major plot actions, discoveries, confrontations).
5. Record any new world-building rules revealed (magic systems, social customs, technology, etc.).
6. Do NOT duplicate existing entries. Check the existing Bible carefully.
7. Do NOT add entries for characters that are only mentioned but have no new information revealed.
8. For character updates, only include if something MEANINGFULLY changed about the character.

OUTPUT FORMAT: Call submitBibleUpdateProposal exactly once with the structured Bible update arguments.

If no changes are needed for a category, pass an empty array for that field.
""".trimIndent()

    override fun buildUserPrompt(input: ContinuityInput): String = buildString {
        appendLine("SCENE TEXT TO ANALYZE:")
        appendLine(input.sceneText)
        appendLine()
        appendLine("CHAPTER ID: ${input.chapterId}")
        appendLine("SCENE ID: ${input.sceneId}")
        appendLine()
        appendLine("EXISTING STORY BIBLE:")
        appendLine()
        if (input.existingBible.characters.isNotEmpty()) {
            appendLine("EXISTING CHARACTERS:")
            input.existingBible.characters.forEach { c ->
                appendLine("- ${c.name}: ${c.currentState}")
            }
            appendLine()
        }
        if (input.existingBible.locations.isNotEmpty()) {
            appendLine("EXISTING LOCATIONS:")
            input.existingBible.locations.forEach { l ->
                appendLine("- ${l.name}")
            }
            appendLine()
        }
        if (input.existingBible.worldRules.isNotEmpty()) {
            appendLine("EXISTING WORLD RULES:")
            input.existingBible.worldRules.forEach { r ->
                appendLine("- [${r.category}] ${r.rule}")
            }
            appendLine()
        }
        appendLine("Analyze the scene text and call the required tool with the Bible diff.")
    }

    override fun parseOutput(rawOutput: String): Result<BibleDiff> {
        return try {
            Result.success(json.decodeObjectOutput<BibleDiff>(rawOutput))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
