# Xiao Shuo Optimization Plan

Audit date: 2026-05-16
Scope: token cost, prose quality, UX friction.

---

## Overall Judgment

```
Architecture skeleton is correct: module boundaries are clean, the bounded-context
promise is genuinely honored, BibleFilter + context trimming + tiered agents is
the real essence of this product — it has not drifted.

But: a few places are burning tokens silently, prose quality is held back by two
conservative design choices, and the 6-tab UI is workbench-brain on a phone.
```

---

## Token-Burning Issues (highest ROI)

### P0-1. `BibleFilter` is a fake filter — BUG-level

File: `core/agent/src/main/kotlin/com/trirrin/xiaoshuo/agent/BibleFilter.kt:125-165`

```kotlin
private fun extractKeywords(text: String): Set<String> {
    return text.split(Regex(...))
        .map { it.normalized() }
        .filter { it.length > 2 }   // any 3+ letter word gets in
        .toSet()
}

private fun String.containsAnyKeyword(keywords: Set<String>): Boolean {
    val normalized = normalized()
    return keywords.any { it in normalized }   // substring match
}
```

**Why it is garbage:**
- A 50-word scene synopsis produces ~30 keywords.
- Substring match means common words like `the / her / was / look` will match
  almost every character description, location entry, world rule, theme.
- Result: nearly all Bible entries get tagged "relevant", and the final cut is
  effectively just `take first N chars until budget runs out` — semantic
  relevance is zero.
- `worldRules.filter{...}.ifEmpty { bible.worldRules }` and
  `themes.filter{...}.ifEmpty { bible.themes }` make it worse: zero matches
  falls back to the full set.

**Fix:**
1. Keywords = capitalized proper nouns only + explicit `characterNames` /
   `locationNames` passed in by the caller.
2. Match by full normalized equality on name / alias, not substring.
3. Add simple TF-IDF or frequency-based scoring, sort by score, take top-K
   rather than collect-all-then-trim.
4. `worldRules` / `themes` zero-hit returns empty set, not the entire set.

**Expected impact:** 30-60% reduction in scene input tokens. Zero prose quality
loss (it was noise).

---

### P0-2. Prompt caching is not used

File: `core/llm/src/main/kotlin/com/trirrin/xiaoshuo/llm/anthropic/AnthropicLlmClient.kt:119-131`

Every agent invocation sends the full system prompt verbatim. Anthropic's
`cache_control` makes cache reads cost 10% of normal input price. Generating
20 chapters means this fixed overhead is paid 20 times.

**Fix:**
- Add `cacheableSystemPrompt: Boolean = false` to `LlmRequest`.
- In `AnthropicLlmClient`, when set, encode `system` as
  `[{"type": "text", "text": ..., "cache_control": {"type": "ephemeral"}}]`.
- Enable for outline / scene_expansion / continuity / review system prompts —
  they are constants.

**Expected impact:** 40-70% input-cost reduction across a long session.
Zero quality impact.

---

### P0-3. ChapterSynopsis re-sends novel-level context per chapter

File: `core/agent/src/main/kotlin/com/trirrin/xiaoshuo/agent/ChapterSynopsisAgent.kt`

Each chapter call resends `premise + majorPlotPoints + styleGuide`. 20 chapters
= 20 retransmissions. These are novel-level constants.

**Fix:**
- Move `premise + majorPlotPoints + styleGuide` into the cacheable system
  prefix (combined with P0-2).
- User prompt keeps only: this chapter brief + neighbor briefs + relevant
  bible + previous ending.

---

## Prose Quality — Two Bottlenecks You Set Yourself

### P1-1. ReviewAgent actively refuses prose-quality judgment

File: `core/prompt/src/main/kotlin/com/trirrin/xiaoshuo/prompt/ReviewPrompt.kt:35-44`

```
2. Do NOT critique writing quality, style, or prose. ONLY check compliance with the directive.
```

**Why this hurts:** the actual failure mode of AI-generated prose is not
"missed beat", it is telling-not-showing, purple prose, stiff dialogue,
repetitive sentence shapes. The review pipeline does not touch any of this,
so a "passed" scene is not necessarily a good scene.

**Fix:**
- Add an optional `qualityScore` (1-10) alongside `complianceScore`.
- Keep compliance as the hard gate; quality is a soft signal.
- Both pass -> continuity. Compliance passes but quality low -> mark
  `NEEDS_POLISH`, surface to user as "accept / polish-regen".
- Retry feeds quality issues back as well.

**Cost:** ~100-200 extra tokens per review output.
**Return:** visibly more readable output.

---

### P1-2. Missing style anchor / few-shot example

File: `core/prompt/src/main/kotlin/com/trirrin/xiaoshuo/prompt/SceneExpansionPrompt.kt`

Only abstract rules ("SHOW DON'T TELL") with no concrete style anchor. The
output collapses into one generic "AI literary voice" regardless of what the
user sets in `narrativeVoice / proseStyle`.

