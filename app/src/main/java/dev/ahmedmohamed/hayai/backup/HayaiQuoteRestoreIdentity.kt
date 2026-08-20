package dev.ahmedmohamed.hayai.backup

import java.util.UUID

/** Allocates the same conflict-free quote ID on every restore of the same payload. */
internal object HayaiQuoteRestoreIdentity {
    fun remappedId(
        quote: HayaiBackupQuote,
        mangaId: Long,
        attempt: Int,
    ): String {
        require(attempt >= 0)
        val seed =
            listOf(
                "hayai-quote-v1",
                quote.id,
                mangaId.toString(),
                quote.novelName,
                quote.chapterName,
                quote.displayedContent,
                quote.originalContent.orEmpty(),
                quote.translatedContent.orEmpty(),
                quote.language.orEmpty(),
                quote.timestamp.toString(),
                attempt.toString(),
            ).joinToString("\u0000")
        return UUID.nameUUIDFromBytes(seed.toByteArray(Charsets.UTF_8)).toString()
    }
}
