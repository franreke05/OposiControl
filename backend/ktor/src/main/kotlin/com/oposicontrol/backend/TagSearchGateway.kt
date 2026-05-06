package com.oposicontrol.backend

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.text.Normalizer

private const val STORED_SEARCH_QUESTION_PREFIX = "__OPOSICONTROL_QUESTION_V1__\n"
private const val STORED_SEARCH_NEWS_PREFIX = "__OPOSICONTROL_NEWS_V1__\n"

@Serializable
data class TagDto(
    val id: String = "",
    val nombre: String,
    val slug: String,
    val origen: String = "auto",
)

@Serializable
data class TaggedSearchResultDto(
    val entityType: String,
    val entityId: String,
    val section: String,
    val title: String,
    val summary: String = "",
    val tags: List<TagDto> = emptyList(),
    val updatedAt: String = "",
)

@Serializable
data class ReindexResultDto(
    val indexed: Int,
    val tags: Int,
)

class SupabaseTagSearchGateway private constructor(
    private val supabaseUrl: String,
    private val serviceRoleKey: String,
    private val json: Json,
    private val client: HttpClient = HttpClient.newHttpClient(),
) {
    fun listTags(query: String): List<TagDto> {
        val normalized = query.trim().lowercase()
        return request("GET", "/rest/v1/tag?select=*&order=nombre.asc&limit=250", null)
            .decodeList<TagRow>()
            .filter { row ->
                normalized.isBlank() ||
                    row.nombre.lowercase().contains(normalized) ||
                    row.slug.lowercase().contains(normalized)
            }
            .take(40)
            .map { it.toDto() }
    }

    fun search(
        query: String,
        tagSlugs: List<String>,
        section: String?,
    ): List<TaggedSearchResultDto> {
        val normalizedQuery = query.trim().lowercase()
        val selectedTags = tagSlugs.map { it.toSlug() }.filter { it.isNotBlank() }.toSet()
        val normalizedSection = section?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
        val indexRows = request("GET", "/rest/v1/search_index?select=*&order=updated_at.desc&limit=500", null)
            .decodeList<SearchIndexRow>()
        if (indexRows.isEmpty()) return emptyList()
        val tagsById = request("GET", "/rest/v1/tag?select=*&limit=1000", null)
            .decodeList<TagRow>()
            .associateBy { it.id }
        val entityTags = request("GET", "/rest/v1/entity_tag?select=*&limit=3000", null)
            .decodeList<EntityTagRow>()
            .groupBy { "${it.entityType}:${it.entityId}" }
        return indexRows.asSequence()
            .map { row ->
                val tags = entityTags["${row.entityType}:${row.entityId}"]
                    .orEmpty()
                    .mapNotNull { tagsById[it.tagId] }
                    .map { it.toDto() }
                    .sortedBy { it.nombre }
                row to tags
            }
            .filter { (row, tags) ->
                val rowTagSlugs = tags.map { it.slug }.toSet()
                val textMatches = normalizedQuery.isBlank() ||
                    row.title.lowercase().contains(normalizedQuery) ||
                    row.summary.lowercase().contains(normalizedQuery) ||
                    row.searchableText.lowercase().contains(normalizedQuery) ||
                    tags.any { it.nombre.lowercase().contains(normalizedQuery) || it.slug.contains(normalizedQuery) }
                val tagsMatch = selectedTags.isEmpty() || selectedTags.all { it in rowTagSlugs }
                val sectionMatch = normalizedSection == null || row.section.lowercase() == normalizedSection
                textMatches && tagsMatch && sectionMatch
            }
            .sortedWith(
                compareByDescending<Pair<SearchIndexRow, List<TagDto>>> { (_, tags) ->
                    tags.count { it.slug in selectedTags }
                }.thenByDescending { (row, _) -> row.updatedAt },
            )
            .take(60)
            .map { (row, tags) ->
                TaggedSearchResultDto(
                    entityType = row.entityType,
                    entityId = row.entityId,
                    section = row.section,
                    title = row.title,
                    summary = row.summary,
                    tags = tags,
                    updatedAt = row.updatedAt,
                )
            }
            .toList()
    }

    fun reindexAll(): ReindexResultDto {
        clearIndex()
        val inputs = buildList {
            addAll(listEspecialidades().map { it.toIndexInput() })
            addAll(listTiposNoticia().map { it.toIndexInput() })
            addAll(listExamenes().map { it.toIndexInput() })
            addAll(listPreguntas().map { it.toIndexInput(json) })
            addAll(listRecursos().map { it.toIndexInput() })
            addAll(listNoticias().map { it.toIndexInput(json) })
            addAll(listUsuarios().map { it.toIndexInput() })
            addAll(listRoles().map { it.toIndexInput() })
            addAll(listArticulos().map { it.toIndexInput() })
            addAll(listTicketTransactions().map { it.toIndexInput() })
            addAll(listSupportConversations().map { it.toIndexInput() })
            addAll(listSupportMessages().map { it.toIndexInput() })
            addAll(listModerationActions().map { it.toIndexInput() })
            addAll(listAuditEntries().map { it.toIndexInput() })
            addAll(listExamAccess().map { it.toIndexInput() })
        }.filter { it.entityId.isNotBlank() && it.title.isNotBlank() }
        inputs.forEach(::index)
        return ReindexResultDto(indexed = inputs.size, tags = listTags("").size)
    }

    fun indexEspecialidad(input: EspecialidadDto) {
        index(input.toIndexInput())
    }

    fun indexSolucionario(input: SolucionarioDto) {
        index(input.toIndexInput())
    }

    fun indexPregunta(input: PreguntaDto) {
        index(input.toIndexInput(json))
    }

    fun indexRecurso(input: RecursoPreguntaDto) {
        index(input.toIndexInput())
    }

    fun indexNoticia(input: NoticiaDto) {
        index(input.toIndexInput())
    }

    fun remove(entityType: String, entityId: String) {
        if (entityId.isBlank()) return
        request(
            "DELETE",
            "/rest/v1/entity_tag?entity_type=eq.${entityType}&entity_id=eq.${entityId}",
            null,
        )
        request(
            "DELETE",
            "/rest/v1/search_index?entity_type=eq.${entityType}&entity_id=eq.${entityId}",
            null,
        )
    }

    private fun index(input: SearchIndexInput) {
        if (input.entityId.isBlank() || input.title.isBlank()) return
        val searchableText = listOf(input.title, input.summary, input.tags.joinToString(" "))
            .joinToString(" ")
            .take(6000)
        request(
            method = "POST",
            path = "/rest/v1/search_index?on_conflict=entity_type,entity_id",
            body = json.encodeToString(
                listOf(
                    SearchIndexUpsertRow(
                        entityType = input.entityType,
                        entityId = input.entityId,
                        section = input.section,
                        title = input.title.take(220),
                        summary = input.summary.take(500),
                        searchableText = searchableText,
                        updatedAt = java.time.Instant.now().toString(),
                    ),
                ),
            ),
            prefer = "resolution=merge-duplicates,return=minimal",
        )
        request(
            "DELETE",
            "/rest/v1/entity_tag?entity_type=eq.${input.entityType}&entity_id=eq.${input.entityId}",
            null,
        )
        input.tags.asSequence()
            .map { it.toSlug() }
            .filter { it.isNotBlank() && it.length >= 2 }
            .distinct()
            .take(16)
            .forEach { slug ->
                val tag = upsertTag(slug)
                request(
                    method = "POST",
                    path = "/rest/v1/entity_tag?on_conflict=tag_id,entity_type,entity_id",
                    body = json.encodeToString(
                        listOf(
                            EntityTagUpsertRow(
                                tagId = tag.id ?: return@forEach,
                                entityType = input.entityType,
                                entityId = input.entityId,
                                score = 1.0,
                            ),
                        ),
                    ),
                    prefer = "resolution=merge-duplicates,return=minimal",
                )
            }
    }

    private fun upsertTag(slug: String): TagRow {
        return request(
            method = "POST",
            path = "/rest/v1/tag?on_conflict=slug",
            body = json.encodeToString(listOf(TagUpsertRow(nombre = slug.toDisplayName(), slug = slug, origen = "auto"))),
            prefer = "resolution=merge-duplicates,return=representation",
        ).decodeList<TagRow>().first()
    }

    private fun clearIndex() {
        request("DELETE", "/rest/v1/entity_tag?id=gte.0", null)
        request("DELETE", "/rest/v1/search_index?id=gte.0", null)
    }

    private fun listEspecialidades(): List<EspecialidadSearchRow> =
        request("GET", "/rest/v1/especialidad?select=*&deleted_at=is.null&limit=1000", null).decodeList()

    private fun listTiposNoticia(): List<TipoNoticiaSearchRow> =
        request("GET", "/rest/v1/tipo_noticia?select=*&deleted_at=is.null&limit=1000", null).decodeList()

    private fun listExamenes(): List<ExamenSearchRow> =
        request("GET", "/rest/v1/examen?select=*&deleted_at=is.null&limit=1000", null).decodeList()

    private fun listPreguntas(): List<PreguntaSearchRow> =
        request("GET", "/rest/v1/pregunta?select=*&deleted_at=is.null&limit=2000", null).decodeList()

    private fun listRecursos(): List<RecursoSearchRow> =
        request("GET", "/rest/v1/recurso_pregunta?select=*&deleted_at=is.null&limit=2000", null).decodeList()

    private fun listNoticias(): List<NoticiaSearchRow> =
        request("GET", "/rest/v1/noticia?select=*&deleted_at=is.null&limit=1000", null).decodeList()

    private fun listUsuarios(): List<UsuarioSearchRow> =
        request("GET", "/rest/v1/usuario?select=*&deleted_at=is.null&limit=1000", null).decodeList()

    private fun listRoles(): List<RoleSearchRow> =
        request("GET", "/rest/v1/app_role?select=*&limit=1000", null).decodeList()

    private fun listArticulos(): List<ArticuloSearchRow> =
        request("GET", "/rest/v1/articulo_tienda?select=*&deleted_at=is.null&limit=1000", null).decodeList()

    private fun listTicketTransactions(): List<TicketSearchRow> =
        request("GET", "/rest/v1/transaccion_ticket?select=*&limit=1000", null).decodeList()

    private fun listSupportConversations(): List<SupportConversationSearchRow> =
        request("GET", "/rest/v1/support_conversation?select=*&limit=1000", null).decodeList()

    private fun listSupportMessages(): List<SupportMessageSearchRow> =
        request("GET", "/rest/v1/support_message?select=*&limit=1000", null).decodeList()

    private fun listModerationActions(): List<ModerationSearchRow> =
        request("GET", "/rest/v1/moderation_action?select=*&limit=1000", null).decodeList()

    private fun listAuditEntries(): List<AuditSearchRow> =
        request("GET", "/rest/v1/audit_log?select=*&limit=1000", null).decodeList()

    private fun listExamAccess(): List<ExamAccessSearchRow> =
        request("GET", "/rest/v1/acceso_examen_usuario?select=*&limit=1000", null).decodeList()

    private fun request(
        method: String,
        path: String,
        body: String?,
        prefer: String? = null,
    ): String {
        val builder = HttpRequest.newBuilder(URI.create("$supabaseUrl$path"))
            .header("apikey", serviceRoleKey)
            .header("Authorization", "Bearer $serviceRoleKey")
            .header("Content-Type", "application/json")
        if (prefer != null) builder.header("Prefer", prefer)
        val publisher = body?.let(HttpRequest.BodyPublishers::ofString) ?: HttpRequest.BodyPublishers.noBody()
        val response = client.send(builder.method(method, publisher).build(), HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            throw IllegalStateException("Supabase request failed with status ${response.statusCode()}")
        }
        return response.body()
    }

    private inline fun <reified T> String.decodeList(): List<T> = json.decodeFromString(this)

    companion object {
        fun fromEnvironment(json: Json): SupabaseTagSearchGateway? {
            val url = System.getenv("SUPABASE_URL")?.trimEnd('/').orEmpty()
            val serviceRoleKey = System.getenv("SUPABASE_SERVICE_ROLE_KEY").orEmpty()
            return if (url.isBlank() || serviceRoleKey.isBlank()) null else SupabaseTagSearchGateway(url, serviceRoleKey, json)
        }
    }
}

