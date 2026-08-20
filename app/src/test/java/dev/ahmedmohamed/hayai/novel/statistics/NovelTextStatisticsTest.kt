package dev.ahmedmohamed.hayai.novel.statistics

import dev.ahmedmohamed.hayai.novel.source.NovelContentType
import dev.ahmedmohamed.hayai.novel.source.NovelDocument
import org.junit.Assert.assertEquals
import org.junit.Test

class NovelTextStatisticsTest {
    @Test
    fun `plain text count handles punctuation contractions numbers and unicode`() {
        assertEquals(7, NovelTextStatistics.wordCount("Hello, world! Don't stop 42 — مرحبا بالعالم."))
    }

    @Test
    fun `unspaced Chinese text counts readable ideographs`() {
        assertEquals(6, NovelTextStatistics.wordCount("这是一个测试"))
    }

    @Test
    fun `Thai combining marks attach to base letters without collapsing the sentence`() {
        assertEquals(7, NovelTextStatistics.wordCount("สวัสดีโลก"))
    }

    @Test
    fun `Korean and Japanese syllables do not collapse into one word`() {
        assertEquals(7, NovelTextStatistics.wordCount("안녕하세요 세계"))
        assertEquals(7, NovelTextStatistics.wordCount("こんにちは世界"))
    }

    @Test
    fun `HTML count excludes scripts styles templates and markup`() {
        val document =
            NovelDocument(
                "<style>hidden style words</style><script>hidden script words</script><p>Visible <b>chapter</b> text.</p><template>hidden</template>",
                NovelContentType.Html,
            )

        assertEquals(3, NovelTextStatistics.analyze(document).wordCount)
    }

    @Test
    fun `Markdown count includes readable labels but not syntax or destinations`() {
        val document = NovelDocument("# Chapter title\n\nRead **bold words** at [the site](https://example.test/path).", NovelContentType.Markdown)

        assertEquals(8, NovelTextStatistics.analyze(document).wordCount)
    }

    @Test
    fun `blank and symbol only chapters have zero words`() {
        assertEquals(0, NovelTextStatistics.wordCount("  \n — … ❝ ❞ "))
    }

    @Test
    fun `reading estimates round up and clamp progress`() {
        val statistics = NovelChapterStatistics(401)

        assertEquals(3, statistics.estimatedMinutes(200))
        assertEquals(200, statistics.wordsRead(50))
        assertEquals(0, statistics.wordsRead(-1))
        assertEquals(401, statistics.wordsRead(500))
        assertEquals(2, statistics.remainingMinutes(50, 200))
        assertEquals(0, statistics.remainingMinutes(100, 200))
        assertEquals(0, NovelChapterStatistics(0).estimatedMinutes())
    }

    @Test
    fun `analysis failure falls back to persisted statistics`() {
        val document = NovelDocument("content", NovelContentType.PlainText)

        val resolved =
            NovelStatisticsResolver.resolve(
                document = document,
                persisted = { NovelChapterStatistics(321) },
                analyze = { error("parser failed") },
            )

        assertEquals(321, resolved.wordCount)
    }

    @Test
    fun `analysis and persistence read failures degrade to zero`() {
        val document = NovelDocument("content", NovelContentType.PlainText)

        val resolved =
            NovelStatisticsResolver.resolve(
                document = document,
                persisted = { error("database failed") },
                analyze = { error("parser failed") },
            )

        assertEquals(0, resolved.wordCount)
    }
}
