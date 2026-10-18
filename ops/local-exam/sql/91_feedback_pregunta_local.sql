-- LOCAL ONLY (never a migration, never MAIN). E5-V1-S10: `feedback_pregunta` exists in MAIN but the local bootstrap subset does not carry it, so POST /api/v1/app/feedback/pregunta (D5-15, Reportar)
-- answered 502 locally. Shape copied from a structure-only read of MAIN (2026-10-16: columns, CHECKs, FKs, indexes, policies, ACL, trigger; no rows). Idempotent; applied by up.mjs and by converge.
create table if not exists public.feedback_pregunta (
  id uuid primary key default gen_random_uuid(),
  usuario_id uuid references auth.users(id) on delete set null,
  pregunta_id uuid not null references public.preguntas(id) on delete cascade,
  intento_id uuid references public.intentos_examen(id) on delete set null,
  tipo_feedback text not null,
  descripcion text,
  estado text not null default 'pendiente',
  creado_en timestamptz not null default now(),
  actualizado_en timestamptz not null default now(),
  constraint feedback_pregunta_estado_check check (estado = any (array['pendiente'::text, 'revisado'::text, 'resuelto'::text, 'descartado'::text])),
  constraint feedback_pregunta_tipo_feedback_check check (tipo_feedback = any (array['mal_redactada'::text, 'respuesta_incorrecta'::text, 'explicacion_insuficiente'::text, 'fuente_dudosa'::text, 'recurso_roto'::text, 'otro'::text]))
);
do $$ begin
  if not exists (select 1 from pg_constraint where conname = 'feedback_pregunta_descripcion_max') then
    alter table public.feedback_pregunta add constraint feedback_pregunta_descripcion_max check (char_length(coalesce(descripcion, '')) <= 4000) not valid;
  end if;
end $$;
create index if not exists idx_feedback_pregunta_estado on public.feedback_pregunta (estado);
create index if not exists idx_feedback_pregunta_intento_id on public.feedback_pregunta (intento_id);
create index if not exists idx_feedback_pregunta_pregunta_id on public.feedback_pregunta (pregunta_id);
create index if not exists idx_feedback_pregunta_usuario_id on public.feedback_pregunta (usuario_id);
alter table public.feedback_pregunta enable row level security;
do $$ begin
  if to_regprocedure('public.es_moderador_o_admin()') is not null then
    drop policy if exists feedback_pregunta_select_staff on public.feedback_pregunta;
    create policy feedback_pregunta_select_staff on public.feedback_pregunta for select to authenticated using (es_moderador_o_admin() or usuario_id = (select auth.uid()));
  end if;
  drop policy if exists feedback_pregunta_insert_auth on public.feedback_pregunta;
  create policy feedback_pregunta_insert_auth on public.feedback_pregunta for insert to authenticated with check (usuario_id = (select auth.uid()) or usuario_id is null);
  if to_regprocedure('public.establecer_actualizado_en()') is not null then
    drop trigger if exists trg_feedback_pregunta_actualizado_en on public.feedback_pregunta;
    create trigger trg_feedback_pregunta_actualizado_en before update on public.feedback_pregunta for each row execute function public.establecer_actualizado_en();
  end if;
end $$;
grant select on table public.feedback_pregunta to anon;
grant select, insert, update, delete on table public.feedback_pregunta to authenticated;
grant all on table public.feedback_pregunta to service_role;
