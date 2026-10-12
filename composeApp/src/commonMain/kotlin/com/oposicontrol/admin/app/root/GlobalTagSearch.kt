package com.oposicontrol.admin.app.root

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.oposicontrol.admin.app.navigation.AdminSection
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.core.ui.components.GlassSearchBar
import com.oposicontrol.admin.core.ui.components.GlassSurface
import com.oposicontrol.admin.core.ui.components.StatusChip
import com.oposicontrol.admin.domain.model.Tag
import com.oposicontrol.admin.domain.model.TagSearchQuery
import com.oposicontrol.admin.domain.model.TaggedSearchResult
import com.oposicontrol.admin.domain.repository.TagSearchRepository
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/** Espera tras la ultima pulsacion antes de consultar: cada busqueda descarga el indice entero desde la base de datos. */
private const val TAG_SUGGESTION_DEBOUNCE_MILLIS = 350L
private const val SEARCH_DEBOUNCE_MILLIS = 450L

/** Las sugerencias de etiquetas que se ensenan sin haber escrito nada, recordadas durante cinco minutos. */
private object IdleTagSuggestions {
    private val ttl = 5.minutes
    private var stored: Pair<TimeMark, List<Tag>>? = null

    fun fresh(): List<Tag>? = stored?.takeIf { it.first.elapsedNow() < ttl }?.second

    fun store(tags: List<Tag>) {
        stored = TimeSource.Monotonic.markNow() to tags
    }
}

@Composable
fun GlobalTagSearch(
    currentSection: AdminSection,
    repository: TagSearchRepository,
    onOpenSection: (AdminSection) -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by remember { mutableStateOf("") }
    var tags by remember { mutableStateOf<List<Tag>>(emptyList()) }
    var selectedTags by remember { mutableStateOf<Set<String>>(emptySet()) }
    var sectionOnly by remember(currentSection) { mutableStateOf(false) }
    var results by remember { mutableStateOf<UiAsync<List<TaggedSearchResult>>>(UiAsync.Idle) }

    if (!repository.isAvailable) {
        Text(
            text = "Busqueda por etiquetas no disponible todavia.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    LaunchedEffect(query) {
        // Sin texto, las sugerencias son siempre las mismas: se reutilizan unos minutos en vez de volver a descargarlas
        // cada vez que se cambia de pantalla (el buscador se monta en casi todas).
        if (query.isBlank()) {
            IdleTagSuggestions.fresh()?.let { tags = it; return@LaunchedEffect }
        }
        delay(TAG_SUGGESTION_DEBOUNCE_MILLIS)
        when (val result = repository.findTags(query)) {
            is AppResult.Failure -> Unit
            is AppResult.Success -> {
                tags = result.value
                if (query.isBlank()) IdleTagSuggestions.store(result.value)
            }
        }
    }

    LaunchedEffect(query, selectedTags, sectionOnly, currentSection) {
        delay(SEARCH_DEBOUNCE_MILLIS)
        if (query.isBlank() && selectedTags.isEmpty()) {
            results = UiAsync.Idle
            return@LaunchedEffect
        }
        results = UiAsync.Loading
        val section = if (sectionOnly) currentSection.searchSectionKey() else null
        results = when (
            val result = repository.search(
                TagSearchQuery(
                    text = query,
                    tagSlugs = selectedTags.toList(),
                    section = section,
                ),
            )
        ) {
            is AppResult.Failure -> UiAsync.Error(result.error)
            is AppResult.Success -> if (result.value.isEmpty()) UiAsync.Empty else UiAsync.Success(result.value)
        }
    }

    GlassSurface(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GlassSearchBar(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = "Buscar por texto o etiquetas",
                    modifier = Modifier.weight(1f),
                )
                SelectableSearchChip(
                    text = "Solo ${currentSection.label}",
                    selected = sectionOnly,
                    onClick = { sectionOnly = !sectionOnly },
                    modifier = Modifier.width(178.dp),
                )
            }
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                items(tags.take(20), key = { it.slug }) { tag ->
                    SelectableSearchChip(
                        text = "#${tag.slug}",
                        selected = tag.slug in selectedTags,
                        onClick = {
                            selectedTags = if (tag.slug in selectedTags) {
                                selectedTags - tag.slug
                            } else {
                                selectedTags + tag.slug
                            }
                        },
                        modifier = Modifier.width(118.dp),
                    )
                }
            }
            SearchResultsPane(
                state = results,
                onOpenResult = { result ->
                    result.section.toAdminSection(result.entityType)?.let(onOpenSection)
                },
            )
        }
    }
}

