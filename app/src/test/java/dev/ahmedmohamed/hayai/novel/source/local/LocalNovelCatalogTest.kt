package dev.ahmedmohamed.hayai.novel.source.local

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class LocalNovelCatalogTest {
    private lateinit var root: File
    private lateinit var catalog: LocalNovelCatalog

    @Before
    fun setUp() {
        root = Files.createTempDirectory("hayai-local-novels").toFile()
        catalog = LocalNovelCatalog(listOf(root))
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun `discovers directories and every supported root file format`() {
        File(root, "Series").mkdir()
        LocalNovelCatalog.SUPPORTED_EXTENSIONS.forEach { extension -> File(root, "book.$extension").writeText("content") }
        File(root, "cover.jpg").writeText("not a novel")
        File(root, ".hidden.txt").writeText("hidden")

        val names = catalog.entries().map(File::getName).toSet()

        assertTrue("Series" in names)
        LocalNovelCatalog.SUPPORTED_EXTENSIONS.forEach { assertTrue("book.$it" in names) }
        assertFalse("cover.jpg" in names)
        assertFalse(".hidden.txt" in names)
    }

    @Test
    fun `directory chapter discovery ignores metadata and empty folders`() {
        val series = File(root, "Series").apply { mkdir() }
        File(series, "01.txt").writeText("one")
        File(series, "02.epub").writeText("epub")
        File(series, "info.json").writeText("{}")
        File(series, "empty").mkdir()
        File(series, "03").apply { mkdir(); File(this, "page.html").writeText("three") }

        assertEquals(setOf("01.txt", "02.epub", "03"), catalog.chapters("Series").map(File::getName).toSet())
    }

    @Test
    fun `single root file is exposed as its only chapter`() {
        val novel = File(root, "standalone.md").apply { writeText("# Novel") }

        assertEquals(listOf(novel.canonicalFile), catalog.chapters("standalone.md").map(File::getCanonicalFile))
    }

    @Test
    fun `resolution rejects absolute and traversal paths`() {
        val outside = File(root.parentFile, "outside.txt").apply { writeText("secret") }
        try {
            assertNull(catalog.resolveChapter(outside.absolutePath))
            assertNull(catalog.resolveChapter("../${outside.name}"))
            assertNull(catalog.resolveChapter("Series/../../${outside.name}"))
        } finally {
            outside.delete()
        }
    }

    @Test
    fun `relative assets cannot escape their chapter directory`() {
        val chapter = File(root, "Series/chapter").apply { mkdirs() }
        val image = File(chapter, "images/pic.png").apply { parentFile.mkdirs(); writeText("image") }
        File(root, "Series/private.png").writeText("private")

        assertEquals(image.canonicalFile, catalog.resolveRelativeAsset("Series/chapter", "images/pic.png"))
        assertNull(catalog.resolveRelativeAsset("Series/chapter", "../private.png"))
    }

    @Test
    fun `first storage root wins duplicate novel names`() {
        val secondRoot = Files.createTempDirectory("hayai-local-novels-second").toFile()
        try {
            val first = File(root, "duplicate.txt").apply { writeText("first") }
            File(secondRoot, "duplicate.txt").writeText("second")
            val multiRootCatalog = LocalNovelCatalog(listOf(root, secondRoot))

            assertEquals(first.canonicalFile, multiRootCatalog.entries().single().canonicalFile)
        } finally {
            secondRoot.deleteRecursively()
        }
    }
}
