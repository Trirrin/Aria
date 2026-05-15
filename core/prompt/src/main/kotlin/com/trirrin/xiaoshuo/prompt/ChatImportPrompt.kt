package com.trirrin.xiaoshuo.prompt

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

object ChatImportPrompt {
    private val json = Json { ignoreUnknownKeys = true }

    val extractionPrompt: String = """
You are helping migrate a long-form fiction project from this chat into Xiao Shuo, a structured novel writing app.

Summarize the entire project as a single JSON object. Return JSON only, no markdown and no commentary. Preserve specific canon over vague summaries. Include only facts that are actually established in this chat.

Use this exact shape:
{
  "title": "working title or concise project title",
  "genre": "FANTASY | SCI_FI | ROMANCE | MYSTERY | THRILLER | LITERARY | HISTORICAL | HORROR | OTHER",
  "concept": "one paragraph describing the core premise, protagonist, central conflict, and current direction",
  "themes": ["theme"],
  "styleGuide": {
    "narrativeVoice": "first person, close third, omniscient, etc.",
    "tense": "past or present",
    "proseStyle": "concise description of prose style"
  },
  "outline": {
    "premise": "story premise",
    "majorPlotPoints": [
      {"name": "Inciting Incident", "description": "what happens", "position": 0.12}
    ],
    "characterArcs": ["arc summary"],
    "thematicStructure": "how themes develop",
    "chapters": [
      {
        "index": 1,
        "title": "chapter title",
        "plotBeats": "major beats",
        "purposeInStory": "why this chapter exists",
        "synopsis": {
          "chapterGoal": "chapter-level goal",
          "chapterEnding": "where the chapter ends",
          "transitionNotes": "handoff to next chapter",
          "scenes": [
            {"index": 1, "synopsis": "scene synopsis", "targetWordCount": 1800, "prose": "existing prose if present"}
          ]
        }
      }
    ]
  },
  "bible": {
    "characters": [
      {"name": "name", "aliases": ["alias"], "description": "external facts", "personality": "internal traits", "currentState": "latest known state", "relationships": [{"targetCharacterName": "name", "relationType": "relationship"}]}
    ],
    "locations": [
      {"name": "name", "description": "facts", "significance": "story role"}
    ],
    "timelineEvents": [
      {"description": "event", "chronologicalOrder": 1}
    ],
    "worldRules": [
      {"category": "category", "rule": "rule", "details": "limits, costs, exceptions"}
    ],
    "themes": [
      {"name": "theme", "description": "meaning", "motifSymbols": ["symbol"]}
    ]
  }
}

If something is unknown, use an empty string or empty array. Do not invent missing chapters, scenes, names, or rules.
""".trimIndent()

    fun parseOutput(rawOutput: String): Result<ChatImportPayload> {
        return try {
            Result.success(json.decodeObjectOutput<ChatImportPayload>(rawOutput))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

@Serializable
data class ChatImportPayload(
    val title: String,
    val genre: String = "OTHER",
    val concept: String,
    val themes: List<String> = emptyList(),
    val styleGuide: ImportedStyleGuide = ImportedStyleGuide(),
    val outline: ImportedOutline? = null,
    val bible: ImportedBible = ImportedBible(),
)

@Serializable
data class ImportedStyleGuide(
    val narrativeVoice: String = "",
    val tense: String = "",
    val proseStyle: String = "",
)

@Serializable
data class ImportedOutline(
    val premise: String = "",
    val majorPlotPoints: List<ImportedPlotPoint> = emptyList(),
    val characterArcs: List<String> = emptyList(),
    val thematicStructure: String = "",
    val chapters: List<ImportedChapter> = emptyList(),
)

@Serializable
data class ImportedPlotPoint(
    val name: String = "",
    val description: String = "",
    val position: Float = 0f,
)

@Serializable
data class ImportedChapter(
    val index: Int = 0,
    val title: String = "",
    val plotBeats: String = "",
    val purposeInStory: String = "",
    val synopsis: ImportedChapterSynopsis? = null,
)

@Serializable
data class ImportedChapterSynopsis(
    val chapterGoal: String = "",
    val chapterEnding: String = "",
    val transitionNotes: String = "",
    val scenes: List<ImportedScene> = emptyList(),
)

@Serializable
data class ImportedScene(
    val index: Int = 0,
    val synopsis: String = "",
    val targetWordCount: Int = 2500,
    val prose: String = "",
)

@Serializable
data class ImportedBible(
    val characters: List<ImportedCharacter> = emptyList(),
    val locations: List<ImportedLocation> = emptyList(),
    val timelineEvents: List<ImportedTimelineEvent> = emptyList(),
    val worldRules: List<ImportedWorldRule> = emptyList(),
    val themes: List<ImportedTheme> = emptyList(),
)

@Serializable
data class ImportedCharacter(
    val name: String = "",
    val aliases: List<String> = emptyList(),
    val description: String = "",
    val personality: String = "",
    val currentState: String = "",
    val relationships: List<ImportedRelationship> = emptyList(),
)

@Serializable
data class ImportedRelationship(
    val targetCharacterName: String = "",
    val relationType: String = "",
)

@Serializable
data class ImportedLocation(
    val name: String = "",
    val description: String = "",
    val significance: String = "",
)

@Serializable
data class ImportedTimelineEvent(
    val description: String = "",
    val chronologicalOrder: Int = 0,
)

@Serializable
data class ImportedWorldRule(
    val category: String = "",
    val rule: String = "",
    val details: String = "",
)

@Serializable
data class ImportedTheme(
    val name: String = "",
    val description: String = "",
    val motifSymbols: List<String> = emptyList(),
)
