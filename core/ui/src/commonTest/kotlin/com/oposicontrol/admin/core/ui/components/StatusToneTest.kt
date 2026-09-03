package com.oposicontrol.admin.core.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class StatusToneTest {

    @Test
    fun `activo y pendiente no comparten tono`() {
        // El defecto que arregla este componente: «activo» usaba el coral del
        // acento y «pendiente» usaba colorScheme.primary, que ES el mismo coral,
        // así que los dos estados se pintaban idénticos en toda la app.
        assertNotEquals(statusToneFor("activo"), statusToneFor("pendiente"))
        assertEquals(StatusTone.POSITIVE, statusToneFor("activo"))
        assertEquals(StatusTone.ATTENTION, statusToneFor("pendiente"))
    }

    @Test
    fun `un estado desconocido nunca se pinta como correcto`() {
        // Si el backend añade un enum nuevo, no puede acabar en verde por
        // descarte: eso convertiría un estado que no entendemos en una
        // afirmación de que todo va bien.
        assertEquals(StatusTone.UNKNOWN, statusToneFor("estado_que_no_existe"))
        assertEquals(StatusTone.UNKNOWN, statusToneFor(""))
    }

    @Test
    fun `la ausencia de dato es desconocido, no negativo ni positivo`() {
        listOf("NOT_CONNECTED", "no conectado", "sin datos", "NO_DATA", "UNKNOWN", "not_instrumented")
            .forEach { status ->
                assertEquals(
                    StatusTone.UNKNOWN,
                    statusToneFor(status),
                    "'$status' debe leerse como «no lo sabemos»",
                )
            }
    }

    @Test
    fun `el vocabulario de estado de sistema esta cubierto`() {
        assertEquals(StatusTone.POSITIVE, statusToneFor("operativo"))
        assertEquals(StatusTone.ATTENTION, statusToneFor("degradado"))
        assertEquals(StatusTone.NEGATIVE, statusToneFor("caído"))
        assertEquals(StatusTone.UNKNOWN, statusToneFor("no conectado"))
    }

    @Test
    fun `da igual el idioma, las mayusculas y los espacios`() {
        listOf("Activo", "  ACTIVO  ", "active", "Published", "resuelto").forEach { status ->
            assertEquals(StatusTone.POSITIVE, statusToneFor(status), "'$status'")
        }
    }
}
