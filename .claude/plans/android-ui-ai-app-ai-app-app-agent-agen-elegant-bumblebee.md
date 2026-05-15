# AI Novel Writing Android App - Implementation Plan

## Context

Writing novels with AI in chat interfaces has three fundamental problems: (1) limited context window causes the AI to "forget" earlier instructions and plot points, (2) the AI drifts from its directives as context grows, (3) quality degrades for long-form content. The solution: a hierarchical agent pipeline where each agent operates within a **bounded, filtered context window**, supplemented by a growing "Novel Bible" knowledge base that accumulates story facts across chapters.

**User decisions**: Anthropic + OpenAI-compatible APIs | Kotlin + Jetpack Compose | Backend/agent pipeline first | Full pipeline scope

---

## Architecture Overview

```
┌─────────────────────────────────────────────────┐
│                   Novel Bible                    │
│  (Characters, Locations, Timeline, World Rules)  │
│          Grows with each scene written           │
└──────────┬──────────────────┬────────────────────┘
           │ filtered         │ full (for extraction)
           ▼                  ▼
┌──────────────┐   ┌───────────────┐   ┌──────────────┐
│ Outline Agent│──▶│Chapter Synopsis│──▶│ Text Agent   │
│  (big idea)  │   │   (beats)     │   │  (prose)     │
└──────────────┘   └───────────────┘   └──────┬───────┘
       │                  │                     │
       ▼                  ▼                     ▼
   ┌─────────────────────────────────────────────────┐
   │              Review Agent (compliance check)     │
   └─────────────────────────────────────────────────┘
                         │
                         ▼
              ┌─────────────────────┐
              │ Continuity Agent    │──▶ Updates Bible
              │ (extract facts)     │
              └─────────────────────┘
```

**Data flow per scene**: User input → Outline → Chapter Synopsis → Scene Text → Review → Bible update. User can edit at every level before proceeding.

---

## Project Structure

```
xiao_shuo/
├── gradle/libs.versions.toml          # Centralized version catalog
├── settings.gradle.kts                # Multi-module definition
├── build.gradle.kts                   # Root build config
├── core/
│   ├── model/                         # Pure Kotlin data classes
│   │   └── src/main/kotlin/.../model/
│   │       ├── StyleGuide.kt
│   │       ├── NovelBible.kt          # Bible data model (key architecture piece)
│   │       ├── Outline.kt
│   │       ├── Novel.kt
│   │       ├── Chapter.kt
│   │       └── Scene.kt
│   ├── llm/                           # LLM client abstraction
│   │   └── src/main/kotlin/.../llm/
│   │       ├── LlmClient.kt           # Core interface
│   │       ├── anthropic/
│   │       │   ├── AnthropicModels.kt
│   │       │   └── AnthropicLlmClient.kt
│   │       ├── openai/
│   │       │   ├── OpenAiModels.kt
│   │       │   └── OpenAiLlmClient.kt
│   │       └── LlmClientFactory.kt
│   ├── prompt/                        # Prompt templates
│   │   └── src/main/kotlin/.../prompt/
│   │       ├── PromptTemplate.kt       # Generic interface
│   │       ├── OutlinePrompt.kt
│   │       ├── ChapterSynopsisPrompt.kt
│   │       ├── SceneExpansionPrompt.kt
│   │       ├── ReviewPrompt.kt
│   │       └── ContinuityPrompt.kt
│   └── agent/                         # Agent pipeline & orchestration
│       └── src/main/kotlin/.../agent/
│           ├── Agent.kt               # Interface + result types
│           ├── BibleFilter.kt         # Token budget filtering
│           ├── OutlineAgent.kt
│           ├── ChapterSynopsisAgent.kt
│           ├── SceneExpansionAgent.kt
│           ├── ReviewAgent.kt
│           ├── ContinuityAgent.kt
│           └── AgentPipeline.kt       # Orchestrator
├── data/                              # Room DB, repositories (Phase 2)
└── app/                               # Android UI (Phase 3)
```

Package: `com.trirrin.xiaoshuo`

---

## Key Design Decisions

### 1. Novel Bible System

The Bible is the answer to "never forget." Structured knowledge that grows organically:

```kotlin
data class NovelBible(
    val characters: List<CharacterEntry>,     // name, description, personality, relationships, currentState
    val locations: List<LocationEntry>,        // name, description, significance
    val timelineEvents: List<TimelineEvent>,   // description, chapterId, chronological order
    val worldRules: List<WorldRule>,           // category, rule, details
    val themes: List<ThemeEntry>,              // name, description, motifs
)
```

**Update flow**: After each scene, Continuity Agent extracts new facts → `BibleDiff` → merge into Bible. Newer written-text facts override old planning assumptions.

**Filtering**: Before each agent call, `BibleFilter` selects only relevant entries via keyword matching + 1-hop relationship expansion, enforced by a token budget (~2000 tokens). This keeps each agent's context small.

### 2. Scene-Level Granularity (Not Chapter-Level)

Chapters split into Scenes of 2000-3000 words each. Each scene receives:
- Its specific scene breakdown from the chapter synopsis
- Filtered Bible entries relevant to THIS scene
- Previous scene's last ~200 words (for continuity)
- Style guide

This keeps the Text Agent's context window focused and manageable.

### 3. LLM Client Abstraction

```kotlin
interface LlmClient {
    val provider: LlmProvider
    suspend fun complete(request: LlmRequest): LlmResponse
    suspend fun stream(request: LlmRequest): Flow<LlmChunk>
}
```

Two implementations: `AnthropicLlmClient` (Messages API, system prompt as top-level param) and `OpenAiLlmClient` (Chat Completions API, system as first message).

