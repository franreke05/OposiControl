# RLS Audit

## Código/SQL inspeccionado

Hay `enable row level security` y policies documentadas para contenido, commerce, tag search, layers y `usuario`. La migration OposiBots crea policy de select propio y revoca insert/update/delete a anon/authenticated.

## Riesgos y límites

- La aplicación Ktor usa service role, por lo que RLS no sustituye autorización de rutas.
- Las policies documentales suelen permitir `select` a `authenticated` y `all` a `service_role`.
- `auth.uid()` y `es_admin()` aparecen en policies/migrations.
- Tablas con RLS y cobertura efectiva actual no pueden confirmarse sin catálogo live.
- Se detectó una migration de fix de initplan y otra de revocación de execute para funciones trigger; esto indica hardening reciente, pero no prueba estado actual.

## Clasificación

RLS definido en SQL: `DOCUMENTED_ONLY`. RLS actual por tabla, tablas sin policy, acceso anon y acceso authenticated: `NOT_VERIFIED`.
