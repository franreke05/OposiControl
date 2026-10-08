package com.oposicontrol.dbtest.slice2

import com.oposicontrol.dbtest.ejecutar
import com.oposicontrol.dbtest.slice0.Slice0Db
import com.oposicontrol.dbtest.slice0.aplicar
import com.oposicontrol.dbtest.slice0.filas
import com.oposicontrol.dbtest.slice0.sqlStateDe
import com.oposicontrol.dbtest.slice3.Slice3Db
import com.oposicontrol.dbtest.slice3.Slice3Db.ESP1
import com.oposicontrol.dbtest.slice3.Slice3Db.UA
import com.oposicontrol.dbtest.slice3.Slice3Db.UB
import com.oposicontrol.dbtest.unico
import java.sql.Connection
import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * SLICE 2: migracion 20261008100300 (`public.app_my_exams_collection`, la lectura de D2-13 `vista = COLECCION`) sobre la
 * forma de MAIN + las tres migraciones de Slice 3, en el PostgreSQL embebido (nunca MAIN).
 *
 * Coleccion de un usuario = examenes PUBLICADOS con concesion VIGENTE o con un intento creado por el comando canonico;
 * un elemento por examen; orden `(orden_en, id)` descendente con keyset estricto; grupos por comunidad de TODO el conjunto
 * filtrado; limite acotado dentro de la funcion; solo `service_role` la ejecuta. Cada garantia lleva su CONTROL NEGATIVO:
 * la misma fila deja de estar excluida en cuanto cumple el predicado, o la misma consulta sin la pieza concreta hace
 * exactamente lo que la garantia impide.
 *
 * Las filas se siembran con `session_replication_role = replica` para que cada instante sea el que se escribe aqui.
 */
class Slice2MyExamsReadRpcMigrationTest {

    private companion object {
        const val MY_EXAMS = "supabase/migrations/20261008100300_slice2_my_exams_read_rpc.sql"
        const val FIRMA = "public.app_my_exams_collection(uuid, text, uuid, integer, timestamp with time zone, uuid, integer, boolean)"
        const val C1 = "c2000000-0000-0000-0000-000000000001"
        const val C2 = "c2000000-0000-0000-0000-000000000002"
    }

    private data class Item(val titulo: String, val id: String, val ordenEn: String, val intentoId: String?, val estado: String?)

    private fun examen(n: Int): String = "52000000-0000-0000-0000-%012d".format(n)

    private fun intento(n: Int): String = "52a00000-0000-0000-0000-%012d".format(n)

    private fun <T> escenario(bloque: (Connection) -> T): T = Slice3Db.escenario { c ->
        c.aplicar(MY_EXAMS)
        c.sembrar("insert into public.comunidades_autonomas (id, codigo, nombre, slug) values ('$C1', 'MAD', 'Madrid', 's2-mad'), ('$C2', 'AND', 'Andalucia', 's2-and')")
        bloque(c)
    }

    private fun Connection.sembrar(sql: String) {
        ejecutar("set session_replication_role = replica")
        try {
            ejecutar(sql)
        } finally {
            ejecutar("set session_replication_role = origin")
        }
    }

    private fun Connection.nuevoExamen(
        n: Int,
        titulo: String = "X$n",
        activo: Boolean = true,
        publicado: String? = "now() - interval '9 day'",
        comunidad: String? = C1,
        tipo: String = "rafaga",
        anio: Int = 2024,
    ) = sembrar(
        "insert into public.examenes (id, codigo_examen, especialidad_id, comunidad_id, titulo, tipo_examen, tipo_acceso, activo, publicado_en, anio) " +
            "values ('${examen(n)}', 'S2-X$n', '$ESP1', ${comunidad?.let { "'$it'" } ?: "null"}, '$titulo', '$tipo', 'premium', $activo, ${publicado ?: "null"}, $anio)",
    )

    private fun Connection.concesion(usuario: String, n: Int, desde: String, hasta: String? = null, motivo: String = "compra") = sembrar(
        "insert into public.contenido_desbloqueado_usuario (usuario_id, examen_id, motivo, valido_desde, valido_hasta) " +
            "values ('$usuario', '${examen(n)}', '$motivo', $desde, ${hasta ?: "null"})",
    )

