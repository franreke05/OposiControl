package com.oposicontrol.dbtest.slice1

import com.oposicontrol.dbtest.PostgresHarness
import com.oposicontrol.dbtest.SupabaseFixture.USER_A
import com.oposicontrol.dbtest.SupabaseFixture.USER_B
import com.oposicontrol.dbtest.SupabaseFixture.USER_C
import com.oposicontrol.dbtest.ejecutar
import com.oposicontrol.dbtest.slice0.aplicar
import com.oposicontrol.dbtest.slice0.filas
import com.oposicontrol.dbtest.slice1.Slice1Db.COM1
import com.oposicontrol.dbtest.slice1.Slice1Db.COM2
import com.oposicontrol.dbtest.slice1.Slice1Db.COM3
import com.oposicontrol.dbtest.slice1.Slice1Db.DOC_COOKIES
import com.oposicontrol.dbtest.slice1.Slice1Db.DOC_OLD
import com.oposicontrol.dbtest.slice1.Slice1Db.DOC_PRIV
import com.oposicontrol.dbtest.slice1.Slice1Db.DOC_TERMS
import com.oposicontrol.dbtest.slice1.Slice1Db.ESP1
import com.oposicontrol.dbtest.slice1.Slice1Db.ESP2
import com.oposicontrol.dbtest.slice1.Slice1Db.ESP_OFF
import com.oposicontrol.dbtest.slice1.Slice1Db.NOEXISTE
import com.oposicontrol.dbtest.slice1.Slice1Db.Res
import com.oposicontrol.dbtest.slice1.Slice1Db.USER_D
import com.oposicontrol.dbtest.slice1.Slice1Db.USER_E
import com.oposicontrol.dbtest.slice1.Slice1Db.aceptar
import com.oposicontrol.dbtest.slice1.Slice1Db.estadoComo
import com.oposicontrol.dbtest.slice1.Slice1Db.llamar
import com.oposicontrol.dbtest.slice1.Slice1Db.n
import com.oposicontrol.dbtest.slice1.Slice1Db.onboarding
import com.oposicontrol.dbtest.slice1.Slice1Db.patch
import com.oposicontrol.dbtest.slice1.Slice1Db.sembrar
import com.oposicontrol.dbtest.unico
import java.sql.Connection
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Comandos de perfil (app_completar_onboarding, app_actualizar_perfil, app_aceptar_documentos_legales)
 * sobre PostgreSQL embebido: camino feliz, idempotencia, atomicidad, concurrencia, puerta de estado
 * (activa/bloqueada/pendiente/desconocido/sin perfil), errores estables y fronteras de privilegios.
 */

class Slice1ProfileRpcMigrationTest {

    private fun <T> escenario(bloque: (Connection) -> T): T = Slice1Db.escenario { c ->
        c.sembrar()
        bloque(c)
    }

    private fun Connection.perfil(uid: String): List<String?> = filas(
        "select username, nombre, apellidos, especialidad_principal_id::text, onboarding_completado::text, estado_cuenta, " +
            "perfil_intro_visto_en::text, comunidad_principal_id::text from public.perfiles_usuario where usuario_id = '$uid'",
    ).single()

    private fun Connection.escrituras(): List<Long> = listOf(
        n("select count(*) from public.aceptaciones_legales"),
        n("select count(*) from public.atribucion_usuario"),
        n("select count(*) from public.perfiles_usuario_detalle"),
        n("select count(*) from public.comunidades_objetivo_usuario"),
        n("select count(*) from public.solicitudes_eliminacion_cuenta"),
        n("select count(*) from public.auditoria_log"),
    )

    private fun Res.assertError(sqlState: String, mensaje: String, contexto: String = "") {
        assertEquals(sqlState to mensaje, this.sqlState to this.mensaje, "$contexto -> $this")
    }

    // ---------------------------------------------------------------- onboarding

