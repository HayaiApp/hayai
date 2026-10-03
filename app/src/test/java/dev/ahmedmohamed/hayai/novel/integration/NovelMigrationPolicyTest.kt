package dev.ahmedmohamed.hayai.novel.integration

import eu.kanade.tachiyomi.data.database.models.Manga
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.FilterList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NovelMigrationPolicyTest {
    @Test
    fun `manual novel type overrides old manga classification without side data`() {
        val entry = Manga.create("/old", "Old entry", 404).apply { genre = "Manga, Novel" }
        val identity = NovelContentIdentity(sourceIsNovel = { false }, hasDurableNovelEvidence = { false })
        assertEquals(ContentKind.Novel, identity.contentKind(entry))
        assertEquals(NovelSeriesType.TYPE_NOVEL, entry.seriesType())
        assertTrue(entry.isSeriesTag("Novel"))
        entry.genre = "Manga"
        assertEquals(ContentKind.Manga, identity.contentKind(entry))
    }

    @Test
    fun `legacy novel data overrides a missing source during migration`() {
        val legacyNovel = Manga.create(pathUrl = "/legacy-novel", title = "Legacy novel", source = 404)
        val policy =
            NovelMigrationPolicy(
                mangaLookup = { legacyNovel },
                contentIdentity = NovelContentIdentity(sourceIsNovel = { false }, hasDurableNovelEvidence = { true }),
            )

        val contentKind = policy.contentKind(legacyNovel)

        assertEquals(ContentKind.Novel, contentKind)
        assertTrue(contentKind.accepts(isNovelSource = true))
        assertFalse(contentKind.accepts(isNovelSource = false))

        val mangaSource = FakeCatalogueSource(id = 1, isNovelSource = false)
        val novelSource = FakeCatalogueSource(id = 2, isNovelSource = true)
        assertEquals(listOf(novelSource), policy.compatibleSources(legacyNovel, listOf(mangaSource, novelSource)))
    }

    @Test
    fun `legacy novel genre survives when the source and side data are unavailable`() {
        val legacyNovel =
            Manga.create(pathUrl = "/legacy-novel", title = "Legacy novel", source = 404).apply {
                genre = "Action, Light Novel"
            }
        val policy =
            NovelMigrationPolicy(
                mangaLookup = { legacyNovel },
                contentIdentity =
                    NovelContentIdentity(
                        sourceIsNovel = { false },
                        hasDurableNovelEvidence = Manga::hasNovelGenreIdentity,
                    ),
            )

        assertEquals(ContentKind.Novel, policy.contentKind(legacyNovel))
    }

    @Test
    fun `ordinary manga is not promoted by a fuzzy novel genre match`() {
        val manga =
            Manga.create(pathUrl = "/manga", title = "Manga", source = 404).apply {
                genre = "Novel adaptation, Action"
            }
        val policy =
            NovelMigrationPolicy(
                mangaLookup = { manga },
                contentIdentity =
                    NovelContentIdentity(
                        sourceIsNovel = { false },
                        hasDurableNovelEvidence = Manga::hasNovelGenreIdentity,
                    ),
            )

        assertEquals(ContentKind.Manga, policy.contentKind(manga))
    }

    private class FakeCatalogueSource(
        override val id: Long,
        override val isNovelSource: Boolean,
    ) : CatalogueSource {
        override val name = "Source $id"
        override val lang = "en"
        override val supportsLatest = false

        override fun getFilterList() = FilterList()
    }
}
