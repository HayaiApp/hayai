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
