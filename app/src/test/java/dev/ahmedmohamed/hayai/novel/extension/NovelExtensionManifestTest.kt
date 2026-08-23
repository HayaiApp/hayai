package dev.ahmedmohamed.hayai.novel.extension

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NovelExtensionManifestTest {
    @Test
    fun `recognizes standard and novel required features`() {
        assertTrue(NovelExtensionManifest.isSupported(listOf(NovelExtensionManifest.STANDARD_FEATURE)))
        assertTrue(NovelExtensionManifest.isSupported(listOf(NovelExtensionManifest.NOVEL_FEATURE)))
        assertFalse(NovelExtensionManifest.isSupported(listOf("unrelated.feature", null)))
    }

    @Test
    fun `resolves the novel metadata namespace`() {
        val namespace =
            NovelExtensionManifest.resolve(
                requiredFeatures = setOf(NovelExtensionManifest.NOVEL_FEATURE),
                metadataKeys = setOf("${NovelExtensionManifest.NOVEL_FEATURE}.class"),
            )

        assertEquals(NovelExtensionManifest.NOVEL_FEATURE, namespace?.prefix)
        assertEquals("tachiyomi.novelextension.class", namespace?.classKey)
        assertEquals("tachiyomi.novelextension.factory", namespace?.factoryKey)
        assertEquals("tachiyomi.novelextension.nsfw", namespace?.nsfwKey)
        assertTrue(namespace?.isNovel == true)
    }

    @Test
    fun `mixed package chooses the namespace that actually declares source classes`() {
        val features = setOf(NovelExtensionManifest.STANDARD_FEATURE, NovelExtensionManifest.NOVEL_FEATURE)

        val standard = NovelExtensionManifest.resolve(features, setOf("tachiyomi.extension.class"))
        val novel = NovelExtensionManifest.resolve(features, setOf("tachiyomi.novelextension.class"))

        assertEquals(NovelExtensionManifest.STANDARD_FEATURE, standard?.prefix)
        assertEquals(NovelExtensionManifest.NOVEL_FEATURE, novel?.prefix)
        assertNull(NovelExtensionManifest.resolve(emptySet(), emptySet()))
    }

    @Test
    fun `normalizes extension labels without damaging ordinary names`() {
        assertEquals("Example", NovelExtensionManifest.displayName("Tachiyomi: Example"))
        assertEquals("Example", NovelExtensionManifest.displayName("Tsundoku: Example"))
        assertEquals("Example", NovelExtensionManifest.displayName(" Example "))
        assertEquals("Metadata name", NovelExtensionManifest.displayName("Tsundoku: Example", " Metadata name "))
        assertEquals("Example", NovelExtensionManifest.displayName("Tsundoku: Example", " "))
    }

    @Test
    fun `library version prefers typed manifest metadata and retains legacy fallback`() {
        assertEquals(1.6, NovelExtensionManifest.libraryVersion("9.9.9", 1.6f)!!, 0.000_001)
        assertEquals(1.5, NovelExtensionManifest.libraryVersion("9.9.9", "1.5")!!, 0.0)
        assertEquals(1.4, NovelExtensionManifest.libraryVersion("1.4.12", null)!!, 0.0)
        assertEquals(1.4, NovelExtensionManifest.libraryVersion("1.4.12", 0f)!!, 0.0)
        assertNull(NovelExtensionManifest.libraryVersion("invalid", null))
    }

    @Test
    fun `android float metadata is normalized before extension compatibility checks`() {
        assertEquals(1.6, NovelExtensionManifest.libraryVersion("1.6.9", 1.6f)!!, 0.0)
    }

    @Test
    fun `class candidates support relative and migrated Tsundoku namespaces`() {
        assertEquals(
            listOf("org.example.extension.Source"),
            NovelExtensionManifest.classCandidates(".Source", "org.example.extension"),
        )
        assertEquals(
            listOf(
                "app.tsundoku.extension.en.example.Example",
                "eu.kanade.tachiyomi.extension.en.example.Example",
            ),
            NovelExtensionManifest.classCandidates("app.tsundoku.extension.en.example.Example", "ignored"),
        )
    }
}
