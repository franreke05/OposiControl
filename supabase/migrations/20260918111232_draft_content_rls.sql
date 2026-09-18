-- M7 · El contenido de examenes en borrador no es legible por consumidores.
-- Rollback: restaurar las expresiones anteriores sin e.publicado_en IS NOT NULL.
-- La policy de storage.objects sólo se altera cuando el ejecutor es propietario;
-- en Supabase MAIN pertenece a supabase_storage_admin y se omite con NOTICE.

set local lock_timeout = '5s';
lock table public.examenes, public.preguntas, public.opciones_pregunta,
  public.preguntas_examen, public.etiquetas_pregunta, public.recursos_pregunta
  in share row exclusive mode;

do $pre$
begin
  if (
    select count(*)
      from pg_class c
      join pg_namespace n on n.oid = c.relnamespace
     where n.nspname = 'public'
       and c.relname in (
         'examenes','preguntas','opciones_pregunta',
         'preguntas_examen','etiquetas_pregunta','recursos_pregunta'
       )
       and c.relrowsecurity
  ) <> 6 then
    raise exception 'PREFLIGHT_FAILED: RLS no esta activa en las seis tablas de contenido';
  end if;

  if (
    select count(*)
      from pg_policies
     where schemaname = 'public'
       and (tablename, policyname) in (
         ('examenes','examenes_select_activos'),
         ('preguntas','preguntas_select_gratuitas'),
         ('opciones_pregunta','opciones_pregunta_select_gratuitas'),
         ('preguntas_examen','preguntas_examen_select_gratuitas'),
         ('etiquetas_pregunta','etiquetas_pregunta_select_gratuitas')
       )
       and cmd = 'SELECT'
       and permissive = 'PERMISSIVE'
       and roles @> array['anon','authenticated']::name[]
       and position('publicado_en' in coalesce(qual,'')) = 0
  ) <> 5 then
    raise exception 'PREFLIGHT_FAILED: drift en las cinco policies publicas de contenido';
  end if;

  if (
    select count(*)
      from pg_policies
     where schemaname = 'public'
       and tablename = 'recursos_pregunta'
       and policyname = 'recursos_pregunta_select_premium'
       and cmd = 'SELECT'
       and permissive = 'PERMISSIVE'
       and roles @> array['authenticated']::name[]
       and position('publicado_en' in coalesce(qual,'')) = 0
  ) <> 1 then
    raise exception 'PREFLIGHT_FAILED: drift en recursos_pregunta_select_premium';
  end if;

  if to_regprocedure('public.es_admin()') is null then
    raise exception 'PREFLIGHT_FAILED: falta public.es_admin()';
  end if;

  if not exists (
    select 1
      from pg_policies
     where schemaname = 'storage'
       and tablename = 'objects'
       and policyname = 'recursos_pregunta_storage_select_premium'
       and cmd = 'SELECT'
       and position('publicado_en' in coalesce(qual,'')) = 0
  ) then
    raise exception 'PREFLIGHT_FAILED: drift en la policy premium de storage.objects';
  end if;
end
$pre$;

alter policy examenes_select_activos
  on public.examenes
  using (((activo = true) and (publicado_en is not null)) or es_admin());

alter policy preguntas_select_gratuitas
  on public.preguntas
  using (
    (
      (activa = true)
      and exists (
        select 1
          from public.preguntas_examen pe
          join public.examenes e on e.id = pe.examen_id
         where pe.pregunta_id = preguntas.id
           and e.activo = true
           and e.publicado_en is not null
           and e.tipo_acceso = 'gratuito'
      )
    )
    or es_admin()
  );

alter policy opciones_pregunta_select_gratuitas
  on public.opciones_pregunta
  using (
    exists (
      select 1
        from public.preguntas p
        join public.preguntas_examen pe on pe.pregunta_id = p.id
        join public.examenes e on e.id = pe.examen_id
       where p.id = opciones_pregunta.pregunta_id
         and p.activa = true
         and e.activo = true
         and e.publicado_en is not null
         and e.tipo_acceso = 'gratuito'
    )
    or es_admin()
  );