    @Test
    fun onboardingFelizEscribeTodoEnUnaTransaccionConTiempoDeServidor(): Unit = escenario { c ->
        val r = c.onboarding(
            USER_A, referral = "AMIGO-1",
            attribution = """{"fuente":"ads","canal":"cpc","campania":"otono","plataforma":"android","installReferrer":"utm_source=x"}""",
        )
        assertTrue(r.ok, r.toString())
        assertEquals("true", r.campo("onboardingCompleted"))
        assertEquals("ana_g", r.campo("username"))
        assertEquals(ESP1, r.campo("especialidadPrincipalId"))
        assertEquals("activa", r.campo("estadoCuenta"))
        assertEquals(null, r.campo("replay"))
        assertEquals(listOf("ana_g", "Ana", "Garcia", ESP1, "true", "activa", null, null), c.perfil(USER_A))

        assertEquals(2L, c.n("select count(*) from public.aceptaciones_legales where usuario_id = '$USER_A' and plataforma = 'android'"))
        assertEquals(
            setOf("terminos|v2", "privacidad|v2"),
            c.filas("select tipo_documento || '|' || version from public.aceptaciones_legales where usuario_id = '$USER_A'").map { it[0]!! }.toSet(),
        )
        // aceptado_en lo pone el servidor (now() de la transaccion), nunca un valor del cliente.
        assertEquals(0L, c.n("select count(*) from public.aceptaciones_legales where aceptado_en > now() or aceptado_en < now() - interval '1 minute'"))
        assertEquals(
            listOf("ads", "cpc", "otono", "android", "utm_source=x", "AMIGO-1", null),
            c.filas("select fuente, canal, campania, plataforma, install_referrer, codigo_referido, referido_por::text from public.atribucion_usuario where usuario_id = '$USER_A'").single(),
        )
        // Sin atribucion ni referido: no crea fila de atribucion.
        assertTrue(c.onboarding(USER_D, username = "dora", docs = "array[]::uuid[]", plataforma = null, esp = null).ok)
        assertEquals(0L, c.n("select count(*) from public.atribucion_usuario where usuario_id = '$USER_D'"))
        assertNull(c.perfil(USER_D)[3])
    }

    @Test
    fun onboardingEsIdempotenteYDetectaConflictos(): Unit = escenario { c ->
        val primera = c.onboarding(USER_A, attribution = """{"fuente":"ads"}""")
        assertTrue(primera.ok)
        val antes = c.escrituras()
        val etag = primera.campo("actualizadoEn")

        // Repeticion identica: 200, perfil intacto, misma marca de version. Desde 20260930100500 (MASTER-DB-008)
        // el replay SI aplica documentos/atribucion del cuerpo; aqui DOC_TERMS ya estaba aceptado y la
        // atribucion es write-once, asi que no hay escrituras nuevas (ver Slice1AuditHardeningMigrationTest).
        val replay = c.onboarding(USER_A, attribution = """{"fuente":"otra"}""", docs = "array['$DOC_TERMS']::uuid[]")
        assertTrue(replay.ok, replay.toString())
        assertEquals("true", replay.campo("replay"))
        assertEquals(etag, replay.campo("actualizadoEn"))
        assertEquals(antes, c.escrituras())
        assertEquals("ads", c.unico("select fuente from public.atribucion_usuario where usuario_id = '$USER_A'"), "atribucion write-once")
        // Sin especialidad en el cuerpo tambien es identico.
        assertEquals("true", c.onboarding(USER_A, esp = null).campo("replay"))

        // Datos distintos -> conflicto estable y sin cambios.
        for (distinto in listOf(
            c.onboarding(USER_A, nombre = "Anita"),
            c.onboarding(USER_A, username = "otro_user"),
            c.onboarding(USER_A, apellidos = "Lopez"),
            c.onboarding(USER_A, esp = ESP2),
        )) distinto.assertError("OC409", "ALREADY_ONBOARDED_CONFLICT")
        assertEquals(listOf("ana_g", "Ana", "Garcia", ESP1, "true", "activa", null, null), c.perfil(USER_A))
        assertEquals(antes, c.escrituras())
    }

    @Test
    fun onboardingValidaYNoDejaEscrituraParcial(): Unit = escenario { c ->
        val antes = c.escrituras()
        val perfilAntes = c.perfil(USER_A)
        val casos = listOf(
            c.onboarding(USER_A, docs = "array['$DOC_TERMS','$DOC_OLD']::uuid[]") to ("22023" to "DOCUMENT_NOT_ACTIVE"),
            c.onboarding(USER_A, docs = "array['$NOEXISTE']::uuid[]") to ("22023" to "DOCUMENT_NOT_ACTIVE"),
            c.onboarding(USER_A, esp = ESP_OFF) to ("22023" to "UNKNOWN_SPECIALTY"),
            c.onboarding(USER_A, esp = NOEXISTE) to ("22023" to "UNKNOWN_SPECIALTY"),
            c.onboarding(USER_A, username = "ab") to ("22023" to "INVALID_ARGUMENT"),
            c.onboarding(USER_A, username = "con espacio") to ("22023" to "INVALID_ARGUMENT"),
            c.onboarding(USER_A, nombre = "   ") to ("22023" to "INVALID_ARGUMENT"),
            c.onboarding(USER_A, apellidos = "x".repeat(101)) to ("22023" to "INVALID_ARGUMENT"),
            c.onboarding(USER_A, plataforma = "web") to ("22023" to "INVALID_ARGUMENT"),
            c.onboarding(USER_A, attribution = """{"plataforma":"symbian"}""") to ("22023" to "INVALID_ARGUMENT"),
            c.onboarding(USER_A, attribution = """{"fuente":"${"x".repeat(501)}"}""") to ("22023" to "INVALID_ARGUMENT"),
            c.onboarding(USER_A, referral = "r".repeat(65)) to ("22023" to "INVALID_ARGUMENT"),
        )
        casos.forEachIndexed { i, (res, esperado) -> res.assertError(esperado.first, esperado.second, "caso $i") }
        assertEquals(antes, c.escrituras())
        assertEquals(perfilAntes, c.perfil(USER_A))
    }

