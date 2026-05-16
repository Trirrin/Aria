# Function-Call Conversational Workflow Plan

## Implementation Status

Last updated: 2026-05-16.

Core judgment: the first usable conversation-first loop is implemented, but the entire plan is not fully complete.

Done:

- App opens into `Conversation` plus `Settings`; the old tab-first workflow is no longer the primary entry point.
- Interaction routing uses provider-native tool calls for background, outline, chapter plan, scene draft, accept, reject, revise, and clarification.
- Structured artifact emission uses provider-native tool calls for background, outline, chapter synopsis, scene review, and Bible update proposals.
- Scene prose remains raw text, not JSON-wrapped.
- Background, Outline, Chapter Plan, Scene Draft, and Canon Update appear as pending approvals before commit.
- Accepting a Scene Draft saves scene text first, then creates a separate Canon Update approval before writing Bible canon.
- Room schema includes persistence tables for conversation sessions, pending approvals, and tool-call audit records.
- `NovelWorkspaceViewModel` now restores the latest persisted conversation session, pending approval, active tool call, audit history, and chapter/scene selection context after process restart.
- `NovelWorkspaceViewModel` now persists interaction-tool audit records and exposes audit history through conversation UI state.
- Repository round-trip tests cover conversation, approval, and audit records.
- Focused tests and full Gradle build pass.

Partially done:

- Bible update proposals are approval-gated, but conflict-resolution workflow is not fully converted into conversation tools.
- Legacy direct generation buttons and overwrite dialogs still exist for some non-conversation paths.
- Restart recovery restores persisted conversation state, but in-progress generation jobs still cannot resume from the middle of a provider call.

Not done:

- Natural-language chapter add/delete/reorder and scene add/delete/reorder functions.
- High-risk approval flow for structural edits.
- Recovery/resume of in-progress generation jobs.
- Complete UI automation for the conversation-first workflow.

Verification completed:

```bash
./gradlew :core:llm:test :core:agent:test :core:prompt:test :app:testDebugUnitTest :data:testDebugUnitTest
./gradlew :app:assembleDebug
./gradlew build
```

Remaining implementation priority:

1. Replace leftover overwrite dialogs with generic pending approvals.
2. Add structure-edit tools for chapter and scene add/delete/reorder with high-risk approvals.
3. Add Bible conflict-resolution tools and approval UI.
4. Add current-UI Compose/instrumentation coverage.
5. Decide and implement the recovery strategy for interrupted generation jobs, likely explicit restart/retry rather than pretending suspended provider calls can resume in place.

## Goal

Replace the tab-first authoring flow with a single conversation-first interaction agent while keeping the structured writing pipeline underneath it.

The app opens into Conversation. Aside from Settings, the interaction agent is the only user-facing entry point. The user describes intent in natural language, reviews generated artifacts, approves or rejects important steps, and keeps writing without learning internal workflow concepts.

This is not a chat wrapper. Conversation is the control layer. The existing artifact model, repositories, bounded context, Bible filtering, review, and generation services remain the quality layer.

## Core Rule

No user-facing or workflow-planning LLM should return a free-standing JSON blob as text for the app to parse as an action or artifact.

Every structured LLM result must be represented as a provider-native function/tool call:

```text
natural language
  -> LLM selects or emits one allowed function call
  -> app validates function name and typed arguments
  -> ViewModel/domain tool executes
  -> proposal or commit result returns through app state
```

Function-call arguments can use JSON Schema because that is how providers describe tool inputs, but the app must treat them as tool arguments, not as assistant text that happens to contain JSON.

Scene prose is the exception because it is intentionally raw prose, not structured JSON.

## Product Shape

```text
User natural language
  -> Interaction Agent
  -> native function call selection
  -> ViewModel/domain tool execution
  -> proposal generation tool
  -> native function call artifact emission
  -> Artifact Preview
  -> User accept / reject / revise
  -> Commit through repositories
```

The interaction agent is a dispatcher. It does not own persistence and does not write canon.

It may:

- choose exactly one allowed workflow function call,
- ask clarification through an `askClarification` function,
- summarize tool results,
- explain what will happen next.

It must not:

- write directly to Room,
- overwrite user prose silently,
- update the Novel Bible without accepted source text,
- bypass approval for destructive or canon-changing operations,
- emit free-form JSON plans or artifacts.

## Function-Call Layers

### 1. Interaction Function Calls

These calls route user intent to safe workflow tools. They are selected by the user-facing interaction agent.