    private fun Connection.intentoDe(usuario: String, n: Int, id: Int, estado: String, actualizado: String, marcado: Boolean = true, respondidas: Int = 1, total: Int = 4) = sembrar(
        "insert into public.intentos_examen (id, usuario_id, examen_id, estado, origen_intento, creado_por_comando, total_preguntas, total_respondidas, actualizado_en) " +
            "values ('${intento(id)}', '$usuario', '${examen(n)}', '$estado', 'examen', ${if (marcado) "'app_start_exam_attempt'" else "null"}, $total, $respondidas, $actualizado)",
    )

    private fun llamada(
        usuario: String?,
        tipo: String? = null,
        comunidad: String? = null,
        anio: Int? = null,
        despues: Pair<String, String>? = null,
        limite: Int? = 21,
        grupos: Boolean = false,
    ): String = "public.app_my_exams_collection(${usuario?.let { "'$it'" } ?: "null"}, ${tipo?.let { "'$it'" } ?: "null"}, ${comunidad?.let { "'$it'" } ?: "null"}, " +
        "${anio ?: "null"}, ${despues?.let { "'${it.first}'::timestamptz" } ?: "null"}, ${despues?.let { "'${it.second}'" } ?: "null"}, ${limite ?: "null"}, $grupos)"

    private fun Connection.coleccion(
        usuario: String?,
        tipo: String? = null,
        comunidad: String? = null,
        anio: Int? = null,
        despues: Pair<String, String>? = null,
        limite: Int? = 21,
    ): List<Item> = filas(
        "select i -> 'examenes' ->> 'titulo', i -> 'examenes' ->> 'id', i ->> 'orden_en', i -> 'intento' ->> 'id', i -> 'intento' ->> 'estado' " +
            "from jsonb_array_elements(${llamada(usuario, tipo, comunidad, anio, despues, limite)} -> 'items') with ordinality t(i, n) order by n",
    ).map { Item(it[0]!!, it[1]!!, it[2]!!, it[3], it[4]) }

    private fun Connection.titulos(usuario: String?, tipo: String? = null, comunidad: String? = null, anio: Int? = null): List<String> =
        coleccion(usuario, tipo, comunidad, anio).map { it.titulo }

    private fun Connection.grupos(usuario: String?, tipo: String? = null, comunidad: String? = null, anio: Int? = null, despues: Pair<String, String>? = null, limite: Int? = 21): String? =
        unico("select (${llamada(usuario, tipo, comunidad, anio, despues, limite, grupos = true)} -> 'grupos')::text")

    // ------------------------------------------------------------------ union + un elemento por examen + orden