private data class SearchIndexInput(
    val entityType: String,
    val entityId: String,
    val section: String,
    val title: String,
    val summary: String,
    val tags: List<String>,
)

@Serializable
private data class TagRow(
    val id: Int? = null,
    val nombre: String,
    val slug: String,
    val origen: String = "auto",
)

@Serializable
private data class TagUpsertRow(
    val nombre: String,
    val slug: String,
    val origen: String,
)

@Serializable
private data class EntityTagRow(
    val id: Int? = null,
    @SerialName("tag_id") val tagId: Int,
    @SerialName("entity_type") val entityType: String,
    @SerialName("entity_id") val entityId: String,
    val score: Double = 1.0,
)

@Serializable
private data class EntityTagUpsertRow(
    @SerialName("tag_id") val tagId: Int,
    @SerialName("entity_type") val entityType: String,
    @SerialName("entity_id") val entityId: String,
    val score: Double = 1.0,
)

@Serializable
private data class SearchIndexRow(
    val id: Int? = null,
    @SerialName("entity_type") val entityType: String,
    @SerialName("entity_id") val entityId: String,
    val section: String,
    val title: String,
    val summary: String = "",
    @SerialName("searchable_text") val searchableText: String = "",
    @SerialName("updated_at") val updatedAt: String = "",
)

