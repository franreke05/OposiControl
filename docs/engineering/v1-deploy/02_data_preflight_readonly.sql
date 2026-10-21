-- V1 deploy · F1.3 · Preflight de DATOS (SOLO LECTURA, SOLO RECUENTOS AGREGADOS: ningun id, correo, titulo ni importe por persona).
-- LEE FILAS DE MAIN: exige la aprobacion EXPLICITA del propietario antes de ejecutarlo (DEPLOYMENT.md, V1 economia).
-- Cada fila es <comprobacion> | <valor> | <regla>. Las marcadas BLOQUEA deben dar 0: si no, la migracion indicada se detiene en su
-- PREFLIGHT sin cambiar nada y el despliegue es NO-GO hasta que el propietario decida. Las INFORMA solo se anotan y aprueban.
begin read only;
select 'slice3_intentos_abiertos_duplicados' as comprobacion,
       (select count(*) from (select 1 from public.intentos_examen where estado = 'en_progreso' group by usuario_id, examen_id having count(*) > 1) d)::bigint as valor,
       'BLOQUEA (debe ser 0): historica de Slice 3, ya aplicada; se repite por seguridad' as regla
union all
select 'examenes_sin_comunidad', (select count(*) from public.examenes where comunidad_id is null),
       'BLOQUEA 20261022100200 (V1_COMMUNITY_PREFLIGHT); 2026-10-19 = 0 de 3'
union all
select 'libro_varias_filas_mismo_tipo_por_pedido',
       (select count(*) from (select 1 from public.transacciones_cartera where compra_id is not null group by compra_id, tipo having count(*) > 1) d),
       'BLOQUEA 20261022100300 (V1_LEDGER_PREFLIGHT 2)'
union all
select 'carteras_total', (select count(*) from public.carteras_usuario), 'INFORMA'
union all
select 'carteras_con_saldo_sin_libro',
       (select count(*) from public.carteras_usuario w where w.saldo_tickets <> 0 and not exists (select 1 from public.transacciones_cartera t where t.usuario_id = w.usuario_id)),
       'INFORMA + APROBACION: recibiran UNA fila legacy_opening = saldo (20261022100300 paso 7)'
union all
select 'carteras_libro_no_cuadra_con_saldo',
       (select count(*) from public.carteras_usuario w
         where exists (select 1 from public.transacciones_cartera t where t.usuario_id = w.usuario_id)
           and w.saldo_tickets <> (select coalesce(sum(t.cantidad), 0) from public.transacciones_cartera t where t.usuario_id = w.usuario_id)),
       'INFORMA + APROBACION: recibiran UNA fila legacy_opening = saldo - suma(libro)'
union all
select 'suma_absoluta_aperturas_previstas_OT',
       (select coalesce(sum(abs(w.saldo_tickets - coalesce((select sum(t.cantidad) from public.transacciones_cartera t where t.usuario_id = w.usuario_id), 0))), 0)
          from public.carteras_usuario w),
       'INFORMA + APROBACION: OT totales que se anotan como apertura (no cambia ningun saldo)'
union all
select 'libro_filas_sin_cartera',
       (select count(*) from public.transacciones_cartera t where not exists (select 1 from public.carteras_usuario w where w.usuario_id = t.usuario_id)),
       'INFORMA: el relleno las encadena; el saldo resultante pasa a ser la suma'
union all
select 'libro_claves_idempotencia_repetidas_por_usuario',
       (select count(*) from (select 1 from public.transacciones_cartera where idempotency_key is not null group by usuario_id, idempotency_key having count(*) > 1) d),
       'BLOQUEA 20261022100300 (unique por usuario): debe ser 0'
union all
select 'compras_duplicadas_proveedor_transaccion',
       (select count(*) from (select 1 from public.compras_usuario where identificador_transaccion is not null group by proveedor, identificador_transaccion having count(*) > 1) d),
       'BLOQUEA 20261022100400 (V1_ORDERS_PREFLIGHT)'
union all
select 'compras_total_por_estado', null::bigint,
       (select coalesce(string_agg(estado || '=' || n, ', ' order by estado), '-') from (select estado, count(*) n from public.compras_usuario group by estado) s)
union all
select 'accesos_compra_duplicados_usuario_examen',
       (select count(*) from (select 1 from public.contenido_desbloqueado_usuario where motivo = 'compra' and examen_id is not null group by usuario_id, examen_id having count(*) > 1) d),
       'BLOQUEA 20261022100500 (V1_REDEEM_PREFLIGHT)'
union all
select 'regalos_sin_plazo', (select count(*) from public.contenido_desbloqueado_usuario where motivo = 'regalo' and valido_hasta is null),
       'INFORMA: 20261022100700 no los toca (solo filas nuevas); se anotan'
union all
select 'suscripciones_duplicadas_proveedor_externo',
       (select count(*) from (select 1 from public.suscripciones where identificador_externo is not null group by proveedor, identificador_externo having count(*) > 1) d),
       'BLOQUEA 20261022102600 (V1_STORE_PAYMENTS_PREFLIGHT)'
union all
select 'perfiles_por_estado_cuenta', null,
       (select coalesce(string_agg(estado_cuenta || '=' || n, ', ' order by estado_cuenta), '-') from (select estado_cuenta, count(*) n from public.perfiles_usuario group by estado_cuenta) s)
union all
select 'admins_y_moderadores_no_activos',
       (select count(distinct ru.usuario_id) from public.roles_usuario ru join public.roles_app ra on ra.id = ru.rol_id
          left join public.perfiles_usuario pu on pu.usuario_id = ru.usuario_id
         where ra.codigo in ('superadmin', 'admin', 'moderador') and coalesce(pu.estado_cuenta, '-') <> 'activa'),
       'INFORMA + APROBACION: dejan de ser staff en RLS con 20261023100000 / 100300'
union all
select 'superadmins_activos',
       (select count(distinct ru.usuario_id) from public.roles_usuario ru join public.roles_app ra on ra.id = ru.rol_id
          join public.perfiles_usuario pu on pu.usuario_id = ru.usuario_id where ra.codigo = 'superadmin' and pu.estado_cuenta = 'activa'),
       'BLOQUEA si 0: nadie podria administrar tras el despliegue'
union all
select 'staging_importacion_duplicados_sin_resolver',
       (select count(*) from public.preguntas_parseadas_staging a join public.preguntas_parseadas_staging b
           on b.lote_id = a.lote_id and b.posicion > a.posicion and a.pregunta_id is null and b.pregunta_id is null
          and a.estado_parseo in ('ok', 'aprobada') and b.estado_parseo in ('ok', 'aprobada') and lower(btrim(a.titulo)) = lower(btrim(b.titulo))),
       'INFORMA: lotes con duplicados no se podran publicar hasta resolverlos (IMPORT_DUPLICATES_UNRESOLVED)'
union all
select 'publicaciones_y_comentarios_oposired',
       (select count(*) from public.publicaciones_oposired) + (select count(*) from public.comentarios_oposired),
       'INFORMA: 2026-10-19 = 0 (20261022101900)'
union all
select 'storage_objetos_por_bucket', null,
       (select coalesce(string_agg(bucket_id || '=' || n, ', ' order by bucket_id), '-') from (select bucket_id, count(*) n from storage.objects group by bucket_id) s)
order by 1;
commit;