```text
createNovelBackgroundProposal(userRequest)
generateOutlineProposal(novelId?)
generateChapterSynopsisProposal(novelId?, chapterIndex?)
generateSceneTextProposal(novelId?, chapterIndex?, sceneIndex?)
acceptPendingApproval()
rejectPendingApproval()
revisePendingApproval(revisionFeedback)
selectNovel(novelId)
askClarification(message)
```

The ViewModel validates the function name, decodes arguments, resolves missing targets, and calls domain services. The LLM never calls repositories directly.

### 2. Artifact Emission Function Calls

These calls replace current prompt contracts that ask the model to output JSON text.

```text
submitNovelBackgroundProposal(...structured background fields...)
submitNovelOutlineProposal(...structured outline fields...)
submitChapterSynopsisProposal(...structured synopsis fields...)
submitSceneReview(...structured review fields...)
submitBibleUpdateProposal(...structured Bible update fields...)
submitBibleConflictReport(...structured conflict fields...)
```

Each generation agent must require the relevant artifact emission function call. The app converts validated function arguments into domain models.

Bad pattern:

```text
LLM returns text containing JSON -> parser tries to decode it
```

Required pattern:

```text
LLM calls submitNovelOutlineProposal(arguments) -> app validates arguments -> domain model
```

### 3. Internal Tool Results

Workflow tools return normal Kotlin result objects. They may contain structured payloads internally, but they are not model output JSON.

```text
WorkflowToolResult
- toolName
- status
- message
- proposalId?
- committedIds
- events
```

## User Workflow

### 1. Create A Novel

User says:

```text
I want to write a cyberpunk xianxia novel about a failed immortal in a corporate megacity.
```

Flow:

```text
Interaction Agent calls createNovelBackgroundProposal(userRequest)
  -> BackgroundAgent requires submitNovelBackgroundProposal(...)
  -> app validates proposal arguments
  -> show Background preview
  -> user accepts, rejects, or revises
  -> accepted background becomes the novel seed
```

The background proposal includes:

- title options,
- genre and tone,
- premise,
- world setup,
- protagonist and core cast seeds,
- major conflict,
- style guide seed,
- initial Bible candidates.

### 2. Generate Outline After Confirmation

After background acceptance:

```text
Interaction Agent or accepted-background flow calls generateOutlineProposal(novelId)
  -> OutlineAgent requires submitNovelOutlineProposal(...)
  -> app validates outline arguments
  -> show Outline preview
  -> user accepts, rejects, or revises
  -> accepted outline is saved
  -> chapter shells are created
```

Outline acceptance is the first point where chapter structure becomes persisted state.

### 3. Generate Chapter Plan

User says:

```text
Start generating chapter 3.
```

Flow:

```text
Interaction Agent calls generateChapterSynopsisProposal(novelId, chapterIndex)
  -> app resolves target novel and chapter
  -> app checks that outline exists
  -> ChapterAgent requires submitChapterSynopsisProposal(...)
  -> show Chapter Plan preview
  -> user accepts, rejects, or revises
  -> accepted synopsis is saved
  -> scene shells are created or updated without deleting prose
```

If the target chapter does not exist, the interaction agent asks for clarification or offers an outline revision function. It must not invent chapter state.

### 4. Generate Scene Prose

After chapter plan acceptance:

```text
Interaction Agent calls generateSceneTextProposal(novelId, chapterIndex, sceneIndex)
  -> prose streams into preview as raw text
  -> ReviewAgent requires submitSceneReview(...)
  -> show Scene Draft and Review
  -> user accepts, rejects, or revises
  -> accepted scene text is saved
  -> Bible update proposal may be generated
```

Scene text is not JSON and should not be wrapped in JSON. Review and Bible extraction must use function calls.

### 5. Canon Update

Only accepted source text can produce canon changes.

```text
accepted scene text
  -> BibleAgent requires submitBibleUpdateProposal(...)
  -> show Canon Update preview
  -> user accepts, rejects, or resolves conflicts
  -> accepted update is merged into Bible
```

Drafts, rejected proposals, and failed generations are not canon.

## Required UI Model

The main screen is Conversation plus Artifact Preview.

```text
Top: current novel and workflow status
Middle: chat transcript and assistant actions
Side/bottom: artifact preview and pending approval
Bottom: natural language input
```

Phone may use a bottom sheet or dedicated review screen. Tablet can show chat and preview side by side.

Do not expose internal pipeline names as primary UX. User-facing labels are:

