# CLEANUP_INVENTORY

Fecha: 2026-10-19. **Inventario sin borrar nada.** Cada acción requiere GO explícito del propietario.

El artefacto `https://claude.ai/artifact/7r2X4D3i2wd7kCLcQZgKr5` no es accesible para esta sesión (doc no compartido; el visor público solo muestra el título «Claude Docs»). **No se infiere su contenido**: pendiente de que el propietario pegue el texto o una captura.

## Worktrees OposiControl

| Worktree | Rama | SHA | Cambios sin commit | Recomendación | Riesgo |
|---|---|---|---|---|---|
| OposiControl | develop | 7fb9000 | 0 (+ `docs/audit-v1/` de esta sesión) | mantener | — |
| OposiControl-E6 | tmp/e6-release-blockers | a301ea7 | 0 | eliminar worktree y rama (ya en develop) | bajo |
| OposiControl-E7 | tmp/e7-release-runbook | 942122a | 0 | eliminar worktree y rama (ya en develop) | bajo |
| OposiControl-E6-Panel | feature/december-v1-control-panel | 29a019f | 0 | mantener (trabajo único) | alto si se borra |
| OposiControl-E7-Integration | integration/bot-backend-on-develop | cb50ee3 | 0 | mantener hasta tener candidato nuevo | medio |
| OposiControl-ExamSecurity | feature/december-v1-security-server | 88d0baf | 14 sin seguimiento (`e2-logs/`, `e2-v1-economy-logs/`, 12 `e2-v1-*-result.json`, `ops/local-exam/pw.tmp.mjs`) | archivar evidencia fuera del repo; añadir `e2-*` a .gitignore; **los JSON contienen tokens del stack local: nunca `git add`** | bajo |
| OposiControl-ExamServer | feature/december-exam-server | 69a3cb3 | 0 | eliminar tras confirmar (sin commits únicos frente a integración) | bajo |
| OposiControl-PhaseB-Server | feature/december-v1-server | 86f0da9 | 0 | **mantener — en uso por Bot** | alto |
| `../OposiControl-mut` | (no es repo ni worktree) | — | — | borrar tras revisar su `.mcp.json` | bajo |
| `.worktrees/phase0-jvm-desktop-foundation` | no registrado | — | solo `local.properties` | borrar | bajo |

Worktrees OposiBot (repo aparte, 20): **no tocar desde Control**. El worktree principal de OposiBot (`Versión-final-Oposibot`) tiene cambios sin commit (`.mcp.json`, `ENDPOINT_CATALOG.md`, `LoginStateHolder.kt`, nuevos ExamCover*): PREEXISTING_PROTECTED_CHANGE.

## Ramas

| Rama | Estado | Recomendación |
|---|---|---|
| tmp/e6-release-blockers, tmp/e7-release-runbook | fusionadas en develop | borrar (local) |
| feature/december-exam-server, feature/december-phase-b-server, feature/december-phase-b-security-server | sin commits únicos frente a integración | borrar local+remoto tras confirmar con Bot |
| feature/december-exam-security-server (f945a22, 1 commit), feature/december-v1-security-server (31 commits) | únicas | mantener |
| main | 33 detrás de develop | mantener |
| 11 ramas dependabot en origin | sin triaje | triar cuando CI corra en develop (RB-08) |
| `stash@{0}` «temporary rebase integration fixes» | desconocido | inspeccionar antes de decidir (riesgo medio) |

## Archivos temporales y builds

| Elemento | Recomendación | Riesgo |
|---|---|---|
| `backend-run.{out,err}.log`, `backend-restart.{out,err}.log` (raíz, ~35 KB, ignorados) | borrar | ninguno |
| `AUDIT_OPOSICONTROL/` vacío, `core/api/` (solo build), `features/roles/` vacío | borrar | bajo |
| `composeApp/oposicontrol-desktop.db` | mantener si se usa | bajo |
| Directorios `build/` (repo 37, E6-Panel 35, -mut 35; tamaño sin medir, probablemente varios GB) | `gradlew clean` en worktrees inactivos | ninguno |

## Docker / QA

| Elemento | Estado | Recomendación |
|---|---|---|
| `oposi-exam-local-db-1` (127.0.0.1:55432) | en marcha, creado 2026-10-19 07:33Z desde `OposiControl-PhaseB-Server/ops/local-exam/docker-compose.yaml` | **sin volumen** (PGDATA en la capa del contenedor): es la causa de la pérdida QA; añadir volumen nombrado (archivo de Bot) |
| `oposi-exam-local-auth-1`, `-mail-1` | parados | mantener |
| `oposilocal-*` (ensayo de producción, 10 días) + volúmenes `oposilocal_*` | parados | mantener hasta nuevo ensayo |
| 5 contenedores `gate-debug-*` | parados, creados sin `--rm`; uno salió minutos antes de la auditoría (posible sesión concurrente) | `docker rm` cuando ninguna sesión los use |
| Volúmenes `supabase_db_localstack`, `supabase_storage_localstack`, `oc-gh` | sin evaluar | no tocar |
| Snapshot `PhaseB-Server/ops/local-exam/.local/backups/db-prereset-20261019093118.sql` (1,1 MB) | existe | conservar |

## Secretos y archivos sensibles

| Elemento | Hallazgo | Acción |
|---|---|---|
| `OposiControl/.env` (ignorado) | `SUPABASE_URL` apunta a **MAIN** y contiene `SUPABASE_SERVICE_ROLE_KEY` → cualquier Ktor de desarrollo local tiene service_role de producción | RB-10: decisión del propietario (apuntar a stack local, rotar si el equipo es compartido) |
| `.gitignore` | ignora `*.keystore` pero no `*.jks`, `*.pem`, `*.key`, `*.p12`, `*.p8`, `secrets.json` | añadir patrones |
| `PhaseB-Server/ops/local-exam/.local/{secrets.json,tokens.json}` | ignorados, stack local | no publicar |
| Ficheros versionados | heurística: sin `-----BEGIN`; `eyJ`/`sb_secret_` solo fixtures falsos | OK; gitleaks no ejecutado aún sobre el historial completo |

## Configuraciones obsoletas

- `ops/monitoring/compose.yaml` (stack legacy con cAdvisor `privileged`) — confirmar si sigue vivo.
- KDoc `PrometheusMetrics.kt:116` y `openapi/foundation/foundation.yaml:158` dicen que sin token el scrape queda abierto (contradice E6).
- `docs/database/MIGRATION_STATE.md:32` vs `:34-37` contradictorios.
- `ops/local-exam/up.mjs:5` afirma persistencia en volumen (falso).
