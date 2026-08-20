# Git / History

## Estado inicial

- Branch: `main`.
- HEAD: `465c911e456e4263b51f3b9fb30cc3e2a8ee3f3d` — `chore(db): audit-driven performance/security hardening on production Supabase`.
- Remote: `origin https://github.com/franreke05/OposiControl.git`.
- `main` está `[ahead 9]` respecto a `origin/main`.
- Dirty preexistente: modificados `backend/ktor/build.gradle.kts`, `OposiBotsAuthGateway.kt`, `OposiBotsAuthRoutes.kt`; no rastreados `.logs/` y `.mcp.json`.
- `git diff --stat` inicial: 37 insertions, 6 deletions en los tres archivos backend.

## Eras detectadas

1. `644caf4` (2026-05-06): import inicial.
2. Mayo-junio: integración KMP/Ktor, Supabase, editor de noticias y subida de recursos.
3. `dcbcb75`/`624beab` (junio): backend V1 real y UX desktop.
4. `d89e200` (2026-07-01): importación de ráfagas desde PDF.
5. `b4d5b8b` (2026-07-30): auth segura OposiBots.
6. `c148b54`–`fcc2958` (2026-08-03): CRUD admin OposiBots y hardening.
7. `465c911`: auditoría de rendimiento/seguridad DB documentada.

## Ramas relevantes

`origin/main` (`6bcfdf8`), `origin/feat/oposibots-auth-secure` (`b4d5b8b`), `origin/feat/v1-real-data-desktop-ux` (`624beab`), `origin/codex/news-content-updates` (`3ae7078`), y dos ramas `agent/kmp-ktor-supabase-*`.

No se ejecutaron checkout, reset, staging, commit, push ni operaciones destructivas.
