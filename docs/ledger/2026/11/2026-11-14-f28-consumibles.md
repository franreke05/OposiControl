# F28 — Market de consumibles: catálogo en 3 secciones, compra con OposiTickets y efectos en el reto (2026-11-14)

Rama local `f28/consumibles` desde develop `08ea7f0f`. Sin push. MAIN/VPS sin tocar. Plan aprobado: `ancient-baking-melody.md` sección 6. Enmienda de contrato **FA52** (registro en CONTRACT_FREEZE.md del repo OposiBot: PENDING_OWNER).

## Decisiones del propietario (2026-11-14)

- El Market tiene como máximo **3 secciones**: `OPOSITICKETS` "OposiTickets" (packs; el pago con dinero sigue apagado), `POTENCIADORES` "Potenciadores y protecciones" (protector_racha/tickets/dual, potenciador, doble/triple oportunidad, carta_ccaa_comodin) y `SERVICIOS` "Servicios y accesos" (premium, corrección, tutoría, contenido extra, modo papel).
- Los consumibles se compran con OposiTickets **y funcionan en la 1.3**.

## Definiciones usadas (única fuente: "Arquitectura compra OposiBots.pdf", sección 8, carpeta del cliente)

| Tipo | Texto del cliente | Lo implementado |
| --- | --- | --- |
| protector_racha | "Se contabiliza la racha pero pierdes los tickets" (20 OT) | Reto FALLADO con racha viva: el día cuenta como día de racha; ese día no cobra. |
| protector_tickets | "Pierdes los tickets pero no se contabiliza la racha... (sin él pierde 60 tickets)" (30 OT) | Reto FALLADO: cobra la recompensa que habría cobrado (base + bonus, como si lo hubiera superado); la racha se rompe. **DUDA**: la frase literal está invertida; se lee con su nota de precio (lo que protege son los tickets). |
| protector_dual | "Protege tu racha y los tickets" (40 OT) | Las dos cosas. |
| potenciador | "solo multiplicarán la recompensa base diaria... El bonus de racha no se multiplicará"; "un solo uso... uno diario por reto. No acumulable" (x2 30, x3 60, x4 90 OT) | Un potenciador por reto multiplica la BASE (nunca el bonus): extra = floor(base·m) − base, abonado al reclamar como movimiento `potenciador` (fuera de los topes de OT gratis: se compró con OT). |
| doble/triple oportunidad | "Deshace la pregunta respondida, otorgándole una oportunidad adicional. Antes de que aparezcan los recursos... pop-up preguntando si desea utilizarlo" ("No MVP"; sin precio) | En el reto diario: un fallo con usos disponibles se RETIENE (sin corrección, nada guardado) y el usuario decide: repetir con otra opción (gasta 1 uso) o rechazar. Triple = 2 usos. Solo en el reto (no en exámenes). |
| modo_papel | "pagará por el número de exámenes que quiera pasar a PDF" | Sin efecto definido: **comprable pero INERTE** (no se siembra). |
| carta_ccaa_comodin | "No en MVP... gamificación distinta" | Sin efecto definido: **comprable pero INERTE** (no se siembra). |
| premium, corrección, tutoría, contenido extra | — | Se listan en SERVICIOS con `comprableConTickets=false` (D6-40 responde 422 NOT_PURCHASABLE). |

Los protectores se gastan solos y **solo si se iba a perder algo** (racha: había racha viva; tickets: el reto pagaba > 0). Con las dos pérdidas se usa un dual si lo hay; si no, un protector de racha y uno de tickets. Un reto superado nunca gasta protectores.

## Migraciones (forward-only, `2026111440xxxx`)