@Serializable
private data class SearchIndexUpsertRow(
    @SerialName("entity_type") val entityType: String,
    @SerialName("entity_id") val entityId: String,
    val section: String,
    val title: String,
    val summary: String,
    @SerialName("searchable_text") val searchableText: String,
    @SerialName("updated_at") val updatedAt: String,
)

@Serializable
private data class EspecialidadSearchRow(val id: Int? = null, val nombre: String, val slug: String = "", val activa: Boolean = true)

@Serializable
private data class TipoNoticiaSearchRow(val id: Int? = null, val nombre: String, val slug: String = "", val activo: Boolean = true)

@Serializable
private data class ExamenSearchRow(
    val id: Int? = null,
    val titulo: String,
    @SerialName("especialidad_id") val especialidadId: Int,
    val estado: String = "draft",
)

@Serializable
private data class PreguntaSearchRow(
    val id: Int? = null,
    @SerialName("examen_id") val examenId: Int,
    val enunciado: String,
    val orden: Int = 0,
)

@Serializable
private data class RecursoSearchRow(
    val id: Int? = null,
    @SerialName("pregunta_id") val preguntaId: Int,
    @SerialName("tipo_recurso") val tipoRecurso: String,
    val url: String,
)

@Serializable
private data class NoticiaSearchRow(
    val id: Int? = null,
    val titulo: String,
    @SerialName("tipo_fondo") val tipoFondo: String = "A",
    @SerialName("especialidad_id") val especialidadId: Int = 1,
    val texto: String,
    @SerialName("tipo_noticia_id") val tipoNoticiaId: Int = 1,
    val estado: String = "draft",
)

