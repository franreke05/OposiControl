-- V1 deploy · F3.2 · triggers propios de auth.users (el volcado del CLI no los trae). SOLO LECTURA. Salida = sentencias CREATE TRIGGER.
select pg_get_triggerdef(oid) || ';' from pg_trigger where tgrelid = 'auth.users'::regclass and not tgisinternal order by tgname;
