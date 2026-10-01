package com.oposicontrol.admin.data.remote.api

import com.oposicontrol.admin.core.network.AdminApiJson
import kotlin.test.Test
import kotlin.test.assertEquals

/** MASTER6-QUALITY-006: id y nombre tambien siguen "canonico antes que legacy"; un canonico en blanco no tapa al legacy. */
class UserProfilePrecedenceLoop6Test {
    private fun profile(perfil: String) = AdminApiJson
        .decodeFromString<UserDetailEnvelope>("""{"data":{"perfil":$perfil}}""").data.toDomain().profile

    @Test fun divergentCanonicalAndLegacyIdAndNameResolveToCanonical() {
        val p = profile("""{"id":"canon-id","usuario_id":"legacy-id","fullName":"Nombre Canonico","nombre_mostrar":"Nombre Legacy"}""")
        assertEquals("canon-id", p.userId)
        assertEquals("Nombre Canonico", p.displayName)
    }

    @Test fun blankCanonicalFallsBackToLegacy() {
        val p = profile("""{"id":" ","usuario_id":"legacy-id","fullName":"","nombre_mostrar":"Nombre Legacy"}""")
        assertEquals("legacy-id", p.userId)
        assertEquals("Nombre Legacy", p.displayName)
    }
}
