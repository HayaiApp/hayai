package dev.ahmedmohamed.hayai.adult.eh.persistence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class EhPersistenceModelsTest {
    @Test
    fun `gallery identity accepts persisted EH values and rejects malformed values`() {
        assertEquals("123", EhGalleryIdentity("123", "abcdef0123").gid)
        assertEquals("token_-", EhGalleryIdentity("123", "token_-").token)
        assertThrows(IllegalArgumentException::class.java) { EhGalleryIdentity("0", "token") }
        assertThrows(IllegalArgumentException::class.java) { EhGalleryIdentity("not-a-number", "token") }
        assertThrows(IllegalArgumentException::class.java) { EhGalleryIdentity("123", "token with spaces") }
    }

    @Test
    fun `favorite category is a remote slot rather than a database ID`() {
        val gallery = EhGalleryIdentity("123", "token")

        assertEquals(9, EhFavoriteSnapshot(gallery, "Gallery", 9).categorySlot)
        assertThrows(IllegalArgumentException::class.java) { EhFavoriteSnapshot(gallery, "Gallery", 10) }
    }

    @Test
    fun `metadata rejects duplicate normalized children`() {
        val identity = SourceMangaIdentity(7, "/gallery")
        val tag = SourceMetadataTag("artist", "Creator", 0)

        assertThrows(IllegalArgumentException::class.java) {
            SourceMetadata(identity, null, "{}", "123", 1, tags = listOf(tag, tag))
        }
    }

    @Test
    fun `gallery alias cannot point to itself`() {
        val gallery = EhGalleryIdentity("123", "token")

        assertThrows(IllegalArgumentException::class.java) { EhGalleryAlias(gallery, gallery) }
    }
}
