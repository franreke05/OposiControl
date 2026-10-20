# Evidencia SEC-V1-02 / RB-14 (2026-10-19, ejecución real de Ktor)

Binario: `:backend:ktor:installDist` de develop (`2cab55b` antes de commit). Entorno limpio con `env -i` (sin `.env` ni MAIN). Supabase falso (`https://stub.invalid` / puerto cerrado). Valkey 8.1 en un contenedor local efímero que se eliminó al terminar. Los secretos eran aleatorios de un solo uso y no aparecen en estos ficheros (comprobado con grep).

| Fichero | Caso | Resultado |
|---|---|---|
| `codes_125_requests.txt` + `ktor-control.log` | control, development, Valkey real | 120 × 410 y, desde la petición 121, 429 |
| `body429.json` | sobre del 429 | `RATE_LIMITED`, `Retry-After: 38` en la cabecera |
| `prod-sin_modo.log` | control, production, sin `ADMIN_RATE_LIMIT_MODE` | exit 1, `ADMIN_RATE_LIMIT_MODE es obligatorio` |
| `prod-memory_en_produccion.log` | control, production, `memory` | exit 1 |
| `prod-valkey_caido.log` | control, production, Valkey inaccesible | exit 1, `RedisConnectionException` |
| `prod-ok.log` | control, production, Valkey válido (con el secreto del consumidor, antes de RB-14) | arranca; token estático → 401 |
| `rb14.log` | control, production, solo variables de las plantillas common+control (después de RB-14) | arranca; `/api/v1/admin/*` sin token → 401; token estático → 401 |
