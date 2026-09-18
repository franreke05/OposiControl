package com.oposicontrol.backend

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Invariantes administrativas de la sincronización de roles — **espejo y
 * detector de deriva, NO la autoridad**.
 *
 * Este fichero tiene dos mitades y conviene no confundirlas.
 *
 * La primera ataca [RoleSyncInvariants], que es el espejo en Kotlin de las
 * reglas. Prueba que la especificación dice lo que creemos que dice. No prueba
 * nada sobre la base de datos.
 *
 * La segunda lee el SQL del candidate y comprueba que ciertas piezas siguen ahí.
 * **Durante cinco revisiones esta mitad se presentó como la garantía, y no podía
 * serlo.** Cada ronda independiente encontró otra forma sintáctica de dejar la
 * guarda muerta con todas estas aserciones en verde: `raise notice` en lugar de
 * `raise exception`, un `case when false` de señuelo que satisfacía los tokens
 * del cuerpo, un `exception when others then null` que se tragaba el aborto
 * dejando confirmada la escritura, una condición imposible añadida al `if`, una
 * segunda definición en MAYÚSCULAS que pisaba a la guardada, y un abre-bloque de
 * comentario escrito dentro de un `--` que hacía que el propio limpiador se
 * comiera el neutralizador antes de buscarlo. Una comprobación de subcadena sobre un
 * fichero no es una comprobación semántica, y ninguna cantidad de expresiones
 * regulares adicionales lo arregla.
 *
 * La autoridad sobre las invariantes vive ahora en `:backend:dbtest`, que aplica
 * este mismo SQL a un PostgreSQL real y desechable y comprueba el ESTADO DE LAS
 * TABLAS: `RoleSyncSemanticTest` (último superadministrador, cero roles),
 * `RoleSyncMutationTest` (cada mutación mata la invariante de verdad),
 * `RoleSyncRlsFailOpenTest`, `RoleSyncConcurrencyTest` y
 * `RoleSyncRollbackAndCollationTest`.
 *
 * Lo que estos tests de texto siguen aportando, y por eso no se borran: avisan
 * de que el fichero revisado ha cambiado. Eso es útil. No es una garantía.
 */
class RoleSyncInvariantsTest {

    private val CATALOGO = setOf("superadmin", "admin", "moderador", "usuario")

    private fun state(
        current: Set<String>,
        requested: Set<String>,
        superadminsBefore: Int,
        known: Set<String> = CATALOGO,
    ) = RoleSyncInvariants.State(
        currentRoleCodes = current,
        requestedRoleCodes = requested,
        knownRoleCodes = known,
        superadminsBefore = superadminsBefore,
    )

    // --- 1. superadmin A + superadmin B: A puede convertirse en admin --------

    @Test
    fun conOtroSuperadminVivoDegradarseAAdminEsLegitimo() {
        // Es la decisión de producto explícita: lo prohibido no es cambiar de
        // superadmin a admin, sino quedarse sin ninguno.
        val rejection = RoleSyncInvariants.rejectionFor(
            state(current = setOf("superadmin"), requested = setOf("admin"), superadminsBefore = 2),
        )

        assertNull(rejection, "Con otro superadministrador vivo, degradarse es legítimo.")
    }

    // --- 2. sólo superadmin A: A intenta quitarse el rol --------------------

    @Test
    fun elUltimoSuperadminNoPuedeRetirarseASiMismo() {
        val rejection = RoleSyncInvariants.rejectionFor(
            state(current = setOf("superadmin"), requested = setOf("admin"), superadminsBefore = 1),
        )

        assertEquals(RoleSyncInvariants.Rejection.LAST_SUPERADMIN, rejection)
    }

    // --- 3. sólo superadmin A: otro administrador se lo quita ---------------

