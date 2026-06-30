package eu.kanade.tachiyomi.data.library

import android.content.Context
import co.touchlab.kermit.Logger
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import yokai.i18n.MR
import yokai.util.lang.getString

/**
 * Snapshot of the most recent library update run, persisted as a JSON sidecar in the cache dir
 * so the in-app "Library update report" screen can be opened after the run finishes — even
 * after the user dismisses the notification or restarts the app. Cleared whenever cache is
 * cleared, which mirrors the lifetime of the existing tachiyomi_update_*.txt log files.
 */
@Serializable
data class LibraryUpdateReport(
    val timestampMs: Long = 0L,
    val errors: List<LibraryUpdateReportEntry> = emptyList(),
    val skipped: List<LibraryUpdateReportEntry> = emptyList(),
)

@Serializable
data class LibraryUpdateReportEntry(
    val mangaId: Long = 0L,
    val mangaTitle: String = "",
    val mangaThumbnailUrl: String? = null,
    val mangaCoverLastModified: Long = 0L,
    val mangaInLibrary: Boolean = true,
    val sourceId: Long = 0L,
    val sourceName: String = "",
    /** Error message or skip reason — already localized when generated. */
    val message: String = "",
)

class LibraryUpdateReportStore(private val context: Context) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun save(report: LibraryUpdateReport) {
        try {
            val file = File(context.cacheDir, REPORT_FILE_NAME)
            file.writeText(json.encodeToString(report))
        } catch (e: Exception) {
            Logger.e(e) { "Failed to persist library update report" }
        }
    }

    fun read(): LibraryUpdateReport? {
        return try {
            val file = File(context.cacheDir, REPORT_FILE_NAME)
            if (!file.exists() || file.length() == 0L) return readFromTextLogs()
            json.decodeFromString<LibraryUpdateReport>(file.readText())
        } catch (e: Exception) {
            Logger.e(e) { "Failed to read library update report" }
            readFromTextLogs()
        }
    }

    fun clear() {
        runCatching { File(context.cacheDir, REPORT_FILE_NAME).delete() }
        clearErrorLog()
        clearSkippedLog()
    }

    /** Path to the human-readable .txt log written alongside the JSON, if it still exists. */
    fun errorLogFile(): File? = context.externalCacheDir?.let { File(it, ERROR_LOG_FILE_NAME) }?.takeIf { it.exists() }

    fun skippedLogFile(): File? = context.externalCacheDir?.let { File(it, SKIPPED_LOG_FILE_NAME) }?.takeIf { it.exists() }

    fun clearErrorLog() {
        deleteExternalCacheFile(ERROR_LOG_FILE_NAME)
    }

    fun clearSkippedLog() {
        deleteExternalCacheFile(SKIPPED_LOG_FILE_NAME)
    }

    private fun deleteExternalCacheFile(name: String) {
        runCatching { context.externalCacheDir?.let { File(it, name) }?.delete() }
    }

    private fun readFromTextLogs(): LibraryUpdateReport? {
        val errorFile = errorLogFile()
        val skippedFile = skippedLogFile()
        val errors = errorFile?.parseTextLog().orEmpty()
        val skipped = skippedFile?.parseTextLog().orEmpty()
        if (errors.isEmpty() && skipped.isEmpty()) return null
        return LibraryUpdateReport(
            timestampMs = listOfNotNull(errorFile?.lastModified(), skippedFile?.lastModified()).maxOrNull()
                ?: System.currentTimeMillis(),
            errors = errors,
            skipped = skipped,
        )
    }

    private fun File.parseTextLog(): List<LibraryUpdateReportEntry> {
        val entries = mutableListOf<LibraryUpdateReportEntry>()
        var message = context.getString(MR.strings.unknown_error)
        var sourceName = ""
        forEachLine { rawLine ->
            val line = rawLine.trim()
            when {
                line.startsWith("!") -> {
                    message = line.removePrefix("!").trim().ifBlank {
                        context.getString(MR.strings.unknown_error)
                    }
                }
                line.startsWith("#") -> {
                    sourceName = line.removePrefix("#").trim()
                }
                line.startsWith("-") -> {
                    val title = line.removePrefix("-").trim()
                    if (title.isNotBlank()) {
                        entries += LibraryUpdateReportEntry(
                            mangaTitle = title,
                            sourceName = sourceName,
                            message = message,
                        )
                    }
                }
            }
        }
        return entries
    }

    companion object {
        const val REPORT_FILE_NAME = "library_update_report.json"
        // Must mirror the names written by LibraryUpdateJob.writeErrorFile.
        const val ERROR_LOG_FILE_NAME = "tachiyomi_update_errors.txt"
        const val SKIPPED_LOG_FILE_NAME = "tachiyomi_update_skipped.txt"
    }
}
