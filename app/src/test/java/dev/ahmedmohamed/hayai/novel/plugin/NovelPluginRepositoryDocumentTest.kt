package dev.ahmedmohamed.hayai.novel.plugin

import dev.ahmedmohamed.hayai.novel.error.NovelFailure
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class NovelPluginRepositoryDocumentTest {
    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
            explicitNulls = false
        }

    @Test
    fun `LNReader v3 root array decodes and validates`() {
        val document = decodeFixture("lnreader-v3-root-array.json")

        assertEquals(2, document.plugins.size)
        val plugin = document.plugins.first().validate(REPOSITORY_URL)
        assertEquals("arnovel", plugin.id)
        assertEquals("ar", plugin.normalizedLanguage())
        assertEquals(
            "https://raw.githubusercontent.com/lnreader/lnreader-plugins/plugins/v3.0.0/.js/src/plugins/arabic/ArNovel%5Bmadara%5D.js",
            plugin.resolvedCodeUrl(REPOSITORY_URL),
        )
        assertEquals("", document.plugins.last().validate(REPOSITORY_URL).site)
    }

    @Test
    fun `legacy sources envelope remains supported`() {
        val document = decodeFixture("lnreader-legacy-sources-envelope.json")

        assertEquals(listOf("legacy-example"), document.plugins.map(NovelPluginDescriptor::id))
        assertEquals(
            "https://raw.githubusercontent.com/LNReader/lnreader-plugins/plugins/v3.0.0/.dist/plugins/LegacyExample.js",
            document.plugins.single().resolvedCodeUrl(REPOSITORY_URL),
        )
    }

    @Test
    fun `plugins envelope remains supported`() {
        val document =
            NovelPluginRepositoryDocument.decode(
                json,
                """{"plugins":[{"id":"wrapped","name":"Wrapped","lang":"en","version":"1","url":"wrapped.js"}]}""",
            )

        assertEquals("wrapped", document.plugins.single().id)
    }

    @Test
    fun `object without a supported descriptor array reports repository array failure`() {
        val error =
            assertThrows(NovelFailure::class.java) {
                NovelPluginRepositoryDocument.decode(json, """{"extensions":[]}""")
            }

        assertEquals(NovelFailure.Code.PluginRepositoryArray, error.code)
    }

    @Test
    fun `scalar root reports repository document failure`() {
        val error = assertThrows(NovelFailure::class.java) { NovelPluginRepositoryDocument.decode(json, "true") }

        assertEquals(NovelFailure.Code.PluginRepositoryDocument, error.code)
    }

    private fun decodeFixture(name: String): NovelPluginRepositoryDocument {
        val path = "/dev/ahmedmohamed/hayai/novel/plugin/$name"
        val value = checkNotNull(javaClass.getResource(path)) { "Missing fixture $path" }.readText()
        return NovelPluginRepositoryDocument.decode(json, value)
    }

    companion object {
        private const val REPOSITORY_URL =
            "https://raw.githubusercontent.com/LNReader/lnreader-plugins/plugins/v3.0.0/.dist/plugins.min.json"
    }
}
