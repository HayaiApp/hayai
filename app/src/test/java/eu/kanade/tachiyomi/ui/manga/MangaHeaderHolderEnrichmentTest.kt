package eu.kanade.tachiyomi.ui.manga

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import yokai.domain.series.model.MetadataProviderType
import yokai.domain.series.model.SeriesMetadataField
import yokai.domain.series.model.SeriesMetadataValue

class MangaHeaderHolderEnrichmentTest {

    @Test
    fun `extra image parser accepts json and delimited metadata values`() {
        val values = listOf(
            imageValue(
                value = """
                    {
                        "images": [
                            { "url": "https://example.test/a.jpg", "title": "Volume art" },
                            "https://example.test/b.jpg"
                        ]
                    }
                """.trimIndent(),
            ),
            imageValue("https://example.test/c.jpg; https://example.test/a.jpg"),
        )

        val images = parseSeriesExtraImages(values)

        assertEquals(
            listOf(
                "https://example.test/a.jpg",
                "https://example.test/b.jpg",
                "https://example.test/c.jpg",
            ),
            images.map { it.url },
        )
        assertEquals("Volume art", images[0].label)
        assertEquals("Provider", images[1].label)
    }

    @Test
    fun `extra image parser ignores unrelated fields and unsupported values`() {
        val values = listOf(
            imageValue("not an image"),
            imageValue("https://example.test/cover.png", field = SeriesMetadataField.COVER.key),
        )

        assertEquals(emptyList<SeriesExtraImage>(), parseSeriesExtraImages(values))
    }

    @Test
    fun `external link parser accepts json and delimited metadata values`() {
        val values = listOf(
            imageValue(
                value = """
                    [
                        { "url": "https://anilist.co/manga/1", "label": "AniList" },
                        "https://example.test/details"
                    ]
                """.trimIndent(),
                field = SeriesMetadataField.EXTERNAL_LINKS.key,
            ),
            imageValue(
                value = "mailto:ignored@example.test; https://example.test/details",
                field = SeriesMetadataField.EXTERNAL_LINKS.key,
            ),
        )

        val links = parseSeriesExternalLinks(values)

        assertEquals(
            listOf(
                "https://anilist.co/manga/1",
                "https://example.test/details",
            ),
            links.map { it.url },
        )
        assertEquals("AniList", links[0].label)
        assertEquals("Provider", links[1].label)
    }

    private fun imageValue(
        value: String,
        field: String = SeriesMetadataField.IMAGES.key,
        extraJson: String? = null,
    ): SeriesMetadataValue =
        SeriesMetadataValue(
            mangaId = 1L,
            field = field,
            providerType = MetadataProviderType.USER,
            providerId = "manual",
            providerName = "Provider",
            value = value,
            extraJson = extraJson,
            confidence = null,
            userLocked = false,
            updatedAt = 1L,
        )
}
