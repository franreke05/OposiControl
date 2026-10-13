package com.oposicontrol.admin.features.users.presentation

import com.oposicontrol.admin.domain.model.Role
import com.oposicontrol.admin.domain.model.User
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * La capa de decisión de Usuarios.
 *
 * Lo que se fija aquí no es estética: es que la pantalla no pueda volver a
 * afirmar cosas que el backend no hace. Cada bloque corresponde a un defecto
 * encontrado en la auditoría del contrato real.
 */
class UserPresentationTest {

    private val ROLE_ADMIN = Role(id = "r-admin", name = "Administrador", code = "admin", permissions = emptyList())
    private val ROLE_SUPER = Role(id = "r-super", name = "Superadmin", code = "superadmin", permissions = emptyList())
    private val ROLE_USER = Role(id = "r-user", name = "Usuario", code = "usuario", permissions = emptyList())
    private val CATALOGO = listOf(ROLE_ADMIN, ROLE_SUPER, ROLE_USER)

    private fun user(
        id: String = "u-1",
        fullName: String = "Ana García",
        email: String = "ana.garcia",
        roleIds: List<String> = listOf("r-user"),
        isRegistered: Boolean? = true,
    ) = User(
        id = id,
        fullName = fullName,
        email = email,
        roleIds = roleIds,
        isRegistered = isRegistered,
        createdAt = Instant.parse("2026-03-04T10:15:00Z"),
    )

    // --- Población ---------------------------------------------------------

    @Test
    fun elRolDeAdministracionSeReconocePorCodigoYNoPorNombre() {
        // El nombre es una etiqueta que alguien puede reescribir sin tocar el
        // permiso; el código es lo que el backend compara de verdad.
        val disfrazado = Role(id = "r-x", name = "Administrador", code = "usuario", permissions = emptyList())

        assertEquals(setOf("r-admin", "r-super"), adminRoleIds(CATALOGO))
        assertEquals(emptySet(), adminRoleIds(listOf(disfrazado)))
    }

    @Test
    fun unUsuarioConRolAdminEsPoblacionDeAdministracion() {
        val admins = adminRoleIds(CATALOGO)

        assertEquals(
            UserPopulation.ADMINISTRACION,
            userPopulation(user(roleIds = listOf("r-admin")), admins),
        )
        assertEquals(
            UserPopulation.OPOSITOR,
            userPopulation(user(roleIds = listOf("r-user")), admins),
        )
    }

    @Test
    fun sinCatalogoDeRolesNoSeAfirmaQueNadieSeaOpositor() {
        // Es el caso real mientras el catálogo carga. Decir «Opositor» ahí haría
        // creer que en la lista no hay administradores cuando sí los hay.
        assertEquals(
            UserPopulation.INDETERMINADA,
            userPopulation(user(roleIds = listOf("r-admin")), emptySet()),
        )
    }

    @Test
    fun unUsuarioSinNingunRolQuedaSinDeterminar() {
        assertEquals(
            UserPopulation.INDETERMINADA,
            userPopulation(user(roleIds = emptyList()), adminRoleIds(CATALOGO)),
        )
    }

    // --- Identidad ---------------------------------------------------------

    @Test
    fun unUsuarioSinNombreSeDiceNoSeRellenaConSuIdentificador() {
        // La ficha anterior pintaba el UUID como identidad principal.
        assertEquals(USER_NAME_MISSING, userDisplayName(user(fullName = "   ")))
        assertFalse(userDisplayName(user(fullName = "")).contains("u-1"))
    }

    @Test
    fun elCampoDeCuentaNoSeLlamaEmailPorqueNoLoEs() {
        // El backend lo rellena con `username`; `perfiles_usuario` no tiene email.
        assertEquals("Nombre de usuario", USER_ACCOUNT_FIELD_LABEL)
        assertEquals("ana.garcia", userAccountName(user()))
        assertEquals(USER_NO_DATA, userAccountName(user(email = "")))
    }

    // --- Invitado vs registrado --------------------------------------------

    @Test
    fun elBooleanoDistingueInvitadoDeRegistradoNoActivoDeBloqueado() {
        assertEquals(UserAccountKind.REGISTRADO, userAccountKind(user(isRegistered = true)))
        assertEquals(UserAccountKind.INVITADO, userAccountKind(user(isRegistered = false)))
        assertEquals(UserAccountKind.SIN_DATO, userAccountKind(user(isRegistered = null)), "sin dato != invitado")

        val etiquetas = UserAccountKind.entries.map { userAccountKindLabel(it) }
        assertEquals(listOf("Registrado", "Invitado", USER_NO_DATA), etiquetas)
        assertFalse(etiquetas.any { it.contains("Bloquead", ignoreCase = true) })
        assertFalse(etiquetas.any { it.contains("Activo", ignoreCase = true) })
    }

    // --- Matriz de honestidad ----------------------------------------------

    @Test
    fun darDeBajaSeOfreceComoParcialPorqueEsSoloParaSuperadministradores() {
        val baja = userCapability("Dar de baja o reactivar")

        assertNotNull(baja)
        assertEquals(UserCapabilityLevel.PARTIAL, baja.level)
    }

    @Test
    fun crearYEliminarEstanImpedidos() {
        assertEquals(UserCapabilityLevel.BLOCKED, userCapability("Crear usuario")?.level)
        assertEquals(UserCapabilityLevel.BLOCKED, userCapability("Eliminar usuario")?.level)
    }

