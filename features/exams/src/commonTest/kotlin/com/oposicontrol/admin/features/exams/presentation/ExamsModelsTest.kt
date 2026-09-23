package com.oposicontrol.admin.features.exams.presentation

import com.oposicontrol.admin.domain.model.SpecialtyCapabilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExamsModelsTest {

    private fun capabilities(
        rafagas: Boolean = false,
        razonados: Boolean = false,
        inmersivos: Boolean = false,
    ) = SpecialtyCapabilities(
        permiteRafagas = rafagas,
        permiteRazonados = razonados,
        permiteInmersivos = inmersivos,
        permiteDemos = false,
        permiteOposimarket = false,
        specialtyId = "specialty-test",
        configured = true,
    )

    @Test
    fun `allows matches each canonical type against its own flag`() {
        val onlyRafagas = capabilities(rafagas = true)
        assertTrue(onlyRafagas.allows("rafaga"))
        assertFalse(onlyRafagas.allows("razonado"))
        assertFalse(onlyRafagas.allows("inmersivo"))
    }

    // "guiado" es el alias legacy de "razonado": debe resolver contra la misma
    // capacidad (permiteRazonados), nunca tratarse como un tipo nuevo.
    @Test
    fun `allows maps the legacy guiado alias to razonado`() {
        val onlyRazonados = capabilities(razonados = true)
        assertTrue(onlyRazonados.allows("guiado"))
        assertTrue(onlyRazonados.allows("razonado"))
    }

    @Test
    fun `allows is case and whitespace insensitive`() {
        val onlyInmersivos = capabilities(inmersivos = true)
        assertTrue(onlyInmersivos.allows(" INMERSIVO "))
    }

    @Test
    fun `allows rejects unknown type strings`() {
        val allAllowed = capabilities(rafagas = true, razonados = true, inmersivos = true)
        assertFalse(allAllowed.allows("desconocido"))
    }

    @Test
    fun `firstAllowedType returns null when the specialty allows nothing`() {
        assertNull(capabilities().firstAllowedType())
    }

    @Test
    fun `firstAllowedType prefers rafaga then razonado then inmersivo`() {
        assertEquals("rafaga", capabilities(rafagas = true, razonados = true, inmersivos = true).firstAllowedType())
        assertEquals("razonado", capabilities(razonados = true, inmersivos = true).firstAllowedType())
        assertEquals("inmersivo", capabilities(inmersivos = true).firstAllowedType())
    }

    // Un examen gratuito ya es accesible para cualquiera (ver el comentario de
    // allowsOposiTicketsPurchase en ExamsModels.kt): la compra con OT sólo
    // tiene sentido real en un examen premium.
    @Test
    fun `allowsOposiTicketsPurchase rejects gratuito`() {
        assertFalse("gratuito".allowsOposiTicketsPurchase())
        assertFalse(" GRATUITO ".allowsOposiTicketsPurchase())
    }

    @Test
    fun `allowsOposiTicketsPurchase accepts premium`() {
        assertTrue("premium".allowsOposiTicketsPurchase())
    }
}
