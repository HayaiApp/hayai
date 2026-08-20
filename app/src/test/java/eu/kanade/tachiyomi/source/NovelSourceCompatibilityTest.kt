package eu.kanade.tachiyomi.source

import eu.kanade.tachiyomi.source.model.Page
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NovelSourceCompatibilityTest {
    @Test
    fun `legacy marker source links and is recognized without overriding the new property`() {
        val source: Source = LegacyCompiledShapeSource()

        assertFalse(source.isNovelSource)
        assertTrue(source.isNovelSource())
    }

    @Suppress("DEPRECATION")
    private class LegacyCompiledShapeSource : NovelSource {
        override val id: Long = 7
        override val name: String = "Legacy novel extension"

        override suspend fun fetchPageText(page: Page): String = "legacy text"
    }
}
