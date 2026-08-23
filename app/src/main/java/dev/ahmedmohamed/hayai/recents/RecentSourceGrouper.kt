package dev.ahmedmohamed.hayai.recents

data class RecentSourceSection<T>(
    val sourceId: Long,
    val rows: List<T>,
)

object RecentSourceGrouper {
    fun <T> sections(
        rows: List<T>,
        sourceId: (T) -> Long,
        recency: (T) -> Long,
    ): List<RecentSourceSection<T>> =
        rows
            .groupBy(sourceId)
            .map { (id, sourceRows) ->
                RecentSourceSection(
                    sourceId = id,
                    rows = sourceRows.sortedByDescending(recency),
                )
            }.sortedByDescending { section ->
                section.rows.maxOfOrNull(recency) ?: Long.MIN_VALUE
            }
}
