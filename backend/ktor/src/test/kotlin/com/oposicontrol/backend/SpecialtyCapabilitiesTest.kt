package com.oposicontrol.backend

import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SpecialtyCapabilitiesTest {

    private val especialidadId = "11111111-2222-3333-4444-555555555555"

    /**
     * El mismo `Json` con el que responde el servidor (`Application.module()`).
     *
     * Lo que importa de esta copia es lo que NO tiene: `encodeDefaults`. Sin él,
     * kotlinx omite toda propiedad cuyo valor coincida con su default declarado.
     */
    private val responseJson = kotlinx.serialization.json.Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        prettyPrint = false
    }

    // --- PATCH de especialidad: no pisar lo que el panel no edita ---

    /**
     * Editar una especialidad no puede borrar su `codigo`.
     *
     * El panel no tiene campo `codigo` —el modelo de dominio `Specialty` tampoco—,
     * así que el PATCH llega con `codigo = ""` (el default del DTO). Como `sbJson`
     * serializa con `encodeDefaults = true`, ese `""` viajaba a PostgREST y
     * sobrescribía el código real en CADA edición. En los datos vivos ya hay una
     * especialidad con el código en blanco por esta vía.
     *
     * Es el mismo defecto que ya se corrigió para exámenes (ver el comentario en
     * `ExamsViewModel.saveExam`, «antes se enviaba "" a la primera edición y
     * borraba el código real»), pero allí se arregló en el cliente porque el
     * editor sí tiene el campo. Aquí el cliente no puede preservar lo que nunca
     * recibe, así que la única defensa está en el servidor: si el campo llega
     * vacío, se OMITE del cuerpo y PostgREST deja la columna intacta.
     */
    @Test
    fun patchOmitsBlankCodigoSoEditingNeverWipesIt() {
        val payload = especialidadPatchPayload(
            AdminEspecialidadDto(id = "esp-1", nombre = "TCAE", slug = "tcae"),
        )

        assertFalse(
            payload.contains("\"codigo\""),
            "El PATCH incluye codigo en blanco y borrará el real: $payload",
        )
        assertTrue(payload.contains("\"nombre\":\"TCAE\""), "el nombre sí tiene que viajar: $payload")
        assertTrue(payload.contains("\"slug\":\"tcae\""), "el slug sí tiene que viajar: $payload")
    }

    @Test
    fun patchKeepsCodigoWhenTheCallerActuallySendsOne() {
        // Omitir el blanco no puede convertirse en "codigo es inmutable": si algún
        // cliente futuro sí lo manda, tiene que escribirse.
        val payload = especialidadPatchPayload(
            AdminEspecialidadDto(id = "esp-1", codigo = "TCAE-2026", nombre = "TCAE", slug = "tcae"),
        )

        assertTrue(payload.contains("\"codigo\":\"TCAE-2026\""), "un codigo explícito debe viajar: $payload")
    }

    // --- contrato de cable: los flags viajan SIEMPRE, valgan lo que valgan ---

    /**
     * El cliente NO puede reconstruir un flag ausente.
     *
     * `data/.../KtorAdminV1ContentRepositoryImpl.kt` tiene su propio DTO con sus
     * propios defaults, y no coinciden con los de aquí. Si el servidor omite
     * `permiteRazonados` porque vale igual que su default, el panel no lee "lo que
     * el servidor decidió": lee el default del CLIENTE, que es el contrario. Un
     * modo abierto se pinta cerrado, y el editor de exámenes bloquea ese tipo.
     *
     * Por eso el payload tiene que ser explícito: es la única forma de que los dos
     * lados no tengan que compartir una constante que nadie sincroniza.
     */
    @Test
    fun capabilitiesPayloadAlwaysCarriesEveryFlagExplicitly() {
        val json = responseJson.encodeToString(
            AdminEspecialidadCapacidadesResponse(defaultCapacidades(especialidadId)),
        )

        listOf(
            "permiteRafagas",
            "permiteRazonados",
            "permiteInmersivos",
            "permiteDemos",
            "permiteOposimarket",
        ).forEach { flag ->
            assertTrue(
                json.contains("\"$flag\""),
                "El payload omite $flag, así que el cliente aplicará su propio default: $json",
            )
        }
    }

    @Test
    fun anExplicitlyEnabledModeSurvivesSerialization() {
        // El caso que más duele: una especialidad configurada A MANO con razonados
        // e inmersivos abiertos. Si coinciden con el default del servidor y se
        // omiten, el panel la pinta como si nadie los hubiera abierto.
        val configured = AdminEspecialidadCapacidadesDto(
            especialidadId = especialidadId,
            permiteRafagas = true,
            permiteRazonados = true,
            permiteInmersivos = true,
        )

        val json = responseJson.encodeToString(AdminEspecialidadCapacidadesResponse(configured))

        assertTrue(json.contains("\"permiteRazonados\":true"), "razonados no viaja: $json")
        assertTrue(json.contains("\"permiteInmersivos\":true"), "inmersivos no viaja: $json")
    }

    // --- modos habilitados: la traducción flag -> vocabulario canónico ---

    @Test
    fun enabledModesUseTheCanonicalVocabulary() {
        val all = AdminEspecialidadCapacidadesDto(
            especialidadId = especialidadId,
            permiteRafagas = true,
            permiteRazonados = true,
            permiteInmersivos = true,
        )

        // rafaga / razonado / inmersivo. Nunca "guiado", que es solo alias legacy.
        assertEquals(listOf("rafaga", "razonado", "inmersivo"), all.modosHabilitados())
    }

    @Test
    fun aSpecialtyWithOnlyBurstsReportsOnlyThatMode() {
        val onlyBursts = AdminEspecialidadCapacidadesDto(
            especialidadId = especialidadId,
            permiteRafagas = true,
            permiteRazonados = false,
            permiteInmersivos = false,
        )

        assertEquals(listOf("rafaga"), onlyBursts.modosHabilitados())
    }

    @Test
    fun demosAndMarketAreNotExamModes() {
        // permiteDemos / permiteOposimarket habilitan otras superficies, no un tipo
        // de examen: colarlos aqui haria que OposiBot pintase un modo inexistente.
        val noExamModes = AdminEspecialidadCapacidadesDto(
            especialidadId = especialidadId,
            permiteRafagas = false,
            permiteRazonados = false,
            permiteInmersivos = false,
            permiteDemos = true,
            permiteOposimarket = true,
        )

        assertTrue(noExamModes.modosHabilitados().isEmpty())
    }

    // --- validación: no dejar al usuario en una pantalla muda ---

    @Test
    fun aSpecialtyWithNoModesRequiresAnExplanatoryMessage() {
        val blocked = AdminEspecialidadCapacidadesDto(
            especialidadId = especialidadId,
            permiteRafagas = false,
            permiteRazonados = false,
            permiteInmersivos = false,
            mensajeLimitacion = null,
        )

        val failure = assertFailsWith<BackendDomainException> { blocked.validated() }
        assertEquals("SPECIALTY_NO_MODES_WITHOUT_MESSAGE", failure.errorCode)
        assertEquals(HttpStatusCode.UnprocessableEntity, failure.status)
    }

    @Test
    fun aBlankMessageDoesNotCountAsAnExplanation() {
        val blocked = AdminEspecialidadCapacidadesDto(
            especialidadId = especialidadId,
            permiteRafagas = false,
            permiteRazonados = false,
            permiteInmersivos = false,
            mensajeLimitacion = "   ",
        )

        assertFailsWith<BackendDomainException> { blocked.validated() }
    }

    @Test
    fun aSpecialtyWithNoModesIsAllowedWhenItExplainsWhy() {
        val blocked = AdminEspecialidadCapacidadesDto(
            especialidadId = especialidadId,
            permiteRafagas = false,
            permiteRazonados = false,
            permiteInmersivos = false,
            mensajeLimitacion = "Contenido en preparación para esta especialidad.",
        )

        assertEquals(blocked, blocked.validated())
    }

    @Test
    fun aSpecialtyWithAtLeastOneModeNeedsNoMessage() {
        val open = AdminEspecialidadCapacidadesDto(especialidadId = especialidadId, permiteRafagas = true)

        assertEquals(open, open.validated())
    }

    // --- defaults: "sin configurar" no es "todo apagado" ---

    @Test
    fun defaultsMatchTheSchemaSoOpeningAndSavingChangesNothing() {
        val defaults = defaultCapacidades(especialidadId)

        // Iguales a los DEFAULT de capacidades_especialidad.
        assertTrue(defaults.permiteRafagas)
        assertTrue(defaults.permiteRazonados)
        assertTrue(defaults.permiteInmersivos)
        assertTrue(defaults.permiteDemos)
        assertTrue(defaults.permiteOposimarket)
        assertNull(defaults.mensajeLimitacion)
    }

    @Test
    fun defaultsOpenTheThreeExamModesSoANewSpecialtyIsNotBornLimitedToRafaga() {
        // El criterio del producto: una especialidad nace admitiendo los tres
        // modos y se RESTRINGE a mano cuando una oposición concreta no tenga
        // uno. Antes nacía sólo con ráfaga, y como nadie ha escrito nunca una
        // fila en capacidades_especialidad, eso dejaba el editor de exámenes
        // con un único tipo elegible para TODAS las especialidades.
        val defaults = defaultCapacidades(especialidadId)

        assertEquals(listOf("rafaga", "razonado", "inmersivo"), defaults.modosHabilitados())
    }

    @Test
    fun defaultsAreFlaggedAsNotConfiguredYet() {
        // Distinguir "nadie lo ha decidido" de "alguien lo dejó así" es la razón de
        // que exista el flag: sin él, el panel no puede avisar de lo primero.
        assertFalse(defaultCapacidades(especialidadId).configurado)
    }

    @Test
    fun defaultsAreValidOnTheirOwn() {
        // Los defaults dejan los tres modos activos, asi que no disparan la regla
        // del mensaje obligatorio: guardar sin tocar nada nunca puede fallar.
        defaultCapacidades(especialidadId).validated()
    }

    // --- ida y vuelta fila <-> DTO ---

    @Test
    fun rowRoundTripPreservesEveryFlag() {
        val row = CapacidadesEspecialidadRow(
            especialidadId = especialidadId,
            permiteRafagas = true,
            permiteRazonados = true,
            permiteInmersivos = false,
            permiteDemos = false,
            permiteOposimarket = true,
            mensajeLimitacion = "Sin inmersivos todavía.",
            actualizadoEn = "2026-09-01T10:00:00Z",
        )

        val dto = row.toDto()
        assertTrue(dto.configurado, "Una fila existente siempre está configurada.")
        assertEquals("2026-09-01T10:00:00Z", dto.actualizadoEn)

        val back = dto.toRow(especialidadId)
        assertEquals(row.permiteRafagas, back.permiteRafagas)
        assertEquals(row.permiteRazonados, back.permiteRazonados)
        assertEquals(row.permiteInmersivos, back.permiteInmersivos)
        assertEquals(row.permiteDemos, back.permiteDemos)
        assertEquals(row.permiteOposimarket, back.permiteOposimarket)
        assertEquals(row.mensajeLimitacion, back.mensajeLimitacion)
    }

    @Test
    fun aBlankMessageIsStoredAsNullNotAsEmptyText() {
        // Evita que la app muestre un aviso de limitación vacío.
        val row = AdminEspecialidadCapacidadesDto(
            especialidadId = especialidadId,
            permiteRafagas = true,
            mensajeLimitacion = "  ",
        ).toRow(especialidadId)

        assertNull(row.mensajeLimitacion)
    }

    @Test
    fun theTargetSpecialtyIdWinsOverWhateverTheBodyCarried() {
        // El id viene de la ruta; si el cuerpo trae otro, escribir ese permitiría
        // reconfigurar una especialidad distinta de la que se está editando.
        val row = AdminEspecialidadCapacidadesDto(especialidadId = "otra-cosa", permiteRafagas = true)
            .toRow(especialidadId)

        assertEquals(especialidadId, row.especialidadId)
    }
}