```text
Background
Outline
Chapter Plan
Scene Draft
Review
Canon Update
```

## Data Model Additions

### ConversationSession

```text
ConversationSession
- id
- novelId?
- messages
- activeToolCall?
- createdAt
- updatedAt
```

### PendingApproval

```text
PendingApproval
- id
- novelId
- targetType
- targetId?
- actionName
- previewTitle
- previewText
- proposedPayload
- riskLevel
- requiredBeforeCommit
- createdAt
```

`proposedPayload` should be a typed domain payload where possible. If persistence needs serialized storage, serialize the typed payload after validation. Do not store unvalidated model-output text as source of truth.

Suggested target types:

```text
NOVEL_BACKGROUND
OUTLINE
CHAPTER_SYNOPSIS
SCENE_TEXT
BIBLE_UPDATE
REGENERATION
OUTLINE_STRUCTURE_CHANGE
```

Risk levels:

```text
LOW: creates a proposal only
MEDIUM: writes a new artifact without overwriting existing user work
HIGH: overwrites, deletes, reorders, or changes canon
```

### ToolCallAudit

```text
ToolCallAudit
- id
- sessionId
- novelId?
- functionName
- argumentSummary
- resultStatus
- resultMessage
- createdAt
```

Audit logs should not contain API keys or full unpublished prose unless the user explicitly exports history.

## Tool Layer

Expose controlled functions to the interaction agent. These functions call ViewModel or domain services, not DAOs directly.

### Novel Functions

```text
createNovelBackgroundProposal(userRequest)
acceptNovelBackground()
reviseNovelBackgroundProposal(revisionFeedback)
selectNovel(novelId)
```

### Outline Functions

```text
generateOutlineProposal(novelId?)
acceptOutlineProposal()
reviseOutlineProposal(revisionFeedback)
extendOutlineProposal(revisionRequest)
```

### Chapter Functions

```text
generateChapterSynopsisProposal(novelId?, chapterIndex?)
acceptChapterSynopsisProposal()
reviseChapterSynopsisProposal(revisionFeedback)
```

### Scene Functions

```text
generateSceneTextProposal(novelId?, chapterIndex?, sceneIndex?)
acceptSceneTextProposal()
reviseSceneTextProposal(revisionFeedback)
queueBlankScenesFromChapter(novelId?, chapterIndex?)
queueBlankScenesFromSelection()
```

### Bible Functions

```text
previewBibleUpdateFromAcceptedScene(sceneId)
acceptBibleUpdate()
rejectBibleUpdate()
resolveBibleConflict(conflictId, resolution)
```

### Safety Functions

```text
createRevisionSnapshot(targetType, targetId)
restoreRevisionSnapshot(snapshotId)
requestOverwriteApproval(targetType, targetId)
cancelPendingApproval()
```

## Approval Rules

Approval is mandatory for:

- saving a generated background as the novel seed,
- saving or replacing an outline,
- changing chapter count or chapter order,
- saving chapter synopsis and scene breakdowns,
- saving scene prose,
- overwriting any non-blank user-owned prose,
- applying Bible updates,
- resolving canon conflicts,
- deleting or reordering chapters or scenes.

Approval is not mandatory for:

- interaction-agent function selection,
- non-persistent draft proposals,
- summaries of existing artifacts,
- read-only context inspection.

## Migration Plan

### Phase 1: Function-Call Foundation

Goal: make provider-native function calling the only structured LLM output path.

Tasks:

1. Extend `LlmRequest` and `LlmResponse` with provider-neutral tool/function types.
2. Implement OpenAI function-call mapping.
3. Implement Anthropic tool-use mapping.
4. Add validation for exactly one required function call where the agent contract requires it.
5. Remove free-form JSON action planners.

Acceptance criteria:

- Interaction agent selects workflow functions through native tool calls.
- Unknown function names are rejected.
- Multiple function calls are rejected unless the caller explicitly allows batching.
- No workflow action depends on parsing assistant text as JSON.

### Phase 2: Proposal And Approval Foundation

Goal: every important generated artifact becomes a proposal before commit.

Tasks:

1. Add generic `PendingApproval` state.
2. Replace narrow overwrite dialogs with generic approval UI.
3. Add typed proposal payloads for background, outline, synopsis, scene text, and Bible updates.
4. Add accept, reject, and revise actions.
5. Keep revision snapshots before high-risk commits.

Acceptance criteria:

- Generated artifacts can be previewed without being committed.
- Rejecting a proposal leaves saved work unchanged.
- Accepting a proposal commits through repository boundaries.

