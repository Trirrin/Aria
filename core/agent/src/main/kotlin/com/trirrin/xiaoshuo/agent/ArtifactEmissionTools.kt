package com.trirrin.xiaoshuo.agent

import com.trirrin.xiaoshuo.llm.LlmResponse
import com.trirrin.xiaoshuo.llm.LlmTool
import com.trirrin.xiaoshuo.model.BibleConflictSection
import com.trirrin.xiaoshuo.model.Genre
import com.trirrin.xiaoshuo.model.NarrativeTense
import com.trirrin.xiaoshuo.model.NarrativeVoice
import com.trirrin.xiaoshuo.model.ProseStyle
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal const val SUBMIT_NOVEL_BACKGROUND_PROPOSAL = "submitNovelBackgroundProposal"
internal const val SUBMIT_NOVEL_OUTLINE_PROPOSAL = "submitNovelOutlineProposal"

internal val novelBackgroundProposalTool = LlmTool(
    name = SUBMIT_NOVEL_BACKGROUND_PROPOSAL,
    description = "Emit a structured novel background proposal for author approval.",
    inputSchema = agentObjectSchema(
        required = listOf(
            "titleOptions",
            "genre",
            "tone",
            "premise",
            "worldSetup",
            "protagonistSeed",
            "coreCastSeeds",
            "majorConflict",
            "themes",
            "styleGuide",
            "initialBibleCandidates",
        ),
        properties = mapOf(
            "titleOptions" to stringArraySchema("Three to five concise title options."),
            "genre" to enumSchema(Genre.entries.map { it.name }, "One supported genre enum name."),
            "tone" to stringSchema("The story's tonal target."),
            "premise" to stringSchema("The core hook and conflict."),
            "worldSetup" to stringSchema("The setting and core world assumptions."),
            "protagonistSeed" to stringSchema("The protagonist seed in one concrete paragraph."),
            "coreCastSeeds" to stringArraySchema("Important supporting cast seeds."),
            "majorConflict" to stringSchema("The central external and internal conflict."),
            "themes" to stringArraySchema("Major themes for the novel."),
            "styleGuide" to styleGuideSchema(),
            "initialBibleCandidates" to bibleCandidateSchema(),
        ),
    ),
)

internal val novelOutlineProposalTool = LlmTool(
    name = SUBMIT_NOVEL_OUTLINE_PROPOSAL,
    description = "Emit a structured novel outline proposal for author approval.",
    inputSchema = agentObjectSchema(
        required = listOf(
            "premise",
            "majorPlotPoints",
            "characterArcs",
            "thematicStructure",
            "chapterCount",
            "chapterBriefs",
        ),
        properties = mapOf(
            "premise" to stringSchema("A clear one-paragraph statement of the story."),
            "majorPlotPoints" to arraySchema(
                item = agentObjectSchema(
                    required = listOf("name", "description", "position"),
                    properties = mapOf(
                        "name" to stringSchema("Plot point name."),
                        "description" to stringSchema("What changes in the story."),
                        "position" to numberSchema("Position from 0.0 to 1.0."),
                    ),
                ),
                description = "Major structural turning points.",
            ),
            "characterArcs" to stringArraySchema("One sentence for each major character arc."),
            "thematicStructure" to stringSchema("How themes develop through the story."),
            "chapterCount" to integerSchema("Total number of chapters."),
            "chapterBriefs" to arraySchema(
                item = agentObjectSchema(
                    required = listOf("chapterIndex", "title", "plotBeats", "purposeInStory"),
                    properties = mapOf(
                        "chapterIndex" to integerSchema("One-based chapter index."),
                        "title" to stringSchema("Chapter title."),
                        "plotBeats" to stringSchema("Important events in the chapter."),
                        "purposeInStory" to stringSchema("Why this chapter exists in the larger story."),
                    ),
                ),
                description = "One brief for each chapter.",
            ),
        ),
    ),
)

internal fun LlmResponse.requireSingleToolCallArguments(toolName: String, agentName: String): Result<String> {
    if (toolCalls.size != 1) {
        return Result.failure(IllegalStateException("$agentName agent must return exactly one tool call; got ${toolCalls.size}"))
    }

    val toolCall = toolCalls.single()
    if (toolCall.name != toolName) {
        return Result.failure(IllegalStateException("$agentName agent returned ${toolCall.name}, expected $toolName"))
    }

    if (toolCall.argumentsJson.isBlank()) {
        return Result.failure(IllegalStateException("$agentName agent returned empty arguments for $toolName"))
    }

    return Result.success(toolCall.argumentsJson)
}

private fun bibleCandidateSchema(): JsonObject = agentObjectSchema(
    properties = mapOf(
        "characters" to arraySchema(characterEntrySchema(), "Candidate characters."),
        "locations" to arraySchema(locationEntrySchema(), "Candidate locations."),
        "timelineEvents" to arraySchema(timelineEventSchema(), "Candidate timeline events."),
        "worldRules" to arraySchema(worldRuleSchema(), "Candidate world rules."),
        "themes" to arraySchema(themeEntrySchema(), "Candidate theme entries."),
        "conflicts" to arraySchema(bibleConflictSchema(), "Potential canon conflicts, usually empty at background stage."),
    ),
)

