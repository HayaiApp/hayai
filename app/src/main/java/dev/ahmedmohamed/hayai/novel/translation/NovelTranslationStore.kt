package dev.ahmedmohamed.hayai.novel.translation

import android.content.ContentValues
import android.database.Cursor
import com.pushtorefresh.storio.sqlite.queries.InsertQuery
import com.pushtorefresh.storio.sqlite.queries.RawQuery
import com.pushtorefresh.storio.sqlite.queries.UpdateQuery
import eu.kanade.tachiyomi.data.database.DatabaseHelper
import java.security.MessageDigest
import java.util.UUID

data class NovelTranslationLocator(
    val chapterId: Long,
    val sourceId: Long,
    val mangaUrl: String,
    val chapterUrl: String,
) {
    fun requireValid(): NovelTranslationLocator {
        require(chapterId > 0)
        require(mangaUrl.length in 1..MAX_URL_CHARS)
        require(chapterUrl.length in 1..MAX_URL_CHARS)
        return this
    }

    private companion object {
        const val MAX_URL_CHARS = 8_192
    }
}

data class StoredNovelTranslation(
    val locator: NovelTranslationLocator,
    val sourceLanguage: String,
    val targetLanguage: String,
    val sourceHash: String,
    val translatedContent: String,
    val contentFormat: String = CONTENT_FORMAT,
    val engineId: String,
    val detectedLanguage: String?,
    val createdAt: Long,
    val updatedAt: Long,
) {
    fun requireValid(): StoredNovelTranslation {
        locator.requireValid()
        require(sourceLanguage.length in 1..MAX_LANGUAGE_CHARS)
        require(targetLanguage.length in 1..MAX_LANGUAGE_CHARS)
        require(sourceHash.matches(SHA_256))
        require(translatedContent.length in 1..MAX_TRANSLATED_CHARS)
        require(contentFormat == CONTENT_FORMAT)
        require(engineId.length in 1..MAX_ENGINE_CHARS)
        require(detectedLanguage == null || detectedLanguage.length <= MAX_LANGUAGE_CHARS)
        require(createdAt >= 0 && updatedAt >= createdAt)
        return this
    }

    companion object {
        const val CONTENT_FORMAT = "plain_text_v1"
        const val MAX_TRANSLATED_CHARS = 2_000_000
        private const val MAX_LANGUAGE_CHARS = 64
        private const val MAX_ENGINE_CHARS = 128
        private val SHA_256 = Regex("[0-9a-f]{64}")
    }
}

data class NovelTranslationJobRequest(
    val jobId: String = UUID.randomUUID().toString(),
    val batchId: String,
    val locator: NovelTranslationLocator,
    val sourceLanguage: String,
    val targetLanguage: String,
    val engineId: String,
    val providerConfigHash: String,
    val position: Int,
    val priority: Int = 0,
    val force: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
)

data class ClaimedNovelTranslationJob(
    val request: NovelTranslationJobRequest,
    val leaseToken: String,
    val leaseUntil: Long,
    val attemptCount: Int,
)

interface NovelTranslationStore {
    fun findCompleted(
        locator: NovelTranslationLocator,
        targetLanguage: String,
        sourceHash: String,
    ): StoredNovelTranslation?

    fun findLatestCompletedForOfflineFallback(
        locator: NovelTranslationLocator,
        targetLanguage: String,
    ): StoredNovelTranslation?

    fun saveCompleted(value: StoredNovelTranslation)

    fun exportCompleted(): List<StoredNovelTranslation>

    fun restoreCompleted(value: StoredNovelTranslation): NovelTranslationRestoreDisposition

    fun enqueue(requests: List<NovelTranslationJobRequest>): Int

    fun claim(
        now: Long,
        leaseMillis: Long,
    ): ClaimedNovelTranslationJob?

    fun hasUnfinishedJobs(): Boolean

    fun complete(
        claim: ClaimedNovelTranslationJob,
        result: StoredNovelTranslation,
    )

    fun fail(
        claim: ClaimedNovelTranslationJob,
        error: String,
        retryable: Boolean,
        now: Long = System.currentTimeMillis(),
    )
}

enum class NovelTranslationRestoreDisposition {
    Inserted,
    ExactDuplicate,
}

