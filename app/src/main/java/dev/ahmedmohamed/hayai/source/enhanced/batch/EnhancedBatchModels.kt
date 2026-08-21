package dev.ahmedmohamed.hayai.source.enhanced.batch

import dev.ahmedmohamed.hayai.source.SourceFamily

@JvmInline
value class EnhancedBatchEntry private constructor(val url: String) {
    companion object {
        fun parse(value: String): EnhancedBatchEntry {
            val normalized = value.trim()
            require(normalized.length in 1..MAX_URL_LENGTH) { "Gallery URL is too long" }
            val uri = runCatching { java.net.URI(normalized) }.getOrNull()
                ?: throw IllegalArgumentException("Invalid gallery URL")
            require(uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrBlank()) {
                "Only HTTP gallery URLs are supported"
            }
            require(uri.userInfo == null) { "Gallery URLs must not contain credentials" }
            return EnhancedBatchEntry(uri.normalize().toASCIIString())
        }

        private const val MAX_URL_LENGTH = 4_096
    }
}

data class EnhancedBatchPlan(val entries: List<EnhancedBatchEntry>) {
    init {
        require(entries.isNotEmpty()) { "Add at least one gallery URL" }
        require(entries.size <= MAX_BATCH_ENTRIES) { "A batch can contain at most $MAX_BATCH_ENTRIES galleries" }
    }

    companion object {
        const val MAX_BATCH_ENTRIES = 500
    }
}

object EnhancedBatchInputParser {
    fun parse(input: String): EnhancedBatchPlan {
        require(input.toByteArray(Charsets.UTF_8).size <= MAX_INPUT_BYTES) { "Batch input is larger than 1 MiB" }
        val entries = linkedMapOf<String, EnhancedBatchEntry>()
        input.lineSequence().forEachIndexed { index, line ->
            val value = line.trim()
            if (value.isEmpty()) return@forEachIndexed
            val entry = runCatching { EnhancedBatchEntry.parse(value) }.getOrElse { cause ->
                throw IllegalArgumentException("Line ${index + 1}: ${cause.message}", cause)
            }
            entries.putIfAbsent(entry.url, entry)
            require(entries.size <= EnhancedBatchPlan.MAX_BATCH_ENTRIES) {
                "A batch can contain at most ${EnhancedBatchPlan.MAX_BATCH_ENTRIES} galleries"
            }
        }
        return EnhancedBatchPlan(entries.values.toList())
    }

    private const val MAX_INPUT_BYTES = 1024 * 1024
}

data class EnhancedImportTarget(
    val sourceId: Long,
    val sourceName: String,
    val family: SourceFamily,
    val mangaUrl: String,
    val submittedUrl: String,
)

sealed interface EnhancedBatchItemResult {
    val submittedUrl: String

    data class Added(
        override val submittedUrl: String,
        val target: EnhancedImportTarget,
        val mangaId: Long,
        val title: String,
    ) : EnhancedBatchItemResult

    data class AlreadyInLibrary(
        override val submittedUrl: String,
        val target: EnhancedImportTarget,
        val mangaId: Long,
        val title: String,
    ) : EnhancedBatchItemResult

    data class Duplicate(
        override val submittedUrl: String,
        val target: EnhancedImportTarget,
    ) : EnhancedBatchItemResult

    data class Failed(
        override val submittedUrl: String,
        val reason: EnhancedBatchFailure,
    ) : EnhancedBatchItemResult
}

sealed interface EnhancedBatchFailure {
    val message: String

    data class UnsupportedSource(override val message: String = "No enabled enhanced source accepts this URL") : EnhancedBatchFailure
    data class AmbiguousSource(val sourceNames: List<String>) : EnhancedBatchFailure {
        override val message: String = "More than one enabled source accepts this URL: ${sourceNames.joinToString()}"
    }
    data class Network(override val message: String) : EnhancedBatchFailure
    data class InvalidGallery(override val message: String) : EnhancedBatchFailure
    data class Persistence(override val message: String) : EnhancedBatchFailure
}

data class EnhancedBatchProgress(
    val completed: Int,
    val total: Int,
    val currentUrl: String?,
    val results: List<EnhancedBatchItemResult>,
) {
    val succeeded: Int get() = results.count { it is EnhancedBatchItemResult.Added || it is EnhancedBatchItemResult.AlreadyInLibrary }
    val failed: Int get() = results.count { it is EnhancedBatchItemResult.Failed }
}

data class EnhancedBatchReport(val results: List<EnhancedBatchItemResult>) {
    val added: Int get() = results.count { it is EnhancedBatchItemResult.Added }
    val alreadyPresent: Int get() = results.count { it is EnhancedBatchItemResult.AlreadyInLibrary }
    val duplicates: Int get() = results.count { it is EnhancedBatchItemResult.Duplicate }
    val failed: Int get() = results.count { it is EnhancedBatchItemResult.Failed }
}