    @Test
    fun theCollectionIsTheUnionOfValidGrantsAndCommandAttemptsOneItemPerExamNewestActivityFirst() = escenario { c ->
        c.nuevoExamen(1) // solo concedido (dos concesiones vigentes: cuenta la mas reciente)
        c.nuevoExamen(2) // solo intentado (dos intentos: se ofrece el actualizado mas tarde)
        c.nuevoExamen(3, comunidad = C2) // concedido E intentado -> UN elemento, ordenado por lo mas reciente de los dos
        c.nuevoExamen(4) // ni concesion ni intento: no es de la coleccion
        c.concesion(UA, 1, "now() - interval '8 day'", "now() + interval '1 day'", motivo = "regalo")
        c.concesion(UA, 1, "now() - interval '3 day'")
        c.concesion(UA, 3, "now() - interval '5 day'", motivo = "admin")
        c.intentoDe(UA, 2, 1, "abandonado", "now() - interval '6 day'")
        c.intentoDe(UA, 2, 2, "finalizado", "now() - interval '1 day'", respondidas = 4)
        c.intentoDe(UA, 3, 3, "en_progreso", "now() - interval '2 hour'")

        val items = c.coleccion(UA)
        assertEquals(listOf("X3", "X2", "X1"), items.map { it.titulo }, "union, un elemento por examen, actividad mas reciente primero")
        assertEquals(listOf(intento(3), intento(2), null), items.map { it.intentoId }, "el intento actualizado mas tarde; sin intento, null")
        assertEquals(listOf("en_progreso", "finalizado", null), items.map { it.estado })
        assertEquals(
            listOf(listOf("t", "t", "t")),
            c.filas(
                "select ('${items[0].ordenEn}'::timestamptz = (select actualizado_en from public.intentos_examen where id = '${intento(3)}'))::text, " +
                    "('${items[1].ordenEn}'::timestamptz = (select actualizado_en from public.intentos_examen where id = '${intento(2)}'))::text, " +
                    "('${items[2].ordenEn}'::timestamptz = (select max(valido_desde) from public.contenido_desbloqueado_usuario where examen_id = '${examen(1)}'))::text",
            ).map { fila -> fila.map { it?.take(1) } },
            "orden_en = lo mas reciente entre la ultima concesion vigente y el ultimo intento, con precision de microsegundo",
        )
        // Forma exacta: metadatos de examen e intento; ni preguntas ni nota.
        assertEquals(
            listOf(listOf("examenes,intento,orden_en", "comunidades_autonomas,demo_disponible,especialidad_id,id,portada_storage_path,tipo_acceso,tipo_examen,titulo", "estado,id,total_preguntas,total_respondidas")),
            c.filas(
                "select (select string_agg(k, ',' order by k) from jsonb_object_keys(i) k), (select string_agg(k, ',' order by k) from jsonb_object_keys(i -> 'examenes') k), " +
                    "(select string_agg(k, ',' order by k) from jsonb_object_keys(i -> 'intento') k) from (select ${llamada(UA)} -> 'items' -> 0 as i) x",
            ),
        )
        assertEquals("""[{"total": 1, "comunidad_codigo": "AND"}, {"total": 2, "comunidad_codigo": "MAD"}]""", c.grupos(UA), "por codigo, sobre todo el conjunto")
        // La funcion es de solo lectura: ninguna fila cambia.
        assertEquals(listOf(listOf("3", "3")), c.filas("select (select count(*) from public.contenido_desbloqueado_usuario)::text, (select count(*) from public.intentos_examen)::text"))
    }

    // ------------------------------------------------------------------ concesiones caducadas / futuras, intentos sin marcador

    @Test
    fun expiredOrFutureGrantsAndAttemptsTheStartCommandDidNotCreateDoNotPutAnExamInTheCollection() = escenario { c ->
        c.nuevoExamen(1, "caducada")
        c.nuevoExamen(2, "futura")
        c.nuevoExamen(3, "legacy")
        c.nuevoExamen(4, "repaso")
        c.concesion(UA, 1, "now() - interval '4 day'", "now() - interval '1 second'")
        c.concesion(UA, 2, "now() + interval '1 hour'")
        c.intentoDe(UA, 3, 1, "finalizado", "now() - interval '1 minute'", marcado = false)
        assertEquals(emptyList(), c.titulos(UA), "ni concesion caducada, ni aun no vigente, ni intento sin marcador de comando")
        assertEquals("[]", c.grupos(UA))

        // Controles negativos: la MISMA fila entra en cuanto cumple el predicado (la exclusion no es un accidente de la siembra).
        c.sembrar("update public.contenido_desbloqueado_usuario set valido_hasta = now() + interval '1 hour' where examen_id = '${examen(1)}'")
        assertEquals(listOf("caducada"), c.titulos(UA))
        c.sembrar("update public.contenido_desbloqueado_usuario set valido_desde = now() - interval '1 second' where examen_id = '${examen(2)}'")
        assertEquals(listOf("futura", "caducada"), c.titulos(UA))
        // El marcador es inmutable (20261008100200): un intento legacy no se "promociona"; uno del comando si cuenta.
        assertEquals("P0001", c.sqlStateDe("update public.intentos_examen set creado_por_comando = 'app_start_exam_attempt' where id = '${intento(1)}'"))
        c.intentoDe(UA, 3, 2, "abandonado", "now() - interval '2 minute'")
        val conLegacy = c.coleccion(UA)
        assertEquals(listOf("futura", "legacy", "caducada"), conLegacy.map { it.titulo })
        assertEquals(intento(2), conLegacy[1].intentoId, "se ofrece el intento del comando, NO el legacy aunque sea mas reciente")
    }

    // ------------------------------------------------------------------ borradores, archivados, programados