    @Test
    fun onboardingEsAtomicoSiFallaUnPasoPosterior(): Unit = escenario { c ->
        // Fallo forzado en el UPDATE final del perfil (despues de insertar las aceptaciones).
        c.ejecutar(
            """
            create function public.zz_fallo() returns trigger language plpgsql as 'begin raise exception using errcode = ''XX001'', message = ''FALLO_FORZADO''; end;';
            create trigger zz_fallo before update on public.perfiles_usuario for each row execute function public.zz_fallo();
            """.trimIndent(),
        )
        val r = c.onboarding(USER_A, attribution = """{"fuente":"ads"}""")
        r.assertError("XX001", "FALLO_FORZADO")
        assertEquals(0L, c.n("select count(*) from public.aceptaciones_legales"), "las aceptaciones se revierten con el perfil")
        assertEquals(0L, c.n("select count(*) from public.atribucion_usuario"))
        assertEquals("false", c.perfil(USER_A)[4])
    }

    @Test
    fun usernameEsUnicoSinDistinguirMayusculasYEnCarreraSoloGanaUno(): Unit = escenario { c ->
        assertTrue(c.onboarding(USER_A, username = "Ana_G").ok)
        c.onboarding(USER_D, username = "ana_g").assertError("23505", "USERNAME_TAKEN")
        c.patch(USER_D, """{"username":"ANA_g"}""").assertError("23505", "USERNAME_TAKEN")
        assertEquals("false", c.perfil(USER_D)[4])

        // Carrera real: dos usuarios reclaman el mismo username a la vez.
        c.ejecutar("insert into auth.users (id, email) values ('$USER_E', 'e@test.local') on conflict do nothing")
        c.ejecutar("insert into public.perfiles_usuario (usuario_id) values ('$USER_E') on conflict do nothing")
        val cx = listOf(PostgresHarness.conexionExtra(c), PostgresHarness.conexionExtra(c))
        val barrera = CyclicBarrier(2)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val futuros = listOf(USER_D to cx[0], USER_E to cx[1]).map { (uid, con) ->
                pool.submit<Slice1Db.Res> {
                    barrera.await()
                    con.onboarding(uid, username = "carrera_x", docs = "array[]::uuid[]", plataforma = null)
                }
            }
            val res = futuros.map { it.get(30, TimeUnit.SECONDS) }
            assertEquals(1, res.count { it.ok }, res.toString())
            res.single { !it.ok }.assertError("23505", "USERNAME_TAKEN")
        } finally {
            pool.shutdownNow()
            cx.forEach { it.close() }
        }
        assertEquals(1L, c.n("select count(*) from public.perfiles_usuario where lower(username) = 'carrera_x'"))
    }

    // ---------------------------------------------------------------- puerta de estado

    @Test
    fun puertaDeEstadoDenegadaPorDefectoEnLosTresComandos(): Unit = escenario { c ->
        // Estado desconocido: se quita el CHECK en esta base desechable para simular un valor futuro/corrupto.
        c.ejecutar("alter table public.perfiles_usuario drop constraint perfiles_usuario_estado_cuenta_chk")
        c.ejecutar("update public.perfiles_usuario set estado_cuenta = 'zzz' where usuario_id = '$USER_D'")
        val antes = c.escrituras()
        val estadoB = c.perfil(USER_B)
        val estadoC = c.perfil(USER_C)

        val esperado = mapOf(
            USER_B to ("42501" to "ACCOUNT_BLOCKED"),
            USER_C to ("42501" to "ACCOUNT_DELETION_PENDING"),
            USER_D to ("42501" to "FORBIDDEN"),
            USER_E to ("OC404" to "PROFILE_MISSING"),
        )
        for ((uid, err) in esperado) {
            c.onboarding(uid).assertError(err.first, err.second, "onboarding $uid")
            c.patch(uid, """{"nombre":"X"}""").assertError(err.first, err.second, "patch $uid")
            c.aceptar(uid, "array['$DOC_TERMS']::uuid[]").assertError(err.first, err.second, "aceptar $uid")
        }
        c.onboarding(NOEXISTE).assertError("OC404", "PROFILE_MISSING")
        c.llamar("select public.app_completar_onboarding(null, 'abc', 'A', 'B', null, null, null, null, null)::text")
            .assertError("22023", "INVALID_ARGUMENT")
        assertEquals(antes, c.escrituras())
        assertEquals(estadoB, c.perfil(USER_B))
        assertEquals(estadoC, c.perfil(USER_C))
        assertEquals("zzz", c.perfil(USER_D)[5])
        assertEquals(0L, c.n("select count(*) from public.perfiles_usuario where usuario_id = '$USER_E'"), "no crea perfiles")
    }

    // ---------------------------------------------------------------- perfil

    @Test
    fun cambiarEspecialidadReemplazaLaUnicaActivaSinTocarElHistorial(): Unit = escenario { c ->
        c.ejecutar(
            """
            create table public.intentos_examen (id serial primary key, usuario_id uuid, especialidad_id uuid, nota int);
            insert into public.intentos_examen (usuario_id, especialidad_id, nota) values ('$USER_A', '$ESP1', 7), ('$USER_A', '$ESP1', 9);
            """.trimIndent(),
        )
        val historial = c.filas("select * from public.intentos_examen order by id")
        assertTrue(c.onboarding(USER_A).ok)

        val r = c.patch(USER_A, """{"especialidadId":"$ESP2"}""")
        assertTrue(r.ok, r.toString())
        assertEquals(ESP2, r.campo("especialidadPrincipalId"))
        assertEquals(ESP2, c.perfil(USER_A)[3])
        assertEquals(historial, c.filas("select * from public.intentos_examen order by id"), "historial intacto")
        // Una sola columna, no un array.
        assertEquals(1L, c.n("select count(*) from information_schema.columns where table_name = 'perfiles_usuario' and column_name like 'especialidad%'"))

        c.patch(USER_A, """{"especialidadId":"$ESP_OFF"}""").assertError("22023", "UNKNOWN_SPECIALTY")
        c.patch(USER_A, """{"especialidadId":"$NOEXISTE"}""").assertError("22023", "UNKNOWN_SPECIALTY")
        c.patch(USER_A, """{"especialidadId":null}""").assertError("22023", "INVALID_ARGUMENT")
        c.patch(USER_A, """{"especialidadId":"no-es-uuid"}""").assertError("22023", "INVALID_ARGUMENT")
        assertEquals(ESP2, c.perfil(USER_A)[3])
    }

    @Test
    fun patchEsIdempotenteConEtagYPrecondicion(): Unit = escenario { c ->
        assertTrue(c.onboarding(USER_A).ok)
        val base = c.patch(USER_A, """{"nombre":"Ana Maria","introSeen":true,"comunidadPrincipalId":"$COM1"}""")
        assertTrue(base.ok, base.toString())
        val etag = base.campo("actualizadoEn")!!
        assertEquals(COM1, base.campo("comunidadPrincipalId"))
        val intro = c.perfil(USER_A)[6]

        // Repetir el mismo patch: nada cambia (ETag estable, intro no se re-sella).
        val otra = c.patch(USER_A, """{"nombre":"Ana Maria","introSeen":true,"comunidadPrincipalId":"$COM1"}""")
        assertEquals(etag, otra.campo("actualizadoEn"))
        assertEquals(intro, c.perfil(USER_A)[6])
        assertEquals(etag, c.patch(USER_A, "{}").campo("actualizadoEn"))
        assertEquals(etag, c.patch(USER_A, """{"introSeen":false}""").campo("actualizadoEn"))

        // If-Match: no coincide -> 412; coincide -> escribe.
        c.patch(USER_A, """{"nombre":"Otra"}""", ifMatch = "2000-01-01T00:00:00Z").assertError("OC412", "PRECONDITION_FAILED")
        assertEquals("Ana Maria", c.perfil(USER_A)[1])
        val ok = c.patch(USER_A, """{"nombre":"Otra"}""", ifMatch = etag)
        assertTrue(ok.ok, ok.toString())
        assertEquals("Otra", c.perfil(USER_A)[1])
        assertTrue(ok.campo("actualizadoEn") != etag)
        // El ETag viejo ya no vale.
        c.patch(USER_A, """{"nombre":"Otra2"}""", ifMatch = etag).assertError("OC412", "PRECONDITION_FAILED")
    }

    @Test
    fun patchDetalleYComunidadesObjetivoSonAtomicosYConSemanticaDeConjunto(): Unit = escenario { c ->
        assertTrue(c.onboarding(USER_A).ok)
        val r = c.patch(
            USER_A,
            """{"detail":{"edad":30,"ciudad":"Sevilla","estadoLaboral":"interino","centroTrabajo":"IES X","tiempoEstudioMin":90,"franja":"tarde","fechaExamenObjetivo":"2027-06-20"},"targetCommunityIds":["$COM2","$COM1","$COM2"]}""",
        )
        assertTrue(r.ok, r.toString())
        assertEquals(
            listOf("30", "Sevilla", "interino", "IES X", "90", "tarde", "2027-06-20"),
            c.filas("select edad::text, ciudad, estado_laboral, centro_trabajo, tiempo_disponible_estudio_minutos::text, franja_preferente_estudio, fecha_examen_objetivo::text from public.perfiles_usuario_detalle where usuario_id = '$USER_A'").single(),
        )
        assertEquals(
            listOf("$COM2|1", "$COM1|2"),
            c.filas("select comunidad_id || '|' || prioridad from public.comunidades_objetivo_usuario where usuario_id = '$USER_A' order by prioridad").map { it[0]!! },
        )
        val etag = r.campo("actualizadoEn")
        // Mismo patch -> no-op (ETag estable).
        assertEquals(etag, c.patch(USER_A, """{"detail":{"edad":30},"targetCommunityIds":["$COM2","$COM1"]}""").campo("actualizadoEn"))
        // Solo cambian las claves presentes; null borra; el conjunto se REEMPLAZA.
        assertTrue(c.patch(USER_A, """{"detail":{"ciudad":null,"edad":31},"targetCommunityIds":["$COM3"]}""").ok)
        assertEquals(
            listOf("31", null, "interino"),
            c.filas("select edad::text, ciudad, estado_laboral from public.perfiles_usuario_detalle where usuario_id = '$USER_A'").single(),
        )
        assertEquals(listOf(COM3), c.filas("select comunidad_id::text from public.comunidades_objetivo_usuario where usuario_id = '$USER_A'").map { it[0] })
        assertTrue(c.patch(USER_A, """{"targetCommunityIds":[]}""").ok)
        assertEquals(0L, c.n("select count(*) from public.comunidades_objetivo_usuario where usuario_id = '$USER_A'"))

        // Validaciones: todo o nada (un fallo tardio revierte el detalle ya escrito en la misma llamada).
        val detalle = c.filas("select * from public.perfiles_usuario_detalle where usuario_id = '$USER_A'")
        c.patch(USER_A, """{"detail":{"ciudad":"Madrid"},"targetCommunityIds":["$NOEXISTE"]}""").assertError("22023", "UNKNOWN_COMMUNITY")
        assertEquals(detalle, c.filas("select * from public.perfiles_usuario_detalle where usuario_id = '$USER_A'"))
        c.patch(USER_A, """{"comunidadPrincipalId":"$NOEXISTE"}""").assertError("22023", "UNKNOWN_COMMUNITY")
        c.patch(USER_A, """{"detail":{"edad":5}}""").assertError("22023", "INVALID_ARGUMENT")
        c.patch(USER_A, """{"detail":{"edad":"treinta"}}""").assertError("22023", "INVALID_ARGUMENT")
        c.patch(USER_A, """{"detail":{"tiempoEstudioMin":-1}}""").assertError("22023", "INVALID_ARGUMENT")
        c.patch(USER_A, """{"detail":{"fechaExamenObjetivo":"2027-13-40"}}""").assertError("22023", "INVALID_ARGUMENT")
        c.patch(USER_A, """{"detail":{"rango":1}}""").assertError("22023", "UNKNOWN_FIELD")
        c.patch(USER_A, """{"targetCommunityIds":"x"}""").assertError("22023", "INVALID_ARGUMENT")
    }

    @Test
    fun patchRechazaCamposDelServidorYTiposIncorrectos(): Unit = escenario { c ->
        assertTrue(c.onboarding(USER_A).ok)
        val antes = c.perfil(USER_A)
        for (campo in listOf("estadoCuenta", "estado_cuenta", "accountStatus", "onboardingCompleted", "roles", "roleIds", "saldo", "id", "creadoEn", "actualizadoEn", "esInvitado")) {
            c.patch(USER_A, """{"$campo":"x"}""").assertError("22023", "UNKNOWN_FIELD", campo)
        }
        c.patch(USER_A, "[]").assertError("22023", "INVALID_ARGUMENT")
        c.llamar("select public.app_actualizar_perfil('$USER_A', null, null)::text").assertError("22023", "INVALID_ARGUMENT")
        c.patch(USER_A, """{"nombre":""}""").assertError("22023", "INVALID_ARGUMENT")
        c.patch(USER_A, """{"nombre":5}""").assertError("22023", "INVALID_ARGUMENT")
        c.patch(USER_A, """{"username":"a"}""").assertError("22023", "INVALID_ARGUMENT")
        c.patch(USER_A, """{"introSeen":"si"}""").assertError("22023", "INVALID_ARGUMENT")
        assertEquals(antes, c.perfil(USER_A))
    }

    // ---------------------------------------------------------------- aceptaciones

    @Test
    fun aceptarDocumentosEsIdempotenteYDevuelvePendientesSoloTerminosYPrivacidad(): Unit = escenario { c ->
        val r1 = c.aceptar(USER_A, "array['$DOC_TERMS']::uuid[]")
        assertTrue(r1.ok, r1.toString())
        assertEquals(1, Regex("\"id\"").findAll(r1.valor!!).count(), r1.valor)
        assertTrue(r1.valor!!.contains(DOC_PRIV) && !r1.valor!!.contains(DOC_TERMS) && !r1.valor!!.contains(DOC_COOKIES))
        val fila = c.filas("select aceptado_en::text, plataforma, tipo_documento, version from public.aceptaciones_legales where usuario_id = '$USER_A'").single()

        // Repetir: no duplica ni cambia aceptado_en.
        assertTrue(c.aceptar(USER_A, "array['$DOC_TERMS','$DOC_TERMS']::uuid[]", "android").ok)
        assertEquals(fila, c.filas("select aceptado_en::text, plataforma, tipo_documento, version from public.aceptaciones_legales where usuario_id = '$USER_A'").single())

        val r2 = c.aceptar(USER_A, "array['$DOC_PRIV']::uuid[]")
        assertEquals("""{"pendingLegalDocuments": []}""", r2.valor)
        assertEquals(2L, c.n("select count(*) from public.aceptaciones_legales where usuario_id = '$USER_A'"))

        val antes = c.escrituras()
        c.aceptar(USER_A, "array['$DOC_OLD']::uuid[]").assertError("22023", "DOCUMENT_NOT_ACTIVE")
        c.aceptar(USER_A, "array['$DOC_PRIV','$NOEXISTE']::uuid[]").assertError("22023", "DOCUMENT_NOT_ACTIVE")
        c.aceptar(USER_A, "array[]::uuid[]").assertError("22023", "INVALID_ARGUMENT")
        c.aceptar(USER_A, "null::uuid[]").assertError("22023", "INVALID_ARGUMENT")
        c.aceptar(USER_A, "array['$DOC_TERMS','$DOC_PRIV','$DOC_OLD','$DOC_COOKIES','$NOEXISTE','${Slice1Db.ESP1}']::uuid[]").assertError("22023", "INVALID_ARGUMENT")
        c.aceptar(USER_A, "array['$DOC_TERMS']::uuid[]", "web").assertError("22023", "INVALID_ARGUMENT")
        c.aceptar(USER_A, "array['$DOC_TERMS']::uuid[]", null).assertError("22023", "INVALID_ARGUMENT")
        assertEquals(antes, c.escrituras())
    }

    // ---------------------------------------------------------------- fronteras de privilegios

    @Test
    fun soloServiceRoleEjecutaLosComandosYLasTablasSonDenyAllParaClientes(): Unit = escenario { c ->
        val rpcs = listOf(
            "public.app_completar_onboarding(uuid,text,text,text,uuid,uuid[],text,jsonb,text)",
            "public.app_actualizar_perfil(uuid,jsonb,timestamptz)",
            "public.app_aceptar_documentos_legales(uuid,uuid[],text)",
            "public.app_solicitar_eliminacion_cuenta(uuid)",
            "public.app_registrar_revocacion_sesiones(uuid,text)",
        )
        for (f in rpcs) {
            assertEquals("true", c.unico("select has_function_privilege('service_role', '$f', 'execute')::text"), f)
            for (rol in listOf("anon", "authenticated", "public")) {
                assertEquals("false", c.unico("select has_function_privilege('$rol', '$f', 'execute')::text"), "$rol $f")
            }
            assertEquals("true", c.unico("select prosecdef::text from pg_proc where oid = '$f'::regprocedure"), f)
            assertEquals("true", c.unico("select ('search_path=\"\"' = any (proconfig))::text from pg_proc where oid = '$f'::regprocedure"), f)
            assertEquals(0L, c.n("select count(*) from pg_proc where oid = '$f'::regprocedure and prosrc ilike '%auth.users%'"))
        }
        // Un usuario autenticado no puede invocar los comandos ni siquiera sobre si mismo.
        for (rol in listOf("anon", "authenticated")) {
            assertEquals("42501", c.estadoComo(rol, "select public.app_completar_onboarding('$USER_A','abc','A','B',null,null,null,null,null)", USER_A), rol)
            assertEquals("42501", c.estadoComo(rol, "select public.app_actualizar_perfil('$USER_A','{}'::jsonb,null)", USER_A), rol)
            assertEquals("42501", c.estadoComo(rol, "select public.app_aceptar_documentos_legales('$USER_A', array['$DOC_TERMS']::uuid[], 'ios')", USER_A), rol)
            assertEquals("42501", c.estadoComo(rol, "select * from public.app_solicitar_eliminacion_cuenta('$USER_A')", USER_A), rol)
        }
        // Los helpers privados no los ejecuta nadie salvo el propietario.
        for (rol in listOf("anon", "authenticated", "service_role")) {
            assertEquals("false", c.unico("select has_function_privilege('$rol', 'private.slice1_lock_profile(uuid,boolean)', 'execute')::text"), rol)
        }
        // Tablas nuevas y existentes de Slice 1: RLS activo, sin politicas, sin privilegios de cliente.
        val tablas = listOf("aceptaciones_legales", "atribucion_usuario", "perfiles_usuario_detalle", "comunidades_objetivo_usuario", "solicitudes_eliminacion_cuenta")
        assertTrue(c.onboarding(USER_A, attribution = """{"fuente":"ads"}""").ok)
        assertTrue(c.patch(USER_A, """{"detail":{"edad":30},"targetCommunityIds":["$COM1"]}""").ok)
        for (t in tablas) {
            assertEquals("true", c.unico("select relrowsecurity::text from pg_class where oid = 'public.$t'::regclass"), t)
            assertEquals(0L, c.n("select count(*) from pg_policies where schemaname = 'public' and tablename = '$t'"), t)
            for (rol in listOf("anon", "authenticated")) {
                for (p in listOf("select", "insert", "update", "delete")) {
                    assertEquals("false", c.unico("select has_table_privilege('$rol', 'public.$t', '$p')::text"), "$rol $t $p")
                }
                assertEquals("42501", c.estadoComo(rol, "select count(*) from public.$t", USER_A), "$rol $t")
            }
        }
        // Aunque un GRANT accidental abriera SELECT, RLS sin politicas devolveria 0 filas (deny-all real).
        c.ejecutar("grant select on public.perfiles_usuario_detalle, public.aceptaciones_legales to authenticated")
        c.ejecutar("select set_config('request.jwt.claim.sub', '$USER_A', false); set role authenticated")
        try {
            assertEquals(0L, c.n("select count(*) from public.perfiles_usuario_detalle"))
            assertEquals(0L, c.n("select count(*) from public.aceptaciones_legales"))
        } finally {
            c.ejecutar("reset role")
        }
        // Documentos legales: ningun cliente escribe (la lectura publica de activos ya existia en MAIN; Ktor lee con service_role).
        assertEquals(3L, c.n("select count(*) from public.documentos_legales where activo"), "fixture")
        assertEquals("42501", c.estadoComo("anon", "update public.documentos_legales set activo = true"))
    }

    @Test
    fun aceptacionesLegalesSonAppendOnlyYNoCascadeanDesdeAuthUsers(): Unit = escenario { c ->
        assertTrue(c.aceptar(USER_A, "array['$DOC_TERMS']::uuid[]").ok)
        // service_role: solo SELECT/INSERT. Ni UPDATE ni DELETE (sin privilegio).
        assertEquals("42501", c.estadoComo("service_role", "update public.aceptaciones_legales set plataforma = 'android'"))
        assertEquals("42501", c.estadoComo("service_role", "delete from public.aceptaciones_legales"))
        assertEquals("42501", c.estadoComo("service_role", "truncate public.aceptaciones_legales"))
        assertEquals(null, c.estadoComo("service_role", "select count(*) from public.aceptaciones_legales"))
        // Propietario: el trigger lo impide igualmente.
        assertEquals("42501", c.estadoComo("postgres", "update public.aceptaciones_legales set plataforma = 'android'"))
        assertEquals("42501", c.estadoComo("postgres", "delete from public.aceptaciones_legales"))
        // Retencion legal: borrar al usuario (auth.users) falla en vez de arrastrar las aceptaciones.
        assertEquals("23503", c.estadoComo("postgres", "delete from auth.users where id = '$USER_A'"))
        assertEquals(1L, c.n("select count(*) from public.aceptaciones_legales where usuario_id = '$USER_A'"))
        assertEquals(1L, c.n("select count(*) from auth.users where id = '$USER_A'"))
        // Unicidad (usuario, documento) tambien por SQL directo.
        assertEquals("23505", c.estadoComo("service_role", "insert into public.aceptaciones_legales (usuario_id, documento_id, tipo_documento, version, plataforma) values ('$USER_A', '$DOC_TERMS', 'terminos', 'v2', 'ios')"))
        assertEquals("23514", c.estadoComo("service_role", "insert into public.aceptaciones_legales (usuario_id, documento_id, tipo_documento, version, plataforma) values ('$USER_A', '$DOC_PRIV', 'terminos', 'v2', 'web')"))
    }

    @Test
    fun serviceRoleAuthUsersBroadSelectSigueDenegadoTrasSlice1(): Unit = escenario { c ->
        val aclAntes = c.unico("select coalesce(relacl::text, '') from pg_class where oid = 'auth.users'::regclass")
        assertTrue(c.onboarding(USER_A).ok)
        assertTrue(c.patch(USER_A, """{"nombre":"Z"}""").ok)
        assertTrue(c.aceptar(USER_A, "array['$DOC_TERMS']::uuid[]").ok)
        assertEquals(aclAntes, c.unico("select coalesce(relacl::text, '') from pg_class where oid = 'auth.users'::regclass"))
        for (rol in listOf("anon", "authenticated", "service_role")) {
            for (p in listOf("select", "insert", "update", "delete", "references", "trigger")) {
                assertEquals("false", c.unico("select has_table_privilege('$rol', 'auth.users', '$p')::text"), "$rol $p")
            }
            assertEquals("false", c.unico("select has_any_column_privilege('$rol', 'auth.users', 'select')::text"), rol)
            assertEquals("42501", c.estadoComo(rol, "select count(*) from auth.users"), rol)
        }
        // Ninguna funcion SECURITY DEFINER de Slice 1 referencia auth.users; los comandos funcionaron sin ese privilegio.
        assertEquals(
            0L,
            c.n("select count(*) from pg_proc p join pg_namespace n on n.oid = p.pronamespace where n.nspname in ('public','private') and (p.proname like 'app\\_%' or p.proname like 'slice1\\_%') and p.prosrc ilike '%auth.users%'"),
        )
    }

    // ---------------------------------------------------------------- migraciones 3: VERIFY / reaplicar / rollback

    @Test
    fun verifyReaplicarYRollbackDeLosComandos(): Unit = escenario { c ->
        assertTrue(c.onboarding(USER_A).ok)
        val verify = { com.oposicontrol.dbtest.slice0.Slice0Db.verifyDe(Slice1Db.RPCS) }
        fun verde() {
            val f = c.filas(verify()).associate { it[0]!! to it[1]!!.toLong() }
            assertEquals(4, f.size, f.toString())
            assertTrue(f.values.all { it == 0L }, "VERIFY: $f")
        }
        verde()
        c.aplicar(Slice1Db.RPCS) // reaplicar: sin error y con los mismos privilegios
        verde()
        assertEquals("true", c.onboarding(USER_A).campo("replay"), "sigue funcionando y los datos permanecen")

        // Rollback en orden inverso: primero la migracion posterior que re-crea estas RPCs (20260930100500).
        c.ejecutar(com.oposicontrol.dbtest.slice0.Slice0Db.rollbackDe(Slice1Db.HARDENING))
        c.ejecutar(com.oposicontrol.dbtest.slice0.Slice0Db.rollbackDe(Slice1Db.RPCS))
        assertEquals(0L, c.n("select count(*) from pg_proc where proname like 'app\\_%' and proname not like 'app\\_solicitar%' and proname not like 'app\\_registrar%'"))
        assertEquals(0L, c.n("select count(*) from pg_proc p join pg_namespace n on n.oid = p.pronamespace where n.nspname = 'private' and p.proname like 'slice1\\_%'"))
        assertEquals("ana_g", c.perfil(USER_A)[0], "el rollback no borra datos de perfil")
        c.ejecutar(com.oposicontrol.dbtest.slice0.Slice0Db.rollbackDe(Slice1Db.RPCS)) // idempotente
        c.aplicar(Slice1Db.RPCS)
        verde()
        assertEquals("true", c.onboarding(USER_A).campo("replay"))
    }
}