    @Test
    fun otroAdministradorTampocoPuedeRetirarElUltimoSuperadmin() {
        // La invariante no mira quién llama: el estado resultante es el mismo, y
        // por eso se evalúa sobre el sistema y no sobre el actor. Que el actor
        // esté autorizado a tocar roles lo decide `adminAuthorizationFailure`,
        // que es otra capa y tiene sus propias pruebas.
        val rejection = RoleSyncInvariants.rejectionFor(
            state(current = setOf("superadmin"), requested = setOf("usuario"), superadminsBefore = 1),
        )

        assertEquals(RoleSyncInvariants.Rejection.LAST_SUPERADMIN, rejection)
    }

    // --- 4. usuario administrativo con lista vacía --------------------------

    @Test
    fun unUsuarioAdministrativoNoPuedeQuedarseSinRoles() {
        val rejection = RoleSyncInvariants.rejectionFor(
            state(current = setOf("admin"), requested = emptySet(), superadminsBefore = 3),
        )

        assertEquals(RoleSyncInvariants.Rejection.EMPTY_ROLES, rejection)
    }

    @Test
    fun aUnUsuarioCorrienteSiSeLePuedenRetirarTodosLosRoles() {
        // No confundir «último rol del usuario» con «último rol privilegiado».
        // Vaciar los roles de un opositor no deja ninguna puerta administrativa
        // a medio cerrar, y prohibirlo sería una regla inventada.
        val rejection = RoleSyncInvariants.rejectionFor(
            state(current = setOf("usuario"), requested = emptySet(), superadminsBefore = 3),
        )

        assertNull(rejection)
    }

    // --- 5. cambio normal ---------------------------------------------------

    @Test
    fun unCambioQueNoTocaLasInvariantesPasa() {
        val rejection = RoleSyncInvariants.rejectionFor(
            state(current = setOf("usuario"), requested = setOf("usuario", "moderador"), superadminsBefore = 2),
        )

        assertNull(rejection)
    }

    // --- 6. caller no autorizado -------------------------------------------

    @Test
    fun laAutorizacionDelLlamanteEsUnaCapaDistintaYYaEstaCubierta() {
        // Este escenario del contrato no se decide aquí: un JWT sin rol admin ni
        // siquiera llega a la ruta de roles. Se afirma el límite para que quede
        // explícito qué capa responde de qué.
        val failure = adminAuthorizationFailure(
            principalName = "usuario-sin-rol",
            path = "/api/v1/admin/usuarios/00000000-0000-0000-0000-000000000000/roles",
            remoteAddress = "127.0.0.1",
            appEnv = "dev",
            hasAdminRole = { false },
        )

        assertTrue(failure is AppForbidden, "Sin rol admin no se alcanza la sincronización de roles.")
    }

    // --- 7. rol inexistente -------------------------------------------------

    @Test
    fun unRolQueNoExisteSeRechazaConNombrePropio() {
        // Sin esto saltaría la violación de clave ajena y llegaría al cliente
        // como un error opaco de base de datos.
        val rejection = RoleSyncInvariants.rejectionFor(
            state(current = setOf("usuario"), requested = setOf("usuario", "inventado"), superadminsBefore = 2),
        )

        assertEquals(RoleSyncInvariants.Rejection.UNKNOWN_ROLE, rejection)
    }

    // --- 8. idempotencia ----------------------------------------------------

    @Test
    fun reenviarLosMismosRolesNoSeRechaza() {
        val rejection = RoleSyncInvariants.rejectionFor(
            state(current = setOf("superadmin"), requested = setOf("superadmin"), superadminsBefore = 1),
        )

        assertNull(rejection, "Reenviar el mismo estado no cambia nada y no puede fallar.")
    }

    @Test
    fun mantenerElRolNoDescuentaSuperadministradores() {
        assertEquals(
            1,
            RoleSyncInvariants.superadminsAfter(
                state(current = setOf("superadmin"), requested = setOf("superadmin"), superadminsBefore = 1),
            ),
        )
    }

    // --- Casos frontera del recuento ---------------------------------------

