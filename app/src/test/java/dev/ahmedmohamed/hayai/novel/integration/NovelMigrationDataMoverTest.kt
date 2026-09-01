package dev.ahmedmohamed.hayai.novel.integration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class NovelMigrationDataMoverTest {
    @Test
    fun `chapter mapping is deterministic and only requires chapters with user state`() {
        val source =
            listOf(
                NovelMigrationChapterReference(1, 1f, recognized = true),
                NovelMigrationChapterReference(2, -1f, recognized = false),
            )
        val target = listOf(NovelMigrationChapterReference(10, 1f, recognized = true))

        assertEquals(
            mapOf(1L to 10L),
            NovelMigrationChapterMapper.map(setOf(1), source, target),
        )
    }

    @Test
    fun `chapter mapping refuses missing and ambiguous user state targets`() {
        val source = listOf(NovelMigrationChapterReference(1, 1f, recognized = true))

        assertThrows(NovelMigrationException::class.java) {
            NovelMigrationChapterMapper.map(setOf(1), source, emptyList())
        }
        assertThrows(NovelMigrationException::class.java) {
            NovelMigrationChapterMapper.map(
                setOf(1),
                source,
                listOf(
                    NovelMigrationChapterReference(10, 1f, recognized = true),
                    NovelMigrationChapterReference(11, 1f, recognized = true),
                ),
            )
        }
    }

    @Test
    fun `chapter mapping refuses two stateful source chapters sharing one target`() {
        assertThrows(NovelMigrationException::class.java) {
            NovelMigrationChapterMapper.map(
                setOf(1, 2),
                listOf(
                    NovelMigrationChapterReference(1, 1f, recognized = true),
                    NovelMigrationChapterReference(2, 1f, recognized = true),
                ),
                listOf(NovelMigrationChapterReference(10, 1f, recognized = true)),
            )
        }
    }

    @Test
    fun `replace rebinds absent statistics and removes source duplicates of identical target statistics`() {
        val actions =
            NovelMigrationStatisticPlanner.plan(
                source =
                    listOf(
                        NovelMigrationStatistic(1, 100),
                        NovelMigrationStatistic(2, 200),
                    ),
                target = listOf(NovelMigrationStatistic(20, 200)),
                chapterMap = mapOf(1L to 10L, 2L to 20L),
                replace = true,
            )

        assertEquals(
            listOf(
                NovelMigrationStatisticAction(NovelMigrationStatisticOperation.Rebind, 1, 10, 100),
                NovelMigrationStatisticAction(NovelMigrationStatisticOperation.DeleteSource, 2, 20, 200),
            ),
            actions,
        )
    }

    @Test
    fun `copy inserts absent statistics and leaves identical target statistics unchanged`() {
        val actions =
            NovelMigrationStatisticPlanner.plan(
                source =
                    listOf(
                        NovelMigrationStatistic(1, 100),
                        NovelMigrationStatistic(2, 200),
                    ),
                target = listOf(NovelMigrationStatistic(20, 200)),
                chapterMap = mapOf(1L to 10L, 2L to 20L),
                replace = false,
            )

        assertEquals(
            listOf(NovelMigrationStatisticAction(NovelMigrationStatisticOperation.Insert, 1, 10, 100)),
            actions,
        )
    }

    @Test
    fun `statistics refuse a different target word count before writing`() {
        assertThrows(NovelMigrationException::class.java) {
            NovelMigrationStatisticPlanner.plan(
                source = listOf(NovelMigrationStatistic(1, 100)),
                target = listOf(NovelMigrationStatistic(10, 101)),
                chapterMap = mapOf(1L to 10L),
                replace = false,
            )
        }
    }

    @Test
    fun `completed translations map to the stable target identity`() {
        val actions =
            NovelMigrationTranslationPlanner.plan(
                source = listOf(translation(1, 7, "/old", "/old/1")),
                target = emptyList(),
                chapterMap = mapOf(1L to 10L),
                targetSourceId = 8,
                targetMangaUrl = "/new",
                targetChapterUrls = mapOf(10L to "/new/1"),
                replace = false,
            )

        assertEquals(NovelMigrationTranslationOperation.Insert, actions.single().operation)
        assertEquals(translation(10, 8, "/new", "/new/1"), actions.single().target)
    }

    @Test
    fun `completed translation migration is idempotent and refuses conflicting target content`() {
        val source = translation(1, 7, "/old", "/old/1")
        val moved = translation(10, 8, "/new", "/new/1")

        assertEquals(
            emptyList<NovelMigrationTranslationAction>(),
            NovelMigrationTranslationPlanner.plan(
                source = listOf(source),
                target = listOf(moved),
                chapterMap = mapOf(1L to 10L),
                targetSourceId = 8,
                targetMangaUrl = "/new",
                targetChapterUrls = mapOf(10L to "/new/1"),
                replace = false,
            ),
        )
        assertThrows(NovelMigrationException::class.java) {
            NovelMigrationTranslationPlanner.plan(
                source = listOf(source),
                target = listOf(moved.copy(translatedContent = "different")),
                chapterMap = mapOf(1L to 10L),
                targetSourceId = 8,
                targetMangaUrl = "/new",
                targetChapterUrls = mapOf(10L to "/new/1"),
                replace = false,
            )
        }
    }

    @Test
    fun `copied quote identities include destination and complete payload`() {
        val quote =
            NovelMigrationQuote(
                id = "quote",
                mangaId = 1,
                novelName = "Novel",
                chapterName = "Chapter 1",
                displayedContent = "Text",
                originalContent = "Original",
                translatedContent = null,
                language = "en",
                timestamp = 42,
            )
        val first = NovelMigrationCopyIdentity.quoteId(quote, targetMangaId = 2, attempt = 0)

        assertEquals(first, NovelMigrationCopyIdentity.quoteId(quote, targetMangaId = 2, attempt = 0))
        assertNotEquals(first, NovelMigrationCopyIdentity.quoteId(quote, targetMangaId = 3, attempt = 0))
        assertNotEquals(
            first,
            NovelMigrationCopyIdentity.quoteId(quote.copy(translatedContent = "Translation"), targetMangaId = 2, attempt = 0),
        )
    }

    @Test
    fun `copied highlight identities include translated stable identity`() {
        val highlight =
            NovelMigrationHighlight(
                id = "highlight",
                mangaId = 2,
                chapterId = 20,
                sourceId = 200,
                mangaUrl = "/target",
                chapterUrl = "/target/chapter",
                color = 1,
                note = null,
                anchorJson = "{}",
                createdAt = 10,
                updatedAt = 11,
            )
        val first = NovelMigrationCopyIdentity.highlightId(highlight, attempt = 0)

        assertEquals(first, NovelMigrationCopyIdentity.highlightId(highlight, attempt = 0))
        assertNotEquals(
            first,
            NovelMigrationCopyIdentity.highlightId(highlight.copy(chapterUrl = "/other/chapter"), attempt = 0),
        )
    }

    private fun translation(
        chapterId: Long,
        sourceId: Long,
        mangaUrl: String,
        chapterUrl: String,
    ) =
        NovelMigrationTranslation(
            chapterId = chapterId,
            sourceId = sourceId,
            mangaUrl = mangaUrl,
            chapterUrl = chapterUrl,
            sourceLanguage = "ja",
            targetLanguage = "en",
            sourceHash = "a".repeat(64),
            translatedContent = "translated",
            contentFormat = "plain_text_v1",
            engineId = "GOOGLE_WEB",
            detectedLanguage = "ja",
            createdAt = 10,
            updatedAt = 11,
        )
}
