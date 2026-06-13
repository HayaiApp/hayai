package yokai.domain.manga.interactor

import eu.kanade.tachiyomi.data.database.models.Chapter
import eu.kanade.tachiyomi.data.database.models.MangaImpl
import io.mockk.mockk
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import yokai.domain.chapter.interactor.GetChapter

class FetchIntervalTest {

    private val zone = ZoneId.of("UTC")
    private val getChapter = mockk<GetChapter>(relaxed = true)
    private val fetchInterval = FetchInterval(getChapter)

    @Test
    fun `uses median upload interval`() {
        val chapters = listOf(
            chapter(uploadDate = "2026-06-29"),
            chapter(uploadDate = "2026-06-22"),
            chapter(uploadDate = "2026-06-08"),
            chapter(uploadDate = "2026-06-01"),
        )

        assertEquals(7, fetchInterval.calculateInterval(chapters, zone))
    }

    @Test
    fun `clamps calculated interval to 28 days`() {
        val chapters = listOf(
            chapter(uploadDate = "2026-06-01"),
            chapter(uploadDate = "2026-04-01"),
            chapter(uploadDate = "2026-02-01"),
        )

        assertEquals(FetchInterval.MAX_INTERVAL, fetchInterval.calculateInterval(chapters, zone))
    }

    @Test
    fun `keeps existing next update inside grace window`() = runTest {
        val dateTime = ZonedDateTime.of(2026, 6, 10, 12, 0, 0, 0, zone)
        val existingNextUpdate = dateMillis("2026-06-11")
        val manga = manga(fetchIntervalDays = -7, nextUpdate = existingNextUpdate)

        val update = fetchInterval.toMangaUpdate(manga, dateTime, fetchInterval.getWindow(dateTime))

        assertEquals(existingNextUpdate, update.nextUpdate)
    }

    @Test
    fun `locked manual interval is stored negative and used as-is`() = runTest {
        val dateTime = ZonedDateTime.of(2026, 6, 3, 12, 0, 0, 0, zone)
        val manga = manga(
            fetchIntervalDays = -14,
            lastUpdate = dateMillis("2026-06-01"),
        )

        val update = fetchInterval.toMangaUpdate(manga, dateTime, fetchInterval.getWindow(dateTime))

        assertEquals(-14, update.fetchInterval)
        assertEquals(dateMillis("2026-06-15"), update.nextUpdate)
    }

    private fun manga(
        fetchIntervalDays: Int,
        nextUpdate: Long = 0L,
        lastUpdate: Long = dateMillis("2026-06-01"),
    ) = MangaImpl(id = 1L).apply {
        fetch_interval = fetchIntervalDays
        next_update = nextUpdate
        last_update = lastUpdate
    }

    private fun chapter(uploadDate: String) = Chapter.create().apply {
        date_upload = dateMillis(uploadDate)
        date_fetch = date_upload
    }

    private fun dateMillis(date: String): Long {
        return LocalDate.parse(date).atStartOfDay(zone).toInstant().toEpochMilli()
    }
}
