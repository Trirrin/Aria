# Xiao Shuo Product Plan

## Product Goal

Xiao Shuo is an Android app for writing long-form fiction with AI without turning the workflow into a fragile chat transcript. The app should help a writer create, review, revise, and continue a novel across many chapters while preserving continuity.

The core problem is simple: chat-based AI writing forgets earlier instructions, drifts from the outline, and degrades as the work grows. Xiao Shuo solves that with a bounded agent pipeline and a structured Novel Bible.

## Core Workflow

```text
Novel concept
  -> Outline Agent
  -> user reviews/edits outline
  -> Chapter Synopsis Agent
  -> user reviews/edits chapter and scene plan
  -> Scene Text Agent
  -> Review Agent checks compliance
  -> user accepts/revises prose
  -> Continuity Agent extracts facts
  -> Novel Bible is updated
  -> next scene uses filtered Bible context
```

The app must never depend on dumping the whole book into one prompt. Each agent receives only the context it needs: the directive for its level, relevant Bible entries, nearby continuity text, and the style guide.

## Architecture

### Modules

```text
:core:model   Pure Kotlin domain models
:core:llm     Provider-neutral LLM request/response abstraction and clients
:core:prompt  Prompt templates and parsing
:core:agent   Agent wrappers, Bible filtering, Bible merging, orchestration
:data         Room persistence, DataStore settings, repositories
:app          Android UI in Kotlin + Jetpack Compose
```

Package: `com.trirrin.xiaoshuo`.

### Data Model

Primary entities:

- `Novel`: title, genre, concept, themes, style guide, outline, Bible, status.
- `NovelOutline`: premise, major plot points, character arcs, thematic structure, chapter briefs.
- `Chapter`: chapter order/title, generated synopsis, review notes, status.
- `ChapterSynopsis`: chapter goal, scene breakdowns, ending, transition notes.
- `Scene`: scene synopsis, generated prose, review notes, status, word count.
- `NovelBible`: characters, locations, timeline events, world rules, themes.

### Agent Pipeline

The canonical generation path is:

1. `generateOutline(novel)` creates a full-book outline and chapter shells.
2. `generateChapterSynopsis(novel, chapter, chapters)` creates scene breakdowns for one chapter and reviews them against the outline.
3. `generateScene(novel, chapter, scene, previousSceneEnding)` writes prose, reviews it against the scene synopsis, extracts continuity facts, and emits a Bible update.

The app currently calls generation manually from the UI. Future work should support a queued full pipeline while still allowing user edits between levels.

### LLM Providers

The app supports:

- Anthropic-style Messages API.
- OpenAI-compatible Chat Completions API.
- Per-agent model selection for outline, synopsis, prose, review, and continuity extraction.

Settings are stored with DataStore. API keys are currently local settings data; do not log them and do not commit test keys.

## Current Status

### Completed

- Multi-module Gradle project.
- Core domain models.
- LLM abstraction and provider clients.
- Prompt templates for outline, chapter synopsis, scene expansion, review, and continuity extraction.
- Agent wrappers and orchestration.
- Bible filtering and Bible merging.
- Room persistence for novels, chapters, and scenes.
- DataStore generation settings.
- Compose MVP workspace with five tabs:
  - Library: create and select novels.
  - Outline: generate, edit, and save outline.
  - Draft: select chapters/scenes, generate and edit chapter synopsis, generate and edit scene prose.
  - Bible: view extracted story facts.
  - Settings: configure provider, API key, base URL, and per-agent models.
- Human editing loop:
  - Editable outline with persistence.
  - Editable chapter synopsis and scene breakdowns with persistence.
  - Editable scene prose with persistence and word-count recalculation.
  - Chapter and scene shell updates preserve existing user text.
- Review and retry workflow:
  - Review score, issues, suggested fixes, pass/fail, retry count, and decision status persist on chapters and scenes.
  - Draft workspace shows structured review panels for chapter synopsis and scene prose.
  - User can accept, retry with feedback, mark for manual edit, or mark approved.
  - Retry attempts are capped in the ViewModel.
  - Failed scene reviews do not update the Novel Bible.
- `./gradlew build` passes.

### Partially Implemented

- Scene continuity: previous scene ending is passed for selected scene generation, but broader chapter/novel continuity UX is thin.
- Bible workflow: facts can be extracted and viewed, but manual editing/conflict resolution is missing.
- Streaming: core pipeline exposes `streamScene`, but the Compose UI currently saves only completed scene text.
- Error handling: errors are shown in the run log, but generation retry/resume behavior is not complete.
- Mobile UI: the workspace is functional, but still more tablet/workbench than polished phone UI.

### Not Yet Implemented

- Bible editor for characters, locations, timeline, world rules, and themes.
- Bible conflict warnings and user resolution.
- Generation queue for chapter/scene batches.
- Real streaming prose UI.
- Token usage tracking and cost estimates.
- Export to Markdown/TXT/EPUB.
- Revision history / snapshots.
- Background generation and interruption recovery.
- App UI tests and ViewModel tests.
- Real-provider smoke test tooling.

## Implementation Roadmap

### Phase 1: Foundation - Done

Goal: establish the core domain, prompts, LLM abstraction, and agent pipeline.

Done:

- Project scaffolding.
- Core models.
- LLM client abstraction.
- Prompt templates.
- Agent framework.
- Core tests.

### Phase 2: Persistence And Settings - Done

Goal: make generated work survive app restarts and store provider configuration.

Done:

- Room database.
- Novel/chapter/scene repositories.
- DataStore generation settings.
- Manual app container.

Remaining cleanup:

- Add repository tests.
- Consider encrypted storage for API keys before serious distribution.

