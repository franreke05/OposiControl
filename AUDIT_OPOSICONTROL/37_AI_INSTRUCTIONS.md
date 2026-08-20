# AI Instructions

## Files found

- `AGENTS.md` at repository root: current governing instructions supplied by project.
- `CODEX_CONTEXT.md`: AI context, dated 2026-06-18.
- `.claude/`, `.ai/`, `.superpowers/`: directories present; tracked instruction/plan material was inspected where relevant.
- `docs/superpowers/plans/2026-08-01-oposibots-user-admin-crud.md`: historical implementation plan, not a current agent rule.

## Effective rules

Read before writing, preserve dirty state, edit minimally, test before declaring done, no destructive Git/DB actions, exact UI measures, and use ponytail/minimal solution. User audit instructions override implementation rules for this turn and explicitly prohibit fixes.

## Contradictions

AI context is stale against current code (see C-002/C-004/C-005). No instruction file was allowed to authorize DB writes in this audit.
