package yokai.domain.manga.interactor

import eu.kanade.tachiyomi.data.database.models.Chapter
import eu.kanade.tachiyomi.domain.manga.models.Manga
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import kotlin.math.absoluteValue
import yokai.domain.chapter.interactor.GetChapter
import yokai.domain.manga.models.MangaUpdate

// Verbatim port of mihon FetchInterval
// (domain/src/main/java/tachiyomi/domain/manga/interactor/FetchInterval.kt). Field accessors
// adapted to Hayai's snake_case Chapter/Manga model; algorithm unchanged.
class FetchInterval(
    private val getChapter: GetChapter,
) {

    suspend fun toMangaUpdate(
        manga: Manga,
        dateTime: ZonedDateTime,
        window: Pair<Long, Long>,
    ): MangaUpdate {
        val interval = manga.fetch_interval.takeIf { it < 0 } ?: calculateInterval(
            chapters = getChapter.awaitAll(manga.id!!, filterScanlators = true),
            zone = dateTime.zone,
        )
        val currentWindow = if (window.first == 0L && window.second == 0L) {
            getWindow(ZonedDateTime.now())
        } else {
            window
        }
        val nextUpdate = calculateNextUpdate(manga, interval, dateTime, currentWindow)

        return MangaUpdate(id = manga.id!!, nextUpdate = nextUpdate, fetchInterval = interval)
    }

    fun getWindow(dateTime: ZonedDateTime): Pair<Long, Long> {
        val today = dateTime.toLocalDate().atStartOfDay(dateTime.zone)
        val lowerBound = today.minusDays(GRACE_PERIOD)
        val upperBound = today.plusDays(GRACE_PERIOD)
        return Pair(lowerBound.toEpochSecond() * 1000, upperBound.toEpochSecond() * 1000 - 1)
    }

    internal fun calculateInterval(chapters: List<Chapter>, zone: ZoneId): Int {
        val chapterWindow = if (chapters.size <= 8) 3 else 10

        val uploadDates = chapters.asSequence()
            .filter { it.date_upload > 0L }
            .sortedByDescending { it.date_upload }
            .map {
                ZonedDateTime.ofInstant(Instant.ofEpochMilli(it.date_upload), zone)
                    .toLocalDate()
                    .atStartOfDay()
            }
            .distinct()
            .take(chapterWindow)
            .toList()

        val fetchDates = chapters.asSequence()
            .sortedByDescending { it.date_fetch }
            .map {
                ZonedDateTime.ofInstant(Instant.ofEpochMilli(it.date_fetch), zone)
                    .toLocalDate()
                    .atStartOfDay()
            }
            .distinct()
            .take(chapterWindow)
            .toList()

        val interval = when {
            // Enough upload date from source
            uploadDates.size >= 3 -> {
                val ranges = uploadDates.windowed(2).map { x -> x[1].until(x[0], ChronoUnit.DAYS) }.sorted()
                ranges[(ranges.size - 1) / 2].toInt()
            }
            // Enough fetch date from client
            fetchDates.size >= 3 -> {
                val ranges = fetchDates.windowed(2).map { x -> x[1].until(x[0], ChronoUnit.DAYS) }.sorted()
                ranges[(ranges.size - 1) / 2].toInt()
            }
            // Default to 7 days
            else -> 7
        }

        return interval.coerceIn(1, MAX_INTERVAL)
    }

    private fun calculateNextUpdate(
        manga: Manga,
        interval: Int,
        dateTime: ZonedDateTime,
        window: Pair<Long, Long>,
    ): Long {
        if (manga.next_update in window.first.rangeTo(window.second + 1)) {
            return manga.next_update
        }

        val latestDate = ZonedDateTime.ofInstant(
            if (manga.last_update > 0) Instant.ofEpochMilli(manga.last_update) else Instant.now(),
            dateTime.zone,
        )
            .toLocalDate()
            .atStartOfDay()
        val timeSinceLatest = ChronoUnit.DAYS.between(latestDate, dateTime).toInt()
        val cycle = timeSinceLatest.floorDiv(
            interval.absoluteValue.takeIf { interval < 0 }
                ?: increaseInterval(interval, timeSinceLatest, increaseWhenOver = 10),
        )
        return latestDate.plusDays((cycle + 1) * interval.absoluteValue.toLong()).toEpochSecond(dateTime.offset) * 1000
    }

    private fun increaseInterval(delta: Int, timeSinceLatest: Int, increaseWhenOver: Int): Int {
        if (delta >= MAX_INTERVAL) return MAX_INTERVAL

        // double delta again if missed more than 9 check in new delta
        val cycle = timeSinceLatest.floorDiv(delta) + 1
        return if (cycle > increaseWhenOver) {
            increaseInterval(delta * 2, timeSinceLatest, increaseWhenOver)
        } else {
            delta
        }
    }

    companion object {
        const val MAX_INTERVAL = 28

        private const val GRACE_PERIOD = 1L
    }
}