### Phase 3: Background And Outline Function Calls

Goal: complete the first creation loop end to end.

Tasks:

1. Add `submitNovelBackgroundProposal` emission function.
2. Convert background generation away from text JSON parsing.
3. Add `submitNovelOutlineProposal` emission function.
4. Convert outline generation away from text JSON parsing.
5. Accept outline and create chapter shells without deleting existing prose.

Acceptance criteria:

- User can describe a novel in chat.
- Background proposal is emitted by function call.
- Outline proposal is emitted by function call.
- Accepted outline becomes source of truth.

### Phase 4: Chapter And Scene Function Calls

Goal: make chapter and scene generation conversational and approval-gated.

Tasks:

1. Add `submitChapterSynopsisProposal` emission function.
2. Convert synopsis generation away from text JSON parsing.
3. Stream scene prose as raw prose preview.
4. Add `submitSceneReview` emission function.
5. Keep scene text out of canon until accepted.

Acceptance criteria:

- User can say `generate chapter 3` and get a Chapter Plan preview.
- User can accept the plan and generate scenes.
- Scene drafts do not overwrite existing prose without approval.
- Review is structured through function call, not output JSON.

### Phase 5: Bible And Continuity Function Calls

Goal: move continuity and canon changes behind explicit proposals.

Tasks:

1. Add `submitBibleUpdateProposal` emission function.
2. Add `submitBibleConflictReport` emission function.
3. Generate Bible updates only from accepted scene text or explicit user edits.
4. Show Canon Update preview before merge.
5. Add conflict resolution functions.

Acceptance criteria:

- Bible updates never happen from rejected drafts.
- Bible extraction does not parse model-output JSON text.
- Canon conflicts wait for user resolution.

### Phase 6: Structure Editing

Goal: support natural language structure changes safely.

Tasks:

1. Add chapter add/delete/reorder functions.
2. Add scene add/delete/reorder functions.
3. Make structure edits high-risk approvals.
4. Preserve existing prose when structure changes.
5. Detect stale shells and show cleanup proposals instead of deleting silently.

Acceptance criteria:

- User can add, rename, or reorder chapters through conversation.
- Existing prose survives unless deletion or replacement is explicitly approved.

### Phase 7: Persistence And Audit

Goal: make the conversational workflow reliable across restarts.

Tasks:

1. Persist conversation sessions.
2. Persist pending approvals.
3. Recover in-progress generation jobs or mark them safely resumable.
4. Add audit history for function calls and approvals.
5. Add tests for proposal, approval, rejection, revision, and commit semantics.

Acceptance criteria:

- App restart does not lose pending approvals.
- User can inspect what the assistant did and why.
- Tests prove rejected proposals do not mutate saved artifacts.

## Testing Plan

Add tests for:

- provider-neutral function-call request formatting,
- OpenAI function-call parsing,
- Anthropic tool-use parsing,
- interaction-agent function-call selection fixtures,
- artifact emission function-call fixtures,
- unknown and duplicate function-call rejection,
- approval requirement decisions,
- proposal commit and rejection semantics,
- scene generation without premature Bible update,
- structure changes preserving existing chapters and scenes,
- process restart with pending approval,
- natural language chapter generation path.

Focused commands:

```bash
./gradlew :core:llm:test
./gradlew :core:agent:test
./gradlew :core:prompt:test
./gradlew build
```

## Non-Negotiable Rules

1. User-owned work is never overwritten without explicit approval.
2. The interaction LLM never writes directly to persistence.
3. Free-standing model-output JSON is not a workflow contract.
4. Structured model results must be function calls.
5. Proposals are not canon.
6. Accepted artifacts become the source of truth.
7. Bible canon changes only after accepted source text or explicit user edit.
8. Conversation UI hides pipeline complexity, but code keeps structured workflow boundaries.
9. Do not solve continuity by dumping the entire novel into prompts.

## First Implementation Slice

Start small:

```text
Chat UI
  -> interaction agent selects createNovelBackgroundProposal function call
  -> BackgroundAgent emits submitNovelBackgroundProposal function call
  -> preview Background approval
  -> accept Background
  -> generateOutlineProposal function call
  -> OutlineAgent emits submitNovelOutlineProposal function call
  -> preview Outline approval
  -> accept Outline
```

Do not rebuild every tab. First prove the `Natural language -> Function call -> Proposal -> Approval -> Commit` loop. Once that loop is solid, chapter, scene, review, and Bible generation become normal extensions instead of special cases.