**Fix — add `REFERENCE PROSE STYLE:` block (80-150 words) to user prompt:**
- Cold start: a static genre-appropriate sample.
- Once running: the opening 100 words of the user's most recently approved
  scene (ViewModel already has `approved` provenance — wire it through).
- Optionally: a novel-level `styleSample` field the user can paste into
  Settings.

**Cost:** ~150 tokens per scene call.
**Return:** prose finally sounds like the requested voice.

---

### P2-1. Mid-range context gap

Scene generation sees `previousSceneEnding` + Bible only. Three scenes ago's
tension does not survive unless it became a Bible fact — but the Bible stores
facts, not narrative tension ("last night A said something B is still angry
about").

**Fix:** add `RollingChapterSummary`:
- After each scene, ask review/continuity to also emit a <=80-word "tension
  summary so far in this chapter" (emotional trajectory, unresolved beats).
- Next scene call injects it. Cheaper than Bible, 80% cheaper than the full
  chapter text.

---

## UX — Workbench Brain on a Phone

### P1-3. 6 tabs is too heavy

Current: `Library / Outline / Draft / Bible / History / Settings`.
A writer's high-frequency surface is just `Draft`. `ScrollableTabRow` forces
horizontal swipe on phone to reach Bible — breaks "main path one tap away".

**Proposed consolidation to 3 bottom tabs + sub-screens:**

```
[ Write ]              [ Material ]              [ Settings ]
  ├─ Library             ├─ Outline (view+edit)
  ├─ Draft (main)        ├─ Bible
  └─ History             └─ Stats
```

Rationale: Outline is read-mostly / occasional-edit, not a daily panel.
History and Stats combine cleanly.

---

### P3-1. No "what's next" guidance

New novel -> user faces a row of tabs without knowing whether to tap Outline
or Draft. `TopBar` already has `MetricStrip` — add a `NextActionChip`:

```
status = DRAFTING_OUTLINE       -> "Next: generate outline"  [button]
status = OUTLINE_COMPLETE       -> "Next: generate chapter 1 synopsis" [button]
chapter[0].synopsis == null     -> same
```

Chip executes the action directly. Zero tab navigation, zero learning.

---

### P3-2. Settings has too many choices

Provider / base URL / 5 per-agent model dropdowns. Most users do not know
what to fill in.

**Smart defaults + inference:**
- API key prefix `sk-ant-` -> auto Anthropic; `sk-` -> OpenAI.
- 5 agents default to one shared model. "Advanced mode" expands per-agent.
- `baseUrl` hidden by default; revealed by "Custom endpoint" toggle.

---

### P3-3. Two monster files

- `MainActivity.kt` — 1843 lines.
- `NovelWorkspaceViewModel.kt` — 1770 lines.

Not a user-facing issue, but every future UI change is now a 1800-line
navigation problem. Split into `LibraryScreen.kt / DraftScreen.kt /
BibleScreen.kt / HistoryScreen.kt / SettingsScreen.kt`. This is paying down
debt, not inventing architecture.

---

## Small Annoyances

- `OutlinePrompt.kt:22` uses `${'$'}GENRE` literal placeholder, then
  `OutlineAgent.kt:23` does `.replace("$" + "GENRE", ...)`. Stop the gymnastics
  — drop GENRE into the user prompt instead, keep system prompt static
  (also makes it cacheable).
- `extractNames` is duplicated in three files:
  - `BibleFilter.kt:116`
  - `SceneExpansionAgent.kt:120`
  - `ChapterSynopsisAgent.kt:84`
  Extract to a `NameExtractor` singleton.
- `BibleFilter.trimToBudget` does not account for `relationships` field when
  estimating tokens, but the prompt does print it — budget is underestimated.

---

## Priority Matrix

| Priority | Item | Benefit | Effort |
|----------|------|---------|--------|
| P0 | Fix `BibleFilter` substring match | token -30~60%, no quality loss | 0.5d |
| P0 | Anthropic prompt caching | token -40~70% (long session) | 0.5d |
| P0 | Move novel-level context into cached prefix | token | folded into P0-2 |
| P1 | Review quality dimension + retry feedback | prose quality up | 1d |
| P1 | 6 tabs -> 3 tabs + NextActionChip | UX up | 1d |
| P1 | Style anchor / user reference sample | prose voice | 0.5d |
| P2 | Rolling chapter summary | long-form coherence | 1d |
| P3 | Settings smart defaults + auto-detect | onboarding friction | 0.5d |
| P3 | Split MainActivity / ViewModel files | maintainability | 1d |
| Cleanup | Dedupe `extractNames` / fix `$GENRE` hack / fix `relationships` budget | code health | <0.5d |

---

## Recommended Order

Start with **P0-1 and P0-2 together** — half a day of work, run two chapters,
the API bill drop is visible immediately. Then P1-1 (review quality) before
P1-3 (UI), because quality wins keep momentum and UI rework is heavier.

Each item is independent — pick them off one at a time.