@Serializable
private data class UsuarioSearchRow(
    val id: Int? = null,
    val email: String? = null,
    val nombre: String? = null,
    @SerialName("especialidad_id") val especialidadId: Int? = null,
)

@Serializable
private data class RoleSearchRow(val id: Int? = null, val name: String, val description: String? = null)

@Serializable
private data class ArticuloSearchRow(
    val id: Int? = null,
    val nombre: String,
    @SerialName("tipo_articulo") val tipoArticulo: String,
    val activo: Boolean = true,
)

@Serializable
private data class TicketSearchRow(
    val id: Int? = null,
    @SerialName("usuario_id") val usuarioId: Int,
    val tipo: String,
    val razon: String,
    @SerialName("referencia_tipo") val referenciaTipo: String? = null,
)

@Serializable
private data class SupportConversationSearchRow(
    val id: Int? = null,
    val estado: String,
    val titulo: String? = null,
)

@Serializable
private data class SupportMessageSearchRow(
    val id: Int? = null,
    @SerialName("conversation_id") val conversationId: Int,
    val body: String? = null,
    @SerialName("internal_note") val internalNote: Boolean = false,
)

@Serializable
private data class ModerationSearchRow(
    val id: Int? = null,
    @SerialName("action_type") val actionType: String,
    val reason: String,
)

@Serializable
private data class AuditSearchRow(
    val id: Long? = null,
    val action: String,
    val resource: String,
    @SerialName("resource_id") val resourceId: String? = null,
)

@Serializable
private data class ExamAccessSearchRow(
    val id: Int? = null,
    @SerialName("usuario_id") val usuarioId: Int,
    @SerialName("examen_id") val examenId: Int,
    @SerialName("tipo_acceso") val tipoAcceso: String,
    val activo: Boolean = true,
)

@Serializable
private data class StoredSearchQuestion(
    val titulo: String = "",
    val enunciado: String = "",
    val opciones: List<OpcionDto> = emptyList(),
)

@Serializable
private data class StoredSearchNewsText(
    val body: String = "",
    val capas: List<NoticiaLayerDto> = emptyList(),
)

private fun TagRow.toDto() = TagDto(id = id?.toString().orEmpty(), nombre = nombre, slug = slug, origen = origen)

private fun EspecialidadDto.toIndexInput() = SearchIndexInput(
    entityType = "especialidad",
    entityId = id,
    section = "especialidades",
    title = nombre,
    summary = slug,
    tags = autoTags("especialidad", nombre, slug, if (activa) "activa" else "inactiva"),
)