    @Test
    fun conceperElPrimerSuperadminEnUnSistemaSinNingunoNoSeBloquea() {
        // Si se prohibiera «después = 0» a secas, un sistema que ya estuviera sin
        // superadministradores no podría crear el primero: quedaría inservible.
        // Lo prohibido es PROVOCAR la transición, no heredarla.
        val creandoElPrimero = RoleSyncInvariants.rejectionFor(
            state(current = setOf("usuario"), requested = setOf("superadmin"), superadminsBefore = 0),
        )
        assertNull(creandoElPrimero)

        val otraEdicionCualquiera = RoleSyncInvariants.rejectionFor(
            state(current = setOf("usuario"), requested = setOf("moderador"), superadminsBefore = 0),
        )
        assertNull(otraEdicionCualquiera)
    }

    @Test
    fun elCodigoDeRolSeNormalizaAntesDeDecidir() {
        val rejection = RoleSyncInvariants.rejectionFor(
            state(current = setOf("  SuperAdmin "), requested = setOf("ADMIN"), superadminsBefore = 1),
        )

        assertEquals(
            RoleSyncInvariants.Rejection.LAST_SUPERADMIN,
            rejection,
            "Un código con mayúsculas o espacios no puede esquivar la invariante.",
        )
    }

    @Test
    fun losCodigosAdministrativosSonLosCanonicos() {
        // Espejo explícito: si alguien añadiera un rol privilegiado nuevo sin
        // tocar `CanonicalAuthorization`, este test lo señala.
        assertEquals(setOf("superadmin", "admin"), RoleSyncInvariants.ADMINISTRATIVE_ROLE_CODES)
        assertEquals("superadmin", RoleSyncInvariants.SUPERADMIN_CODE)
    }

    // --- 9. concurrencia y autoridad: la garantía vive en el SQL -------------

    @Test
    fun laGarantiaDefinitivaSigueEnElSql() {
        // MUTACIÓN C del contrato: si alguien dejara la única comprobación en
        // Kotlin y vaciara la RPC, todos los tests de arriba seguirían verdes y
        // el agujero estaría abierto otra vez para cualquiera que llame a la RPC
        // con la clave de `service_role`. Este test es lo único que lo detecta.
        //
        // Contra el SQL SIN COMENTARIOS: la cabecera de este candidate cita el
        // cuerpo roto original, así que buscar sobre el texto completo daría por
        // buena una guarda que sólo existiera dentro de un comentario.
        val sql = candidateSqlCode()

        assertTrue(
            "ROLE_SYNC_LAST_SUPERADMIN" in sql,
            "La RPC ya no levanta la excepción del último superadministrador: la garantía " +
                "definitiva ha desaparecido del único sitio donde protege.",
        )
        assertTrue(
            "ROLE_SYNC_EMPTY_ROLES" in sql,
            "La RPC ya no rechaza dejar sin roles a un usuario administrativo.",
        )
        assertTrue(
            "v_superadmins_antes > 0 and v_superadmins_despues = 0" in sql,
            "La comprobación del último superadministrador ya no compara el estado anterior " +
                "con el resultante dentro de la transacción.",
        )
    }

    @Test
    fun laProteccionNoDependeDeDosConsultasIndependientes() {
        // La invariante es GLOBAL: dos transacciones que degraden a
        // superadministradores distintos no se ven entre sí, cada una cuenta uno
        // restante y al confirmar quedan cero. El cerrojo consultivo tomado antes
        // de la primera lectura es lo que impide ese entrelazado; sin él, la
        // comprobación es una foto vieja.
        val sql = candidateSqlCode()
        val lockAt = sql.indexOf("pg_advisory_xact_lock")
        val firstCountAt = sql.indexOf("select count(*) into v_desconocidos")

        assertTrue(lockAt > 0, "La RPC ya no serializa la sincronización de roles.")
        assertTrue(
            firstCountAt > 0 && lockAt < firstCountAt,
            "El cerrojo debe tomarse ANTES de la primera lectura: tomarlo después deja la " +
                "misma ventana de carrera que no tenerlo.",
        )
    }