private fun styleGuideSchema(): JsonObject = agentObjectSchema(
    required = listOf(
        "narrativeVoice",
        "tense",
        "proseStyle",
        "targetSceneWordCountMin",
        "targetSceneWordCountMax",
        "additionalNotes",
    ),
    properties = mapOf(
        "narrativeVoice" to enumSchema(NarrativeVoice.entries.map { it.name }, "Narrative point of view."),
        "tense" to enumSchema(NarrativeTense.entries.map { it.name }, "Narrative tense."),
        "proseStyle" to enumSchema(ProseStyle.entries.map { it.name }, "Prose style enum name."),
        "targetSceneWordCountMin" to integerSchema("Minimum target scene length."),
        "targetSceneWordCountMax" to integerSchema("Maximum target scene length."),
        "additionalNotes" to stringSchema("Additional style notes."),
    ),
)

private fun characterEntrySchema(): JsonObject = agentObjectSchema(
    required = listOf("name"),
    properties = mapOf(
        "name" to stringSchema("Character name."),
        "aliases" to stringArraySchema("Character aliases."),
        "description" to stringSchema("Character description."),
        "personality" to stringSchema("Personality notes."),
        "relationships" to arraySchema(
            agentObjectSchema(
                required = listOf("targetCharacterName", "relationType"),
                properties = mapOf(
                    "targetCharacterName" to stringSchema("Related character name."),
                    "relationType" to stringSchema("Relationship type."),
                ),
            ),
            "Relationships to other characters.",
        ),
        "currentState" to stringSchema("Current state at story start."),
    ),
)

private fun locationEntrySchema(): JsonObject = agentObjectSchema(
    required = listOf("name"),
    properties = mapOf(
        "name" to stringSchema("Location name."),
        "description" to stringSchema("Location description."),
        "significance" to stringSchema("Why the location matters."),
    ),
)

private fun timelineEventSchema(): JsonObject = agentObjectSchema(
    required = listOf("description"),
    properties = mapOf(
        "description" to stringSchema("Event description."),
        "chronologicalOrder" to integerSchema("Relative chronological order."),
    ),
)

private fun worldRuleSchema(): JsonObject = agentObjectSchema(
    required = listOf("category", "rule"),
    properties = mapOf(
        "category" to stringSchema("Rule category."),
        "rule" to stringSchema("World rule."),
        "details" to stringSchema("Rule details."),
    ),
)

private fun themeEntrySchema(): JsonObject = agentObjectSchema(
    required = listOf("name"),
    properties = mapOf(
        "name" to stringSchema("Theme name."),
        "description" to stringSchema("Theme description."),
        "motifSymbols" to stringArraySchema("Symbols or motifs for the theme."),
    ),
)

private fun bibleConflictSchema(): JsonObject = agentObjectSchema(
    required = listOf("section", "entryId", "title", "field", "existingValue", "incomingValue"),
    properties = mapOf(
        "section" to enumSchema(BibleConflictSection.entries.map { it.name }, "Bible section."),
        "entryId" to stringSchema("Conflicting entry id."),
        "title" to stringSchema("Conflict title."),
        "field" to stringSchema("Conflicting field."),
        "existingValue" to stringSchema("Existing value."),
        "incomingValue" to stringSchema("Incoming value."),
    ),
)

private fun agentObjectSchema(
    required: List<String> = emptyList(),
    properties: Map<String, JsonObject> = emptyMap(),
): JsonObject = buildJsonObject {
    put("type", "object")
    put(
        "properties",
        buildJsonObject {
            properties.forEach { (name, schema) -> put(name, schema) }
        },
    )
    put(
        "required",
        buildJsonArray {
            required.forEach { add(JsonPrimitive(it)) }
        },
    )
}

private fun stringSchema(description: String): JsonObject = buildJsonObject {
    put("type", "string")
    put("description", description)
}

private fun integerSchema(description: String): JsonObject = buildJsonObject {
    put("type", "integer")
    put("description", description)
}

private fun numberSchema(description: String): JsonObject = buildJsonObject {
    put("type", "number")
    put("description", description)
}

private fun stringArraySchema(description: String): JsonObject = arraySchema(stringSchema("List item."), description)

private fun arraySchema(item: JsonObject, description: String): JsonObject = buildJsonObject {
    put("type", "array")
    put("description", description)
    put("items", item)
}

private fun enumSchema(values: List<String>, description: String): JsonObject = buildJsonObject {
    put("type", "string")
    put("description", description)
    put(
        "enum",
        buildJsonArray {
            values.forEach { add(JsonPrimitive(it)) }
        },
    )
}
