package eu.kanade.tachiyomi.data.track.anilist

import eu.kanade.tachiyomi.data.track.anilist.dto.ALCharacterConnection
import eu.kanade.tachiyomi.data.track.anilist.dto.ALCharacterEdge
import eu.kanade.tachiyomi.data.track.anilist.dto.ALCharacterImage
import eu.kanade.tachiyomi.data.track.anilist.dto.ALCharacterName
import eu.kanade.tachiyomi.data.track.anilist.dto.ALCharacterNode
import eu.kanade.tachiyomi.data.track.anilist.dto.ALCharactersData
import eu.kanade.tachiyomi.data.track.anilist.dto.ALCharactersMedia
import eu.kanade.tachiyomi.data.track.anilist.dto.ALCharactersResult
import eu.kanade.tachiyomi.data.track.anilist.dto.toCharacterMetadata
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ALCharactersTest {

    @Test
    fun `maps anilist character edges into distinct metadata`() {
        val result = ALCharactersResult(
            data = ALCharactersData(
                media = ALCharactersMedia(
                    characters = ALCharacterConnection(
                        edges = listOf(
                            edge(name = "Sunny", role = "MAIN", image = "https://img.test/sunny.jpg"),
                            edge(name = "Sunny", role = "SUPPORTING", image = null),
                            edge(name = "Nephis", role = "MAIN", image = null),
                        ),
                    ),
                ),
            ),
        )

        val metadata = result.toCharacterMetadata()

        assertEquals(listOf("Sunny", "Nephis"), metadata.map { it.name })
        assertEquals("MAIN", metadata.first().role)
        assertEquals("https://img.test/sunny.jpg", metadata.first().imageUrl)
    }

    private fun edge(name: String, role: String, image: String?): ALCharacterEdge =
        ALCharacterEdge(
            role = role,
            node = ALCharacterNode(
                name = ALCharacterName(userPreferred = name),
                image = ALCharacterImage(large = image),
            ),
        )
}