    @Test
    fun elRecuentoDeSuperadminsSeHaceDespuesDeEscribir() {
        // La presencia de la comprobación no basta: si el recuento «después»
        // subiera por encima del `delete`, la invariante B pasaría a mirar el
        // estado ANTERIOR y dejaría de garantizar nada, con todos los demás
        // tests en verde. Es la mutación que los guards por subcadena no veían.
        //
        // Sobre el SQL SIN COMENTARIOS: la cabecera cita el cuerpo roto original
        // —`delete from public.roles_usuario ...`—, así que sobre el texto
        // completo el índice caía en el comentario y no en la sentencia.
        val sql = candidateSqlCode()
        val deleteAt = sql.indexOf("delete from public.roles_usuario")
        val afterCountAt = sql.indexOf("select count(*) into v_superadmins_despues")
        val beforeCountAt = sql.indexOf("select count(*) into v_superadmins_antes")

        assertTrue(deleteAt > 0 && afterCountAt > 0 && beforeCountAt > 0)
        assertTrue(
            beforeCountAt < deleteAt,
            "El recuento previo debe leerse antes de escribir.",
        )
        assertTrue(
            deleteAt < afterCountAt,
            "El recuento posterior debe hacerse DESPUÉS del delete/insert: si no, la " +
                "invariante B comprueba el estado anterior y no garantiza nada.",
        )
    }

    @Test
    fun lasTresComparacionesNormalizanPorElMismoCamino() {
        // La versión anterior comprobaba que la forma normalizada apareciese
        // ALGUNA vez en el fichero. Con eso, quitarla sólo de los dos recuentos
        // de superadmins y dejarla en la comprobación de usuario administrativo
        // pasaba en verde mientras la invariante B dejaba de normalizar. Se
        // cuentan los tres usos, y se prohíbe cualquier comparación cruda.
        val sql = candidateSqlCode()
        val usos = Regex("public\\.admin_role_code_canonical\\(r\\.codigo\\)").findAll(sql).count()

        assertEquals(
            3,
            usos,
            "El helper canónico debe decidir las TRES comparaciones de invariante; hay $usos.",
        )
        assertEquals(false, "btrim(" in sql, "Queda un btrim: la normalización debe pasar por el helper.")
        assertEquals(false, "lower(r.codigo" in sql, "Queda un lower(r.codigo) crudo: se salta el recorte de espacios.")
    }

    @Test
    fun elSqlEjecutableEsAsciiYConservaLosLiteralesCanonicos() {
        // Un homóglifo cirílico en 'superadmin' dejaría ambas invariantes
        // permanentemente inertes sin que ningún verificador lo notara.
        val sql = candidateSqlCode()

        assertTrue("'superadmin'" in sql, "Falta el literal 'superadmin'.")
        assertTrue("'admin'" in sql, "Falta el literal 'admin'.")
        assertTrue(
            sql.none { it.code > 127 },
            "El SQL ejecutable tiene caracteres no ASCII: un homóglifo en un literal de rol " +
                "haría inertes las dos invariantes con todo en verde.",
        )
    }

    @Test
    fun hayUnaSolaDefinicionDeCadaFuncion() {
        // Una segunda definición más abajo pisaría a la guardada, y las
        // aserciones por índice seguirían midiendo la primera.
        val sql = candidateSqlCode()

        assertEquals(
            1,
            Regex("create or replace function public\\.admin_sync_roles").findAll(sql).count(),
            "Debe haber exactamente una definición de admin_sync_roles.",
        )
        assertEquals(
            1,
            Regex("create or replace function public\\.admin_role_code_canonical").findAll(sql).count(),
            "Debe haber exactamente una definición del helper de normalización.",
        )
    }