    @Test
    fun draftArchivedAndScheduledExamsNeverAppearEvenWhenGrantedAndAttempted() = escenario { c ->
        c.nuevoExamen(1, "borrador", activo = false, publicado = null)
        c.nuevoExamen(2, "archivado", activo = false)
        c.nuevoExamen(3, "programado", publicado = "now() + interval '1 day'")
        c.nuevoExamen(4, "sin fecha", publicado = null)
        c.nuevoExamen(5, "publicado")
        for (n in 1..5) {
            c.concesion(UA, n, "now() - interval '$n hour'")
            c.intentoDe(UA, n, n, "finalizado", "now() - interval '$n minute'")
        }
        assertEquals(listOf("publicado"), c.titulos(UA), "solo activo AND publicado_en <= now()")
        assertEquals("""[{"total": 1, "comunidad_codigo": "MAD"}]""", c.grupos(UA), "tampoco cuentan en los grupos")

        // Control negativo: al publicarlos, los mismos examenes aparecen (por su actividad mas reciente: minuto 1 primero).
        c.sembrar("update public.examenes set activo = true, publicado_en = now() - interval '1 day' where id::text like '52000000-%'")
        assertEquals(listOf("borrador", "archivado", "programado", "sin fecha", "publicado"), c.titulos(UA))
        // ...y al despublicar uno deja de estar, sin tocar concesiones ni intentos.
        c.sembrar("update public.examenes set activo = false where id = '${examen(5)}'")
        assertEquals(listOf("borrador", "archivado", "programado", "sin fecha"), c.titulos(UA))
    }

    // ------------------------------------------------------------------ datos ajenos

    @Test
    fun anotherUsersGrantsAndAttemptsNeverAppearNorTheirAttemptOnASharedExam() = escenario { c ->
        c.nuevoExamen(1, "de A")
        c.nuevoExamen(2, "de B concedido")
        c.nuevoExamen(3, "de B intentado")
        c.nuevoExamen(4, "compartido")
        c.concesion(UA, 1, "now() - interval '3 hour'")
        c.concesion(UA, 4, "now() - interval '4 hour'")
        c.concesion(UB, 2, "now() - interval '1 hour'")
        c.intentoDe(UB, 3, 1, "finalizado", "now() - interval '1 minute'")
        c.intentoDe(UB, 4, 2, "en_progreso", "now() - interval '1 minute'")

        val deA = c.coleccion(UA)
        assertEquals(listOf("de A", "compartido"), deA.map { it.titulo }, "nada de B en la coleccion de A")
        assertEquals(listOf<String?>(null, null), deA.map { it.intentoId }, "el intento de B sobre el examen compartido no se ofrece a A")
        assertEquals("t", c.unico("select ('${deA[1].ordenEn}'::timestamptz = (select valido_desde from public.contenido_desbloqueado_usuario where usuario_id = '$UA' and examen_id = '${examen(4)}'))::text")?.take(1), "ni mueve su orden")
        assertEquals(listOf("compartido", "de B intentado", "de B concedido"), c.titulos(UB))
        assertEquals("""[{"total": 2, "comunidad_codigo": "MAD"}]""", c.grupos(UA), "los grupos tampoco cuentan lo ajeno")
        assertEquals(emptyList(), c.titulos(null), "sin usuario no hay coleccion")
        assertEquals(emptyList(), c.titulos("00000000-0000-0000-0000-000000000000"))

        // Control negativo: las MISMAS filas, pasadas a nombre de A, aparecen en su coleccion.
        c.sembrar("update public.contenido_desbloqueado_usuario set usuario_id = '$UA' where usuario_id = '$UB'")
        c.sembrar("update public.intentos_examen set usuario_id = '$UA' where usuario_id = '$UB'")
        assertEquals(setOf("de A", "de B concedido", "de B intentado", "compartido"), c.titulos(UA).toSet())
        assertEquals(emptyList(), c.titulos(UB))
    }

    // ------------------------------------------------------------------ keyset: empates, sin duplicados ni huecos

    /** Recorre la coleccion como lo hace Ktor: pide `tam + 1`, sirve `tam` y continua desde la clave del ultimo servido. */
    private fun Connection.recorrer(usuario: String, tam: Int): Pair<List<Item>, Int> {
        val vistos = mutableListOf<Item>()
        var despues: Pair<String, String>? = null
        var paginas = 0
        while (true) {
            val filas = coleccion(usuario, despues = despues, limite = tam + 1)
            paginas++
            vistos += filas.take(tam)
            if (filas.size <= tam || paginas > 50) return vistos to paginas
            despues = filas[tam - 1].let { it.ordenEn to it.id }
        }
    }

