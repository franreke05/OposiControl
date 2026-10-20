# SEC-V1-01 · Expediente de intervención en MAIN

Proyecto: MAIN `ndiooyyqtaeysnedywer`. Migración: `supabase/migrations/20261023100000_security_content_admin_write_revoke.sql` (develop `2cab55b`). **Nada de esto se ejecuta sin GO específico del propietario.** Independiente de la integración V1: no depende de ninguna migración `1022*` (comprobado: ninguna redefine `es_admin()`, las `*_admin_all` ni `perfiles_usuario_update_propio`).

## Qué cierra

Un JWT de administrador (o uno robado) más la anon key pública escribe directamente por PostgREST en 13 tablas de contenido (precios, acceso, preguntas, soluciones, noticias), saltándose Ktor, `auditoria_log` y el rate limit. Un administrador **bloqueado** sigue siendo admin en RLS. Cualquier admin reescribe perfiles ajenos.

Estado de MAIN (VERIFY de solo lectura con GO, 2026-10-19): 39 privilegios de escritura de `authenticated`, 13 políticas `*_admin_all`, `es_admin()` sin `estado_cuenta`, rama admin en `perfiles_usuario_update_propio`, `service_role` intacto.

## Evidencia previa (local, nunca MAIN)

| Prueba | Resultado |
|---|---|
| `ContentAdminWriteRevokeMigrationTest` (PG16 embebido) | 8/8: control negativo, 42501 para admin activo/bloqueado/usuario/anon en las 13 tablas, lecturas de admin activo intactas, service_role intacto, rollback exacto, idempotencia, 6 preflights |
| Gate PG17.6 imagen Supabase (copia) | aplica en orden, VERIFY = 0, `assertions.sql` OK, rollback = huella del bootstrap, re-aplicación idéntica |
| `01_preflight_readonly.sql` en PG17 con forma MAIN | todas las filas `t` antes; tras aplicar, las de estado MAIN pasan a `f` y el VERIFY da 0 |

## Compatibilidad e impacto en clientes administrativos

- **Panel OposiControl:** no usa la Data API para datos de negocio (`scripts/verify-no-direct-supabase.sh` en verde). Escribe todo vía Ktor con `service_role`. Sin impacto.
- **Ktor:** escribe con `service_role` (sin cambios de privilegios). El único JWT de usuario que reenvía es el logout de verificación (`AdminAccessRequests.kt:1629`, `/auth/v1`). Sin impacto.
- **OposiBot cliente:** `PhaseBAccessGatePinTest` prohíbe `/rest/v1` directo. Sin impacto en escrituras.
- **Admins con cuenta no `activa`:** dejan de ser admin en RLS, la misma regla que ya aplica Ktor. El preflight cuenta cuántos hay (solo el número).
- **Políticas y funciones que dependen de `es_admin()`:** `02_checkpoint_and_compat_readonly.sql` las lista. En la forma local son 32. Con la migración solo cambian para admins no activos. Hay que revisar la lista real de MAIN, incluido `storage`, antes de aplicar.
- **Preexistente, no lo cambia:** `anon` no tiene EXECUTE sobre `es_admin()`, y una lectura anónima directa de un borrador falla con 42501 (SEC-V1-23).
- **Edge Functions:** si existiera alguna que escriba estas tablas con el JWT del usuario, se rompería. Comprobarlo con `list_edge_functions` (lectura de metadatos) dentro del GO de preflight.

## Procedimiento (cada paso con su GO)

| Paso | Acción | Tipo | GO |
|---|---|---|---|
| 0 | Confirmar ventana: poco tráfico admin; ningún despliegue ni migración en curso | — | — |
| 1 | Ejecutar `01_preflight_readonly.sql` en MAIN | solo lectura | **GO-1** |
| 2 | Ejecutar `02_checkpoint_and_compat_readonly.sql` y **guardar la salida completa fuera del repo** (es la foto que el ROLLBACK restaura) + `list_edge_functions` | solo lectura | **GO-1** |
| 3 | Revisión humana: todas las filas `t`; número de admins no activos aceptado; lista de dependencias revisada | — | propietario |
| 4 | Punto de recuperación: confirmar backup/PITR del proyecto (si el plan lo ofrece) o anotar la hora exacta | — | propietario |
| 5 | Aplicar la migración (una transacción, `lock_timeout 5s`; si aborta por bloqueo, reintentar en otro momento; si aborta por PREFLIGHT, no hay cambios) | **escritura** | **GO-2** |
| 6 | Postcheck: bloque VERIFY de la migración, todas las filas a 0 | solo lectura | incluido en GO-2 |
| 7 | Humo: panel (crear/editar examen y noticia vía Ktor) y app (catálogo público, intento) | runtime | propietario |

## Ventana y bloqueos

`DROP/ALTER POLICY` toman ACCESS EXCLUSIVE sobre cada una de las 14 tablas hasta el COMMIT (solo catálogo, milisegundos). Con `lock_timeout = 5s`, si hay una transacción larga, la migración aborta en vez de bloquear las lecturas. Ventana recomendada: un momento sin importaciones de PDF ni jobs largos.

## Recuperación

- **Abort en preflight o postcheck:** la transacción hace rollback sola y no cambia nada.
- **Problema funcional después del COMMIT:** ejecutar el bloque `ROLLBACK-BEGIN/END` de la migración, que es idempotente y está probado en PG16 y PG17. Restaura los grants de `authenticated`, las 13 políticas, el cuerpo MAIN de `es_admin()` y la política de perfiles. Reabre el agujero: es una medida temporal.
- **Comprobación tras el rollback:** volver a ejecutar `01_preflight_readonly.sql`; todo debe dar `t` y coincidir con la salida guardada en el paso 2.

## Riesgos residuales (fuera de esta migración)

`sesiones_estudio_todo_propio` y `abandonos_examen_insert_propio` mantienen la rama `OR es_admin()` (SEC-V1-01b, P2). `acciones_moderacion_staff_all` usa `es_moderador_o_admin()`, que no comprueba el estado de la cuenta.