private fun EspecialidadSearchRow.toIndexInput() = EspecialidadDto(
    id = id?.toString().orEmpty(),
    nombre = nombre,
    slug = slug,
    activa = activa,
).toIndexInput()

private fun TipoNoticiaSearchRow.toIndexInput() = SearchIndexInput(
    entityType = "tipo_noticia",
    entityId = id?.toString().orEmpty(),
    section = "tipos_noticia",
    title = nombre,
    summary = slug,
    tags = autoTags("tipo-noticia", nombre, slug, if (activo) "activo" else "inactivo"),
)

private fun SolucionarioDto.toIndexInput() = SearchIndexInput(
    entityType = "examen",
    entityId = id,
    section = "examenes",
    title = titulo,
    summary = descripcion,
    tags = autoTags("examen", titulo, descripcion, estado, especialidadId),
)

private fun ExamenSearchRow.toIndexInput() = SearchIndexInput(
    entityType = "examen",
    entityId = id?.toString().orEmpty(),
    section = "examenes",
    title = titulo,
    summary = "Especialidad $especialidadId",
    tags = autoTags("examen", titulo, estado, "especialidad-$especialidadId"),
)

private fun PreguntaDto.toIndexInput(json: Json) = SearchIndexInput(
    entityType = "pregunta",
    entityId = id,
    section = "preguntas_recursos",
    title = titulo.ifBlank { "Pregunta $orden" },
    summary = enunciado,
    tags = autoTags("pregunta", titulo, enunciado, solucionarioId) + opciones.map { it.label },
)

private fun PreguntaSearchRow.toIndexInput(json: Json): SearchIndexInput {
    val stored = enunciado.decodeStoredSearchQuestion(json)
    return SearchIndexInput(
        entityType = "pregunta",
        entityId = id?.toString().orEmpty(),
        section = "preguntas_recursos",
        title = stored.titulo.ifBlank { "Pregunta ${orden + 1}" },
        summary = stored.enunciado.ifBlank { enunciado },
        tags = autoTags("pregunta", stored.titulo, stored.enunciado, "examen-$examenId") + stored.opciones.map { it.label },
    )
}

private fun RecursoPreguntaDto.toIndexInput() = SearchIndexInput(
    entityType = "recurso_pregunta",
    entityId = id,
    section = "preguntas_recursos",
    title = titulo.ifBlank { url.substringAfterLast('/').ifBlank { "Recurso" } },
    summary = url.ifBlank { publicUrl ?: storagePath.orEmpty() },
    tags = autoTags("recurso", tipoRecurso, titulo, url, preguntaId),
)

private fun RecursoSearchRow.toIndexInput() = SearchIndexInput(
    entityType = "recurso_pregunta",
    entityId = id?.toString().orEmpty(),
    section = "preguntas_recursos",
    title = url.substringAfterLast('/').ifBlank { "Recurso $id" },
    summary = url,
    tags = autoTags("recurso", tipoRecurso, url, "pregunta-$preguntaId"),
)

private fun NoticiaDto.toIndexInput() = SearchIndexInput(
    entityType = "noticia",
    entityId = id,
    section = "noticias",
    title = titulo,
    summary = texto,
    tags = autoTags("noticia", titulo, texto, especialidad, tipo, estado, fondo) + capas.map { it.tipo },
)

private fun NoticiaSearchRow.toIndexInput(json: Json): SearchIndexInput {
    val stored = texto.decodeStoredSearchNews(json)
    return SearchIndexInput(
        entityType = "noticia",
        entityId = id?.toString().orEmpty(),
        section = "noticias",
        title = titulo,
        summary = stored.body.ifBlank { texto },
        tags = autoTags("noticia", titulo, stored.body, estado, tipoFondo, "especialidad-$especialidadId", "tipo-$tipoNoticiaId") +
            stored.capas.map { it.tipo },
    )
}

private fun UsuarioSearchRow.toIndexInput() = SearchIndexInput(
    entityType = "usuario",
    entityId = id?.toString().orEmpty(),
    section = "usuarios",
    title = nombre ?: email ?: "Usuario $id",
    summary = email.orEmpty(),
    tags = autoTags("usuario", nombre.orEmpty(), email.orEmpty(), especialidadId?.let { "especialidad-$it" }.orEmpty()),
)

private fun RoleSearchRow.toIndexInput() = SearchIndexInput(
    entityType = "rol",
    entityId = id?.toString().orEmpty(),
    section = "roles",
    title = name,
    summary = description.orEmpty(),
    tags = autoTags("rol", name, description.orEmpty()),
)