    @Test
    fun theCursorRoundTripHasNoDuplicatesOrGapsAcrossPagesWithTies() = escenario { c ->
        // 9 examenes de A. Cinco comparten EXACTAMENTE el mismo orden_en (misma concesion al microsegundo) y otros dos
        // empatan entre un intento y una concesion: el desempate es el id del examen.
        c.ejecutar("create temp table s2_t as select now() - interval '1 day' as empate, now() - interval '2 day' as otro")
        for (n in 1..9) c.nuevoExamen(n, comunidad = if (n % 2 == 0) C2 else C1)
        for (n in 1..5) c.concesion(UA, n, "(select empate from s2_t)")
        c.concesion(UA, 6, "(select otro from s2_t)")
        c.intentoDe(UA, 7, 7, "finalizado", "(select otro from s2_t)")
        c.concesion(UA, 8, "now() - interval '1 hour'")
        c.intentoDe(UA, 9, 9, "en_progreso", "now() - interval '5 day'")

        val completa = c.coleccion(UA, limite = 51)
        assertEquals(listOf("X8", "X5", "X4", "X3", "X2", "X1", "X7", "X6", "X9"), completa.map { it.titulo }, "orden_en desc, id desc")
        assertEquals(5, completa.count { it.ordenEn == completa[1].ordenEn }, "cinco claves de orden identicas")
        for (tam in listOf(1, 2, 3, 4, 8, 9, 50)) {
            val (vistos, paginas) = c.recorrer(UA, tam)
            assertEquals(completa, vistos, "paginas de $tam: las mismas filas, una vez y en el mismo orden")
            assertEquals((completa.size + tam - 1) / tam, paginas, "paginas de $tam")
        }
        // La pagina que empieza en mitad del empate no repite ni salta.
        val trasX4 = c.coleccion(UA, despues = completa[2].ordenEn to completa[2].id, limite = 3)
        assertEquals(listOf("X3", "X2", "X1"), trasX4.map { it.titulo })
        // Tras la ultima fila no queda nada; los grupos siguen siendo los de TODO el conjunto, con cursor o sin el.
        assertEquals(emptyList(), c.coleccion(UA, despues = completa.last().ordenEn to completa.last().id))
        val grupos = """[{"total": 4, "comunidad_codigo": "AND"}, {"total": 5, "comunidad_codigo": "MAD"}]"""
        assertEquals(grupos, c.grupos(UA, limite = 1))
        assertEquals(grupos, c.grupos(UA, despues = completa[6].ordenEn to completa[6].id, limite = 1), "los grupos no dependen de la pagina")

        // Controles negativos: el id del desempate es necesario. Un cursor solo por instante (id maximo / minimo) repite o salta.
        val empate = completa[1].ordenEn
        assertEquals(
            listOf("X5", "X4", "X3"),
            c.coleccion(UA, despues = empate to "ffffffff-ffff-ffff-ffff-ffffffffffff", limite = 3).map { it.titulo },
            "sin desempate por arriba: X5 y X4 (ya servidos) se repetirian",
        )
        assertEquals(
            listOf("X7", "X6", "X9"),
            c.coleccion(UA, despues = empate to "00000000-0000-0000-0000-000000000000", limite = 3).map { it.titulo },
            "sin desempate por abajo: X3, X2 y X1 se saltarian",
        )
        // Un cursor a medias no se interpreta como primera pagina.
        assertEquals("22023", c.sqlStateDe("select ${llamada(UA).replace("null, null, 21", "now(), null, 21")}"))
        assertEquals("22023", c.sqlStateDe("select ${llamada(UA).replace("null, null, 21", "null, '${examen(1)}', 21")}"))
    }

    // ------------------------------------------------------------------ limite acotado dentro de la funcion

