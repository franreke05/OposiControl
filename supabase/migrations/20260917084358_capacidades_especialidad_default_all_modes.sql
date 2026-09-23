-- Capacidades por especialidad: abierto por defecto, se restringe a mano (2026-09-14).
--
-- `capacidades_especialidad` nacía con permite_razonados y permite_inmersivos en
-- false, de modo que una especialidad sin fila propia sólo admitía ráfagas. En la
-- práctica eso significaba TODAS: la tabla está vacía (0 filas), nadie ha
-- configurado nunca una especialidad, y el editor de exámenes del panel valida
-- `tipo_examen` contra estas capacidades — así que el único tipo elegible para
-- cualquier especialidad era «Ráfaga».
--
-- Se invierte el punto de partida: una especialidad nace admitiendo los tres
-- modos y se le quita el que su oposición no tenga. La restricción por
-- especialidad sigue existiendo; cambia de qué lado empieza.
--
-- Estos DEFAULT deben seguir coincidiendo con `defaultCapacidades()` en
-- backend/ktor/src/main/kotlin/com/oposicontrol/backend/SpecialtyCapabilities.kt:
-- el backend sirve esos valores cuando la especialidad no tiene fila, así que si
-- los dos lados se separan, "abrir y guardar" en el panel cambiaría en silencio
-- lo que ve el usuario final. Los otros tres flags (rafagas, demos, oposimarket)
-- ya estaban en true y no se tocan.
--
-- Sólo cambia el DEFAULT para filas NUEVAS. No hay filas existentes que migrar, y
-- un UPDATE masivo sería incorrecto de todos modos: una fila escrita a mano
-- representa una decisión de producto explícita que esta migración no debe pisar.

alter table public.capacidades_especialidad alter column permite_razonados set default true;
alter table public.capacidades_especialidad alter column permite_inmersivos set default true;
