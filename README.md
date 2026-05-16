# Aria

[中文](README_zh.md)

AI-assisted long-form fiction writing for Android.

The problem is simple: chat-based AI writing forgets early instructions, drifts from the outline, and degrades as the work grows. Shoving 100k words into a single prompt doesn't fix this — it just burns your API budget.

Aria's answer: each agent gets only the context it needs. The Outline Agent sees the premise. The Scene Agent gets the scene synopsis, relevant Bible entries, and where the last scene left off. Nothing more, nothing less.

## How it works

```
Novel concept
  → Outline Agent generates the full outline
  → You edit the outline
  → Chapter Synopsis Agent breaks it into scenes
  → You edit the scene plan
  → Scene Agent writes prose
  → Review Agent checks it against the synopsis
  → You accept, edit, or retry
  → Continuity Agent feeds facts into the Novel Bible
  → Next scene pulls filtered Bible context
```

Every level is editable. Regeneration requires confirmation. Your edits always take precedence.

## What's built

- **Library** — create novels, set genre/concept/themes
- **Outline** — generate, edit, save full-book outline
- **Draft** — chapter synopses, scene breakdowns, streaming scene prose, generation queues
- **Bible** — characters, locations, timeline, world rules, themes. User-written entries survive merges. Conflicts flagged for resolution.
- **Review** — scores, issues, suggested fixes. Accept, retry with feedback, or mark approved. Failed reviews block Bible updates.
- **History** — revision snapshots, restore/delete, token usage and cost tracking
- **Export** — Markdown, plain text, or EPUB via Android share
- **Settings** — provider (Anthropic/OpenAI-compatible), API key, base URL, per-agent model selection. Keys encrypted at rest.

## Architecture

```
:core:model   Pure Kotlin domain models, no Android deps
:core:llm     Provider-neutral LLM API types and clients
:core:prompt  Prompt templates and output parsing
:core:agent   Agent wrappers, Bible filtering/merging, pipeline
:data         Room + DataStore, repositories
:app          Compose UI + ViewModels
```

### Agents

| Agent | Job |
|---|---|
| OutlineAgent | Full-book outline and chapter shells |
| ChapterSynopsisAgent | Scene breakdowns with goals and transitions |
| SceneExpansionAgent | Prose, streamed |
| ReviewAgent | Compliance check against synopsis |
| ContinuityAgent | Extract facts for the Bible |
| RollingSummaryAgent | Per-chapter continuity summaries |

### LLM providers

- Anthropic Messages API
- OpenAI Chat Completions API (and OpenAI-compatible endpoints)
- Per-agent model selection

## Tech

Kotlin · Jetpack Compose · Material 3 · Room · DataStore · OkHttp · JUnit 5 · MockK

No DI framework. Manual `AppContainer`.

## Build

```bash
./gradlew :app:assembleDebug        # debug APK
./gradlew build                     # full build + tests
./gradlew :core:agent:test          # targeted tests
```

Android SDK 35 · JDK 17 · Kotlin 2.1.21

## Status

Core pipeline works end to end. Editing, review/retry, streaming, export, history, and generation queuing are all shipped.

Working on: mobile UI polish, process-death-safe background retry.

## License

Proprietary. All rights reserved.