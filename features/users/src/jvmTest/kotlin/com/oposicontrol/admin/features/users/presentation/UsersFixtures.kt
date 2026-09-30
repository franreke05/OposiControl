package com.oposicontrol.admin.features.users.presentation

import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.common.result.AppError
import com.oposicontrol.admin.domain.model.Role
import com.oposicontrol.admin.domain.model.User
import com.oposicontrol.admin.domain.model.UserCompetitiveState
import com.oposicontrol.admin.domain.model.UserDetail
import com.oposicontrol.admin.domain.model.UserDetailSection
import com.oposicontrol.admin.domain.model.UserEconomy
import com.oposicontrol.admin.domain.model.UserExtendedProfile
import com.oposicontrol.admin.domain.model.UserPerformance
import com.oposicontrol.admin.domain.model.UserProfile
import com.oposicontrol.admin.domain.model.UserStreak
import kotlinx.datetime.Instant

/**
 * Estados de Usuarios para el banco de pruebas visual.
 *
 * Los datos son inventados y no tocan ningún repositorio: esto sirve para revisar
 * jerarquía, densidad, tema y responsive, no para validar el contrato.
 *
 * Un detalle deliberado de las fixtures: los `username` **no son correos**
 * (`ana.garcia`, `invitado_4f`), porque en el sistema real ese campo es
 * `perfiles_usuario.username` y rara vez lo es. Si aquí se pusieran emails, las
 * capturas enseñarían una pantalla más ordenada que la de verdad.
 */
internal object UsersFixtures {

    private val NOW = Instant.parse("2026-03-04T10:15:00Z")

    val ROL_USUARIO = Role(id = "r-user", name = "Usuario", code = "usuario", permissions = emptyList())
    val ROL_ADMIN = Role(id = "r-admin", name = "Administrador", code = "admin", permissions = emptyList())
    val ROL_SUPERADMIN = Role(id = "r-super", name = "Superadministrador", code = "superadmin", permissions = emptyList())
    val ROL_MODERADOR = Role(id = "r-mod", name = "Moderador", code = "moderador", permissions = emptyList())

    val CATALOGO_ROLES: List<Role> = listOf(ROL_USUARIO, ROL_ADMIN, ROL_SUPERADMIN, ROL_MODERADOR)

    private fun user(
        id: String,
        fullName: String,
        username: String,
        roleIds: List<String> = listOf("r-user"),
        registrado: Boolean = true,
        tickets: Int = 0,
        daysAgo: Int = 0,
    ) = User(
        id = id,
        fullName = fullName,
        email = username,
        roleIds = roleIds,
        isRegistered = registrado,
        createdAt = Instant.fromEpochSeconds(NOW.epochSeconds - daysAgo * 86_400L),
        ticketsBalance = tickets,
    )

    /**
     * El censo de ejemplo.
     *
     * Mezcla a propósito las dos poblaciones —opositores y cuentas con acceso al
     * panel—, porque es exactamente lo que hace la tabla real y lo que la pantalla
     * tiene que dejar ver de un vistazo.
     */
    val CENSO: List<User> = listOf(
        user("u-1", "Ana García", "ana.garcia", tickets = 120, daysAgo = 0),
        user("u-2", "Marta Ruiz Peña", "mruiz", listOf("r-admin"), tickets = 0, daysAgo = 12),
        user("u-3", "", "invitado_4f", registrado = false, daysAgo = 1),
        user("u-4", "Javier Ortega", "jortega", tickets = 35, daysAgo = 40),
        user("u-5", "Lucía Fernández", "lucia.f", listOf("r-super", "r-admin"), tickets = 8, daysAgo = 320),
        user("u-6", "Pablo Serrano", "pserrano", listOf("r-mod"), tickets = 64, daysAgo = 95),
        user("u-7", "Carmen Vidal", "cvidal", tickets = 0, daysAgo = 5),
        user("u-8", "Diego Navarro", "dnavarro", registrado = false, daysAgo = 2),
    )

