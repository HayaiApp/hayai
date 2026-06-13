package eu.kanade.tachiyomi.data.track.myanimelist

import eu.kanade.tachiyomi.data.track.myanimelist.dto.MALCharacterEntry
import eu.kanade.tachiyomi.data.track.myanimelist.dto.MALCharacterImage
import eu.kanade.tachiyomi.data.track.myanimelist.dto.MALCharacterImages
import eu.kanade.tachiyomi.data.track.myanimelist.dto.MALCharacterNode
import eu.kanade.tachiyomi.data.track.myanimelist.dto.MALCharactersResult
import eu.kanade.tachiyomi.data.track.myanimelist.dto.toCharacterMetadata
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MALCharactersTest {

    @Test
    fun `maps mal character entries into distinct metadata`() {
        val result = MALCharactersResult(
            data = listOf(
                entry(name = "Cid Kagenou", role = "Main", image = "https://img.test/cid.jpg"),
                entry(name = "Cid Kagenou", role = "Supporting", image = null),
                entry(name = "Alpha", role = "Supporting", image = null),
            ),
        )

        val metadata = result.toCharacterMetadata()

        assertEquals(listOf("Cid Kagenou", "Alpha"), metadata.map { it.name })
        assertEquals("Main", metadata.first().role)
        assertEquals("https://img.test/cid.jpg", metadata.first().imageUrl)
    }

    private fun entry(name: String, role: String, image: String?): MALCharacterEntry =
        MALCharacterEntry(
            role = role,
            character = MALCharacterNode(
                name = name,
                images = MALCharacterImages(
                    jpg = MALCharacterImage(imageUrl = image),
                ),
            ),
        )
}
