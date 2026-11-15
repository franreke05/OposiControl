# F31b — Reto diario todo o nada + reintento de pago (FA57) (2026-11-15)

Rama local `f31/racha-calendario` (segundo commit, tras el calendario F31). Sin push. MAIN/VPS sin tocar. Decision del propietario 2026-11-15 transmitida por el coordinador.

## Reglas

1. El reto es `SUPERADO` solo con TODAS sus preguntas bien (`necesarios` = numero de preguntas: 3 normalmente, menos si la especialidad tiene menos elegibles). Si no, `FALLADO` y la racha se pierde salvo protector (F28 sin cambios: `protector_racha`/`dual` guardan la racha, `protector_tickets`/`dual` la recompensa).
2. Reintento de PAGO antes de perder: respuesta definitiva MAL + el cliente declara `admiteReintentoPago: true` + NO le quedan usos de doble/triple oportunidad (los consumibles van PRIMERO, sin cambios; rechazar un consumible disponible es definitivo) -> la respuesta se RETIENE (no se guarda, sin correccion) y se ofrece el reintento.
   - Precio = `floor((recompensa base + bonus de racha que pagaria superar HOY) / 2)`, minimo 1 OT; se congela con la respuesta retenida.
   - `pagarReintento: true` con OTRA opcion -> cargo atomico en el libro de la cartera y se responde esa opcion; si vuelve a fallar es definitiva (UN reintento de pago por pregunta).
   - Sin saldo -> 409 `INSUFFICIENT_BALANCE` con `details.balance` y `details.price`; no cambia nada y la oferta sigue.
   - `pagarReintento: false` -> rechaza; la retenida queda definitiva. `false` en la primera respuesta = no ofrecer nunca.
   - La misma opcion sin decision repite la oferta (idempotente); cualquier otra combinacion = 409 `ANSWER_LOCKED`.
   - Sin `admiteReintentoPago` nunca se retiene (clientes viejos); una retenida de un cliente que deja de declararlo queda rechazada (nunca atascado).
3. Orden ante un fallo: consumible doble/triple (si lo tiene y el cliente lo admite) -> oferta de pago -> definitiva; en un reto FALLADO los protectores actuan como hoy (probado: protector_racha tras un reintento rechazado).

## Desviacion consciente: tipo del libro

El coordinador sugirio un tipo nuevo (p. ej. `reintento_reto`). NO se anade: `transacciones_cartera_tipo_check` es un CHECK de MAIN y el contrato de cartera (`openapi/wallet/wallet.yaml` `LedgerTipo`, enum cerrado; `WalletService.WalletTipo`) solo lista los 14 tipos actuales: un tipo nuevo exigiria cambiar el CHECK de una tabla de dinero, el contrato D6-01/D6-02 y el cliente. Se usa el tipo existente `gasto` (gasto generico, origen `otros` en D6-02) con `referencia_tipo = 'reto'`, `referencia_id = reto`, `descripcion = 'Reintento del reto'`, `metadatos.op = 'reintento_reto'` (+ reto_id, pregunta_id, dia, precio) e `idempotency_key = reintento-reto:<usuario>:<reto>:<pregunta>` (unico global: un cargo por pregunta). Si el propietario quiere el tipo propio, es una enmienda aparte (CHECK + wallet.yaml + cliente).

## Contrato para el cliente (FA57)

D3-17 `PUT /api/v1/app/retos/{retoId}/respuestas/{preguntaId}` cuerpo:

```json
{"opcionSeleccionadaId":"<uuid>","admiteOportunidad":true,"usarOportunidad":null,"admiteReintentoPago":true,"pagarReintento":null}
```

Respuesta retenida con oferta de pago (200):

```json
{"esCorrecta":false,"estado":"EN_CURSO","aciertos":1,"respondidas":1,
 "reintentoPago":{"disponible":true,"precioTickets":30,"saldoTickets":200},"racha":{"actual":4,"maxima":4}}
```

(sin `opcionCorrectaId` mientras esta retenida; `consumiblesUsados` si en esa misma llamada se gasto una oportunidad). Pagar: mismo PUT con OTRA opcion y `"pagarReintento":true` -> resultado normal (con correccion). Rechazar: `"pagarReintento":false` (cualquier opcion; se guarda la retenida). Sin saldo: 409 `{"code":"INSUFFICIENT_BALANCE","details":{"balance":12,"price":30},...}`.

