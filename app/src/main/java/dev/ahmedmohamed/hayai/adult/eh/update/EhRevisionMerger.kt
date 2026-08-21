package dev.ahmedmohamed.hayai.adult.eh.update

import dev.ahmedmohamed.hayai.adult.eh.domain.GalleryKey

object EhRevisionMerger {
    fun merge(
        remote: List<EhRemoteRevision>,
        local: List<EhLocalRevision>,
    ): EhRevisionMerge {
        require(remote.isNotEmpty()) { "A gallery revision chain cannot be empty" }
        val distinctRemote = remote.distinctBy { it.key }.sortedWith(compareBy(EhRemoteRevision::postedAt, { it.key.id.value }))
        val localByKey = local.groupBy { runCatching { GalleryKey.parse(it.url) }.getOrNull() }
        val mutations = distinctRemote.map { revision ->
            val matches = localByKey[revision.key].orEmpty()
            val preferred = matches.sortedWith(compareByDescending<EhLocalRevision> { it.downloaded }.thenBy { it.id }).firstOrNull()
            EhRevisionMutation(
                localId = preferred?.id,
                remote = revision,
                read = matches.any(EhLocalRevision::read),
                bookmark = matches.any(EhLocalRevision::bookmark),
                lastPageRead = matches.maxOfOrNull(EhLocalRevision::lastPageRead) ?: 0,
                pagesLeft = matches.map(EhLocalRevision::pagesLeft).filter { it > 0 }.minOrNull() ?: 0,
                downloaded = matches.any(EhLocalRevision::downloaded),
                historyLastRead = matches.maxOfOrNull(EhLocalRevision::historyLastRead) ?: 0,
                historyTimeRead = matches.maxOfOrNull(EhLocalRevision::historyTimeRead) ?: 0,
            )
        }
        return EhRevisionMerge(mutations, mutations.count { it.localId == null })
    }
}