    private fun base(selected: String? = null) = UsersState(
        users = UiAsync.Success(CENSO),
        availableRoles = CATALOGO_ROLES,
        selectedUserId = selected,
    )

    private fun detail(
        userId: String,
        displayName: String?,
        username: String,
        invitado: Boolean = false,
        conPerfilAmpliado: Boolean = true,
    ) = UserDetail(
        profile = UserProfile(
            userId = userId,
            displayName = displayName,
            username = username,
            isGuest = invitado,
            onboardingCompleted = !invitado,
            createdAt = "2025-11-18",
        ),
        extendedProfile = if (!conPerfilAmpliado) {
            null
        } else {
            UserExtendedProfile(
                fullName = displayName,
                age = 31,
                city = "Valencia",
                employmentStatus = "Trabajando a turnos",
                workplace = "Hospital La Fe",
                availableStudyMinutes = 90,
                preferredStudySlot = "Noche",
                targetExamDate = "2026-06-13",
            )
        },
        performance = UserPerformance(
            finishedExams = 14,
            answeredQuestions = 1_284,
            correctAnswers = 951,
            successRate = 74.1,
            totalPoints = 4_820,
            level = 7,
        ),
        competitive = UserCompetitiveState(
            leagueCode = "plata",
            leagueName = "Liga Plata",
            calculationWindow = 200,
            rankingEligibleQuestions = 200,
            currentAverage = 7.4,
            evolutionReference = 6.6,
            evolution = 0.8,
            questionsSinceLastEvolution = 42,
            leagueRank = 12,
            globalRank = 348,
            globalPercentile = 82.0,
        ),
        streak = UserStreak(currentDays = 9, bestDays = 23, lastActivityDate = "2026-03-03"),
        economy = UserEconomy(
            ticketBalance = 120,
            isPremium = true,
            subscriptionStatus = "activa",
            productCode = "premium_anual",
            provider = "google_play",
            renewsAt = "2026-11-02",
        ),
        // Dos secciones que el sistema real no siempre puede leer: la ficha tiene
        // que distinguir «no hay fila» de «no se pudo leer», y las capturas deben
        // enseñar las dos formas.
        unavailableSections = setOf(UserDetailSection.TESTER),
    )

    // -----------------------------------------------------------------------
    // Casos
    // -----------------------------------------------------------------------

    /** El catálogo, sin nadie seleccionado. */
    fun listado(): UsersState = base()

    /** Una opositora abierta, sin cambios pendientes. */
    fun opositoraSeleccionada(): UsersState {
        val u = CENSO.first { it.id == "u-1" }
        return base(selected = u.id).copy(
            editorName = u.fullName,
            editorRoleIds = u.roleIds.toSet(),
            detail = UiAsync.Success(detail("u-1", "Ana García", "ana.garcia")),
        )
    }

    /**
     * Una cuenta con acceso al panel.
     *
     * Es el caso que motivó la auditoría: administrar OposiControl y opositar en
     * OposiBot son la misma tabla, y la pantalla tiene que decirlo.
     */
    fun administradoraSeleccionada(): UsersState {
        val u = CENSO.first { it.id == "u-5" }
        return base(selected = u.id).copy(
            editorName = u.fullName,
            editorRoleIds = u.roleIds.toSet(),
            detail = UiAsync.Success(detail("u-5", "Lucía Fernández", "lucia.f")),
        )
    }

    /** Un invitado: sin nombre y sin perfil ampliado. No es un usuario «bloqueado». */
    fun invitadoSeleccionado(): UsersState {
        val u = CENSO.first { it.id == "u-3" }
        return base(selected = u.id).copy(
            editorName = u.fullName,
            editorRoleIds = u.roleIds.toSet(),
            detail = UiAsync.Success(
                detail("u-3", null, "invitado_4f", invitado = true, conPerfilAmpliado = false),
            ),
        )
    }

