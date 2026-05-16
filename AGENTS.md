# AGENTS.md

## Project Identity

This repository is `xiao_shuo`, an Android app for AI-assisted long-form novel writing.

The product is not a chat wrapper. It is a structured writing workbench built around:

- bounded context windows,
- hierarchical writing agents,
- user-editable story artifacts,
- a persistent Novel Bible for continuity.

Package: `com.trirrin.xiaoshuo`.

## Communication Rules

- Think and work in English.
- Report final user-facing summaries in Simplified Chinese when talking to Trirrin.
- Do not put Chinese text in code, comments, commit messages, identifiers, or test names.
- Be direct. If code is bad, say why and fix it.
- Criticize code, not people.
- Use Conventional Commits for commits:

```text
<type>(<scope>): <description>
```

Examples:

```text
feat(app): add scene editor
fix(agent): preserve edited scene text during regeneration
refactor(data): simplify novel graph loading
```

## Engineering Principles

1. Preserve user work.
   - Never overwrite generated or manually edited prose unless the user explicitly requested regeneration or replacement.
   - Treat outline, synopsis, scene text, and Bible entries as user-owned once saved.

2. Keep context bounded.
   - Do not solve continuity by shoving the whole novel into prompts.
   - Use `BibleFilter`, scene synopsis, previous scene ending, and style guide deliberately.

3. Prefer simple data flow.
   - Repository owns persistence.
   - ViewModel owns UI state and user actions.
   - Agent pipeline owns generation orchestration.
   - Compose renders state and sends events upward.

4. Do not invent architecture for sport.
   - The app currently uses a manual `AppContainer`, not Hilt.
   - Do not introduce DI/navigation frameworks unless the change actually pays for itself.

5. Backward compatibility matters.
   - Room schema and serialized model changes must be deliberate.
   - Do not break existing stored novels casually.

## Module Guide

```text
:core:model   Domain models only. No Android dependencies.
:core:llm     Provider-neutral LLM API types and clients.
:core:prompt  Prompt templates and output parsing.
:core:agent   Agent wrappers, Bible filtering/merging, pipeline.
:data         Room, DataStore, repositories.
:app          Compose UI and Android ViewModels.
```

## Current App State

Implemented:

- Novel creation and selection.
- Provider/model settings.
- Outline generation and display.
- Chapter synopsis generation for selected chapter.
- Scene prose generation for selected scene.
- Bible viewing.
- Room/DataStore persistence.

Not fully implemented:

- Editing outline/synopsis/scene prose.
- Bible editor.
- Review accept/retry workflow.
- Streaming scene UI.
- Export.
- Revision history.
- Token/cost tracking.
- UI/ViewModel tests.

## UI Rules

- Build the writing workbench, not a landing page.
- Prefer dense, readable, editor-like UI over marketing-style cards.
- Keep cards/panels simple with small radius.
- Do not nest decorative cards inside cards.
- Avoid giant hero text in tool screens.
- Make mobile layout usable; do not assume tablet width forever.
- All visible app text should be English unless the product explicitly gains localization.

## Persistence Rules

- `NovelRepository` is the persistence boundary for novels, chapters, and scenes.
- `GenerationSettingsRepository` is the persistence boundary for provider settings.
- API keys must not be logged.
- If adding fields to persisted entities, handle migration instead of pretending old installs do not exist.
- If updating chapter synopsis scene breakdowns, do not delete existing scene prose blindly.

## Agent Pipeline Rules

- Outline, synopsis, review, and continuity outputs should remain structured JSON.
- Scene prose should remain raw prose, not JSON-wrapped.
- Review failures should become actionable UI state, not just log messages.
- Continuity extraction should update the Bible only after the user has accepted or saved the scene text when that workflow exists.
- Newer generated prose may inform continuity, but user-authored Bible canon should win until explicit conflict resolution exists.

## Testing And Verification

Before committing substantial changes, run:

```bash
./gradlew :app:assembleDebug
./gradlew build
```

For focused backend changes, run relevant tests:

```bash
./gradlew :core:agent:test
./gradlew :core:llm:test
./gradlew :core:prompt:test
```

Add tests when changing:

- prompt parsing,
- LLM request formatting,
- Bible filtering/merging,
- generation state transitions,
- persistence behavior,
- edit/regeneration semantics.

## Commit Discipline

- Check `git status --short` before editing and before committing.
- Do not revert user changes unless explicitly asked.
- Commit only files relevant to the task.
- Use Conventional Commits.
- Keep generated build artifacts out of commits.

## High-Priority Next Work

The next product-critical feature is the human editing loop:

1. Editable outline with save.
2. Editable chapter synopsis and scene breakdowns with save.
3. Editable scene prose with save.
4. Generation uses edited content as source of truth.
5. Regeneration never destroys user edits without confirmation.

Without this, the app is only a generator. With this, it becomes a writing tool.
