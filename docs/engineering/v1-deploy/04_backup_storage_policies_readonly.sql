-- V1 deploy · F3.2 · politicas de storage.objects (el volcado del CLI no las trae). SOLO LECTURA. Referencia para comparar/reconstruir.
select policyname, permissive, cmd, roles, qual, with_check from pg_policies where schemaname = 'storage' order by policyname;
