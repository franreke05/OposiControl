package com.oposicontrol.admin.data.repository

import com.oposicontrol.admin.core.network.NetworkResultAdapter
import com.oposicontrol.admin.domain.model.Tag
import com.oposicontrol.admin.domain.model.TagSearchQuery
import com.oposicontrol.admin.domain.model.TaggedSearchResult
import com.oposicontrol.admin.domain.repository.TagSearchRepository

/**
 * Búsqueda por etiquetas: enlazada en `dataModule`, pero SIN backend todavía.
 *
 * Los tres métodos devuelven vacío porque `/tags`, `/search` y
 * `/search/reindex` siguen en la API legacy y no se han migrado a `/v1`.
 *
 * El fichero arrastraba además el cliente HTTP, el constructor de URL, las
 * cabeceras de autenticación, dos DTOs y sus mappers: ~50 líneas que ninguna
 * de las tres funciones podía alcanzar, y que hacían leer esta clase como si
 * hiciera peticiones. Se han borrado. Lo que queda dice la verdad.
 *
 * DEUDA CONOCIDA, no resuelta aquí: devolver vacío hace que el panel no
 * distinga "no hay resultados" de "esto todavía no existe". Arreglarlo es un
 * cambio del contrato [TagSearchRepository], no una optimización.
 */
class KtorTagSearchRepositoryImpl(
    private val adapter: NetworkResultAdapter,
) : TagSearchRepository {
    override suspend fun findTags(query: String) = adapter.safeCall {
        // Legacy /tags disabled until the endpoint is migrated to /v1.
        emptyList<Tag>()
    }

    override suspend fun search(query: TagSearchQuery) = adapter.safeCall {
        // Legacy /search disabled until the endpoint is migrated to /v1.
        emptyList<TaggedSearchResult>()
    }

    override suspend fun reindex() = adapter.safeCall {
        // Legacy /search/reindex disabled until search is migrated to /v1.
        Unit
    }
}
