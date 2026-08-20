# OPOSICONTROL AUDIT HANDOFF

## 1. Executive status

Verdict: `AUDIT_PARTIAL` on 2026-08-19. Repository inspection is complete at source/config/history level. Supabase runtime and visual UI runtime are `NOT_VERIFIED`. No functional source, Gradle or database changes were made.

Main risks: disabled panel login, severe schema/migration drift, non-atomic PDF import, duplicated legacy/V1 contracts, and global build failure in Audit.

## 2. Repository

- Branch `main`, HEAD `465c911`.
- Remote `origin`; branch ahead 9 of origin/main.
- Pre-existing dirty files: backend Gradle and two OposiBots auth files; untracked `.logs/`, `.mcp.json`.
- Modules: composeApp, core common/network/database/ui/testing, domain, data, 17 features, backend ktor.
- Targets: Android, iOS arm64/simulator arm64, Desktop JVM. No JS/Wasm target configured.

## 3. What OposiControl actually is

It is a Kotlin Multiplatform administrative application backed by a Ktor JVM service. It manages exam/content catalogs and operational domains. It is not only a frontend: the backend owns service-role Supabase access, admin routes, consumer app routes, OposiBots auth, and PDF extraction.

## 4. Architecture

Compose feature → MVI ViewModel → domain use case → repository interface → Ktor/data repository → Ktor route → gateway → Supabase/PostgREST/Auth/Storage. SQLDelight is local persistence for session, settings and news drafts. No queue/worker is present.

## 5. Tech stack

Kotlin 2.3.20, AGP 8.11.2, SDK 36/min 30, Compose 1.10.3, Material3 alpha, Ktor 3.2.3, Koin 4.1, coroutines 1.10.2, serialization 1.9, datetime 0.7.1, SQLDelight 2.1, PDFBox 3.0.2, Auth0 JWT 4.4.0, Lettuce 6.5.5, JDK 21, Logback 1.5.16 in pre-existing dirty change.

## 6. UI

Desktop uses `AppRoot`/`AdminShell`/`AdminScaffold`; Android/iOS use `MobileContentRoot` and `MobileAdminPanel`. Shared design system includes glass theme, Manrope, exact spacing tokens, admin tables, forms, dialogs, async/empty/error states. UI is coherent but responsive/accessibility runtime is unverified.

Critical behavior: `TEMPORARILY_DISABLE_LOGIN = true`; panel opens dashboard without AuthScreen. Backend routes still require auth.

## 7. Existing screens

Auth, Dashboard, Users, Roles, Specialties, Communities, Tags, News Types, News, Exams, Questions & Resources, Exam Access, Store, Tickets, Support, Moderation and Audit. All are in `AdminSection.entries`; Auth is bypassed by default. Audit currently blocks global Android compilation at `AuditViewModel.kt:35`.

## 8. Navigation

State-only navigator, no URL/deep-link/history/permission filtering. Dashboard quick actions target questions, news, exams, store, tickets or audit. All admin sections are visible by default. Mobile navigation is a different composition, not a 17-section equivalent.

## 9. Backend

`Application.module` supports `combined` and `consumer` profiles. It installs JSON, StatusPages and bearer auth. Gateways: legacy content/news/admin, tag search, V1, Admin V1, App V1 and OposiBots user admin. Service role is read from environment and remains backend-side.

## 10. Endpoints

Full grouped catalog is in `11_ENDPOINT_CATALOG.md`. Important families: `/content/*` legacy admin, `/v1/*` public catalog, `/api/v1/admin/*` admin V1, `/api/v1/app/*` JWT consumer, `/v1/auth/*` OposiBots auth, `/api/v1/admin/oposibots/usuarios` admin CRUD.

## 11. Supabase / database

Runtime DB was not accessible through a callable MCP in this session. Source and docs mention Spanish plural UUID V1 tables, older singular/int tables, app tables, import staging, support, commerce and search. Rows, current policies, buckets and current schema are unknown.

## 12. Security

`.env` is local-only/ignored; `.env.example` contains placeholders. No real committed secret found in safe scan. Admin bearer can be static token or admin-role JWT. OposiBots auth has validation, rate limiting, captcha configuration and OAuth flows. Main operational risk is disabled panel login plus broad service-role backend.

## 13. Content management

Exams, questions/options, resources, specialties, tags and communities have UI and backend slices. Lifecycle draft/published/archived exists in models/routes. Persistence and DB integrity are only partly confirmed because schema families coexist.

## 14. News

Editor, assets, types, layers/canvas models and lifecycle use cases exist. Layer SQL is documented as an extension and mapping has known drift. Preview, history and publish notifications are not verified.

## 15. Support / OposiTester

Support conversations/messages/internal notes, reports, moderation actions, tickets and wallet transactions exist in UI/API. OposiTester client is not in this repository. Consumer feedback endpoint connects to admin feedback list/resolver; full external ticket flow is unknown.