    @Test
    fun cadaCodigoSeLevantaConRaiseExceptionYNoSoloAparece() {
        // Comprobar que la cadena ROLE_SYNC_* aparece no prueba nada: un
        // `raise notice` con el mismo texto deja la invariante completamente
        // inerte —no aborta la transacción— y pasaba en verde.
        val sql = candidateSqlCode()

        listOf(
            "ROLE_SYNC_LAST_SUPERADMIN",
            "ROLE_SYNC_EMPTY_ROLES",
            "ROLE_SYNC_UNKNOWN_ROLE",
            "ROLE_SYNC_INVALID_USER",
        ).forEach { codigo ->
            assertTrue(
                "raise exception '$codigo'" in sql,
                "$codigo no se levanta con `raise exception`: un `raise notice` no deshace nada.",
            )
        }
    }

    @Test
    fun laCondicionDeLaInvarianteBEsExactamenteEsa() {
        // Una lista negra de neutralizadores siempre se queda corta: añadir
        // `and v_desconocidos > 0` —imposible en ese punto, porque un valor
        // mayor que cero ya habría abortado antes— anula la invariante sin ser
        // `1=0` ni `and false`. Fijar la línea entera cierra la clase.
        assertTrue(
            "if v_superadmins_antes > 0 and v_superadmins_despues = 0 then" in candidateSqlCode(),
            "La condición del último superadministrador ha cambiado: cualquier conjunto extra " +
                "puede hacerla inalcanzable dejando su texto intacto.",
        )
    }

    @Test
    fun elCuerpoDelCanonicalizadorEstaAsegurado() {
        // Contar sus tres usos no sirve si el helper puede vaciarse a
        // `select coalesce(p_codigo,'')`: las dos invariantes quedarían ciegas a
        // 'SuperAdmin' y a "superadmin"+U+000C con todo en verde.
        val sql = candidateSqlCode()

        assertTrue("regexp_replace(" in sql, "El helper debe recortar con regexp_replace.")
        assertTrue("lower(" in sql, "El helper debe bajar a minúsculas.")
        assertTrue(
            "collate \"C\"" in sql,
            "Sin `collate \"C\"` el lower depende de la intercalación: bajo tr_TR deja de casar SUPERADMIN.",
        )
        listOf(
            "\\u0009-\\u000D", "\\u001C-\\u001F", "\\u00A0", "\\u1680",
            "\\u2000-\\u200A", "\\u2028", "\\u2029", "\\u202F", "\\u205F", "\\u3000",
        ).forEach { punto ->
            assertTrue(
                punto in sql,
                "Falta $punto en la clase de espacios: dejaría de ser equivalente a Char.isWhitespace().",
            )
        }
    }

    @Test
    fun laSincronizacionDeRolesValidaElIdentificadorAntesDeUsarlo() {
        // P0 reabierto una vez ya: validar sólo `roleIds` dejaba abierto el path.
        // `mapSupabaseDomainError` busca sus códigos como subcadena en TODO el
        // cuerpo que devuelve Supabase, así que un
        // `POST /usuarios/ROLE_SYNC_LAST_SUPERADMIN/roles` hacía que Postgres
        // repitiera esa cadena en «invalid input syntax for type uuid» y el
        // backend la tradujera a un 409 con el mensaje de la guarda, sin que la
        // guarda llegara a ejecutarse. Un 409 tiene que seguir siendo prueba de
        // que la invariante saltó.
        val rutas = repoFile("backend/ktor/src/main/kotlin/com/oposicontrol/backend/AdminV1Routes.kt")
        val cuerpo = rutas.substringAfter("fun syncRoles(").substringBefore("registrarAuditoria")

        assertTrue(
            "usuarioId.validatedUuid(" in cuerpo,
            "`syncRoles` ya no valida el identificador antes de usarlo: el 409 de la guarda " +
                "vuelve a ser falsificable desde el path.",
        )
    }

    @Test
    fun elPreflightDelAclObligaEnVezDeSugerir() {
        // `create or replace` conserva el ACL previo. Pedirle a un operador que
        // mire un volcado no es un control: la migración tiene que abortar.
        val sql = candidateSqlCode()

        assertTrue("aclexplode" in sql, "El preflight del ACL debe leer proacl de verdad.")
        assertTrue(
            "ROLE_SYNC_GUARD_UNEXPECTED_ACL" in sql,
            "La migración debe abortar ante un grantee heredado inesperado, no sólo documentarlo.",
        )
    }

