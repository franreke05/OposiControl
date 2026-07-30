# Supabase local y migraciones

Este directorio convierte el SQL administrativo en migraciones ordenadas. No
incluye ni modifica tablas, capas, buckets o politicas de Noticias.

## Requisitos

- Supabase CLI.
- Docker Desktop o un runtime compatible.
- PostgreSQL local con la misma major version que el proyecto remoto. Antes del
  primer despliegue, compara `show server_version;` con `db.major_version` en
  `config.toml`.

## Validacion local

```powershell
supabase start
supabase db reset
supabase test db
supabase db lint --level warning
```

Las pruebas de `supabase/tests` usan pgTAP y no escriben en el proyecto remoto.

## Orden de despliegue remoto

1. Crear un backup verificable.
2. Ejecutar inventario de esquema, grants, policies, duplicados y conteos.
3. Vincular el proyecto correcto: `supabase link --project-ref <PROJECT_REF>`.
4. Previsualizar: `supabase db push --dry-run`.
5. Aplicar primero en staging: `supabase db push`.
6. Ejecutar las consultas de verificacion del runbook.
7. Desplegar el backend compatible con `core:api`.
8. Repetir en produccion dentro de la ventana aprobada.

No usar `supabase db reset --linked`: reinicia el esquema remoto y es
destructivo.

## Compatibilidad y datos existentes

- El modelo canónico es singular con IDs enteros.
- Las migraciones no eliminan tablas UUID/plurales heredadas.
- Si detectan esquemas paralelos o duplicados, escriben una fila en
  `private.migration_guardrail` y conservan los datos.
- Las preguntas codificadas con `__OPOSICONTROL_QUESTION_V1__` se copian a
  `legacy_payload` y sus opciones se normalizan. El `enunciado` original no se
  reescribe hasta retirar los clientes antiguos.
- `exam-resources` pasa a privado sin eliminar objetos.
- La limpieza de `search_index` elimina solo índices derivados sensibles; no
  elimina usuarios, soporte, moderación, auditoría ni transacciones.

Un despliegue no puede considerarse listo si existen guardrails sin resolver:

```sql
select code, details, detected_at
from private.migration_guardrail
where resolved_at is null
order by detected_at;
```
