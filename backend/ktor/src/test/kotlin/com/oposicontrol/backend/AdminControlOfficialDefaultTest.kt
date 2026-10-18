package com.oposicontrol.backend

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Un examen OFICIAL nace con la penalización racional encendida; uno que no lo es, o que ya la decidió, no se toca. */
class AdminControlOfficialDefaultTest {
    @Test
    fun theDefaultIsAppliedOnlyWhileTheExamIsOfficialAndStillOnTheDatabaseDefault() {
        val f = ControlFake { method, _, _ ->
            if (method == "PATCH") V1StubResponse(200, """[{"id":"${ControlFake.EXAM}"}]""") else V1StubResponse(201, "")
        }
        assertTrue(f.gateway().applyOfficialExamDefaults(ControlFake.EXAM))
        val patch = f.writes().single { it.method == "PATCH" }
        // El filtro vive en la propia sentencia: no hay lectura previa que pueda quedarse vieja.
        assertTrue(patch.pathAndQuery.contains("es_oficial=is.true") && patch.pathAndQuery.contains("penalizacion_racional=is.false"), patch.pathAndQuery)
        assertEquals("""{"penalizacion_racional":true}""", patch.body)
        val audit = f.writes().single { it.pathAndQuery.contains("auditoria_log") }
        assertTrue(audit.body.contains(ControlFake.ACTOR) && audit.body.contains("examen oficial"), audit.body)
    }

    @Test
    fun nothingIsAuditedWhenNothingChanged() {
        val f = ControlFake { _, _, _ -> V1StubResponse(200, "[]") }
        assertFalse(f.gateway().applyOfficialExamDefaults(ControlFake.EXAM))
        assertTrue(f.writes().none { it.pathAndQuery.contains("auditoria_log") }, f.describe())
    }
}
