package dev.ahmedmohamed.hayai.adult.eh.favorites

import dev.ahmedmohamed.hayai.adult.eh.persistence.EhGalleryIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class EhFavoritesProtocolTest {
    @Test
    fun `category parser requires all ten remote slots`() {
        val html = (0..9).joinToString("", prefix = "<div>", postfix = "</div>") {
            "<div class='fp'><span></span><span></span><span>Category $it</span></div>"
        }

        val categories = EhFavoritesHtmlParser.categories(html)

        assertEquals((0..9).toList(), categories.map { it.slot.value })
        val failure = assertThrows(EhFavoritesFailure::class.java) { EhFavoritesHtmlParser.categories(html.replace("Category 9", "")) }
        assertEquals(EhFavoritesFailureReason.InvalidCategories, failure.reason)
    }

    @Test
    fun `favorite note parser preserves text`() {
        assertEquals("Keep this note", EhFavoritesHtmlParser.existingNote("<textarea name='favnote'>Keep this note</textarea>"))
    }

    @Test
    fun `journal operation codec round trips remote preconditions`() {
        val gallery = EhGalleryIdentity("12", "token")
        val expected = EhFavoriteState(gallery, "Title", EhFavoriteSlot(1))
        val desired = expected.copy(category = EhFavoriteSlot(4))
        val operation = EhFavoriteOperation.SetRemote("operation", 7, gallery, expected, desired)

        val restored = EhFavoriteOperationCodec.decode(EhFavoriteOperationCodec.kind(operation), EhFavoriteOperationCodec.encode(operation))

        assertEquals(operation, restored)
        assertTrue(EhFavoriteOperationCodec.encode(operation).length < 1_048_576)
    }
}
