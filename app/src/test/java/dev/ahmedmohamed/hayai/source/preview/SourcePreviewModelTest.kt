package dev.ahmedmohamed.hayai.source.preview

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SourcePreviewModelTest {
    @Test
    fun `serialized listing retains pagination and crop identity`() {
        val page = SourcePreviewPage(
            page = 2,
            previews = listOf(
                SourcePagePreview(41, "https://ehgt.org/sprite.jpg", "https://e-hentai.org/s/a/1-41", SourcePreviewCrop(10, 4, 100, 140)),
            ),
            hasNextPage = true,
            totalPages = 4,
        )

        val restored = Json.decodeFromString<SourcePreviewPage>(Json.encodeToString(SourcePreviewPage.serializer(), page))

        assertEquals(page, restored)
    }

    @Test
    fun `cache identity separates source and sprite crop`() {
        val first = SourcePagePreview(1, "https://ehgt.org/sprite.jpg", crop = SourcePreviewCrop(0, 0, 100, 140))
        val second = first.copy(index = 2, crop = SourcePreviewCrop(100, 0, 100, 140))

        assertNotEquals(first.cacheIdentity(6901), second.cacheIdentity(6901))
        assertNotEquals(first.cacheIdentity(6901), first.cacheIdentity(6902))
    }

    @Test
    fun `invalid preview pages are rejected at the boundary`() {
        assertThrows(IllegalArgumentException::class.java) { SourcePreviewPage(0, emptyList(), false) }
        assertThrows(IllegalArgumentException::class.java) { SourcePagePreview(0, "https://example.com/preview.jpg") }
    }
}
