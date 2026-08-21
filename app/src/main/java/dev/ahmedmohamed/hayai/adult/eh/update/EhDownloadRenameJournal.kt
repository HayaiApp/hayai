package dev.ahmedmohamed.hayai.adult.eh.update

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class EhPendingDownloadRename(
    val mangaId: Long,
    val chapterId: Long,
    val oldName: String,
    val newName: String,
)

class EhDownloadRenameJournal(context: Context) {
    private val preferences = context.getSharedPreferences("hayai.eh.gallery_updater.renames", Context.MODE_PRIVATE)

    @Synchronized
    fun pending(mangaId: Long): List<EhPendingDownloadRename> = read().filter { it.mangaId == mangaId }

    @Synchronized
    fun prepare(rename: EhPendingDownloadRename) {
        val values = read().filterNot { it.mangaId == rename.mangaId && it.chapterId == rename.chapterId } + rename
        write(values)
    }

    @Synchronized
    fun complete(mangaId: Long, chapterIds: Set<Long>) {
        write(read().filterNot { it.mangaId == mangaId && it.chapterId in chapterIds })
    }

    private fun read(): List<EhPendingDownloadRename> = runCatching {
        val array = JSONArray(preferences.getString("pending", "[]"))
        List(array.length()) { index ->
            val value = array.getJSONObject(index)
            EhPendingDownloadRename(value.getLong("mangaId"), value.getLong("chapterId"), value.getString("oldName"), value.getString("newName"))
        }
    }.getOrDefault(emptyList())

    private fun write(values: List<EhPendingDownloadRename>) {
        val array = JSONArray()
        values.sortedWith(compareBy(EhPendingDownloadRename::mangaId, EhPendingDownloadRename::chapterId)).forEach { value ->
            array.put(
                JSONObject()
                    .put("mangaId", value.mangaId)
                    .put("chapterId", value.chapterId)
                    .put("oldName", value.oldName)
                    .put("newName", value.newName),
            )
        }
        preferences.edit().putString("pending", array.toString()).commit()
    }
}