    @Test
    fun ningunaGuardaQuedaPresenteYMuerta() {
        // `and 1=0` en el recuento previo, o `and false` en la condición, dejan
        // la invariante inerte con su texto intacto: los guards por subcadena
        // los aprobaban.
        val sql = candidateSqlCode()

        assertEquals(false, Regex("1\\s*=\\s*0").containsMatchIn(sql), "Hay un predicado 1=0 que anula una guarda.")
        assertEquals(false, Regex("and\\s+false").containsMatchIn(sql), "Hay un `and false` que anula una guarda.")
    }

    @Test
    fun laRpcEsExplicitamenteVolatil() {
        // Marcarla `stable` reinstauraría el TOCTOU que el cerrojo cierra, y
        // ningún otro test fallaría.
        assertTrue(
            Regex("(?m)^volatile$").containsMatchIn(candidateSqlCode()),
            "La RPC debe declarar `volatile` explícitamente.",
        )
    }

    @Test
    fun elCandidateTraeUnRollbackQueAvisaDeLoQueReabre() {
        val rollback = candidateFile("002_rollback_admin_sync_roles.sql")

        assertTrue("security invoker" in rollback, "El rollback debe restaurar el modelo de privilegios anterior.")
        assertTrue(
            "reabre SEC-006" in rollback,
            "El rollback debe decir sin rodeos que revertir reabre la vulnerabilidad.",
        )
    }

    @Test
    fun laRpcRechazaUnUsuarioQueNoExiste() {
        val sql = candidateSqlCode()

        assertTrue(
            "from auth.users u where u.id = p_usuario_id" in sql,
            "Sin comprobar la existencia del usuario, un uuid inventado con lista vacía " +
                "«tiene éxito» sin hacer nada y con lista no vacía devuelve un 500 opaco.",
        )
    }

    @Test
    fun laRpcSigueSiendoSecurityDefinerYSoloParaServiceRole() {
        val sql = candidateSqlCode()

        assertTrue("security definer" in sql, "La RPC debe ejecutarse con privilegios propios.")
        assertTrue(
            "revoke execute on function public.admin_sync_roles(uuid, uuid[])" in sql,
            "La RPC no puede quedar ejecutable por public/anon/authenticated.",
        )
        assertTrue("to service_role;" in sql, "La ejecución debe concederse sólo a service_role.")
        // Éste sí sobre el texto completo: es una declaración de cabecera, y vive
        // en un comentario a propósito.
        assertTrue(
            "APPLY_PENDING_APPROVAL" in candidateSql(),
            "El candidate debe seguir declarando que no está aplicado.",
        )
    }

    @Test
    fun losCodigosDelSqlYLosDeLaEspecificacionSonLosMismos() {
        // Si el SQL renombrara un código y la especificación no, el backend
        // dejaría de traducir el error y el operador vería un 500 genérico.
        val sql = candidateSqlCode()

        // `ROLE_SYNC_INVALID_USER` va aparte: no es un motivo que la
        // especificación pura pueda decidir —no recibe la existencia del
        // usuario—, pero desde que la RPC comprueba contra `auth.users` es una
        // ruta alcanzable de verdad, así que su código tiene que estar cubierto
        // igual que los otros tres.
        val codigos = RoleSyncInvariants.Rejection.entries.map { it.code } + "ROLE_SYNC_INVALID_USER"

        codigos.forEach { codigo ->
            assertTrue(
                codigo in sql,
                "El código '$codigo' no aparece en la RPC: el error llegaría sin traducir.",
            )
            assertNotNull(
                mapSupabaseDomainError(400, codigo),
                "El código '$codigo' no está en SUPABASE_DOMAIN_ERRORS: llegaría como 500.",
            )
        }
    }

    // --- localización del candidate ----------------------------------------

