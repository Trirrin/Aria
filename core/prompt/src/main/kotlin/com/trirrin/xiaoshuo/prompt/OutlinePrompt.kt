package com.trirrin.xiaoshuo.prompt

import com.trirrin.xiaoshuo.model.*
import kotlinx.serialization.json.Json

data class OutlineInput(
    val concept: String,
    val genre: Genre,
    val themes: List<String>,
    val styleGuide: StyleGuide,
    val chapterCount: IntRange = 15..25,
)

class OutlinePrompt : PromptTemplate<OutlineInput, NovelOutline> {

    override val name = "outline"
    override val description = "Generate a detailed novel outline from concept"

    private val json = Json { ignoreUnknownKeys = true }

    override fun buildSystemPrompt(): String = """
You are a master novelist and plot architect with deep expertise in ${'$'}GENRE fiction structure.

Your task is to create a detailed novel outline from a given concept. The outline must include:

1. **Premise**: A clear one-paragraph statement of the story's core conflict and hook.
2. **Major Plot Points**: Key turning points mapped to their position in the story (0.0 = beginning, 1.0 = end).
   - Inciting incident at ~0.10-0.15
   - First turning point at ~0.25
   - Midpoint reversal at ~0.50
   - Dark moment / all-is-lost at ~0.75
   - Climax at ~0.85-0.90
   - Resolution at ~0.95-1.00
3. **Character Arcs**: One sentence each describing the major characters' internal journeys.
4. **Thematic Structure**: How themes weave through the narrative.
5. **Chapter Briefs**: For each chapter, provide:
   - Title
   - Plot beats (what happens)
   - Purpose in the overall story

OUTPUT FORMAT: Return ONLY a JSON object with this exact structure, no markdown, no extra text:
{
  "premise": "...",
  "majorPlotPoints": [{"name": "...", "description": "...", "position": 0.5}],
  "characterArcs": ["..."],
  "thematicStructure": "...",
  "chapterCount": 20,
  "chapterBriefs": [{"chapterIndex": 1, "title": "...", "plotBeats": "...", "purposeInStory": "..."}]
}
""".trimIndent()

    override fun buildUserPrompt(input: OutlineInput): String = """
NOVEL CONCEPT:
${input.concept}

GENRE: ${input.genre.label}

THEMES: ${input.themes.joinToString(", ")}

NARRATIVE STYLE: ${input.styleGuide.narrativeVoice}, ${input.styleGuide.tense} tense, ${input.styleGuide.proseStyle} prose

TARGET CHAPTER COUNT: ${input.chapterCount.first}-${input.chapterCount.last}

Create a complete novel outline following the JSON format specified in your instructions.
""".trimIndent()

    override fun parseOutput(rawOutput: String): Result<NovelOutline> {
        return try {
            Result.success(json.decodeObjectOutput<NovelOutline>(rawOutput))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
