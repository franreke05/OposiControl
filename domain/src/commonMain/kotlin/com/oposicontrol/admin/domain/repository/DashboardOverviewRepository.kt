package com.oposicontrol.admin.domain.repository

import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.domain.model.DashboardBlock
import com.oposicontrol.admin.domain.model.DashboardOverview

/**
 * Contrato agregado del Dashboard V2.
 *
 * Un único método, y una única petición de red por refresco. Es lo contrario del
 * repositorio anterior ([DashboardRepository]), que desde el cliente orquestaba
 * cinco repositorios y lanzaba además **una llamada por examen** para auditar
 * recursos: un coste que crecía con el catálogo y que componía datos de negocio
 * en la capa de datos del cliente en vez de en el servidor.
 *
 * Aquí la agregación vive entera en Ktor, que es la única frontera con acceso de
 * `service_role` y el único sitio donde componer sale barato.
 *
 * El coste real, contado sobre el código y no estimado: **17 conteos exactos más
 * la sonda de Supabase (18 en total) y una lectura de `auditoria_log`, 19
 * peticiones upstream** por refresco completo, para 15 cifras publicadas. Tres de
 * esos conteos están duplicados entre franjas —`examenes` total, `examenes`
 * publicados y `suscripciones` activas se piden desde dos franjas distintas—, y
 * es un precio deliberado: compartirlos entre franjas acoplaría el fallo de una a
 * otra, que es justo lo que la degradación por franja existe para evitar.
 *
 * La alternativa que se descartó era componer el panel desde los seis endpoints
 * de analítica (`/api/v1/admin/analytics/producto`, `.../estudio` y los cuatro
 * restantes), que ejecutan entre diez y veinte conteos cada uno para responder a
 * otra pantalla; pedir seis secciones enteras para pintar quince cifras era el
 * desperdicio que este contrato evita. (La cifra concreta de esa alternativa no se
 * ha medido y no se afirma aquí.)
 *
 * Nota para quien edite este KDoc: no escribas una ruta terminada en comodín. Un
 * `/` seguido de `*` abre un comentario de bloque anidado —Kotlin los anida— y
 * deja el KDoc sin cerrar, con el resto del fichero dentro del comentario.
 *
 * [DashboardRepository] sigue existiendo y no se retira aquí: alimenta el Home
 * móvil, que conserva su contrato hasta que tenga uno propio.
 */
interface DashboardOverviewRepository {

    /**
     * Lee el panel completo, o sólo las franjas pedidas.
     *
     * @param sections franjas a componer. Vacío significa **todas**, que es el
     *   caso de la carga inicial. Pasar un subconjunto es lo que permite refrescar
     *   la franja de estado del sistema cada minuto sin volver a ejecutar los
     *   conteos de contenido y economía.
     *
     * Devolver [AppResult.Failure] queda reservado a que falle la petición entera
     * —red caída, sin sesión, sin permisos—. Que Ktor no consiga componer una
     * franja concreta **no** es un fallo de la llamada: eso llega como
     * [DashboardOverview.failedBlocks], para que las demás franjas se pinten
     * igualmente.
     */
    suspend fun getOverview(
        sections: Set<DashboardBlock> = emptySet(),
    ): AppResult<DashboardOverview>
}