class SqliteNovelTranslationStore(
    private val database: DatabaseHelper,
) : NovelTranslationStore {
    override fun findCompleted(
        locator: NovelTranslationLocator,
        targetLanguage: String,
        sourceHash: String,
    ): StoredNovelTranslation? {
        locator.requireValid()
        require(targetLanguage.length in 1..64)
        require(sourceHash.matches(SHA_256))
        return query(
            "$SELECT_COMPLETED WHERE chapter_id = ? AND target_language = ? AND source_hash_sha256 = ?",
            locator.chapterId,
            targetLanguage,
            sourceHash,
        ).use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            cursor.toStored().takeIf { it.locator == locator }
        }
    }

    override fun findLatestCompletedForOfflineFallback(
        locator: NovelTranslationLocator,
        targetLanguage: String,
    ): StoredNovelTranslation? {
        locator.requireValid()
        require(targetLanguage.length in 1..64)
        return query(
            "$SELECT_COMPLETED WHERE chapter_id = ? AND target_language = ?",
            locator.chapterId,
            targetLanguage,
        ).use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            cursor.toStored().takeIf { it.locator == locator }
        }
    }

    override fun saveCompleted(value: StoredNovelTranslation) {
        val checked = value.requireValid()
        database.inTransaction {
            saveCompletedInternal(checked)
        }
    }

    override fun exportCompleted(): List<StoredNovelTranslation> =
        query("$SELECT_COMPLETED ORDER BY source_id,manga_url,chapter_url,target_language").use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.toStored())
            }
        }

    override fun restoreCompleted(value: StoredNovelTranslation): NovelTranslationRestoreDisposition {
        val checked = value.requireValid()
        val existing = findByEitherIdentity(checked)
        if (existing != null) {
            require(existing == checked) { "Completed translation conflicts with restored data" }
            return NovelTranslationRestoreDisposition.ExactDuplicate
        }
        check(database.lowLevel().insert(insertQuery(TABLE_COMPLETED), checked.toValues()) >= 0)
        return NovelTranslationRestoreDisposition.Inserted
    }

    override fun enqueue(requests: List<NovelTranslationJobRequest>): Int {
        require(requests.size in 1..MAX_BATCH_SIZE)
        return database.inTransactionReturn {
            var inserted = 0
            requests.forEach { request ->
                request.requireValid()
                val existing = job(request.batchId, request.locator.chapterId, request.targetLanguage)
                if (existing != null) {
                    require(existing.sameRequestAs(request)) { "Translation job identity conflicts" }
                } else if (!hasEquivalentUnfinishedJob(request)) {
                    check(database.lowLevel().insert(insertQuery(TABLE_JOBS), request.toValues()) >= 0)
                    inserted++
                }
            }
            inserted
        }
    }

    override fun claim(
        now: Long,
        leaseMillis: Long,
    ): ClaimedNovelTranslationJob? {
        require(now >= 0 && leaseMillis in 1..MAX_LEASE_MILLIS)
        return database.inTransactionReturn {
            execute(
                "UPDATE $TABLE_JOBS SET state = 'failed',lease_token = NULL,lease_until = NULL," +
                    "last_error = 'retry limit reached after expired lease',updated_at = ? " +
                    "WHERE state = 'running' AND lease_until <= ? AND attempt_count >= ?",
                now,
                now,
                MAX_ATTEMPTS,
            )
            execute(
                "UPDATE $TABLE_JOBS SET state = 'queued',lease_token = NULL,lease_until = NULL,updated_at = ? " +
                    "WHERE state = 'running' AND lease_until <= ? AND attempt_count < ?",
                now,
                now,
                MAX_ATTEMPTS,
            )
            val request =
                query(
                    "$SELECT_JOB WHERE state = 'queued' AND attempt_count < ? " +
                        "ORDER BY priority DESC,position_index,created_at,job_id LIMIT 1",
                    MAX_ATTEMPTS,
                ).use { cursor -> if (cursor.moveToFirst()) cursor.toJobRequest() else null }
                    ?: return@inTransactionReturn null
            val token = UUID.randomUUID().toString()
            val leaseUntil = now + leaseMillis
            val updated =
                database.lowLevel().update(
                    UpdateQuery.builder().table(TABLE_JOBS).where("job_id = ? AND state = 'queued'").whereArgs(request.jobId).build(),
                    ContentValues(5).apply {
                        put("state", "running")
                        put("attempt_count", jobAttemptCount(request.jobId) + 1)
                        put("lease_token", token)
                        put("lease_until", leaseUntil)
                        put("updated_at", now)
                    },
                )
            check(updated == 1) { "Translation job was claimed concurrently" }
            ClaimedNovelTranslationJob(request, token, leaseUntil, jobAttemptCount(request.jobId))
        }
    }

    override fun hasUnfinishedJobs(): Boolean =
        query("SELECT 1 FROM $TABLE_JOBS WHERE state IN ('queued', 'running') LIMIT 1").use { it.moveToFirst() }

    override fun complete(
        claim: ClaimedNovelTranslationJob,
        result: StoredNovelTranslation,
    ) {
        require(result.locator == claim.request.locator)
        require(result.targetLanguage == claim.request.targetLanguage)
        database.inTransaction {
            val updated =
                database.lowLevel().update(
                    UpdateQuery.builder()
                        .table(TABLE_JOBS)
                        .where("job_id = ? AND state = 'running' AND lease_token = ?")
                        .whereArgs(claim.request.jobId, claim.leaseToken)
                        .build(),
                    ContentValues(4).apply {
                        put("state", "completed")
                        putNull("lease_token")
                        putNull("lease_until")
                        put("updated_at", result.updatedAt)
                    },
                )
            check(updated == 1) { "Translation job lease is no longer owned" }
            saveCompletedInternal(result.requireValid())
        }
    }

    override fun fail(
        claim: ClaimedNovelTranslationJob,
        error: String,
        retryable: Boolean,
        now: Long,
    ) {
        val message = error.take(MAX_ERROR_CHARS)
        val nextState = if (retryable && claim.attemptCount < MAX_ATTEMPTS) "queued" else "failed"
        val updated =
            database.lowLevel().update(
                UpdateQuery.builder()
                    .table(TABLE_JOBS)
                    .where("job_id = ? AND state = 'running' AND lease_token = ?")
                    .whereArgs(claim.request.jobId, claim.leaseToken)
                    .build(),
                ContentValues(5).apply {
                    put("state", nextState)
                    putNull("lease_token")
                    putNull("lease_until")
                    put("last_error", message)
                    put("updated_at", now)
                },
            )
        check(updated == 1) { "Translation job lease is no longer owned" }
    }

    private fun findByEitherIdentity(value: StoredNovelTranslation): StoredNovelTranslation? {
        val matches =
            query(
                "$SELECT_COMPLETED WHERE (chapter_id = ? AND target_language = ?) OR " +
                    "(source_id = ? AND manga_url = ? AND chapter_url = ? AND target_language = ?)",
                value.locator.chapterId,
                value.targetLanguage,
                value.locator.sourceId,
                value.locator.mangaUrl,
                value.locator.chapterUrl,
                value.targetLanguage,
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) add(cursor.toStored())
                }
            }
        require(matches.size <= 1) { "Completed translation identities resolve to different rows" }
        return matches.singleOrNull()
    }

    private fun saveCompletedInternal(checked: StoredNovelTranslation) {
        val existing = findByEitherIdentity(checked)
        if (existing != null) {
            require(existing.locator == checked.locator) { "Completed translation identity conflicts" }
            val values = checked.toValues(includeIdentity = false).apply {
                put("created_at", existing.createdAt)
            }
            val updated =
                database.lowLevel().update(
                    UpdateQuery.builder()
                        .table(TABLE_COMPLETED)
                        .where("chapter_id = ? AND target_language = ?")
                        .whereArgs(checked.locator.chapterId, checked.targetLanguage)
                        .build(),
                    values,
                )
            check(updated == 1) { "Completed translation disappeared during update" }
        } else {
            check(database.lowLevel().insert(insertQuery(TABLE_COMPLETED), checked.toValues()) >= 0)
        }
    }

    private fun job(
        batchId: String,
        chapterId: Long,
        targetLanguage: String,
    ): NovelTranslationJobRequest? =
        query(
            "$SELECT_JOB WHERE batch_id = ? AND chapter_id = ? AND target_language = ?",
            batchId,
            chapterId,
            targetLanguage,
        ).use { cursor -> if (cursor.moveToFirst()) cursor.toJobRequest() else null }

    private fun hasEquivalentUnfinishedJob(request: NovelTranslationJobRequest): Boolean =
        query(
            "SELECT 1 FROM $TABLE_JOBS WHERE chapter_id = ? AND source_id = ? AND manga_url = ? AND chapter_url = ? " +
                "AND source_language = ? AND target_language = ? AND engine_id = ? AND provider_config_hash = ? " +
                "AND force_retranslate = ? AND state IN ('queued', 'running') LIMIT 1",
            request.locator.chapterId,
            request.locator.sourceId,
            request.locator.mangaUrl,
            request.locator.chapterUrl,
            request.sourceLanguage,
            request.targetLanguage,
            request.engineId,
            request.providerConfigHash,
            if (request.force) 1 else 0,
        ).use { it.moveToFirst() }

    private fun jobAttemptCount(jobId: String): Int =
        query("SELECT attempt_count FROM $TABLE_JOBS WHERE job_id = ?", jobId).use { cursor ->
            check(cursor.moveToFirst()) { "Translation job disappeared" }
            cursor.getInt(0)
        }

    private fun query(
        sql: String,
        vararg args: Any,
    ): Cursor =
        database.lowLevel().rawQuery(RawQuery.builder().query(sql).args(*args).build())

    private fun execute(
        sql: String,
        vararg args: Any,
    ) {
        database.lowLevel().executeSQL(RawQuery.builder().query(sql).args(*args).build())
    }

    private fun StoredNovelTranslation.toValues(includeIdentity: Boolean = true): ContentValues =
        ContentValues(if (includeIdentity) 13 else 9).apply {
            if (includeIdentity) {
                put("chapter_id", locator.chapterId)
                put("source_id", locator.sourceId)
                put("manga_url", locator.mangaUrl)
                put("chapter_url", locator.chapterUrl)
                put("target_language", targetLanguage)
            }
            put("source_language", sourceLanguage)
            put("source_hash_sha256", sourceHash)
            put("translated_content", translatedContent)
            put("content_format", contentFormat)
            put("engine_id", engineId)
            put("detected_language", detectedLanguage)
            put("created_at", createdAt)
            put("updated_at", updatedAt)
        }

    private fun NovelTranslationJobRequest.toValues(): ContentValues =
        ContentValues(20).apply {
            put("job_id", jobId)
            put("batch_id", batchId)
            put("chapter_id", locator.chapterId)
            put("source_id", locator.sourceId)
            put("manga_url", locator.mangaUrl)
            put("chapter_url", locator.chapterUrl)
            put("source_language", sourceLanguage)
            put("target_language", targetLanguage)
            put("engine_id", engineId)
            put("provider_config_hash", providerConfigHash)
            put("position_index", position)
            put("priority", priority)
            put("force_retranslate", force)
            put("state", "queued")
            put("attempt_count", 0)
            putNull("lease_token")
            putNull("lease_until")
            putNull("last_error")
            put("created_at", createdAt)
            put("updated_at", createdAt)
        }

    private fun NovelTranslationJobRequest.requireValid() {
        require(jobId.length in 1..128 && batchId.length in 1..128)
        locator.requireValid()
        require(sourceLanguage.length in 1..64 && targetLanguage.length in 1..64)
        require(engineId.length in 1..128 && providerConfigHash.matches(SHA_256))
        require(position >= 0 && createdAt >= 0)
    }

    private fun NovelTranslationJobRequest.sameRequestAs(other: NovelTranslationJobRequest): Boolean =
        batchId == other.batchId &&
            locator == other.locator &&
            sourceLanguage == other.sourceLanguage &&
            targetLanguage == other.targetLanguage &&
            engineId == other.engineId &&
            providerConfigHash == other.providerConfigHash &&
            position == other.position &&
            priority == other.priority &&
            force == other.force

    private fun Cursor.toStored(): StoredNovelTranslation =
        StoredNovelTranslation(
            locator = NovelTranslationLocator(getLong(0), getLong(1), getString(2), getString(3)),
            sourceLanguage = getString(4),
            targetLanguage = getString(5),
            sourceHash = getString(6),
            translatedContent = getString(7),
            contentFormat = getString(8),
            engineId = getString(9),
            detectedLanguage = if (isNull(10)) null else getString(10),
            createdAt = getLong(11),
            updatedAt = getLong(12),
        ).requireValid()

    private fun Cursor.toJobRequest(): NovelTranslationJobRequest =
        NovelTranslationJobRequest(
            jobId = getString(0),
            batchId = getString(1),
            locator = NovelTranslationLocator(getLong(2), getLong(3), getString(4), getString(5)),
            sourceLanguage = getString(6),
            targetLanguage = getString(7),
            engineId = getString(8),
            providerConfigHash = getString(9),
            position = getInt(10),
            priority = getInt(11),
            force = getInt(12) != 0,
            createdAt = getLong(13),
        )

    private fun insertQuery(table: String): InsertQuery = InsertQuery.builder().table(table).build()

    private companion object {
        const val TABLE_COMPLETED = "hayai_novel_translations"
        const val TABLE_JOBS = "hayai_novel_translation_jobs"
        const val MAX_BATCH_SIZE = 10_000
        const val MAX_ATTEMPTS = 5
        const val MAX_ERROR_CHARS = 4_096
        const val MAX_LEASE_MILLIS = 24L * 60 * 60 * 1000
        val SHA_256 = Regex("[0-9a-f]{64}")
        const val SELECT_COMPLETED =
            "SELECT chapter_id,source_id,manga_url,chapter_url,source_language,target_language," +
                "source_hash_sha256,translated_content,content_format,engine_id,detected_language,created_at,updated_at " +
                "FROM hayai_novel_translations"
        const val SELECT_JOB =
            "SELECT job_id,batch_id,chapter_id,source_id,manga_url,chapter_url,source_language,target_language," +
                "engine_id,provider_config_hash,position_index,priority,force_retranslate,created_at " +
                "FROM hayai_novel_translation_jobs"
    }
}

object NovelTranslationHash {
    fun sha256(value: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
}