    @Test
    fun advertirEsParcialPorqueNoLlegaAlUsuario() {
        // El backend sólo escribe una fila de auditoría.
        val advertir = userCapability("Advertir")

        assertNotNull(advertir)
        assertEquals(UserCapabilityLevel.PARTIAL, advertir.level)
        assertTrue(advertir.note.contains("no envía", ignoreCase = true))
    }

    @Test
    fun loQueSiPersisteSeDeclaraReal() {
        assertEquals(UserCapabilityLevel.REAL, userCapability("Editar nombre")?.level)
        assertEquals(UserCapabilityLevel.REAL, userCapability("Asignar roles")?.level)
        assertEquals(UserCapabilityLevel.REAL, userCapability("Conceder OposiTickets")?.level)
    }

    @Test
    fun todaCapacidadQueNoEsRealExplicaPorQue() {
        userUnavailableCapabilities().forEach { capability ->
            assertTrue(
                capability.note.isNotBlank(),
                "«${capability.name}» no dice por qué no está disponible",
            )
        }
    }

    @Test
    fun lasCapacidadesNoDisponiblesSeOrdenanDeMenosAMasGrave() {
        val niveles = userUnavailableCapabilities().map { it.level }

        assertEquals(niveles.sortedBy { it.ordinal }, niveles)
        assertFalse(niveles.contains(UserCapabilityLevel.REAL))
    }

    // --- Paginación --------------------------------------------------------

    @Test
    fun elRecuentoHablaDeUsuariosCargadosNoDelTotalDelSistema() {
        assertEquals("Sin usuarios", userCountLabel(shown = 0, loaded = 0))
        assertEquals("1 usuario", userCountLabel(shown = 1, loaded = 1))
        assertEquals("12 usuarios", userCountLabel(shown = 12, loaded = 12))
        assertEquals("3 de 12 cargados", userCountLabel(shown = 3, loaded = 12))
        assertEquals("1 de 12 cargados", userCountLabel(shown = 1, loaded = 12))
    }

    // --- Editor ------------------------------------------------------------

    @Test
    fun cambiarNombreORolesMarcaCambiosSinGuardar() {
        val u = user(fullName = "Ana García", roleIds = listOf("r-user"))

        assertFalse(userEditorIsDirty(u, "Ana García", setOf("r-user")))
        assertTrue(userEditorIsDirty(u, "Ana Garcia Pérez", setOf("r-user")))
        assertTrue(userEditorIsDirty(u, "Ana García", setOf("r-user", "r-admin")))
    }

    @Test
    fun elGuardadoNoSeBloqueaPorFaltaDeCorreo() {
        // El editor anterior exigía un email válido para poder guardar, y el
        // backend lo descartaba: bloqueaba el nombre por un campo inútil.
        assertEquals(emptyList(), userSaveBlockers("Ana García"))
        assertEquals(1, userSaveBlockers("   ").size)
    }

    @Test
    fun elAvisoDeRolesDescribeLaGuardaYaInstaladaEnElServidor() {
        // MASTER4-OC-002: la guarda de `admin_sync_roles` (20260921071916 role_sync_guard) esta INSTALADA en MAIN y el
        // servidor rechaza dejar la plataforma sin superadministrador (409 ROLE_SYNC_LAST_SUPERADMIN). Este test
        // antes fijaba el texto contrario («todavía no impide»); se cambia a proposito junto con la cadena.
        assertFalse(
            USER_ADMIN_ROLE_WARNING.contains("todavía no impide"),
            "El aviso declara pendiente una protección que ya está instalada: $USER_ADMIN_ROLE_WARNING",
        )
        assertTrue(
            USER_ADMIN_ROLE_WARNING.contains("el servidor rechaza dejar la plataforma sin ningún superadministrador"),
            "El aviso debe decir que el servidor rechaza el caso: $USER_ADMIN_ROLE_WARNING",
        )
    }

    @Test
    fun guardarSinNombreSoloSePermiteSiYaNoTeniaNombre() {
        // MASTER4-API-001: edición «sólo roles» de un usuario sin nombre.
        assertEquals(emptyList(), userSaveBlockers("", currentName = ""))
        assertEquals(emptyList(), userSaveBlockers("  ", currentName = "  "))
        // Vaciar un nombre existente, o no conocer el actual, sigue bloqueado.
        assertEquals(1, userSaveBlockers("", currentName = "Ana").size)
        assertEquals(1, userSaveBlockers("", currentName = null).size)
        assertEquals(emptyList(), userSaveBlockers("Ana", currentName = ""))
    }

    @Test
    fun conceptoDeAccesoAdministrativoSoloAvisaCuandoDeVerdadCambia() {
        val admins = adminRoleIds(CATALOGO)

        // De admin a superadmin sigue siendo administrador: no es un cambio de acceso.
        assertFalse(userAdminAccessChanges(setOf("r-admin"), setOf("r-super"), admins))
        // Conceder y retirar sí lo son.
        assertTrue(userAdminAccessChanges(setOf("r-user"), setOf("r-admin"), admins))
        assertTrue(userAdminAccessChanges(setOf("r-admin"), setOf("r-user"), admins))
        assertFalse(userAdminAccessChanges(setOf("r-user"), setOf("r-user"), admins))
    }
}
