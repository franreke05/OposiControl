-- EX16 / EX15 (LOCAL ONLY). "Publicar Y": edicion simulada del contenido de EX_NORMAL P1 y P2 DESPUES de que un intento
-- empiece sobre X. Mismo efecto que el editor (upsert por letra, ids de opcion conservados, solucion actualizada), escrita
-- directamente porque la autoria es protegida y no se ejecuta aqui. Idempotente.
set session_replication_role = replica;
update public.preguntas set titulo = 'Enunciado Y 1.1' where id = 'f1000000-0000-4000-8000-000000000001';
update public.opciones_pregunta set texto_opcion = replace(texto_opcion, ' X', ' Y') where pregunta_id = 'f1000000-0000-4000-8000-000000000001';
-- P2: la correcta pasa de A a B.
update public.soluciones_pregunta set opcion_correcta_id = '01000000-0000-4000-8200-000000000002', razonamiento = 'Razonamiento Y'
 where pregunta_id = 'f1000000-0000-4000-8000-000000000002';
-- P3: cambia de etiqueta.
delete from public.etiquetas_pregunta where pregunta_id = 'f1000000-0000-4000-8000-000000000003';
insert into public.etiquetas_pregunta (pregunta_id, etiqueta_id) values ('f1000000-0000-4000-8000-000000000003', '7a000000-0000-4000-8000-000000000002')
on conflict do nothing;
set session_replication_role = origin;