D3-16 `GET /api/v1/app/retos/hoy`: `necesarios` = numero de preguntas; `preguntas[].reintentoPagoPendiente: true` mientras espera decision (como `oportunidadPendiente`); `reintentoPago: {"precioTickets":30,"saldoTickets":200}` mientras el reto esta `DISPONIBLE`/`EN_CURSO` (precio congelado de la retenida, o lo que costaria un fallo ahora).

## Migracion `20261115100100_v1_reto_todo_o_nada`

- Tabla `public.reto_reintentos_pago` (usuario, reto, pregunta; `pendiente -> pagado | rechazado`; precio congelado; sin acceso de clientes).
- `private.reto_reintento_precio(usuario, especialidad, now)` (mismas reglas de plan y racha que la completacion).
- `private.reto_reintento_cargar(usuario, reto, pregunta, precio, dia)`: cerrojo de cartera (orden reto -> cartera, como el cobro), saldo y UNA fila del libro. Funcion propia para que la regla W-10 de `assertions.sql` (solo `private.free_ot_apply` escribe los tipos de ganancia gratuita) siga comprobable: la respuesta ya no inserta en el libro.
- `private.ensure_challenge_row`: cuerpo de F20b con `aciertos_necesarios = n`; filas de hoy en adelante alineadas (`aciertos_necesarios` = numero de preguntas).
- `public.app_answer_daily_challenge` pasa a 8 argumentos (`p_pagar_reintento DEFAULT NULL`, `p_admite_reintento_pago DEFAULT false`), UNA sola sobrecarga (se borra la de 6): la imagen en produccion llama con 6 argumentos con nombre y sigue funcionando (nunca retiene por pago).
- `public.app_get_daily_challenge`: `reintentoPagoPendiente`, `reintentoPago`, `necesarios` 3 en NO_DISPONIBLE.
- `NOTIFY pgrst, 'reload schema'` al final. ROLLBACK: cuerpos anteriores literales (respuesta de 6 argumentos y hoy de 20261114400200, `ensure_challenge_row` de 20261109100200), filas abiertas a ceil(2n/3), borra tabla y helper. VERIFY: 7 filas.
- Manifiesto: segunda linea de `ops/deploy/v1/main-migrations-2026-11-f31.manifest` (predecesora `20261115100000`).

## Anexo al runbook

1. Tras `20261115100000`: `MANIFEST=ops/deploy/v1/main-migrations-2026-11-f31.manifest bash ops/deploy/v1/apply-main-migration.sh supabase/migrations/20261115100100_v1_reto_todo_o_nada.sql`.
2. DESPUES la imagen Ktor nueva (envia `p_admite_reintento_pago` siempre: necesita la base nueva).
3. Rollback: imagen anterior primero, luego el bloque ROLLBACK y borrar la version del historial.

## Verificacion

- dbtest `V1RetoTodoONadaMigrationTest`: todo bien = SUPERADO; 2/3 = FALLADO y rompe la racha; reintento pagado (precio 30 = (40+20)/2, cargo unico, reintento del pago idempotente, otra pregunta con su propia oferta, fallo tras pagar definitivo); saldo insuficiente (nada cambia, oferta sigue, luego rechazo); consumible primero y luego oferta; rechazar consumible disponible es definitivo; protector tras reintento rechazado; llamada de 6 argumentos nunca retenida; retenida de cliente que deja de declararlo = rechazada; una sola sobrecarga; VERIFY/ROLLBACK ida y vuelta.
- Ajustes por el cambio de regla: `supabase/baseline/assertions.sql` (necesarios 3; la finalidad se prueba con una respuesta correcta; 3 de 3; firma de 8 argumentos) y las clases dbtest de F20/F20b/F28 que probaban la regla anterior (ver commit).
- Ktor `ChallengeRoutesTest` (decision de pago solo con `admiteReintentoPago`, 409 `INSUFFICIENT_BALANCE` con detalles, cuerpo estricto).