    @Test
    fun theLimitIsBoundedInsideTheFunction() = escenario { c ->
        c.sembrar(
            "insert into public.examenes (id, codigo_examen, especialidad_id, titulo, tipo_examen, tipo_acceso, activo, publicado_en) " +
                "select ('52000000-0000-0000-0000-' || lpad(n::text, 12, '0'))::uuid, 'S2-L' || n, '$ESP1', 'L' || n, 'rafaga', 'premium', true, now() - interval '1 day' " +
                "from generate_series(1, 80) n",
        )
        c.sembrar(
            "insert into public.contenido_desbloqueado_usuario (usuario_id, examen_id, motivo, valido_desde) " +
                "select '$UA', ('52000000-0000-0000-0000-' || lpad(n::text, 12, '0'))::uuid, 'compra', now() - make_interval(mins => n) from generate_series(1, 80) n",
        )
        fun tam(limite: Int?) = c.coleccion(UA, limite = limite).size
        assertEquals(51, tam(1000000), "un limite enorme se recorta a 51 (50 + la fila de anticipacion)")
        assertEquals(51, tam(52))
        assertEquals(51, tam(51))
        assertEquals(50, tam(50))
        assertEquals(21, tam(null), "sin limite: 21")
        assertEquals(1, tam(0))
        assertEquals(1, tam(-7))
        assertEquals("L1", c.coleccion(UA, limite = 1).single().titulo, "la mas reciente")
        // Los grupos son exactos sobre los 80 (sin comunidad no hay grupo): aqui, ninguno; el recuento no depende del limite.
        assertEquals("[]", c.grupos(UA, limite = 1))
        c.sembrar("update public.examenes set comunidad_id = '$C1' where id::text like '52000000-%'")
        assertEquals("""[{"total": 80, "comunidad_codigo": "MAD"}]""", c.grupos(UA, limite = 1), "80 examenes, no los de la pagina")
    }

    // ------------------------------------------------------------------ filtros

    @Test
    fun filtersNarrowTheItemsAndTheGroups() = escenario { c ->
        c.nuevoExamen(1, tipo = "rafaga", comunidad = C1, anio = 2024)
        c.nuevoExamen(2, tipo = "razonado", comunidad = C1, anio = 2023)
        c.nuevoExamen(3, tipo = "rafaga", comunidad = C2, anio = 2023)
        c.nuevoExamen(4, tipo = "inmersivo", comunidad = null, anio = 2024)
        for (n in 1..4) c.concesion(UA, n, "now() - interval '$n hour'")
        assertEquals(listOf("X1", "X2", "X3", "X4"), c.titulos(UA))
        assertEquals(listOf("X1", "X3"), c.titulos(UA, tipo = "rafaga"))
        assertEquals(listOf("X1", "X2"), c.titulos(UA, comunidad = C1))
        assertEquals(listOf("X2", "X3"), c.titulos(UA, anio = 2023))
        assertEquals(listOf("X3"), c.titulos(UA, tipo = "rafaga", comunidad = C2, anio = 2023))
        assertEquals(emptyList(), c.titulos(UA, tipo = "no-existe"))
        assertEquals("""[{"total": 1, "comunidad_codigo": "AND"}, {"total": 2, "comunidad_codigo": "MAD"}]""", c.grupos(UA), "el examen sin comunidad no esta en ningun grupo")
        assertEquals("""[{"total": 1, "comunidad_codigo": "AND"}, {"total": 1, "comunidad_codigo": "MAD"}]""", c.grupos(UA, tipo = "rafaga"))
        assertEquals("[]", c.grupos(UA, tipo = "inmersivo"))
        assertEquals("null", c.unico("select (${llamada(UA)} -> 'grupos')::text"), "sin p_con_grupos: null de JSON")
        assertNull(c.unico("select ${llamada(UA)} ->> 'grupos'"))
    }

    // ------------------------------------------------------------------ privilegios

