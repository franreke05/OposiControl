package com.oposicontrol.admin.data.repository

import com.oposicontrol.admin.core.common.result.AppError
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.domain.model.Tag
import com.oposicontrol.admin.domain.model.TagSearchQuery
import com.oposicontrol.admin.domain.model.TaggedSearchResult
import com.oposicontrol.admin.domain.repository.TagSearchRepository

/**
 * Búsqueda por etiquetas: NO DISPONIBLE, y lo dice.
 *
 * Los endpoints legacy `/tags`, `/search` y `/search/reindex` leen `search_index`/`tag`/`entity_tag` y el RPC
 * `admin_replace_search_index`, que no existen en MAIN (los endpoints fallan en runtime; EP-122/EP-208 de la
 * matriz de migración los retiran). El reemplazo es el filtro canónico `etiquetaId` + `q` de los listados admin.
 *
 * Por eso no hace ninguna petición y nunca devuelve un éxito vacío (eso se pintaba como «Sin resultados» o
 * «Indice actualizado.»): cada operación devuelve [AppResult.Failure] y [isAvailable] es false para que la UI
 * oculte el buscador.
 */
class KtorTagSearchRepositoryImpl : TagSearchRepository {
    override val isAvailable: Boolean = false

    override suspend fun findTags(query: String): AppResult<List<Tag>> = unavailable()

    override suspend fun search(query: TagSearchQuery): AppResult<List<TaggedSearchResult>> = unavailable()

    override suspend fun reindex(): AppResult<Unit> = unavailable()

    private fun unavailable(): AppResult.Failure = AppResult.Failure(AppError.NotFound(UNAVAILABLE_MESSAGE))

    companion object {
        const val UNAVAILABLE_MESSAGE = "La busqueda por etiquetas no esta disponible todavia."
    }
}