    /**
     * Una ficha con secciones que faltan y otras que no se pudieron leer: `NO_DATA` y
     * `NO_DISPONIBLE` conviven en la misma pantalla y cada tarjeta lo dice por su cuenta.
     */
    fun fichaParcial(): UsersState {
        val u = CENSO.first { it.id == "u-4" }
        return base(selected = u.id).copy(
            editorName = u.fullName,
            editorRoleIds = u.roleIds.toSet(),
            detail = UiAsync.Success(
                UserDetail(
                    profile = UserProfile(userId = "u-4", displayName = "Javier Ortega", username = "jortega", onboardingCompleted = false, createdAt = "2026-01-23"),
                    performance = UserPerformance(finishedExams = 3, answeredQuestions = 120, correctAnswers = 80, successRate = 66.7),
                    unavailableSections = setOf(UserDetailSection.STREAK, UserDetailSection.SUBSCRIPTIONS, UserDetailSection.TESTER),
                ),
            ),
        )
    }

    /** La ficha todavía carga; el catálogo y el editor siguen usables. */
    fun fichaCargando(): UsersState = opositoraSeleccionada().copy(detail = UiAsync.Loading)

    /** La ficha entera no se pudo leer: se dice, y aun así se puede editar el nombre y los roles. */
    fun fichaCaida(): UsersState = opositoraSeleccionada().copy(
        detail = UiAsync.Error(AppError.Network("connection refused", code = 503)),
    )

    /** Nombre corregido y todavía sin guardar: la barra tiene que decirlo. */
    fun conCambiosSinGuardar(): UsersState =
        opositoraSeleccionada().copy(editorName = "Ana García Molina")

    /** Saltando a otra fila con cambios encima: se pregunta antes de perderlos. */
    fun confirmandoDescarte(): UsersState =
        conCambiosSinGuardar().copy(pendingSelectionUserId = "u-4")

    /** Concediendo acceso al panel: el cambio se avisa antes de confirmarlo. */
    fun concediendoAccesoAdministrativo(): UsersState =
        opositoraSeleccionada().copy(editorRoleIds = setOf("r-user", "r-admin"))

    /** El backend rechaza el guardado. Error, nunca «Operación completada». */
    fun guardadoRechazado(): UsersState = conCambiosSinGuardar().copy(
        actionState = UiAsync.Error(AppError.Network("connection refused")),
    )

    /** Han pulsado «Nuevo usuario»: el alta no se hace aquí y se explica. */
    fun avisoDeAlta(): UsersState = base().copy(createNoticeVisible = true)

    /** Página llena: son los primeros 50, no el censo entero. */
    fun paginaLlena(): UsersState {
        val relleno = (CENSO.size until USER_PAGE_LIMIT).map { index ->
            user("u-r$index", "Usuario de relleno $index", "relleno$index", daysAgo = index)
        }
        return base().copy(users = UiAsync.Success(CENSO + relleno))
    }

    /** Censo vacío de verdad: el backend respondió sin filas. */
    fun listaVacia(): UsersState = base().copy(users = UiAsync.Empty)

    /**
     * El backend no responde.
     *
     * Antes esto se pintaba igual que [listaVacia] —«Todavía no hay ningún usuario
     * registrado», con un botón de crear—, porque el origen convertía cualquier
     * fallo en una lista vacía.
     */
    fun listaCaida(): UsersState = base().copy(
        users = UiAsync.Error(AppError.Network("connection refused", code = 503)),
    )

    /** Todavía cargando. */
    fun cargando(): UsersState = UsersState(users = UiAsync.Loading, availableRoles = CATALOGO_ROLES)

    /** Filtros que no dejan pasar nada: distinto de un censo vacío. */
    fun sinCoincidencias(): UsersState = base().copy(searchQuery = "zzzzz")

    /** El catálogo de roles aún no ha cargado: no se puede afirmar la población. */
    fun sinCatalogoDeRoles(): UsersState = base().copy(availableRoles = emptyList())
}