    @Test
    fun onlyServiceRoleCanExecuteTheReadAndItNeverWidensTheOwnerFilter() = escenario { c ->
        c.nuevoExamen(1)
        c.concesion(UA, 1, "now() - interval '1 hour'")
        for (rol in listOf("anon", "authenticated")) {
            c.ejecutar("set role $rol")
            try {
                assertEquals("42501", c.sqlStateDe("select ${llamada(UA)}"), "$rol no ejecuta la lectura, ni para su propio id")
                assertEquals("42501", c.sqlStateDe("select ${llamada(null)}"), rol)
            } finally {
                c.ejecutar("reset role")
            }
        }
        c.ejecutar("set role service_role")
        try {
            assertEquals(listOf("X1"), c.titulos(UA), "el camino del servidor esta abierto")
            assertEquals(emptyList(), c.titulos(UB))
        } finally {
            c.ejecutar("reset role")
        }
        assertEquals(
            listOf(listOf("t", "s", "jsonb", "t")),
            c.filas("select prosecdef::text, provolatile::text, prorettype::regtype::text, (proconfig @> array['search_path=\"\"'])::text from pg_proc where oid = '$FIRMA'::regprocedure")
                .map { f -> listOf(f[0]?.take(1), f[1], f[2], f[3]?.take(1)) },
            "SECURITY DEFINER, STABLE (no puede escribir), search_path vacio",
        )

        // Control negativo: el 42501 es el REVOKE de la migracion. Con un GRANT, `authenticated` leeria la coleccion de
        // CUALQUIER usuario (la funcion es SECURITY DEFINER y confia en el id que le pasa Ktor): por eso es solo de servidor.
        c.ejecutar("grant execute on function $FIRMA to authenticated")
        c.ejecutar("set role authenticated")
        try {
            assertEquals(listOf("X1"), c.titulos(UA))
        } finally {
            c.ejecutar("reset role")
        }
        assertEquals("1", c.filas(Slice0Db.verifyDe(MY_EXAMS)).single { it[0] == "my_exams_rpc_executable_by_clients" }[1], "y el VERIFY lo detecta")
    }

    // ------------------------------------------------------------------ camino de acceso (por que no hay indice nuevo)

    @Test
    fun theTwoOwnerLookupsUseIndexesThatAlreadyExistOnMain() = escenario { c ->
        fun plan(sql: String): String {
            c.ejecutar("set enable_seqscan = off")
            try {
                return c.filas("explain $sql").joinToString("\n") { it[0].orEmpty() }
            } finally {
                c.ejecutar("reset enable_seqscan")
            }
        }
        val concesiones = plan("select cd.examen_id, max(cd.valido_desde) from public.contenido_desbloqueado_usuario cd where cd.usuario_id = '$UA' and cd.examen_id is not null group by cd.examen_id")
        assertTrue("idx_contenido_desbloqueado_usuario_usuario_id" in concesiones, concesiones)
        val intentos = plan(
            "select distinct on (ie.examen_id) ie.examen_id, ie.id from public.intentos_examen ie where ie.usuario_id = '$UA' and ie.examen_id is not null " +
                "and ie.origen_intento = 'examen' and ie.creado_por_comando is not null order by ie.examen_id, ie.actualizado_en desc, ie.id desc",
        )
        assertTrue("idx_intentos_examen_usuario_examen" in intentos || "ux_intentos_examen_abierto" in intentos || "idx_intentos_usuario" in intentos, intentos)
        // La migracion no crea ningun indice ni ninguna tabla: los mismos antes y despues.
        val objetos = "select string_agg(c.relname, ',' order by c.relname) from pg_class c where c.relnamespace = 'public'::regnamespace and c.relkind in ('r', 'i', 'S', 'v')"
        val con = c.unico(objetos)
        c.ejecutar(Slice0Db.rollbackDe(MY_EXAMS))
        assertEquals(con, c.unico(objetos), "ni tablas ni indices: solo la funcion")
    }

    // ------------------------------------------------------------------ preflight, VERIFY, rollback, idempotencia

    @Test
    fun theMigrationStopsWithoutTheCommandMarkerAndAppliesNothing() = Slice3Db.escenario(endurecimiento = false) { c ->
        val error = runCatching { c.aplicar(MY_EXAMS) }.exceptionOrNull() as? SQLException
        assertNotNull(error, "la migracion debe detenerse sin 20261008100200")
        c.ejecutar("rollback")
        assertTrue(error.message.orEmpty().contains("SLICE2_MY_EXAMS_PREFLIGHT"), error.message)
        assertTrue(error.message.orEmpty().contains("intentos_examen.creado_por_comando"), "dice que falta: ${error.message}")
        assertEquals("0", c.unico("select count(*) from pg_proc where proname = 'app_my_exams_collection'"))
        c.aplicar(Slice3Db.HARDENING)
        c.aplicar(MY_EXAMS)
        assertEquals("1", c.unico("select count(*) from pg_proc where proname = 'app_my_exams_collection'"))
    }

