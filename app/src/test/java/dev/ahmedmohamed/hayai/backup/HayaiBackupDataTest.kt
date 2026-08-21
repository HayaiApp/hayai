package dev.ahmedmohamed.hayai.backup

import eu.kanade.tachiyomi.data.backup.models.Backup
import kotlinx.serialization.protobuf.ProtoBuf
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HayaiBackupDataTest {
    @Test
    fun `protobuf round trip retains every typed side payload`() {
        val data =
            HayaiBackupData(
                version = HayaiBackupData.CURRENT_VERSION,
                quotes =
                    listOf(
                        HayaiBackupQuote(
                            id = "quote-id",
                            sourceId = 7,
                            mangaUrl = "/novel",
                            novelName = "Novel",
                            chapterName = "Chapter",
                            displayedContent = "Quote",
                            originalContent = "Quote",
                            translatedContent = "Translation",
                            language = "en",
                            timestamp = 123,
                        ),
                    ),
                novelRepositories = listOf(HayaiBackupNovelRepository("https://repo.test", "Repo")),
                chapterStats = listOf(HayaiBackupChapterStat(7, "/novel", "/chapter", 900)),
                ehFavorites = listOf(HayaiBackupEhFavorite("1", "token", "Gallery", 2)),
                ehGalleryAliases = listOf(HayaiBackupEhGalleryAlias("1", "token", "2", "replacement")),
                ehCategoryMappings = listOf(HayaiBackupEhCategoryMapping(2, "Remote", "Remote (E-Hentai)")),
                sourceMetadata =
                    listOf(
                        HayaiBackupSourceMetadata(
                            sourceId = 7,
                            mangaUrl = "/g/1/token/",
                            uploader = "uploader",
                            extra = "{}",
                            indexedExtra = "1",
                            extraVersion = 1,
                            tags = listOf(HayaiBackupSourceMetadataTag("artist", "Creator", 0)),
                            titles = listOf(HayaiBackupSourceMetadataTitle("Alternative title", 1)),
                        ),
                    ),
            )

        val encoded = ProtoBuf.encodeToByteArray(HayaiBackupData.serializer(), data)

        assertEquals(data, ProtoBuf.decodeFromByteArray(HayaiBackupData.serializer(), encoded))
    }

    @Test
    fun `validation rejects unsupported versions and unsafe values before writes`() {
        val invalid =
            HayaiBackupData(
                version = 99,
                quotes = listOf(HayaiBackupQuote("", 1, "", "", "", "", timestamp = 0)),
                chapterStats = listOf(HayaiBackupChapterStat(1, "manga", "chapter", -1)),
            )

        val errors = HayaiBackupLimits.validate(invalid)

        assertTrue(errors.any { it.contains("version") })
        assertTrue(errors.any { it.contains("quote") })
        assertTrue(errors.any { it.contains("statistic") })
    }

    @Test
    fun `version one payload remains supported after adding EH persistence`() {
        val versionOne =
            HayaiBackupData(
                version = 1,
                ehFavorites = listOf(HayaiBackupEhFavorite("1", "token", "Gallery", 0)),
            )

        assertTrue(HayaiBackupLimits.validate(versionOne).isEmpty())
        assertEquals(
            versionOne,
            ProtoBuf.decodeFromByteArray(
                HayaiBackupData.serializer(),
                ProtoBuf.encodeToByteArray(HayaiBackupData.serializer(), versionOne),
            ),
        )
    }

    @Test
    fun `validation rejects conflicting EH identities but accepts exact repeats`() {
        val favorite = HayaiBackupEhFavorite("1", "token", "Gallery", 0)
        val metadata = HayaiBackupSourceMetadata(7, "/gallery", extra = "{}", extraVersion = 1)
        val exactRepeats =
            HayaiBackupData(
                ehFavorites = listOf(favorite, favorite),
                sourceMetadata = listOf(metadata, metadata),
            )
        val conflicts =
            exactRepeats.copy(
                ehFavorites = listOf(favorite, favorite.copy(category = 1)),
                ehGalleryAliases =
                    listOf(
                        HayaiBackupEhGalleryAlias("1", "canonical", "2", "alternate"),
                        HayaiBackupEhGalleryAlias("3", "other", "2", "alternate"),
                    ),
                sourceMetadata = listOf(metadata, metadata.copy(extra = "{\"changed\":true}")),
            )

        assertTrue(HayaiBackupLimits.validate(exactRepeats).isEmpty())
        val errors = HayaiBackupLimits.validate(conflicts)
        assertTrue(errors.any { it.contains("duplicate E-Hentai favorite") })
        assertTrue(errors.any { it.contains("duplicate E-Hentai gallery alias") })
        assertTrue(errors.any { it.contains("duplicate source metadata") })
    }

    @Test
    fun `top level backup keeps Hayai payload optional for old backup compatibility`() {
        val data = HayaiBackupData(novelRepositories = listOf(HayaiBackupNovelRepository("https://repo.test", "Repo")))
        val withHayai = Backup(backupManga = emptyList(), hayaiData = data)
        val withoutHayai = Backup(backupManga = emptyList())

        val restoredWithHayai =
            ProtoBuf.decodeFromByteArray(Backup.serializer(), ProtoBuf.encodeToByteArray(Backup.serializer(), withHayai))
        val restoredWithoutHayai =
            ProtoBuf.decodeFromByteArray(Backup.serializer(), ProtoBuf.encodeToByteArray(Backup.serializer(), withoutHayai))

        assertEquals(data, restoredWithHayai.hayaiData)
        assertEquals(null, restoredWithoutHayai.hayaiData)
    }

    @Test
    fun `protobuf round trip preserves installed novel plugin code`() {
        val plugin =
            HayaiBackupNovelPlugin(
                descriptorJson = """{"id":"example"}""",
                repositoryUrl = "https://repo.test/plugins.json",
                code = "exports.default = class Example {}".toByteArray(),
                preferences =
                    listOf(
                        HayaiBackupPluginPreference("username", "reader"),
                        HayaiBackupPluginPreference("showAdult", "true"),
                    ),
            )
        val restored =
            ProtoBuf
                .decodeFromByteArray(
                    HayaiBackupData.serializer(),
                    ProtoBuf.encodeToByteArray(HayaiBackupData.serializer(), HayaiBackupData(novelPlugins = listOf(plugin))),
                ).novelPlugins
                .single()

        assertEquals(plugin.descriptorJson, restored.descriptorJson)
        assertEquals(plugin.repositoryUrl, restored.repositoryUrl)
        assertArrayEquals(plugin.code, restored.code)
        assertEquals(plugin.preferences, restored.preferences)
    }

    @Test
    fun `validation rejects duplicate or oversized plugin settings`() {
        val plugin =
            HayaiBackupNovelPlugin(
                descriptorJson = """{"id":"example"}""",
                repositoryUrl = "https://repo.test/plugins.json",
                code = "plugin".toByteArray(),
                preferences =
                    listOf(
                        HayaiBackupPluginPreference("duplicate", "first"),
                        HayaiBackupPluginPreference("duplicate", "second"),
                        HayaiBackupPluginPreference("large", "x".repeat(1024 * 1024 + 1)),
                    ),
            )

        assertTrue(HayaiBackupLimits.validate(HayaiBackupData(novelPlugins = listOf(plugin))).any { it.contains("plugin backup") })
    }

    @Test
    fun `quote conflict IDs are deterministic and include the complete restore identity`() {
        val quote =
            HayaiBackupQuote(
                id = "existing-id",
                sourceId = 7,
                mangaUrl = "/novel",
                novelName = "Novel",
                chapterName = "Chapter",
                displayedContent = "same visible text",
                originalContent = "original",
                translatedContent = "translation one",
                language = "en",
                timestamp = 123,
            )

        val first = HayaiQuoteRestoreIdentity.remappedId(quote, mangaId = 42, attempt = 0)

        assertEquals(first, HayaiQuoteRestoreIdentity.remappedId(quote, mangaId = 42, attempt = 0))
        assertTrue(first != HayaiQuoteRestoreIdentity.remappedId(quote.copy(translatedContent = "translation two"), 42, 0))
        assertTrue(first != HayaiQuoteRestoreIdentity.remappedId(quote, mangaId = 43, attempt = 0))
        assertTrue(first != HayaiQuoteRestoreIdentity.remappedId(quote, mangaId = 42, attempt = 1))
    }
}
