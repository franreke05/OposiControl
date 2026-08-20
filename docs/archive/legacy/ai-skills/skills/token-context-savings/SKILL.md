---
name: token-context-savings
description: Tactical rules for minimizing token usage and context window pressure during any session in this repo — how to read, search, build, and report without flooding context. Use continuously, especially in long sessions, large builds, big diffs, or when working across many files. Complements project-context-map (which maintains durable docs); this skill governs moment-to-moment tool usage.
---

# Token & Context Savings Skill

## Purpose

Keep the context window lean during active work so long sessions stay coherent and don't hit compaction sooner than necessary. This is about *how* you use tools on every turn, not about maintaining project documentation — see `project-context-map` for that.

## When to use

Continuously, as a default posture in this repo. Pay particular attention when:
- a task will touch many files,
- a build, test, or Gradle task might produce a long log,
- a git diff or git log could be large,
- the session has already been running a while,
- a subagent's raw output would be large but only a conclusion is needed.

## Core rules

### Reading files
- Never read a whole file to answer a narrow question. Grep for the symbol/string first, then read only the surrounding range.
- For files you already read earlier in the session, don't re-read them — trust what's in context unless you edited them or suspect drift.
- For large generated files (lockfiles, `.csv` data files, build outputs), read a small sample or grep, never the whole thing.
- Don't open `build/`, `.gradle/`, `.idea/`, `node_modules/`, `.git/`, or platform build directories (`Pods/`, `DerivedData/`) — nothing there is source of truth.

### Searching
- Use Grep/Glob for "does X exist" or "where is X" questions instead of Read + manual scanning.
- Use `head_limit` / targeted globs to keep search results small; don't request unlimited output for broad patterns.
- Prefer `git diff --stat` or `git diff --name-only` to see the shape of a change before requesting the full diff.

### Running commands and builds
- Don't run full verbose builds (`./gradlew build`, full clean builds) when a scoped task will answer the question (`./gradlew :module:compileCommonMainKotlinMetadata`, `--quiet`).
- Pipe long-running command output through `tail`, `grep`, or `--quiet`/`-q` flags rather than dumping everything. Only show full output when the task specifically requires it (e.g. diagnosing a build failure needs the actual error block).
- For test runs, prefer scoping to the affected module/class over running the entire suite when iterating.

### Delegating work
- When a lookup requires broad, multi-step exploration (many files, uncertain location, "where does X happen across the codebase"), delegate to the Explore agent or a general-purpose subagent instead of doing many sequential greps yourself — the subagent's exploration tokens don't pollute your main context, only its summary does.
- Ask subagents to report back concisely (state a word/line budget in the prompt) rather than pasting raw findings.
- Don't duplicate a subagent's search in your own context after delegating it — wait for its result.

### Reporting back to the user
- Summarize outcomes; don't paste full file contents, full stack traces, or full build logs into chat unless the user needs to read the raw text (e.g. debugging a specific error).
- When multiple files changed, report file paths and line numbers rather than re-pasting the diffs.

### Session hygiene
- If `docs/AI_CONTEXT_MAP.md` / `docs/AI_TOKEN_INDEX.md` exist (see `project-context-map`), consult them before re-deriving architecture from scratch.
- Don't re-explain or re-summarize decisions already established earlier in the same conversation.
- Prefer incremental edits (Edit tool) over rewriting whole files (Write tool) when changing existing code — smaller diffs, less context churn.

## Anti-patterns to avoid

- Reading an entire module's source tree "to be safe" before making a one-line change.
- Cat-ing a whole log file when only the last 20 lines or the error block matters.
- Re-reading a file you already have in context out of caution.
- Running `git log` with no limit, or `git diff` on the whole repo when only one module changed.
- Spawning a subagent and then also independently redoing its search yourself.
- Pasting a full Gradle stack trace when the root cause is one line in the middle of it — extract and show that line plus a few lines of surrounding context instead.