### 4. Per-Agent Model Selection

Different agents can use different models to save tokens:
- **Outline/Chapter/Text Agents**: Powerful model (Sonnet/GPT-4o) — creative work
- **Review Agent**: Cheaper model (Haiku/GPT-4o-mini) — evaluation only
- **Continuity Agent**: Cheapest model — structured data extraction

### 5. Prompt Output Format

- Outline, Synopsis, Review, Continuity agents → **structured JSON** (reliably parseable)
- Text Agent → **raw prose** (no JSON wrapping, maximum creative quality)
- JSON parsing has fallback: strict parse → regex extract from markdown code fences → `AgentResult.Error`

---

## Phase 1: Foundation (Implementation Sequence) - Completed

### Step 1: Project Scaffolding
- `gradle/libs.versions.toml` — Kotlin 2.x, Coroutines, Compose BOM, Hilt, Room, OkHttp, kotlinx-serialization, JUnit 5, MockK, Turbine
- `settings.gradle.kts` — include all submodules
- `build.gradle.kts` (root) — plugin declarations
- All module `build.gradle.kts` files

### Step 2: Core Models (`core:model`)
Order: `StyleGuide.kt` → `NovelBible.kt` → `Outline.kt` → `Novel.kt` → `Chapter.kt` → `Scene.kt`

Key enums: `NovelStatus` (DRAFTING_OUTLINE → OUTLINE_COMPLETE → DRAFTING_CHAPTERS → COMPLETE), `ChapterStatus`, `SceneStatus`, `Genre`, `NarrativeVoice`, `ProseStyle`

### Step 3: LLM Client (`core:llm`)
- `LlmClient.kt` — interface + request/response/error types
- `AnthropicLlmClient.kt` — OkHttp POST to Messages API, handle system prompt as top-level param, parse `usage` fields, map errors (429→RateLimited, 401→AuthFailed, context_length_exceeded→ContextTooLong)
- `OpenAiLlmClient.kt` — OkHttp POST to Chat Completions, SSE streaming, same error mapping
- `LlmClientFactory.kt` — creates correct implementation from config

### Step 4: Prompt Templates (`core:prompt`)
Each implements `PromptTemplate<TInput, TOutput>` with `buildSystemPrompt()`, `buildUserPrompt(input)`, `parseOutput(raw)`.

**OutlinePrompt** — Input: concept, genre, themes, styleGuide. Output: `NovelOutline` JSON. System: "master novelist and plot architect." Genre-specific structural constraints (inciting incident ~15%, midpoint ~50%, climax ~85%).

**ChapterSynopsisPrompt** — Input: chapter brief, adjacent briefs, Bible entries, previous chapter ending. Output: `ChapterSynopsis` JSON with 2-4 scene breakdowns. System: "story breakdown specialist."

**SceneExpansionPrompt** — Input: scene breakdown, chapter context, Bible entries, previous scene ending, style guide. Output: raw prose. System: "award-winning prose writer, show don't tell." Most detailed prompt.

**ReviewPrompt** — Input: parent directive, child output, review type. Output: `{complianceScore, issues, suggestedFixes, passed}`. Temperature 0.3. Score 7+ passes.

**ContinuityPrompt** — Input: scene text, existing Bible. Output: `BibleDiff` JSON with add/update lists. Temperature 0.2. Extract characters, locations, events, rules.

### Step 5: Agent Framework (`core:agent`)
- `Agent.kt` — interface + sealed `AgentResult` class + `BibleDiff`
- `BibleFilter.kt` — keyword extraction + 1-hop expansion + token budget trimming
- Each agent implementation wraps its prompt template + LLM call + output parsing
- `AgentPipeline.kt` — orchestrator with methods: `generateOutline()`, `generateChapterSynopsis()`, `generateScene()` (returns `Flow<SceneGenerationEvent>` for streaming)

### Step 6: Testing
- `MockLlmClient` — returns pre-configured responses based on system prompt matching
- Unit tests per agent: verify context assembly, output parsing, error handling
- `BibleFilterTest` — verify relevance filtering under various scenarios
- `AgentPipelineTest` — full pipeline integration test with mock LLM
- LLM client tests use OkHttp `MockWebServer` for HTTP serialization verification

---

## Edge Cases to Handle

| Case | Mitigation |
|------|-----------|
| LLM outputs invalid JSON | Two-step parsing: strict → regex fallback → retry once |
| Context window overflow | BibleFilter enforces token budget, previous scene ending capped at 200 words |
| Review score < 7 | Return to caller, support retry with feedback (max 2 retries) |
| Bible conflict (text contradicts Bible) | Newer written-text wins, log warning for user review |
| Bible grows unbounded (50+ chapters) | Token budget + relevance scoring + deprioritize stale entries |
| Interrupted generation | Scene status tracks GENERATING → reset to PENDING on restart |

---

## Verification Plan

1. **Compile check**: All modules build with `./gradlew build`
2. **Unit tests**: Run `./gradlew :core:agent:test` — all agent tests pass with MockLlmClient
3. **Integration test**: `AgentPipelineTest` runs full concept → outline → synopsis → scene → review → bible-update cycle
4. **Manual smoke test**: Write a minimal `main()` function that creates a pipeline with a real API key, generates an outline from a test concept, and prints the result
5. **LLM client tests**: Verify Anthropic and OpenAI request/response formats against MockWebServer

---

## Future Phases (Not in Scope for Phase 1)

- **Phase 2**: OpenAI implementation + Room DB + repositories + API key management
- **Phase 3**: Android UI (Compose) — novel creation, outline editor, chapter/scene viewer, generation controls, settings
- **Phase 4**: Bible viewer/editor, token tracking, export, revision history, background generation
