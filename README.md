# Aria (咏叹)

[中文](README_zh.md)

An Android app for writing long-form fiction with AI—without reducing the workflow to a fragile chat transcript.

Aria is a structured writing workbench built around bounded context windows, hierarchical writing agents, user-editable story artifacts, and a persistent Novel Bible for continuity.

## Why Aria

Chat-based AI writing forgets early instructions, drifts from the outline, and degrades as the work grows. Dumping 100,000 words into a single prompt is not a solution—it is a budget fire.

Aria takes a different approach: each agent receives only the context it needs. The Outline Agent sees the premise. The Scene Agent sees the scene synopsis, relevant Bible entries, and the previous scene ending. Nothing more, nothing less.

## Core Workflow

```
Novel concept
  → Outline Agent generates full-book outline
  → You review and edit the outline
  → Chapter Synopsis Agent creates scene breakdowns
  → You review and edit the chapter plan
  → Scene Expansion Agent writes prose
  → Review Agent checks compliance against synopsis
  → You accept, edit, or retry
  → Continuity Agent extracts facts into the Novel Bible
  → Next scene uses filtered Bible context
```

Every level is editable. Every regeneration requires confirmation. Your words always win.

## Features

**Library** — Create novels, set genre/concept/themes, manage your bookshelf.

**Outline** — Generate a full-book outline with premise, major plot points, character arcs, and chapter briefs. Edit and save freely.

**Draft** — Select chapters and scenes. Generate and edit chapter synopses and scene breakdowns. Stream scene prose live into the editor. Queue generation by chapter or from any scene to the end.

**Bible** — Maintain a persistent Novel Bible with characters, locations, timeline events, world rules, and themes. User-authored canon always wins over extracted facts. Resolve conflicts visually.

**Review** — Every generated output gets a structured review with scores, issues, and suggested fixes. Accept, retry with feedback, edit manually, or mark approved. Failed reviews block Bible updates.

**History** — Revision snapshots for outline, synopses, scenes, and Bible entries. Restore or delete previous versions. Token usage and cost tracking by provider, model, and agent.

**Export** — Export your manuscript as Markdown, plain text, or EPUB through Android share intents.

**Settings** — Configure provider (Anthropic or OpenAI-compatible), API key, base URL, and per-agent model selection. API keys are encrypted at rest.

## Architecture

```
:core:model   Pure Kotlin domain models — no Android dependencies
:core:llm     Provider-neutral LLM API types and clients
:core:prompt  Prompt templates and output parsing
:core:agent   Agent wrappers, Bible filtering, Bible merging, pipeline orchestration
:data         Room persistence, DataStore settings, repositories
:app          Compose UI and Android ViewModels
```

### Agent Pipeline

| Agent | Role |
|---|---|
| OutlineAgent | Full-book outline and chapter shells |
| ChapterSynopsisAgent | Scene breakdowns with goals and transitions |
| SceneExpansionAgent | Prose generation with streaming |
| ReviewAgent | Compliance check against synopsis with scores and fixes |
| ContinuityAgent | Fact extraction for the Novel Bible |
| RollingSummaryAgent | Per-chapter continuity summaries |

### LLM Providers

- **Anthropic** Messages API
- **OpenAI** Chat Completions API (and OpenAI-compatible providers)
- Per-agent model selection

## Tech Stack

| Layer | Technology |
|---|---|
| UI | Kotlin + Jetpack Compose + Material 3 |
| Persistence | Room + DataStore Preferences |
| HTTP | OkHttp |
| DI | Manual `AppContainer` (no framework overhead) |
| Testing | JUnit 5 / JUnit 4, MockK, Turbine, MockWebServer, Robolectric |

## Build

```bash
# Debug APK
./gradlew :app:assembleDebug

# Full build (all modules + tests)
./gradlew build

# Targeted test runs
./gradlew :core:agent:test
./gradlew :core:llm:test
./gradlew :core:prompt:test
```

Requirements: Android SDK 35, JDK 17, Kotlin 2.1.21.

## Project Status

The core writing pipeline is fully functional: outline → synopsis → scene prose → review → Bible. Human editing, review/retry, streaming, export, revision history, and generation queuing are all implemented.

Current focus areas:
- Deeper mobile UI polish
- Process-death-safe background generation retry/resume

See [`Plan.md`](Plan.md) for the detailed roadmap.

## License

Proprietary. All rights reserved.