@Composable
private fun SelectableSearchChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    StatusChip(
        status = if (selected) "$text activo" else text,
        modifier = modifier
            .height(34.dp)
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick),
    )
}

@Composable
private fun SearchResultsPane(
    state: UiAsync<List<TaggedSearchResult>>,
    onOpenResult: (TaggedSearchResult) -> Unit,
) {
    when (state) {
        UiAsync.Idle -> Text(
            text = "Selecciona una etiqueta o escribe para buscar en toda la app.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        UiAsync.Loading -> Text(
            text = "Buscando...",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        UiAsync.Empty -> Text(
            text = "Sin resultados para esta busqueda.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        is UiAsync.Error -> Text(
            text = state.error.message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )

        is UiAsync.Success -> LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .height(238.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(state.value, key = { "${it.entityType}:${it.entityId}" }) { result ->
                SearchResultRow(result = result, onClick = { onOpenResult(result) })
            }
        }
    }
}

@Composable
private fun SearchResultRow(
    result: TaggedSearchResult,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(72.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = result.title,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            StatusChip(result.section)
        }
        Text(
            text = result.summary.ifBlank { result.tags.joinToString(" ") { "#${it.slug}" } },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun AdminSection.searchSectionKey(): String {
    return when (this) {
        AdminSection.DASHBOARD -> "dashboard"
        AdminSection.USERS -> "usuarios"
        AdminSection.ROLES -> "roles"
        AdminSection.SPECIALTIES -> "especialidades"
        AdminSection.COMMUNITIES -> "comunidades"
        AdminSection.TAGS -> "etiquetas"
        AdminSection.NEWS_TYPES -> "tipos_noticia"
        AdminSection.NEWS -> "noticias"
        AdminSection.EXAMS -> "examenes"
        AdminSection.QUESTIONS_RESOURCES -> "preguntas_recursos"
        AdminSection.EXAM_ACCESS -> "accesos"
        AdminSection.STORE -> "tienda"
        AdminSection.TICKETS -> "tickets"
        AdminSection.SUPPORT -> "soporte"
        AdminSection.MODERATION -> "moderacion"
        AdminSection.SETTINGS -> "ajustes"
        AdminSection.AUDIT -> "auditoria"
    }
}

private fun String.toAdminSection(entityType: String): AdminSection? {
    return when (lowercase()) {
        "usuarios" -> AdminSection.USERS
        "roles" -> AdminSection.ROLES
        "especialidades" -> AdminSection.SPECIALTIES
        "comunidades" -> AdminSection.COMMUNITIES
        "etiquetas" -> AdminSection.TAGS
        "tipos_noticia" -> AdminSection.NEWS_TYPES
        "noticias" -> AdminSection.NEWS
        "examenes" -> AdminSection.EXAMS
        "preguntas_recursos" -> AdminSection.QUESTIONS_RESOURCES
        "accesos" -> AdminSection.EXAM_ACCESS
        "tienda" -> AdminSection.STORE
        "tickets" -> AdminSection.TICKETS
        "soporte" -> AdminSection.SUPPORT
        "moderacion" -> AdminSection.MODERATION
        "auditoria" -> AdminSection.AUDIT
        else -> when (entityType) {
            "noticia" -> AdminSection.NEWS
            "examen" -> AdminSection.EXAMS
            "pregunta", "recurso_pregunta" -> AdminSection.QUESTIONS_RESOURCES
            "usuario" -> AdminSection.USERS
            "rol" -> AdminSection.ROLES
            else -> null
        }
    }
}