    @Test
    fun verifyIsZeroIsIdempotentDetectsEveryMutationAndTheRollbackDropsOnlyTheFunction() = Slice3Db.escenario { c ->
        val funciones = "select string_agg(p.oid::regprocedure::text, ',' order by p.oid::regprocedure::text) from pg_proc p where p.pronamespace in ('public'::regnamespace, 'private'::regnamespace)"
        val antes = c.unico(funciones)
        c.aplicar(MY_EXAMS)
        c.aplicar(MY_EXAMS) // idempotente
        fun verify(): Map<String, String> = c.filas(Slice0Db.verifyDe(MY_EXAMS)).associate { it[0]!! to it[1]!! }
        val limpio = verify()
        assertEquals(9, limpio.size, "filas del VERIFY: $limpio")
        assertEquals(emptyMap(), limpio.filterValues { it != "0" }, "VERIFY con contadores distintos de 0")
        assertEquals("2", c.filas(Slice0Db.verifyDe(MY_EXAMS)).single { it[0] == "my_exams_supporting_indexes_info" }[2], "los dos indices de MAIN en los que se apoya")

        // Controles negativos del VERIFY: cada guarda quitada del cuerpo se detecta por SU contador.
        val original = Slice0Db.leer(MY_EXAMS).replace("\r\n", "\n")
        fun mutado(de: String, a: String): Map<String, String> {
            assertTrue(de in original, "la mutacion no es vacua: $de")
            c.ejecutar(original.replace(de, a))
            return verify().filterValues { it != "0" }.also { c.aplicar(MY_EXAMS) }
        }
        assertEquals(
            mapOf("my_exams_rpc_without_published_guard" to "1"),
            mutado("    where e.activo and e.publicado_en is not null and e.publicado_en <= v_ahora\n", "    where true\n"),
        )
        assertEquals(
            mapOf("my_exams_rpc_without_owner_or_marker_guard" to "1"),
            mutado("      and ie.origen_intento = 'examen' and ie.creado_por_comando is not null\n", ""),
        )
        assertEquals(
            mapOf("my_exams_rpc_without_owner_or_marker_guard" to "1"),
            mutado("    where cd.usuario_id = p_usuario_id and cd.examen_id is not null\n", "    where cd.examen_id is not null\n"),
        )
        assertEquals(
            mapOf("my_exams_rpc_without_bounded_limit" to "1"),
            mutado("least(greatest(coalesce(p_limite, 21), 1), 51)", "coalesce(p_limite, 21)"),
        )
        assertEquals(
            mapOf("my_exams_rpc_wrong_security_or_volatility" to "1"),
            mutado("language plpgsql\nstable\nsecurity definer\n", "language plpgsql\nsecurity definer\n"),
        )
        assertEquals(
            mapOf("my_exams_rpc_writes_or_reads_question_content" to "1"),
            mutado("    left join public.comunidades_autonomas ca on ca.id = e.comunidad_id\n", "    left join public.comunidades_autonomas ca on ca.id = e.comunidad_id\n    left join public.preguntas_examen pe on pe.examen_id = e.id\n"),
        )
        assertEquals(emptyMap(), verify().filterValues { it != "0" }, "re-aplicada la migracion real, VERIFY vuelve a 0")
        c.ejecutar("revoke execute on function $FIRMA from service_role")
        assertEquals(mapOf("my_exams_rpc_not_executable_by_service_role" to "1"), verify().filterValues { it != "0" })

        c.ejecutar(Slice0Db.rollbackDe(MY_EXAMS))
        assertEquals(antes, c.unico(funciones), "el rollback deja exactamente las funciones de antes")
        assertEquals(mapOf("my_exams_rpc_missing" to "1"), verify().filterValues { it != "0" })
        c.ejecutar(Slice0Db.rollbackDe(MY_EXAMS)) // idempotente tambien
        c.aplicar(MY_EXAMS)
        assertEquals(emptyMap(), verify().filterValues { it != "0" })
    }
}