private fun ArticuloSearchRow.toIndexInput() = SearchIndexInput(
    entityType = "articulo_tienda",
    entityId = id?.toString().orEmpty(),
    section = "tienda",
    title = nombre,
    summary = tipoArticulo,
    tags = autoTags("tienda", nombre, tipoArticulo, if (activo) "activo" else "inactivo"),
)

private fun TicketSearchRow.toIndexInput() = SearchIndexInput(
    entityType = "transaccion_ticket",
    entityId = id?.toString().orEmpty(),
    section = "tickets",
    title = razon,
    summary = referenciaTipo.orEmpty(),
    tags = autoTags("ticket", tipo, razon, referenciaTipo.orEmpty(), "usuario-$usuarioId"),
)

private fun SupportConversationSearchRow.toIndexInput() = SearchIndexInput(
    entityType = "support_conversation",
    entityId = id?.toString().orEmpty(),
    section = "soporte",
    title = titulo ?: "Conversacion $id",
    summary = estado,
    tags = autoTags("soporte", estado, titulo.orEmpty()),
)

private fun SupportMessageSearchRow.toIndexInput() = SearchIndexInput(
    entityType = "support_message",
    entityId = id?.toString().orEmpty(),
    section = "soporte",
    title = body?.take(80)?.ifBlank { "Mensaje $id" } ?: "Mensaje $id",
    summary = body.orEmpty(),
    tags = autoTags("soporte", "mensaje", if (internalNote) "nota-interna" else "respuesta", "conversacion-$conversationId"),
)

private fun ModerationSearchRow.toIndexInput() = SearchIndexInput(
    entityType = "moderation_action",
    entityId = id?.toString().orEmpty(),
    section = "moderacion",
    title = actionType,
    summary = reason,
    tags = autoTags("moderacion", actionType, reason),
)

private fun AuditSearchRow.toIndexInput() = SearchIndexInput(
    entityType = "audit_log",
    entityId = id?.toString().orEmpty(),
    section = "auditoria",
    title = action,
    summary = listOf(resource, resourceId.orEmpty()).joinToString(" "),
    tags = autoTags("auditoria", action, resource, resourceId.orEmpty()),
)

private fun ExamAccessSearchRow.toIndexInput() = SearchIndexInput(
    entityType = "acceso_examen_usuario",
    entityId = id?.toString().orEmpty(),
    section = "accesos",
    title = tipoAcceso,
    summary = "Usuario $usuarioId | Examen $examenId",
    tags = autoTags("acceso", tipoAcceso, if (activo) "activo" else "inactivo", "usuario-$usuarioId", "examen-$examenId"),
)

private fun String.decodeStoredSearchQuestion(json: Json): StoredSearchQuestion {
    if (!startsWith(STORED_SEARCH_QUESTION_PREFIX)) return StoredSearchQuestion(enunciado = this)
    return runCatching { json.decodeFromString<StoredSearchQuestion>(removePrefix(STORED_SEARCH_QUESTION_PREFIX)) }
        .getOrElse { StoredSearchQuestion(enunciado = this) }
}

private fun String.decodeStoredSearchNews(json: Json): StoredSearchNewsText {
    if (!startsWith(STORED_SEARCH_NEWS_PREFIX)) return StoredSearchNewsText(body = this)
    return runCatching { json.decodeFromString<StoredSearchNewsText>(removePrefix(STORED_SEARCH_NEWS_PREFIX)) }
        .getOrElse { StoredSearchNewsText(body = this) }
}

private fun autoTags(vararg values: String): List<String> {
    val stopWords = setOf("para", "por", "con", "sin", "una", "uno", "del", "las", "los", "the", "and", "draft")
    return values.asSequence()
        .flatMap { value -> value.split(Regex("[\\s,.;:/|()\\[\\]{}]+")).asSequence() }
        .map { it.toSlug() }
        .filter { it.length >= 2 && it !in stopWords }
        .distinct()
        .take(16)
        .toList()
}

private fun String.toSlug(): String {
    val normalized = Normalizer.normalize(trim().lowercase(), Normalizer.Form.NFD)
        .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
    return normalized.replace(Regex("[^a-z0-9]+"), "-").trim('-')
}

private fun String.toDisplayName(): String {
    return split("-").filter { it.isNotBlank() }.joinToString(" ") { part ->
        part.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }.ifBlank { this }
}
