-- Catálogo de comunidades y ciudades autónomas (2026-09-14).
--
-- `public.comunidades_autonomas` existía con la forma correcta (codigo, nombre,
-- slug, activa, posicion) pero con CERO filas. La consola lo nota y es honesta al
-- respecto: `ExamCommunityPicker` sólo dibuja el desplegable cuando el catálogo
-- llega con datos, y con la tabla vacía cae a la rama `Empty` — una celda de sólo
-- lectura con «Nacional» y el aviso «No hay comunidades en el catálogo». No era un
-- defecto de interfaz: era la ausencia del dato maestro.
--
-- Son las 17 comunidades autónomas más las 2 ciudades autónomas (Ceuta y Melilla).
--
-- ## Por qué `codigo` es el código ISO 3166-2:ES
--
-- Las portadas de examen van a ser vectoriales y a enlazarse con la comunidad, así
-- que `codigo` deja de ser decorativo: es la clave de unión con los mapas. ISO
-- 3166-2:ES es lo que usan los conjuntos de SVG de España, así que enlazar un
-- vector será una correspondencia directa por este campo y no una tabla de
-- traducción escrita a mano. El código INE (01..19) queda reflejado en `posicion`,
-- que es lo que ordena el desplegable.
--
-- ## Idempotente por fila
--
-- El `not exists` va por `codigo`, no sobre la tabla entera: volver a ejecutarla no
-- duplica nada, y si alguien ya hubiera dado de alta alguna comunidad a mano, se
-- respeta la suya y sólo se añaden las que falten. No se usa `on conflict (codigo)`
-- porque no se ha verificado que exista un índice único sobre esa columna, y sin él
-- esa cláusula falla.
--
-- `activa` se deja en su default (true): las 19 nacen disponibles. Archivar una es
-- una decisión de producto, no de esta siembra.

insert into public.comunidades_autonomas (codigo, nombre, slug, posicion)
select v.codigo, v.nombre, v.slug, v.posicion
from (values
  ('ES-AN', 'Andalucía',                    'andalucia',            1),
  ('ES-AR', 'Aragón',                       'aragon',               2),
  ('ES-AS', 'Principado de Asturias',       'asturias',             3),
  ('ES-IB', 'Illes Balears',                'illes-balears',        4),
  ('ES-CN', 'Canarias',                     'canarias',             5),
  ('ES-CB', 'Cantabria',                    'cantabria',            6),
  ('ES-CL', 'Castilla y León',              'castilla-y-leon',      7),
  ('ES-CM', 'Castilla-La Mancha',           'castilla-la-mancha',   8),
  ('ES-CT', 'Cataluña',                     'cataluna',             9),
  ('ES-VC', 'Comunitat Valenciana',         'comunitat-valenciana', 10),
  ('ES-EX', 'Extremadura',                  'extremadura',          11),
  ('ES-GA', 'Galicia',                      'galicia',              12),
  ('ES-MD', 'Comunidad de Madrid',          'madrid',               13),
  ('ES-MC', 'Región de Murcia',             'murcia',               14),
  ('ES-NC', 'Comunidad Foral de Navarra',   'navarra',              15),
  ('ES-PV', 'País Vasco',                   'pais-vasco',           16),
  ('ES-RI', 'La Rioja',                     'la-rioja',             17),
  ('ES-CE', 'Ceuta',                        'ceuta',                18),
  ('ES-ML', 'Melilla',                      'melilla',              19)
) as v (codigo, nombre, slug, posicion)
where not exists (
  select 1 from public.comunidades_autonomas c where c.codigo = v.codigo
);
