package eu.kanade.tachiyomi.ui.source.browse

import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PagerTest {

    @Test
    fun `page completed before collection is still delivered`() = runTest {
        val manga = SManga.create().apply {
            url = "/title"
            title = "Title"
        }
        val pager = object : Pager() {
            override suspend fun requestNextPage() {
                onPageReceived(MangasPage(listOf(manga), hasNextPage = false))
            }
        }

        pager.requestNextPage()

        val (page, results) = pager.asFlow().first()
        assertEquals(1, page)
        assertEquals(listOf(manga), results)
    }
}
