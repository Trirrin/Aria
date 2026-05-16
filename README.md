# Aria

[中文](README_zh.md)

AI-assisted long-form fiction writing for Android.

The problem is simple: chat-based AI writing forgets early instructions, drifts from the outline, and degrades as the work grows. Shoving 100k words into a single prompt doesn't fix this — it just burns your API budget.

Aria's answer: each agent gets only the context it needs. The Outline Agent sees the premise. The Scene Agent gets the scene synopsis, relevant Bible entries, and where the last scene left off. Nothing more, nothing less.

## How it works

The app opens into **Conversation**. You describe intent in natural language, review generated artifacts, approve or reject important steps, and keep writing without learning internal workflow concepts.

```
User natural language
  -> Interaction Agent selects a workflow function call
  -> ViewModel/domain tool executes
  -> Generation agent emits structured artifact via function call
  -> Artifact Preview
  -> User accept / reject / revise
  -> Commit through repositories
```

Every structured LLM result is represented as a provider-native function call, not free-standing JSON text. Scene prose remains raw text, not JSON-wrapped. Every level is editable. Regeneration requires confirmation. Your edits always take precedence.

## What's built

- **Conversation** — natural language entry point, interaction-agent function-call routing, artifact previews
- **Library** — create novels, set genre/concept/themes
- **Outline** — generate, edit, save full-book outline (conversation-first workflow)
- **Draft** — chapter synopses, scene breakdowns, streaming scene prose, generation queues
- **Bible** — characters, locations, timeline, world rules, themes. User-written entries survive merges. Conflicts flagged for resolution
- **Review** — scores, issues, suggested fixes. Accept, retry with feedback, or mark approved. Failed reviews block Bible updates
- **History** — revision snapshots, restore/delete, token usage and cost tracking
- **Export** — Markdown, plain text, or EPUB via Android share
- **Settings** — provider (Anthropic/OpenAI-compatible), API key, base URL, per-agent model selection. Keys encrypted at rest

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

Core pipeline works end to end. The first usable conversation-first loop is implemented:

- App opens into `Conversation` plus `Settings`
- Interaction routing uses provider-native tool calls for background, outline, chapter plan, scene draft, accept, reject, revise, and clarification
- Structured artifact emission uses provider-native tool calls for background, outline, chapter synopsis, scene review, and Bible update proposals
- Background, Outline, Chapter Plan, Scene Draft, and Canon Update appear as pending approvals before commit
- Room schema includes persistence tables for conversation sessions, pending approvals, and tool-call audit records
- `NovelWorkspaceViewModel` restores the latest persisted conversation session, pending approval, active tool call, audit history, and chapter/scene selection context after process restart

Working on: Bible conflict-resolution workflow, natural-language structure editing (chapter/scene add/delete/reorder), recovery/resume of in-progress generation jobs, mobile UI polish.

## License

Proprietary. All rights reserved.
