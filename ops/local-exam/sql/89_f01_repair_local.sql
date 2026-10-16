-- LOCAL ONLY (E5-V1-F01). An earlier draft of 20261022100300 (then stamped 20261018100100) appended a `legacy_opening` row equal to the WHOLE balance on top of old ledger rows that already
-- summed to it (double count). The fixed migration never does that, but a stack that applied the draft still holds the bad row. This repair removes ONLY an
-- opening row whose wallet also has older rows without seq (the exact bad shape) and its FIFO trace, with triggers skipped (superuser, replica role); the
-- (fixed) migration then sequences the old rows and appends an opening row only if a difference remains. Idempotent: a clean wallet matches nothing.
begin;
set local session_replication_role = replica;
delete from public.ot_consumos k using public.transacciones_cartera o
 where (k.credit_tx_id = o.id or k.debit_tx_id = o.id) and o.referencia_tipo = 'legacy_opening'
   and exists (select 1 from public.transacciones_cartera t where t.usuario_id = o.usuario_id and t.seq is null);
delete from public.transacciones_cartera o
 where o.referencia_tipo = 'legacy_opening'
   and exists (select 1 from public.transacciones_cartera t where t.usuario_id = o.usuario_id and t.seq is null);
commit;
