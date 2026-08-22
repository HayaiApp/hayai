package dev.ahmedmohamed.hayai.adult.eh.persistence

import android.content.ContentValues
import android.database.Cursor
import com.pushtorefresh.storio.sqlite.queries.InsertQuery
import com.pushtorefresh.storio.sqlite.queries.RawQuery
import com.pushtorefresh.storio.sqlite.queries.UpdateQuery
import eu.kanade.tachiyomi.data.database.DatabaseHelper
import dev.ahmedmohamed.hayai.adult.eh.favorites.EhFavoriteCategoryMapping
import dev.ahmedmohamed.hayai.adult.eh.favorites.EhFavoriteConflict
import dev.ahmedmohamed.hayai.adult.eh.favorites.EhFavoriteConflictKind
import dev.ahmedmohamed.hayai.adult.eh.favorites.EhFavoriteOperation
import dev.ahmedmohamed.hayai.adult.eh.favorites.EhFavoriteOperationCodec
import dev.ahmedmohamed.hayai.adult.eh.favorites.EhFavoriteSlot
import dev.ahmedmohamed.hayai.adult.eh.favorites.EhFavoritesPlan
import org.json.JSONObject

class HayaiEhPersistenceStore(
    private val database: DatabaseHelper,
) {
    fun categoryMappings(): List<EhFavoriteCategoryMapping> =
        query("SELECT slot, category_id, remote_name FROM hayai_eh_category_map ORDER BY slot").use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(EhFavoriteCategoryMapping(EhFavoriteSlot(cursor.getInt(0)), cursor.getInt(1), cursor.getString(2)))
            }
        }

    fun upsertCategoryMapping(mapping: EhFavoriteCategoryMapping) {
        upsert(
            table = "hayai_eh_category_map",
            where = "slot = ?",
            whereArgs = arrayOf(mapping.slot.value),
            values = ContentValues(3).apply {
                put("slot", mapping.slot.value)
                put("category_id", mapping.categoryId)
                put("remote_name", mapping.remoteName)
            },
        )
    }

    fun activeSyncRunId(): String? =
        query("SELECT run_id FROM hayai_eh_sync_runs WHERE status = 'running' ORDER BY started_at LIMIT 1").use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }

    fun runExpectedFingerprint(runId: String): String =
        query("SELECT expected_fingerprint FROM hayai_eh_sync_runs WHERE run_id = ?", runId).use { cursor ->
            check(cursor.moveToFirst()) { "Unknown E-Hentai sync run $runId" }
            cursor.getString(0)
        }

    fun createSyncPlan(
        runId: String,
        mode: EhSyncMode,
        plan: EhFavoritesPlan,
        startedAt: Long = System.currentTimeMillis(),
    ) {
        database.inTransaction {
            check(activeSyncRunId() == null) { "Another E-Hentai sync is already running" }
            insert(
                "hayai_eh_sync_runs",
                ContentValues(7).apply {
                    put("run_id", runId)
                    put("mode", mode.storedValue)
                    put("status", EhSyncRunStatus.Running.storedValue)
                    put("started_at", startedAt)
                    put("remote_fingerprint", plan.expectedRemoteFingerprint)
                    put("expected_fingerprint", plan.expectedRemoteFingerprint)
                },
            )
            plan.operations.forEach { operation ->
                appendOperation(
                    EhSyncJournalOperation(
                        operationId = operation.operationId,
                        runId = runId,
                        sequence = operation.sequence,
                        kind = EhFavoriteOperationCodec.kind(operation),
                        gallery = operation.gallery,
                        payloadJson = EhFavoriteOperationCodec.encode(operation),
                        status = EhSyncOperationStatus.Pending,
                        attempts = 0,
                        lastError = null,
                        createdAt = startedAt,
                        updatedAt = startedAt,
                    ),
                )
            }
            plan.conflicts.forEach { conflict -> insertConflict(runId, conflict, startedAt) }
        }
    }

    fun typedPendingOperations(runId: String): List<EhFavoriteOperation> =
        pendingOperations(runId).map { EhFavoriteOperationCodec.decode(it.kind, it.payloadJson) }

    fun unresolvedConflictKinds(runId: String): List<EhFavoriteConflictKind> =
        query("SELECT payload_json FROM hayai_eh_sync_conflicts WHERE run_id = ? AND resolution IS NULL ORDER BY created_at", runId).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(EhFavoriteConflictKind.valueOf(JSONObject(cursor.getString(0)).getString("kind")))
                }
            }
        }

    fun markAttemptStarted(operationId: String, updatedAt: Long = System.currentTimeMillis()) {
        val updated = database.lowLevel().update(
            UpdateQuery.builder().table("hayai_eh_sync_journal").where("operation_id = ? AND status != 'applied'").whereArgs(operationId).build(),
            ContentValues(2).apply {
                put("attempts", operationAttempts(operationId) + 1)
                put("updated_at", updatedAt)
            },
        )
        check(updated == 1) { "Unknown or completed sync operation $operationId" }
    }

    fun applyLocalOperation(
        operationId: String,
        mutation: () -> Unit,
        updatedAt: Long = System.currentTimeMillis(),
    ) {
        database.inTransaction {
            mutation()
            val updated = database.lowLevel().update(
                UpdateQuery.builder().table("hayai_eh_sync_journal").where("operation_id = ? AND status != 'applied'").whereArgs(operationId).build(),
                ContentValues(3).apply {
                    put("status", EhSyncOperationStatus.Applied.storedValue)
                    put("last_error", null as String?)
                    put("updated_at", updatedAt)
                },
            )
            check(updated == 1) { "Unknown or completed sync operation $operationId" }
        }
    }

    fun completeSyncWithSnapshot(
        runId: String,
        snapshot: Collection<EhFavoriteSnapshot>,
        remoteFingerprint: String,
        completedAt: Long = System.currentTimeMillis(),
    ) {
        database.inTransaction {
            check(pendingOperations(runId).isEmpty()) { "Sync run still has unapplied operations" }
            execute("DELETE FROM hayai_eh_favorites")
            snapshot.forEach { favorite ->
                insert("hayai_eh_favorites", ContentValues(4).apply {
                    put("gid", favorite.gallery.gid)
                    put("token", favorite.gallery.token)
                    put("title", favorite.title)
                    put("category", favorite.categorySlot)
                })
            }
            finishRun(runId, EhSyncRunStatus.Complete, null, completedAt)
            execute(
                "UPDATE hayai_eh_sync_checkpoint SET generation = generation + 1, completed_at = ?, remote_fingerprint = ?, requires_full_reconcile = 0 WHERE singleton = 1",
                completedAt,
                remoteFingerprint,
            )
        }
    }

    private fun insertConflict(runId: String, conflict: EhFavoriteConflict, createdAt: Long) {
        insert(
            "hayai_eh_sync_conflicts",
            ContentValues(8).apply {
                put("conflict_id", conflict.id)
                put("run_id", runId)
                put("gid", conflict.gallery?.gid)
                put("token", conflict.gallery?.token)
                put("conflict_kind", conflict::class.java.simpleName)
                put("payload_json", JSONObject().put("kind", conflict.kind.name).toString())
                put("created_at", createdAt)
            },
        )
    }
    fun metadata(identity: SourceMangaIdentity): SourceMetadata? =
        query(
            "SELECT uploader, extra, indexed_extra, extra_version FROM hayai_source_metadata WHERE source_id = ? AND manga_url = ?",
            identity.sourceId,
            identity.mangaUrl,
        ).use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            SourceMetadata(
                identity = identity,
                uploader = cursor.stringOrNull(0),
                extra = cursor.getString(1),
                indexedExtra = cursor.stringOrNull(2),
                extraVersion = cursor.getInt(3),
                tags = metadataTags(identity),
                titles = metadataTitles(identity),
            )
        }

    fun replaceMetadata(metadata: SourceMetadata): Boolean {
        var stored = false
        database.inTransaction {
            if (!mangaExists(metadata.identity)) return@inTransaction
            upsert(
                table = "hayai_source_metadata",
                where = "source_id = ? AND manga_url = ?",
                whereArgs = arrayOf<Any>(metadata.identity.sourceId, metadata.identity.mangaUrl),
                values =
                    ContentValues(7).apply {
                        put("source_id", metadata.identity.sourceId)
                        put("manga_url", metadata.identity.mangaUrl)
                        put("uploader", metadata.uploader)
                        put("extra", metadata.extra)
                        put("indexed_extra", metadata.indexedExtra)
                        put("extra_version", metadata.extraVersion)
                        put("updated_at", System.currentTimeMillis())
                    },
            )
            deleteMetadataChildren(metadata.identity)
            metadata.tags.forEach { insertTag(metadata.identity, it) }
            metadata.titles.forEach { insertTitle(metadata.identity, it) }
            stored = true
        }
        return stored
    }

    fun favorites(): List<EhFavoriteSnapshot> =
        query("SELECT gid, token, title, category FROM hayai_eh_favorites ORDER BY gid, token").use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        EhFavoriteSnapshot(
                            EhGalleryIdentity(cursor.getString(0), cursor.getString(1)),
                            cursor.getString(2),
                            cursor.getInt(3),
                        ),
                    )
                }
            }
        }

    fun replaceFavorites(favorites: Collection<EhFavoriteSnapshot>) {
        require(favorites.map { it.gallery }.distinct().size == favorites.size) { "Duplicate favorite identity" }
        database.inTransaction {
            execute("DELETE FROM hayai_eh_favorites")
            favorites.forEach { favorite ->
                insert(
                    "hayai_eh_favorites",
                    ContentValues(4).apply {
                        put("gid", favorite.gallery.gid)
                        put("token", favorite.gallery.token)
                        put("title", favorite.title)
                        put("category", favorite.categorySlot)
                    },
                )
            }
        }
    }

    fun aliases(): List<EhGalleryAlias> =
        query(
            "SELECT canonical_gid, canonical_token, alternate_gid, alternate_token FROM hayai_eh_gallery_aliases " +
                "ORDER BY canonical_gid, canonical_token, alternate_gid, alternate_token",
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        EhGalleryAlias(
                            EhGalleryIdentity(cursor.getString(0), cursor.getString(1)),
                            EhGalleryIdentity(cursor.getString(2), cursor.getString(3)),
                        ),
                    )
                }
            }
        }

    fun addAlias(alias: EhGalleryAlias) {
        database.inTransaction {
            val existing =
                query(
                    "SELECT canonical_gid, canonical_token FROM hayai_eh_gallery_aliases WHERE alternate_gid = ? AND alternate_token = ? LIMIT 1",
                    alias.alternate.gid,
                    alias.alternate.token,
                ).use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) to cursor.getString(1) else null
                }
            if (existing != null) {
                require(existing == (alias.canonical.gid to alias.canonical.token)) {
                    "Alternate gallery already belongs to another canonical gallery"
                }
                return@inTransaction
            }
            insert(
                "hayai_eh_gallery_aliases",
                ContentValues(4).apply {
                    put("canonical_gid", alias.canonical.gid)
                    put("canonical_token", alias.canonical.token)
                    put("alternate_gid", alias.alternate.gid)
                    put("alternate_token", alias.alternate.token)
                },
            )
        }
    }

    fun checkpoint(): EhSyncCheckpoint =
        query(
            "SELECT generation, completed_at, remote_fingerprint, requires_full_reconcile FROM hayai_eh_sync_checkpoint WHERE singleton = 1",
        ).use { cursor ->
            check(cursor.moveToFirst()) { "Missing E-Hentai sync checkpoint" }
            EhSyncCheckpoint(cursor.getLong(0), cursor.longOrNull(1), cursor.stringOrNull(2), cursor.getInt(3) != 0)
        }

    fun startSync(
        runId: String,
        mode: EhSyncMode,
        startedAt: Long = System.currentTimeMillis(),
    ) {
        require(runId.isNotBlank() && runId.length <= 128) { "Invalid sync run ID" }
        database.inTransaction {
            val existingRun = syncRun(runId)
            if (existingRun != null) {
                require(existingRun == (mode.storedValue to EhSyncRunStatus.Running.storedValue)) {
                    "Sync run ID conflicts with existing state"
                }
                return@inTransaction
            }
            check(
                !query("SELECT 1 FROM hayai_eh_sync_runs WHERE status = 'running' LIMIT 1").use(Cursor::moveToFirst),
            ) { "Another E-Hentai sync is already running" }
            insert(
                "hayai_eh_sync_runs",
                ContentValues(4).apply {
                    put("run_id", runId)
                    put("mode", mode.storedValue)
                    put("status", EhSyncRunStatus.Running.storedValue)
                    put("started_at", startedAt)
                },
            )
        }
    }

    fun appendOperation(operation: EhSyncJournalOperation) {
        require(operation.status == EhSyncOperationStatus.Pending) { "New sync operations must be pending" }
        operation(operation.operationId)?.let { existing ->
            require(existing == operation) { "Sync operation ID conflicts with existing state" }
            return
        }
        insert(
            "hayai_eh_sync_journal",
            ContentValues(13).apply {
                put("operation_id", operation.operationId)
                put("run_id", operation.runId)
                put("sequence", operation.sequence)
                put("operation_kind", operation.kind)
                put("gid", operation.gallery?.gid)
                put("token", operation.gallery?.token)
                put("payload_json", operation.payloadJson)
                put("status", operation.status.storedValue)
                put("attempts", operation.attempts)
                put("last_error", operation.lastError)
                put("created_at", operation.createdAt)
                put("updated_at", operation.updatedAt)
            },
        )
    }

    fun pendingOperations(runId: String): List<EhSyncJournalOperation> =
        query(
            "SELECT operation_id, sequence, operation_kind, gid, token, payload_json, status, attempts, last_error, created_at, updated_at " +
                "FROM hayai_eh_sync_journal WHERE run_id = ? AND status != 'applied' ORDER BY sequence",
            runId,
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        EhSyncJournalOperation(
                            operationId = cursor.getString(0),
                            runId = runId,
                            sequence = cursor.getLong(1),
                            kind = cursor.getString(2),
                            gallery = if (cursor.isNull(3)) null else EhGalleryIdentity(cursor.getString(3), cursor.getString(4)),
                            payloadJson = cursor.getString(5),
                            status = operationStatus(cursor.getString(6)),
                            attempts = cursor.getInt(7),
                            lastError = cursor.stringOrNull(8),
                            createdAt = cursor.getLong(9),
                            updatedAt = cursor.getLong(10),
                        ),
                    )
                }
            }
        }

    fun markOperation(
        operationId: String,
        status: EhSyncOperationStatus,
        error: String? = null,
        updatedAt: Long = System.currentTimeMillis(),
    ) {
        require(status != EhSyncOperationStatus.Pending) { "A pending operation has no result" }
        operation(operationId)?.let { existing ->
            if (existing.status == status && existing.lastError == error) return
        }
        val updated =
            database.lowLevel().update(
                UpdateQuery.builder().table("hayai_eh_sync_journal").where("operation_id = ?").whereArgs(operationId).build(),
                ContentValues(4).apply {
                    put("status", status.storedValue)
                    put("last_error", error)
                    put("updated_at", updatedAt)
                },
            )
        check(updated == 1) { "Unknown sync operation $operationId" }
    }

    fun completeSync(
        runId: String,
        remoteFingerprint: String,
        completedAt: Long = System.currentTimeMillis(),
    ) {
        require(remoteFingerprint.isNotBlank() && remoteFingerprint.length <= 512) { "Invalid remote fingerprint" }
        database.inTransaction {
            val runStatus = syncRunStatus(runId)
            if (runStatus == EhSyncRunStatus.Complete.storedValue) {
                require(checkpoint().remoteFingerprint == remoteFingerprint) { "Completed sync fingerprint conflicts" }
                return@inTransaction
            }
            check(runStatus == EhSyncRunStatus.Running.storedValue) { "Sync run is missing or already failed" }
            check(pendingOperations(runId).isEmpty()) { "Sync run still has unapplied operations" }
            finishRun(runId, EhSyncRunStatus.Complete, null, completedAt)
            execute(
                "UPDATE hayai_eh_sync_checkpoint SET generation = generation + 1, completed_at = ?, remote_fingerprint = ?, " +
                    "requires_full_reconcile = 0 WHERE singleton = 1",
                completedAt,
                remoteFingerprint,
            )
        }
    }

    fun failSync(
        runId: String,
        error: String,
        finishedAt: Long = System.currentTimeMillis(),
    ) {
        require(error.isNotBlank() && error.length <= 8_192) { "Invalid sync failure" }
        val current = syncRunDetails(runId)
        if (current?.first == EhSyncRunStatus.Failed.storedValue) {
            require(current.second == error) { "Failed sync result conflicts" }
            return
        }
        finishRun(runId, EhSyncRunStatus.Failed, error, finishedAt)
    }

    fun requireFullReconcile() {
        execute("UPDATE hayai_eh_sync_checkpoint SET requires_full_reconcile = 1 WHERE singleton = 1")
    }

    private fun finishRun(
        runId: String,
        status: EhSyncRunStatus,
        error: String?,
        finishedAt: Long,
    ) {
        val updated =
            database.lowLevel().update(
                UpdateQuery.builder().table("hayai_eh_sync_runs").where("run_id = ? AND status = 'running'").whereArgs(runId).build(),
                ContentValues(3).apply {
                    put("status", status.storedValue)
                    put("finished_at", finishedAt)
                    put("error", error)
                },
            )
        check(updated == 1) { "Sync run is missing or already finished" }
    }

    private fun metadataTags(identity: SourceMangaIdentity): List<SourceMetadataTag> =
        query(
            "SELECT namespace, name, type FROM hayai_source_metadata_tags WHERE source_id = ? AND manga_url = ? ORDER BY namespace, name, type",
            identity.sourceId,
            identity.mangaUrl,
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(SourceMetadataTag(cursor.getString(0).ifEmpty { null }, cursor.getString(1), cursor.getInt(2)))
            }
        }

    private fun metadataTitles(identity: SourceMangaIdentity): List<SourceMetadataTitle> =
        query(
            "SELECT title, type FROM hayai_source_metadata_titles WHERE source_id = ? AND manga_url = ? ORDER BY type, title",
            identity.sourceId,
            identity.mangaUrl,
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(SourceMetadataTitle(cursor.getString(0), cursor.getInt(1)))
            }
        }

    private fun mangaExists(identity: SourceMangaIdentity): Boolean =
        query("SELECT 1 FROM mangas WHERE source = ? AND url = ? LIMIT 1", identity.sourceId, identity.mangaUrl).use(Cursor::moveToFirst)

    private fun deleteMetadataChildren(identity: SourceMangaIdentity) {
        execute("DELETE FROM hayai_source_metadata_tags WHERE source_id = ? AND manga_url = ?", identity.sourceId, identity.mangaUrl)
        execute("DELETE FROM hayai_source_metadata_titles WHERE source_id = ? AND manga_url = ?", identity.sourceId, identity.mangaUrl)
    }

    private fun insertTag(
        identity: SourceMangaIdentity,
        tag: SourceMetadataTag,
    ) {
        insert(
            "hayai_source_metadata_tags",
            ContentValues(5).apply {
                put("source_id", identity.sourceId)
                put("manga_url", identity.mangaUrl)
                put("namespace", tag.namespace.orEmpty())
                put("name", tag.name)
                put("type", tag.type)
            },
        )
    }

    private fun insertTitle(
        identity: SourceMangaIdentity,
        title: SourceMetadataTitle,
    ) {
        insert(
            "hayai_source_metadata_titles",
            ContentValues(4).apply {
                put("source_id", identity.sourceId)
                put("manga_url", identity.mangaUrl)
                put("title", title.title)
                put("type", title.type)
            },
        )
    }

    private fun operationAttempts(operationId: String): Int =
        query("SELECT attempts FROM hayai_eh_sync_journal WHERE operation_id = ?", operationId).use { cursor ->
            check(cursor.moveToFirst()) { "Unknown sync operation $operationId" }
            cursor.getInt(0)
        }

    private fun operation(operationId: String): EhSyncJournalOperation? =
        query(
            "SELECT run_id, sequence, operation_kind, gid, token, payload_json, status, attempts, last_error, created_at, updated_at " +
                "FROM hayai_eh_sync_journal WHERE operation_id = ?",
            operationId,
        ).use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            EhSyncJournalOperation(
                operationId = operationId,
                runId = cursor.getString(0),
                sequence = cursor.getLong(1),
                kind = cursor.getString(2),
                gallery = if (cursor.isNull(3)) null else EhGalleryIdentity(cursor.getString(3), cursor.getString(4)),
                payloadJson = cursor.getString(5),
                status = operationStatus(cursor.getString(6)),
                attempts = cursor.getInt(7),
                lastError = cursor.stringOrNull(8),
                createdAt = cursor.getLong(9),
                updatedAt = cursor.getLong(10),
            )
        }

    private fun syncRun(runId: String): Pair<String, String>? =
        query("SELECT mode, status FROM hayai_eh_sync_runs WHERE run_id = ?", runId).use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) to cursor.getString(1) else null
        }

    private fun syncRunStatus(runId: String): String? =
        query("SELECT status FROM hayai_eh_sync_runs WHERE run_id = ?", runId).use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }

    private fun syncRunDetails(runId: String): Pair<String, String?>? =
        query("SELECT status, error FROM hayai_eh_sync_runs WHERE run_id = ?", runId).use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) to cursor.stringOrNull(1) else null
        }

    private fun operationStatus(value: String): EhSyncOperationStatus =
        EhSyncOperationStatus.entries.singleOrNull { it.storedValue == value } ?: error("Unknown sync operation status $value")

    private fun upsert(
        table: String,
        where: String,
        whereArgs: Array<out Any>,
        values: ContentValues,
    ) {
        val exists = query("SELECT 1 FROM $table WHERE $where LIMIT 1", *whereArgs).use(Cursor::moveToFirst)
        if (exists) {
            check(
                database.lowLevel().update(
                    UpdateQuery.builder().table(table).where(where).whereArgs(*whereArgs).build(),
                    values,
                ) == 1,
            )
        } else {
            insert(table, values)
        }
    }

    private fun insert(
        table: String,
        values: ContentValues,
    ) {
        val query = InsertQuery.builder().table(table).build()
        val result = database.lowLevel().insert(query, values)
        check(result >= 0) { "Unable to insert $table" }
    }

    private fun execute(
        sql: String,
        vararg args: Any,
    ) {
        database.lowLevel().executeSQL(RawQuery.builder().query(sql).args(*args).build())
    }

    private fun query(
        sql: String,
        vararg args: Any,
    ): Cursor = database.lowLevel().rawQuery(RawQuery.builder().query(sql).args(*args).build())

    private fun Cursor.stringOrNull(index: Int): String? = if (isNull(index)) null else getString(index)

    private fun Cursor.longOrNull(index: Int): Long? = if (isNull(index)) null else getLong(index)
}
