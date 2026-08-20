# Backend Map

## Boot

`Application.module()` configura JSON, `StatusPages`, `Authentication`, gateways Supabase y selección `SERVER_PROFILE=combined|consumer`. `consumer` solo monta auth OposiBots; combined monta contenido/admin/app/auth.

## Gateway registry

`SupabaseContentGateway` (legacy), `SupabaseNewsGateway`, `SupabaseTagSearchGateway`, `SupabaseAdminGateway` (legacy admin), `SupabaseV1Gateway`, `AdminV1Gateway`, `SupabaseOposiBotsUserAdminGateway` y `AppV1Gateway`.

## Auth

- `gateway-auth`: bearer estático `OPOSICONTROL_BACKEND_TOKEN` o JWT Supabase con rol admin.
- Protección adicional: token estático solo desde peer local de desarrollo.
- App V1: JWT Supabase ES256; `X-Usuario-Id` solo con bypass dev.
- OposiBots auth: endpoints propios, rate limit memory dev/Valkey fuera de desarrollo.

## Errores

`StatusPages` mapea unauthorized, not found, bad request, Supabase/illegal state e internal. Las rutas legacy/V1 producen DTOs distintos y algunos mensajes dependen de excepciones.

## Background / jobs

No hay worker de negocio, scheduler, cola ni job de importación. Solo reinicio/reconexión de backend por thread/proceso de desarrollo.
