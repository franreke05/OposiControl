package com.oposicontrol.dbtest.slice3

import com.oposicontrol.dbtest.PostgresHarness
import com.oposicontrol.dbtest.ejecutar
import com.oposicontrol.dbtest.slice0.Slice0Db
import com.oposicontrol.dbtest.slice0.aplicar
import com.oposicontrol.dbtest.slice0.filas
import java.sql.Connection
import java.util.concurrent.atomic.AtomicInteger

/**
 * SLICE 3: soporte para probar las migraciones REALES del runtime de examen sobre el PostgreSQL embebido (nunca MAIN).
 *
 * La forma de MAIN NO se copia a mano: se carga `supabase/baseline/main_shape_subset_20260929.sql`, el fichero GENERADO
 * (verbatim de la captura de catalogo de MAIN) que usa el gate de PostgreSQL 17 del CI, y encima las tres migraciones de
 * Slice 3 tal cual estan en el repositorio (esquema, RPC de comando y el endurecimiento 20261008100200 que las redefine). Solo se quitan sus dos `CREATE EXTENSION` (los binarios embebidos no traen
 * `uuid-ossp`; `gen_random_uuid()` es nativo) y la palabra `MAINTAIN` de sus GRANT / REVOKE (privilegio de PG17; el
 * embebido es PG16), y se ponen los stubs minimos de `auth` que en Supabase pone GoTrue.
 *
 * Las filas de CONTENIDO se siembran con `session_replication_role = replica`: los triggers de autoria de MAIN llaman a
 * ayudantes que el cierre del subconjunto no incluye, y el flujo de autoria no es lo que se prueba aqui. Todo lo demas
 * (los comandos, sus triggers `preparar_intento_examen` / `validar_y_calcular_respuesta_intento`, las FK) corre normal.
 */
object Slice3Db {
    const val SCHEMA = "supabase/migrations/20261008100000_slice3_attempt_runtime_schema.sql"
    const val RPCS = "supabase/migrations/20261008100100_slice3_attempt_command_rpcs.sql"

    /** Gate de Slice 3 (F-01 / F-05 / F-06): marcador `creado_por_comando`, dos indices de FK y las RPC redefinidas. */
    const val HARDENING = "supabase/migrations/20261008100200_slice3_attempt_hardening.sql"
    // La forma de MAIN ANTERIOR a Slice 0/1/2/3/7 (captura 2026-09-29): estas pruebas aplican esas migraciones sobre ella. Desde
    // 2026-10-14 el gate PG17 usa la captura actual (main_shape_subset.sql), en la que ya estan aplicadas.
    private const val MAIN_SUBSET = "supabase/baseline/main_shape_subset_20260929.sql"

    const val UA = "a3000000-0000-0000-0000-00000000000a"
    const val UB = "a3000000-0000-0000-0000-00000000000b"
    const val ESP1 = "e1300000-0000-0000-0000-000000000001"
    const val ESP2 = "e1300000-0000-0000-0000-000000000002"
    const val EX = "e3000000-0000-0000-0000-000000000001"
    const val EX_DRAFT = "e3000000-0000-0000-0000-000000000002"
    const val EX_EMPTY = "e3000000-0000-0000-0000-000000000003"
    const val TAG = "7a300000-0000-0000-0000-000000000001"

    /** Pregunta n (1..6). En EX: 1..4 evaluables, 5 archivada (`activa = false`), 6 anulada ANTES de empezar. */
    fun p(n: Int): String = "f3000000-0000-0000-0000-00000000000$n"

    /** Opcion de la pregunta n: letra 1 = A (la correcta), 2 = B. */
    fun o(n: Int, letra: Int): String = "0a300000-0000-0000-000$n-00000000000$letra"

    private val contador = AtomicInteger()
    private val MAINTAIN = Regex("""\bMAINTAIN,\s*|,\s*MAINTAIN\b""")

    private val PRERREQUISITOS = """
        create schema extensions;
        create schema auth;
        create table auth.users (id uuid primary key, email text, raw_user_meta_data jsonb);
        create function auth.uid() returns uuid language sql stable
        as ${'$'}${'$'} select nullif(current_setting('request.jwt.claim.sub', true), '')::uuid ${'$'}${'$'};
        create function auth.jwt() returns jsonb language sql stable
        as ${'$'}${'$'} select coalesce(nullif(current_setting('request.jwt.claims', true), ''), '{}')::jsonb ${'$'}${'$'};
        grant usage on schema auth to anon, authenticated, service_role;
    """.trimIndent()

    private val subsetMain: String by lazy {
        Slice0Db.leer(MAIN_SUBSET).lines()
            .filterNot { it.startsWith("CREATE EXTENSION ") }
            // El privilegio MAINTAIN es de PostgreSQL 17 (MAIN); el embebido es 16. Solo se quita esa palabra de los
            // GRANT / REVOKE de tabla: ningun test de Slice 3 depende de el (el gate de PG17 del CI lo carga entero).
            .map { linea -> if (linea.startsWith("GRANT ") || linea.startsWith("REVOKE ")) linea.replace(MAINTAIN, "") else linea }
            .joinToString("\n")
    }