    /**
     * El SQL del candidate, buscando la raíz del repositorio hacia arriba.
     *
     * Ningún otro test de este módulo lee ficheros, así que no hay convención que
     * copiar: se sube desde el directorio de trabajo hasta encontrar
     * `settings.gradle.kts`, que es estable tanto si Gradle ejecuta desde la raíz
     * como desde el módulo.
     */
    private fun candidateSql(): String = candidateFile("001_admin_sync_roles_guard.sql")

    private fun candidateFile(name: String): String =
        repoFile("supabase/canonical/role_sync_guard_candidate_v1/$name")

    private fun repoFile(relativePath: String): String {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null && !File(dir, "settings.gradle.kts").isFile) {
            dir = dir.parentFile
        }
        val root = assertNotNull(dir, "No se encontró la raíz del repositorio desde ${System.getProperty("user.dir")}")
        val file = File(root, relativePath)
        assertTrue(file.isFile, "Falta $relativePath en ${file.absolutePath}")
        return file.readText()
    }

    /**
     * El candidate **sin comentarios**: sólo SQL que se ejecuta.
     *
     * Es la diferencia entre comprobar que una guarda existe y comprobar que
     * está viva. La cabecera de este fichero cita el cuerpo roto original
     * —incluido su `delete from public.roles_usuario`— y explica cada invariante
     * por su nombre, así que buscar sobre el texto completo daría por buena una
     * RPC vaciada cuyas guardas sólo sobrevivieran dentro de un comentario. Ese
     * falso verde ya se materializó una vez aquí: el índice del `delete` caía en
     * la línea 10 del comentario en vez de en la sentencia real.
     */
    private fun candidateSqlCode(): String =
        candidateSql()
            // Bloque primero, y después TODO lo que siga a `--` en cualquier
            // posición. Filtrar sólo las líneas que EMPIEZAN por `--` dejaba
            // pasar una guarda vaciada con un `-- ROLE_SYNC_...` al final de una
            // línea cualquiera: texto presente, SQL muerto.
            //
            // Y AUN ASÍ este limpiador es derrotable en los dos sentidos: los
            // comentarios de bloque anidan en PostgreSQL y esta expresión no, y
            // un abre-bloque escrito dentro de un `--` hace que se coma código
            // real —con lo que un `and 1 = 0` desaparece de lo que aquí se llama
            // «SQL ejecutable» mientras Postgres sí lo ejecuta. Por eso la
            // autoridad está en `:backend:dbtest` y no aquí.
            .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
            .lineSequence()
            .map { it.substringBefore("--") }
            .joinToString("\n")

    /**
     * La autoridad semántica no puede desaparecer en silencio.
     *
     * Si alguien borrara `:backend:dbtest`, este fichero volvería a ser la única
     * comprobación del candidate y nadie lo notaría: exactamente el estado que
     * cinco revisiones tardaron en desmontar. Esto lo convierte en un fallo
     * ruidoso.
     */
    @Test
    fun laSuiteSemanticaDePostgresSigueSiendoLaAutoridad() {
        val modulo = repoFile("settings.gradle.kts")
        assertTrue(
            "include(\":backend:dbtest\")" in modulo,
            "El módulo de la suite semántica ya no está registrado en el build.",
        )

        listOf(
            "PostgresHarness.kt",
            "SupabaseFixture.kt",
            "RoleSyncSemanticTest.kt",
            "RoleSyncMutationTest.kt",
            "RoleSyncRlsFailOpenTest.kt",
            "RoleSyncConcurrencyTest.kt",
            "RoleSyncRollbackAndCollationTest.kt",
        ).forEach { fichero ->
            // `repoFile` ya falla si el fichero no existe; esto además exige que
            // no sea un cascarón vacío que satisfaga la comprobación.
            assertTrue(
                repoFile("backend/dbtest/src/test/kotlin/com/oposicontrol/dbtest/$fichero").isNotBlank(),
                "Falta $fichero: sin la suite semántica, las aserciones de texto de este " +
                    "fichero volverían a presentarse como si fueran la garantía.",
            )
        }
    }
}
