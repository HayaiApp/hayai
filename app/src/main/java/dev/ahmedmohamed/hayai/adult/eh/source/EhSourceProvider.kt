package dev.ahmedmohamed.hayai.adult.eh.source

import android.content.Context
import dev.ahmedmohamed.hayai.adult.eh.domain.EhSite
import dev.ahmedmohamed.hayai.adult.eh.network.EhHttpGateway
import dev.ahmedmohamed.hayai.adult.eh.persistence.HayaiEhPersistenceStore
import dev.ahmedmohamed.hayai.adult.eh.session.EhSessionState
import dev.ahmedmohamed.hayai.adult.eh.session.EhSessionStore
import dev.ahmedmohamed.hayai.adult.eh.settings.EhPreferences
import eu.kanade.tachiyomi.source.Source
import kotlinx.coroutines.flow.Flow

class EhSourceProvider(
    context: Context,
    private val gateway: EhHttpGateway,
    private val sessions: EhSessionStore,
    metadataStore: HayaiEhPersistenceStore,
    preferences: EhPreferences,
) {
    private val sources = EhSite.entries.map { EhentaiSource(context, it, gateway, metadataStore, preferences) }
    val discoveryChanges: Flow<EhSessionState> = sessions.state

    fun allSources(): List<Source> = sources

    fun owns(sourceId: Long): Boolean = EhSite.entries.any { it.sourceId == sourceId }

    fun isDiscoverable(sourceId: Long): Boolean =
        when (sourceId) {
            EhSite.EHentai.sourceId -> true
            EhSite.ExHentai.sourceId -> sessions.state.value is EhSessionState.Verified
            else -> true
        }
}