### Phase 3: Compose MVP Workspace - Done

Goal: make the pipeline usable from Android UI.

Done:

- Library workspace.
- Outline generation/view.
- Chapter and scene selection.
- Chapter synopsis generation.
- Scene prose generation.
- Bible viewer.
- Provider/model settings.
- Run status/error display.

Known weakness: this is a generation workbench, not yet a satisfying writing editor.

### Phase 4: Human Editing Loop - Done

Goal: honor the core product promise that the writer can edit each level before proceeding.

Done:

- Editable outline screen.
- Save edited `NovelOutline` back to `Novel`.
- Editable chapter synopsis and scene breakdown screen.
- Save edited `ChapterSynopsis` and update scene shells without destroying existing scene prose.
- Editable scene prose screen.
- Save edited scene text and recalculate word count.
- Generation commands use edited content as the source of truth.

Acceptance criteria:

- A user can generate an outline, edit it, then generate chapter synopsis from the edited outline.
- A user can edit scene breakdowns before generating prose.
- A user can edit generated prose before continuity extraction or export.
- Existing generated text is not overwritten unless the user explicitly regenerates.

### Phase 5: Review And Retry Workflow - Done

Goal: make review results useful instead of decorative.

Done:

1. Persist review score, issues, suggested fixes, pass/fail, retry count, and decision status separately from legacy review notes.
2. Add review panels for synopsis/prose compliance in the Draft workspace.
3. Add actions: accept, retry with feedback, edit manually, mark approved.
4. Implement max retry policy in the ViewModel.
5. Show failed review state clearly in chapter/scene lists.
6. Block Bible updates when scene review fails.

Acceptance criteria:

- Low-scoring outputs do not silently look complete.
- User can decide whether to accept, edit, or regenerate.
- Retry uses review feedback as context.

### Phase 6: Bible Editor And Continuity Control

Goal: make the Novel Bible trustworthy and user-correctable.

Tasks:

1. Add Bible editor tabs for characters, locations, timeline, world rules, and themes.
2. Add create/update/delete operations for Bible entries.
3. Track source chapter/scene for extracted facts.
4. Detect likely conflicts during Bible merge.
5. Add a conflict resolution UI.
6. Add relevance preview for what Bible entries will be sent to an agent.

Acceptance criteria:

- User can correct wrong extracted facts.
- Newer prose facts do not blindly erase important user-authored canon.
- Agent context can be inspected before generation.

### Phase 7: Streaming And Generation Queue

Goal: make long generation feel responsive and recoverable.

Tasks:

1. Wire `streamScene` into Compose.
2. Save incremental scene drafts safely.
3. Add cancellation.
4. Add queue for selected chapter or selected scene range.
5. Reset interrupted `GENERATING` scenes to a recoverable state on app start.
6. Record pipeline events with timestamps.

Acceptance criteria:

- User can watch prose stream in.
- User can cancel without corrupting saved state.
- App restart does not leave scenes permanently stuck as `GENERATING`.

### Phase 8: Export, Revision History, And Metrics

Goal: make the app useful after drafting.

Tasks:

1. Export manuscript to Markdown and TXT.
2. Add EPUB export if scope allows.
3. Add revision snapshots for outline, synopsis, scenes, and Bible.
4. Track token usage by provider/model/agent.
5. Add cost estimates.
6. Add project stats: word count, chapter progress, generated vs edited scenes.

Acceptance criteria:

- A complete novel can leave the app in a usable format.
- User can recover earlier versions.
- User can understand generation cost.

### Phase 9: Tests And Hardening

Goal: stop accidental breakage.

Tasks:

1. Add ViewModel tests for selection and generation state transitions.
2. Add repository tests.
3. Add Compose UI smoke tests for the main tabs.
4. Add fake LLM pipeline for deterministic UI tests.
5. Add real-provider manual smoke test behind local-only configuration.
6. Add lint/static checks to CI when CI exists.

Acceptance criteria:

- Core build and tests pass with `./gradlew build`.
- UI state changes are covered without hitting real LLM APIs.
- Provider request formatting remains covered by MockWebServer tests.

## Edge Cases To Handle

| Case | Required behavior | Current status |
| --- | --- | --- |
| Invalid JSON from LLM | strict parse, code-fence fallback, then error/retry | partially implemented in prompts; retry workflow incomplete |
| Context overflow | BibleFilter enforces token budget and previous scene ending is capped | partially implemented |
| Review score too low | expose issues and allow retry/edit/accept | incomplete |
| Bible conflict | preserve user canon, flag conflict, let user resolve | incomplete |
| Large Bible | relevance scoring and token budget | partially implemented |
| Interrupted generation | recover scene status on restart | incomplete |
| Regeneration | do not destroy user edits without explicit confirmation | incomplete |
| Missing API key | block generation with clear error | implemented |

## Verification Commands

Use these before committing behavior changes:

```bash
./gradlew :app:assembleDebug
./gradlew build
```

Use targeted commands while iterating:

```bash
./gradlew :core:agent:test
./gradlew :core:llm:test
./gradlew :core:prompt:test
```

Manual smoke path:

1. Create a novel.
2. Save provider settings with a real key.
3. Generate outline.
4. Generate synopsis for chapter 1.
5. Generate scene 1.
6. Confirm scene text is saved.
7. Confirm Bible entries appear.
8. Restart app and confirm persisted state is still available.

## Product Priorities

The next highest-value work is Phase 5: review and retry. The editor now exists; the next risk is that weak generations still look finished instead of becoming actionable review states.

Do not overbuild infrastructure before that. The real product risk is not whether the architecture is fancy enough. The risk is whether a writer can control the generated novel without fighting the app.
