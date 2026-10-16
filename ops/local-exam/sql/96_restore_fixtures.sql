-- E5-V1-F03 (LOCAL ONLY, never a migration, never MAIN): put Q1..Q4 and Q2Z back to their baseline balances and entitlements WITHOUT --reset.
-- Run through `node ops/local-exam/up.mjs --restore-fixtures` (guarded by STACK_LOCK). Balances move ONLY through the ledger (one `ajuste` row of the
-- difference, ledger-first, so reconcile stays clean); entitlements: every row of these users outside the baseline set is deleted and the baseline rows
-- are rewritten to their time-relative values (Q3: LOC-OT-EXPIRED ended 10 days ago, LOC-OT-EXPIRING ends in 1 hour).
do $$
declare
  t record; v_cur integer; v_diff integer; v_key text := 'fixture:restore:' || to_char(clock_timestamp(), 'YYYYMMDDHH24MISSUS');
begin
  for t in select * from (values ('2a000000-0000-4000-8000-00000000002a'::uuid, 200), ('2b000000-0000-4000-8000-00000000002b'::uuid, 29),
                                 ('2c000000-0000-4000-8000-00000000002c'::uuid, 0),   ('2d000000-0000-4000-8000-00000000002d'::uuid, 30),
                                 ('2e000000-0000-4000-8000-00000000002e'::uuid, 25)) v(u, target)
  loop
    select saldo_tickets into v_cur from public.carteras_usuario where usuario_id = t.u;
    v_cur := coalesce(v_cur, 0);
    v_diff := t.target - v_cur;
    if v_diff <> 0 then
      perform public.admin_grant_tickets(t.u, v_diff, 'restore fixture baseline', v_key || ':' || right(t.u::text, 2), 'ajuste');
    end if;
  end loop;
end $$;

delete from public.contenido_desbloqueado_usuario g
 where g.usuario_id in ('2a000000-0000-4000-8000-00000000002a', '2b000000-0000-4000-8000-00000000002b', '2c000000-0000-4000-8000-00000000002c',
                        '2d000000-0000-4000-8000-00000000002d', '2e000000-0000-4000-8000-00000000002e')
   and not (g.usuario_id = '2d000000-0000-4000-8000-00000000002d' and g.motivo = 'compra'
            and g.examen_id in ('e3000000-0000-4000-8000-000000000010', 'e3000000-0000-4000-8000-000000000011'));

insert into public.contenido_desbloqueado_usuario (usuario_id, examen_id, motivo, valido_desde, valido_hasta, precio_pagado, moneda, transaccion_id, comprado_en)
values ('2d000000-0000-4000-8000-00000000002d', 'e3000000-0000-4000-8000-000000000010', 'compra', now() - interval '40 days', now() - interval '10 days', 10, 'OPOSITICKETS', '7d300000-0000-4000-8000-000000000010', now() - interval '40 days'),
       ('2d000000-0000-4000-8000-00000000002d', 'e3000000-0000-4000-8000-000000000011', 'compra', now() - interval '29 days', now() + interval '1 hour', 10, 'OPOSITICKETS', '7d300000-0000-4000-8000-000000000011', now() - interval '29 days')
on conflict (usuario_id, examen_id) where motivo = 'compra'
do update set valido_desde = excluded.valido_desde, valido_hasta = excluded.valido_hasta, precio_pagado = excluded.precio_pagado, transaccion_id = excluded.transaccion_id;