1. `20261114400000_v1_servicios_catalogo` — `servicios`: `visible`, `posicion`, `seccion` (CHECK de las 3; trigger que la deriva del tipo si queda vacía), `usos`, `duracion_horas`, `multiplicador` (con CHECK). Valores derivados para las filas existentes. **Semilla PLACEHOLDER** (`on conflict (codigo) do nothing`): protector_racha 20, protector_tickets 30, protector_dual 40, potenciador_x2/x3/x4 30/60/90 OT (precios del documento del cliente), doble_oportunidad 30 OT (1 uso) y triple_oportunidad 50 OT (2 usos) — **estos dos sin precio en ningún documento: PLACEHOLDER de F28**. Todos activos y visibles; el propietario los ajusta en Control > Catálogo > Servicios.
2. `20261114400100_v1_servicios_canje` — `app_market_catalog(usuario)` y `app_redeem_service(usuario, codigo, clave, precio_esperado)` (débito `compra_servicio` + activación en UNA transacción con el cerrojo de cartera, repetición por clave, rechazos sin escritura).
3. `20261114400200_v1_consumibles_efectos` — `app_answer_daily_challenge` pasa a 5 argumentos (`p_usar_oportunidad` opcional; se BORRA la de 4 para que PostgREST no vea dos candidatas), `app_get_daily_challenge` y `app_claim_daily_challenge` con los efectos; tabla `reto_oportunidades_pendientes`. Base: los cuerpos de 20261022103500/101200; F20b (`ensure_challenge_row`) y F27 (trigger sobre `respuestas_reto_diario`) no tocan estas funciones: sin conflicto de cuerpo.

**AVISO para migraciones futuras:** cualquier `create or replace function public.app_answer_daily_challenge(uuid,uuid,uuid,uuid)` posterior crearía una SEGUNDA sobrecarga y PostgREST fallaría por ambigüedad: hay que redefinir la de 5 argumentos.

## Contrato (FA52)

- `GET /api/v1/app/market/servicios` (D6-39), `POST /api/v1/app/wallet/servicios/{codigo}/canje` (D6-40, `Idempotency-Key` obligatoria, cuerpo opcional `{"expectedPrice"}`): `openapi/economy/economy.yaml`, `root.yaml`.
- D3-16/D3-17/D3-18 (`engagement.yaml`): `usarOportunidad` en la respuesta al reto; resultado retenido (`oportunidad`, sin `opcionCorrectaId`; `respondidas` mínimo 0), `consumiblesUsados`, `recompensa.multiplicador/extraPotenciador`, `preguntas[].oportunidadPendiente`, `protectorUsado`, `consumibles`; recompensa en un FALLADO protegido y su reclamación.
- Admin `Servicio` (D6-18, `commerce.yaml`): `visible`, `posicion`, `seccion`, `usos`, `duracion_horas`, `multiplicador`.
- `GET /api/v1/app/wallet` sin cambios: ya lista `usosRestantes` de cada activación; ahora los efectos los gastan.

## Despliegue (lo hace el propietario, con GO)

Orden del plan: F20b, F14b, F23, estadísticas (F27), avatares, **consumibles (F28)**, biblioteca. F28 no depende técnicamente de F20b/F27 (predecesora en el manifiesto: `20261109100000`, ya en MAIN), pero su sello es posterior a ambos.

1. `MANIFEST=ops/deploy/v1/main-migrations-2026-11-f28.manifest bash ops/deploy/v1/apply-main-migration.sh supabase/migrations/<cada una>.sql`, en orden 400000, 400100, 400200. Cada una: `aplicada, VERIFY = 0, registrada`.
2. **Imagen nueva: SÍ** (rutas D6-39/D6-40, `usarOportunidad`, DTO admin de servicios). La imagen anterior sigue funcionando con la base nueva (llama a la respuesta del reto con 4 argumentos con nombre; el 5º tiene valor por defecto). La imagen nueva con la base anterior: D6-39/D6-40 fallan (función inexistente) y `usarOportunidad` también; por eso **migraciones antes que imagen**.
3. Control: el panel nuevo envía `visible`/`posicion`; un panel anterior no los manda y el servidor conserva lo guardado.
4. Rollback: bloques ROLLBACK en orden inverso (400200 devuelve los cuerpos anteriores; las activaciones gastadas se quedan gastadas) + borrar las versiones de `supabase_migrations.schema_migrations`.

## Verificación

Ver el informe final de F28 (dbtest `V1ServiciosCatalogoMigrationTest`, `V1ServiciosCanjeMigrationTest`, `V1ConsumiblesEfectosMigrationTest`; Ktor `MarketServicesRoutesTest`, `ChallengeRoutesTest`, `AdminMarketUpsertTest`, OpenAPI, inventario; Control `ServiceShowcaseSpecTest`; gate PG17; ensayo `docs/ledger/2026/11/rehearse-f28-2026-11.sh`).
