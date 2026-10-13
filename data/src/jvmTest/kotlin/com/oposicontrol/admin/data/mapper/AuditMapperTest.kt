package com.oposicontrol.admin.data.mapper

import com.oposicontrol.admin.data.remote.dto.AuditMovementDetailDto
import com.oposicontrol.admin.data.remote.dto.AuditMovementsPageDto
import com.oposicontrol.admin.domain.model.AuditField
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** El cable de Auditoría tal como lo manda el servidor, hasta el modelo que lee la pantalla. */
class AuditMapperTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `a page keeps the three kinds of unknown actor apart`() {
        val page = json.decodeFromString<AuditMovementsPageDto>(
            """
            {"items":[
              {"id":"m1","actorId":"u1","actorNombre":"Ana García","accion":"cuenta_dada_de_baja","tabla":"perfiles_usuario","registroId":"r1","creadoEn":"2026-10-13T12:34:56.123456+00:00"},
              {"id":"m2","actorId":"u2","accion":"plan_eliminado","creadoEn":"2026-10-13T12:00:00+00:00"},
              {"id":"m3","actorId":null,"actorNombre":" ","accion":"lote_importado","tabla":"","creadoEn":"2026-10-13T11:00:00+00:00"}
            ],"nextCursor":"abc"}
            """.trimIndent(),
        ).toDomain()

        assertEquals("abc", page.nextCursor)
        val (named, unnamed, system) = page.items
        assertEquals("Ana García", named.actorName)
        // Hay persona pero no perfil: el identificador se conserva y el nombre queda sin dato.
        assertEquals("u2", unnamed.actorId)
        assertNull(unnamed.actorName)
        assertNull(unnamed.table)
        // Sin persona: ni identificador ni un nombre en blanco haciéndose pasar por dato.
        assertNull(system.actorId)
        assertNull(system.actorName)
        assertNull(system.table)
        // El instante viaja tal cual: la hora local la pone la pantalla.
        assertEquals("2026-10-13T12:34:56.123456+00:00", named.occurredAt)
    }

    @Test
    fun `the last page has no cursor`() {
        assertNull(json.decodeFromString<AuditMovementsPageDto>("""{"items":[]}""").toDomain().nextCursor)
        assertNull(json.decodeFromString<AuditMovementsPageDto>("""{"items":[],"nextCursor":""}""").toDomain().nextCursor)
    }

    @Test
    fun `before and after are flattened in the order they were stored`() {
        val detail = json.decodeFromString<AuditMovementDetailDto>(
            """
            {"id":"m1",
             "antes":{"estado_cuenta":"activa","roleIds":["a","b"],"vacia":[],"nada":null},
             "despues":{"activo":true,"nivel":2,"perfil":{"nombre":"Ana"},"tarifas":[{"precio_centimos":299},{"precio_centimos":499}]}}
            """.trimIndent(),
        ).toDomain()

        assertEquals(
            listOf(AuditField("estado_cuenta", "activa"), AuditField("roleIds", "a, b"), AuditField("vacia", ""), AuditField("nada", null)),
            detail.before,
        )
        assertEquals(
            listOf(
                AuditField("activo", "true"),
                AuditField("nivel", "2"),
                AuditField("perfil.nombre", "Ana"),
                AuditField("tarifas.1.precio_centimos", "299"),
                AuditField("tarifas.2.precio_centimos", "499"),
            ),
            detail.after,
        )
    }

    @Test
    fun `a side that was not stored is null, not an empty list`() {
        val created = json.decodeFromString<AuditMovementDetailDto>("""{"id":"m1","antes":null,"despues":{}}""").toDomain()
        assertNull(created.before)
        assertEquals(emptyList(), created.after)
        val bare = json.decodeFromString<AuditMovementDetailDto>("""{"id":"m2"}""").toDomain()
        assertNull(bare.before)
        assertNull(bare.after)
        // Un valor suelto en la raíz no se pierde: queda como un único dato sin nombre.
        assertEquals(listOf(AuditField("", "texto")), json.decodeFromString<AuditMovementDetailDto>("""{"id":"m3","despues":"texto"}""").toDomain().after)
    }
}