## 16. PDF bulk import

Two distinct systems exist. Backend PDFBox route imports ráfaga questions directly. UI parser imports text Aiken/GIFT and sends generated questions. The staging API is a third, not visibly wired to PDF route. Overall capability is `PARTIAL`.

## 17. PDF parser

Backend format uses `--- PREGUNTA ---`, required `TITULO`, A-D and `RESPUESTA`; optional reasoning/source/tags. UI supports Aiken and GIFT. PDF extraction is plain text via PDFBox. OCR, AI, tables, images, page-aware parsing and arbitrary exam variants are missing/unknown.

## 18. Exam types found

Verified model values: `rafaga` and `inmersivo`; access `gratuito` and `premium`; status draft/published/archived. No evidence of official/simulation/topic/custom/psychotechnical variants beyond generic fields.

## 19. Validation / duplicate handling / transactions

UI text parser validates four options, answer, blank input, duplicate statements and max 50. PDF route validates file size, title, A-D and answer. PDF route has no visible preview/hash/transaction and loops creates; failure may leave partial inserts. Exact DB behavior unknown.

## 20. File storage

Code supports local picker → Ktor multipart → Supabase Storage for covers/resources/news assets. SQL docs define `exam-resources`. Current bucket/policy/object state is not verified.

## 21. Testing

Backend tests pass; exams debug unit tests pass. Full `test` fails at `features/audit/.../AuditViewModel.kt:35:49` with unresolved `System`. No PDF fixture/E2E/UI suite found.

## 22. Build

Official commands are in README and `30_BUILD_AND_RUNTIME.md`. Backend test task passes. Full build/test status is partial due Audit compile failure. No fix was attempted.

## 23. OposiBot integration

Auth `/v1/auth/*`, consumer `/api/v1/app/*`, public `/v1/*`, and admin OposiBots user CRUD are real source contracts. No OposiBot client is present; external consumers and live compatibility are unknown.

## 24. Existing docs

README/AGENTS current; CODEX_CONTEXT and old audit useful but stale; admin catalog docs accurately reveal legacy/V1 split; Supabase SQL docs are not a single migration source of truth; August plan records historical direct production operations.

## 25. Existing AI agents/instructions

Root `AGENTS.md` governs. It requires read-before-write, exact UI dimensions, no destructive operations and proportionate verification. This audit request further forbade fixes and DB writes. Historical plan/context are not higher-priority instructions.

## 26. Contradictions

See `33_CONTRADICTIONS.md`: singular/int vs plural/UUID schema, stale wiring docs, `/content` vs `/api/v1/admin`, stale AI context, historical production claims vs unavailable runtime, roles read-only mismatch, and login bypass.

## 27. Unknowns

See `34_UNKNOWNS.md`: live DB/RLS/RPC/storage, deployed env, UI runtime, external OposiBot consumer, PDF product formats and transaction semantics.

## 28. Risks

P1: disabled login, schema drift, non-atomic import, global build failure, service-role concentration. P2: parser limitations, legacy contracts, news/commerce persistence, local settings, missing pagination/error contract, platform parity. P3: accessibility/design/documentation follow-up. No P0 confirmed from repository evidence.

## 29. Technical debt

Canonical migration history, route consolidation, DTO/error versioning, removal of legacy paths, atomic import job/staging, parser fixtures, observability, and platform parity are the dominant debt clusters.

## 30. Functionality matrix

See `39_FUNCTIONALITY_MATRIX.md`. Content, exams, questions, news, support, users and moderation have code slices; PDF is partial; subscriptions and notifications are missing/partial; DB/runtime evidence is not verified.

## 31. Product decisions required from user

Only decisions code cannot answer:

1. Which admin roles may view/edit/publish/delete each domain?
2. Must panel login be mandatory in every non-local environment, and which identity provider/session policy is authoritative?
3. Which exact PDF templates and exam variants must be supported, including OCR/scanned documents, images and tables?
4. Should import be all-or-nothing, staged with manual correction, or allow partial acceptance? What blocks publication?
5. What is the duplicate policy: skip, merge, replace, or fail; what constitutes a duplicate?
6. Who may publish/unpublish/schedule news and exams, and is editorial history/notification required?
7. Which support fields and SLA workflow are mandatory (priority, assignment, attachments, client reply, resolution)?
8. Which schema family is the intended canonical production contract and what is the approved migration ownership process?

## Handoff use

Read `00_EXECUTIVE_SUMMARY.md` first, then `05_ARCHITECTURE.md`, `11_ENDPOINT_CATALOG.md`, `14–18` for database/security, `22–28` for import, and `33–39` for contradictions/risks/matrix. Do not treat `DOCUMENTED_ONLY` as live verification.