alter policy preguntas_examen_select_gratuitas
  on public.preguntas_examen
  using (
    exists (
      select 1
        from public.examenes e
       where e.id = preguntas_examen.examen_id
         and e.activo = true
         and e.publicado_en is not null
         and e.tipo_acceso = 'gratuito'
    )
    or es_admin()
  );

alter policy etiquetas_pregunta_select_gratuitas
  on public.etiquetas_pregunta
  using (
    exists (
      select 1
        from public.preguntas p
        join public.preguntas_examen pe on pe.pregunta_id = p.id
        join public.examenes e on e.id = pe.examen_id
       where p.id = etiquetas_pregunta.pregunta_id
         and p.activa = true
         and e.activo = true
         and e.publicado_en is not null
         and e.tipo_acceso = 'gratuito'
    )
    or es_admin()
  );

alter policy recursos_pregunta_select_premium
  on public.recursos_pregunta
  using (
    activo = true
    and exists (
      select 1
        from public.estado_premium_usuario epu
       where epu.usuario_id = (select auth.uid())
         and epu.es_premium = true
    )
    and exists (
      select 1
        from public.preguntas p
        join public.preguntas_examen pe on pe.pregunta_id = p.id
        join public.examenes e on e.id = pe.examen_id
       where p.id = recursos_pregunta.pregunta_id
         and p.activa = true
         and e.activo = true
         and e.publicado_en is not null
         and e.tipo_acceso = 'premium'
         and e.tipo_examen = 'inmersivo'
    )
  );

do $storage$
declare
  v_owner name;
begin
  select pg_get_userbyid(c.relowner)
    into v_owner
    from pg_class c
    join pg_namespace n on n.oid = c.relnamespace
   where n.nspname = 'storage'
     and c.relname = 'objects';

  if v_owner = current_user then
    execute $policy$
      alter policy recursos_pregunta_storage_select_premium
        on storage.objects
        using (
          bucket_id = 'recursos-pregunta'
          and (
            es_admin()
            or exists (
              select 1
                from public.recursos_pregunta rp
                join public.preguntas p on p.id = rp.pregunta_id
                join public.preguntas_examen pe on pe.pregunta_id = p.id
                join public.examenes e on e.id = pe.examen_id
                join public.estado_premium_usuario epu on epu.usuario_id = auth.uid()
               where rp.bucket_storage = 'recursos-pregunta'
                 and rp.ruta_storage = objects.name
                 and rp.activo = true
                 and p.activa = true
                 and e.activo = true
                 and e.publicado_en is not null
                 and e.tipo_acceso = 'premium'
                 and e.tipo_examen = 'inmersivo'
                 and epu.es_premium = true
            )
          )
        )
    $policy$;
  else
    raise notice 'M7_STORAGE_POLICY_SKIPPED: storage.objects owner=%, current_user=%', v_owner, current_user;
  end if;
end
$storage$;

do $post$
begin
  if (
    select count(*)
      from pg_policies
     where schemaname = 'public'
       and (tablename, policyname) in (
         ('examenes','examenes_select_activos'),
         ('preguntas','preguntas_select_gratuitas'),
         ('opciones_pregunta','opciones_pregunta_select_gratuitas'),
         ('preguntas_examen','preguntas_examen_select_gratuitas'),
         ('etiquetas_pregunta','etiquetas_pregunta_select_gratuitas'),
         ('recursos_pregunta','recursos_pregunta_select_premium')
       )
       and cmd = 'SELECT'
       and position('publicado_en' in coalesce(qual,'')) > 0
  ) <> 6 then
    raise exception 'POSTCHECK_FAILED: no quedaron endurecidas las seis policies de contenido';
  end if;
end
$post$;
