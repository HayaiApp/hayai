package dev.ahmedmohamed.hayai.recents

import eu.kanade.tachiyomi.ui.recents.RecentsPresenter
import org.junit.Assert.assertEquals
import org.junit.Test

class RecentSourceGrouperTest {
    @Test
    fun `sections are ordered by newest source row`() {
        val rows =
            listOf(
                Row(sourceId = 2, recency = 20, label = "two-newer"),
                Row(sourceId = 1, recency = 10, label = "one"),
                Row(sourceId = 2, recency = 5, label = "two-older"),
                Row(sourceId = 3, recency = 30, label = "three"),
            )

        val sections =
            RecentSourceGrouper.sections(
                rows = rows,
                sourceId = Row::sourceId,
                recency = Row::recency,
            )

        assertEquals(listOf(3L, 2L, 1L), sections.map { it.sourceId })
        assertEquals(listOf("two-newer", "two-older"), sections.single { it.sourceId == 2L }.rows.map { it.label })
    }

    @Test
    fun `history group type contains the restored BySource member`() {
        assertEquals(RecentsPresenter.GroupType.BySource, RecentsPresenter.GroupType.valueOf("BySource"))
        assertEquals(false, RecentsPresenter.GroupType.BySource.isByTime)
        assertEquals(true, RecentsPresenter.GroupType.BySource.isBySource)
    }

    private data class Row(
        val sourceId: Long,
        val recency: Long,
        val label: String,
    )
}
