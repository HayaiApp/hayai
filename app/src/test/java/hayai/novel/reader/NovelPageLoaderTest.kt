package hayai.novel.reader

import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.SChapter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class NovelPageLoaderTest {

    @Test
    fun `source chapter URL takes priority`() {
        val source = TestSource(
            chapterUrl = "https://cdn.example.test/chapter/42",
            webViewUrl = "https://example.test/novels/book/",
        )

        assertEquals(
            "https://cdn.example.test/chapter/42",
            resolveSourceUrl(source, "chapter-42"),
        )
    }

    @Test
    fun `relative URL resolves against source web URL`() {
        val source = TestSource(
            chapterUrl = null,
            webViewUrl = "https://example.test/novels/book/",
        )

        assertEquals(
            "https://example.test/novels/book/chapter-42",
            resolveSourceUrl(source, "chapter-42"),
        )
    }

    @Test
    fun `broken source URL resolver falls back without crashing reader`() {
        val source = TestSource(
            chapterUrl = null,
            webViewUrl = "https://example.test/novels/book/",
            throwFromChapterUrl = true,
        )

        assertEquals(
            "https://example.test/novels/book/chapter-42",
            resolveSourceUrl(source, "chapter-42"),
        )
    }

    @Test
    fun `unresolvable URL is preserved`() {
        val source = TestSource(
            chapterUrl = "",
            webViewUrl = "http://[invalid",
        )

        assertEquals("chapter-42", resolveSourceUrl(source, "chapter-42"))
    }

    private class TestSource(
        private val chapterUrl: String?,
        override val webViewUrl: String?,
        private val throwFromChapterUrl: Boolean = false,
    ) : Source {
        override val id = 1L
        override val name = "Test"

        override fun getChapterUrl(chapter: SChapter): String? {
            if (throwFromChapterUrl) error("Broken plugin URL resolver")
            return chapterUrl
        }
    }
}
