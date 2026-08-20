package dev.ahmedmohamed.hayai.novel.download

import dev.ahmedmohamed.hayai.novel.source.NovelContentType
import dev.ahmedmohamed.hayai.novel.source.NovelDocument
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream

class NovelDownloadStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `round trips a document and its referenced assets`() =
        runBlocking {
            val store = NovelDownloadStore(temporaryFolder.newFolder("downloads"))
            val document =
                NovelDocument(
                    content = "<p>Offline</p><img src='hayai-novel-image://images/a%20b.png'>",
                    contentType = NovelContentType.Html,
                    baseUrl = "https://example.test/chapter/1",
                )
            val reference = "hayai-novel-image://images/a%20b.png"
            val provider = providerOf(reference to "image-bytes".encodeToByteArray())

            val result = store.save(7L, "/chapter/1", document, provider)

            val loaded = store.loadDocument(7L, "/chapter/1")!!
            val offlinePath = NovelAssetReferences.providerPath(NovelAssetReferences.extract(loaded).single())!!
            assertEquals(NovelContentType.Html, loaded.contentType)
            assertNull(loaded.baseUrl)
            assertEquals("image-bytes", store.openAsset(7L, "/chapter/1", offlinePath)!!.bufferedReader().readText())
            assertEquals(1, result.savedAssetCount)
            assertEquals(0, result.unavailableAssetCount)
            assertTrue(result.bytesWritten > document.content.length)
        }

    @Test
    fun `a corrupt document is rejected and can be replaced`() =
        runBlocking {
            val root = temporaryFolder.newFolder("downloads")
            val store = NovelDownloadStore(root)
            val first = NovelDocument("first", NovelContentType.PlainText)
            val second = NovelDocument("second", NovelContentType.Markdown)
            store.save(9L, "chapter", first, null)
            root.walkTopDown().first { it.name == "document.txt" }.writeText("tampered")

            assertNull(store.loadDocument(9L, "chapter"))
            store.save(9L, "chapter", second, null)
            assertEquals(second, store.loadDocument(9L, "chapter"))
        }

    @Test
    fun `missing source assets are reported without losing readable text`() =
        runBlocking {
            val store = NovelDownloadStore(temporaryFolder.newFolder("downloads"))
            val document = NovelDocument("<img src='novel-image://missing.png'>text", NovelContentType.Html)

            val result = store.save(1L, "chapter", document, providerOf())

            val loaded = store.loadDocument(1L, "chapter")!!
            assertEquals(1, result.unavailableAssetCount)
            val offlinePath = NovelAssetReferences.providerPath(NovelAssetReferences.extract(loaded).single())!!
            assertNull(store.openAsset(1L, "chapter", offlinePath))
        }

    @Test
    fun `tampered assets are not served and removal deletes the complete chapter`() =
        runBlocking {
            val root = temporaryFolder.newFolder("downloads")
            val store = NovelDownloadStore(root)
            val document = NovelDocument("<img src='novel-image://image.png'>", NovelContentType.Html)
            store.save(3L, "chapter", document, providerOf("novel-image://image.png" to byteArrayOf(1, 2, 3)))
            root.walkTopDown().first { it.parentFile?.name == "assets" }.writeBytes(byteArrayOf(9))

            val loaded = store.loadDocument(3L, "chapter")!!
            val offlinePath = NovelAssetReferences.providerPath(NovelAssetReferences.extract(loaded).single())!!
            assertNull(store.openAsset(3L, "chapter", offlinePath))
            assertTrue(store.remove(3L, "chapter"))
            assertNull(store.loadDocument(3L, "chapter"))
        }

    private fun providerOf(vararg assets: Pair<String, ByteArray>) =
        object : NovelDownloadAssetResolver {
            private val values = assets.toMap()

            override suspend fun open(reference: String) = values[reference]?.let(::ByteArrayInputStream)
        }
}