    private val SIEMBRA = """
        set session_replication_role = replica;
        insert into auth.users (id, email) values ('$UA', 'a@s3.local'), ('$UB', 'b@s3.local');
        insert into public.especialidades (id, codigo, nombre, slug) values
          ('$ESP1', 'S3E1', 'Uno', 's3-uno'), ('$ESP2', 'S3E2', 'Dos', 's3-dos');
        insert into public.examenes (id, codigo_examen, especialidad_id, titulo, tipo_examen, tipo_acceso, activo, publicado_en) values
          ('$EX', 'S3-EX1', '$ESP1', 'Slice 3', 'rafaga', 'gratuito', true, now() - interval '1 day'),
          ('$EX_DRAFT', 'S3-EX2', '$ESP1', 'Borrador', 'rafaga', 'gratuito', false, null),
          ('$EX_EMPTY', 'S3-EX3', '$ESP1', 'Vacio', 'rafaga', 'gratuito', true, now() - interval '1 day');
        insert into public.preguntas (id, codigo_pregunta, tipo_pregunta, titulo, activa)
        select ('f3000000-0000-0000-0000-00000000000' || n)::uuid, 'S3-P' || n, 'rafaga', 'Pregunta ' || n, n <> 5
        from generate_series(1, 6) n;
        insert into public.opciones_pregunta (id, pregunta_id, letra, texto_opcion, posicion)
        select ('0a300000-0000-0000-000' || n || '-00000000000' || l)::uuid, ('f3000000-0000-0000-0000-00000000000' || n)::uuid, chr(64 + l), 'Opcion', l
        from generate_series(1, 6) n, generate_series(1, 2) l;
        insert into public.soluciones_pregunta (pregunta_id, opcion_correcta_id, razonamiento)
        select ('f3000000-0000-0000-0000-00000000000' || n)::uuid, ('0a300000-0000-0000-000' || n || '-000000000001')::uuid, 'porque si'
        from generate_series(1, 6) n;
        insert into public.preguntas_examen (examen_id, pregunta_id, posicion, numero_en_examen, anulada, motivo_anulacion)
        select '$EX', ('f3000000-0000-0000-0000-00000000000' || n)::uuid, n * 10, n, n = 6, case when n = 6 then 'tribunal' end
        from generate_series(1, 6) n;
        insert into public.etiquetas (id, nombre, slug) values ('$TAG', 'Tema 1', 's3-tema-1');
        insert into public.etiquetas_pregunta (pregunta_id, etiqueta_id)
        select ('f3000000-0000-0000-0000-00000000000' || n)::uuid, '$TAG' from generate_series(1, 4) n;
        set session_replication_role = origin;
    """.trimIndent()

    /**
     * Base nueva: stubs de `auth` + forma de MAIN (+ las migraciones de Slice 3 si [migraciones]) + siembra.
     * [endurecimiento] = false deja SOLO 20261008100000 + 20261008100100 (las RPC tal como se publicaron): es el control
     * negativo de las garantias que da 20261008100200.
     */
    fun <T> escenario(migraciones: Boolean = true, endurecimiento: Boolean = true, bloque: (Connection) -> T): T {
        val nombre = "slice3_" + contador.incrementAndGet()
        PostgresHarness.conexionMaestra().use { it.ejecutar("create database $nombre") }
        return PostgresHarness.baseLlamada(nombre).use { c ->
            c.ejecutar(PRERREQUISITOS)
            c.ejecutar(subsetMain)
            c.ejecutar(SIEMBRA)
            if (migraciones) aplicarMigraciones(c, endurecimiento)
            bloque(c)
        }
    }

    fun aplicarMigraciones(c: Connection, endurecimiento: Boolean = true) {
        c.aplicar(SCHEMA)
        c.aplicar(RPCS)
        if (endurecimiento) c.aplicar(HARDENING)
    }
}

/** `outcome` + `intento_id` de `app_start_exam_attempt`. */
fun Connection.iniciar(usuario: String, examen: String): Pair<String, String?> =
    filas("select r ->> 'outcome', r ->> 'intento_id' from public.app_start_exam_attempt('$usuario', '$examen') as r")
        .single().let { it[0]!! to it[1] }

/** El jsonb de `app_record_attempt_answer` como texto. [opcion] `null` = saltar. */
fun Connection.responder(usuario: String, intento: String, pregunta: String, opcion: String?, base: Int, ms: Int = 0): Map<String, String?> =
    campos(
        "public.app_record_attempt_answer('$usuario', '$intento', '$pregunta', ${opcion?.let { "'$it'" } ?: "null"}, $ms, $base, null)",
        "outcome", "estado", "revision", "current_revision", "es_primera_respuesta", "respondidas", "noop",
    )

fun Connection.finalizar(usuario: String, intento: String): Map<String, String?> =
    campos("public.app_finish_exam_attempt('$usuario', '$intento')", "outcome", "already")

fun Connection.abandonar(usuario: String, intento: String, posicion: Int, motivo: String = "salida_usuario"): Map<String, String?> =
    campos("public.app_abandon_exam_attempt('$usuario', '$intento', $posicion, '$motivo')", "outcome", "already")

private fun Connection.campos(llamada: String, vararg claves: String): Map<String, String?> {
    val fila = filas("select ${claves.joinToString(", ") { "r ->> '$it'" }} from $llamada as r").single()
    return claves.withIndex().associate { (i, clave) -> clave to fila[i] }
}
