package dev.ahmedmohamed.hayai.novel.reader

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.text.HtmlCompat
import androidx.core.view.setPadding
import androidx.lifecycle.lifecycleScope
import dev.ahmedmohamed.hayai.novel.source.NovelContentType
import dev.ahmedmohamed.hayai.novel.source.NovelDocument
import dev.ahmedmohamed.hayai.novel.source.NovelSource
import eu.kanade.tachiyomi.data.database.DatabaseHelper
import eu.kanade.tachiyomi.data.database.models.History
import eu.kanade.tachiyomi.source.SourceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class NovelReaderActivity : AppCompatActivity() {
    private lateinit var titleView: TextView
    private lateinit var contentView: TextView
    private lateinit var progress: ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(createContentView())

        val mangaId = intent.getLongExtra(EXTRA_MANGA_ID, -1L)
        val chapterId = intent.getLongExtra(EXTRA_CHAPTER_ID, -1L)
        if (mangaId < 0 || chapterId < 0) {
            showError("The novel chapter could not be opened.")
            return
        }

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { loadDocument(mangaId, chapterId) }
            result.fold(::showDocument) { showError(it.message ?: "The novel chapter could not be loaded.") }
        }
    }

    private suspend fun loadDocument(
        mangaId: Long,
        chapterId: Long,
    ): Result<LoadedDocument> =
        runCatching {
            val db = Injekt.get<DatabaseHelper>()
            val manga = requireNotNull(db.getManga(mangaId).executeAsBlocking())
            val chapter = requireNotNull(db.getChapter(chapterId).executeAsBlocking())
            val source =
                Injekt.get<SourceManager>().get(manga.source) as? NovelSource
                    ?: error("This source does not provide novel text.")
            val document = source.getChapterDocument(chapter)
            db
                .upsertHistoryLastRead(
                    History.create(chapter).apply { last_read = System.currentTimeMillis() },
                ).executeAsBlocking()
            LoadedDocument(chapter.name, document)
        }

    private fun showDocument(loaded: LoadedDocument) {
        progress.visibility = View.GONE
        titleView.text = loaded.title
        contentView.text = loaded.document.asDisplayText()
    }

    private fun showError(message: String) {
        progress.visibility = View.GONE
        titleView.text = "Unable to open chapter"
        contentView.text = message
    }

    private fun createContentView(): View {
        val density = resources.displayMetrics.density
        val padding = (24 * density).toInt()
        val container =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(padding)
            }
        titleView =
            TextView(this).apply {
                textSize = 24f
                setPadding(0, 0, 0, (16 * density).toInt())
            }
        progress =
            ProgressBar(this).apply {
                isIndeterminate = true
                layoutParams =
                    LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                        gravity = Gravity.CENTER_HORIZONTAL
                    }
            }
        contentView =
            TextView(this).apply {
                textSize = 18f
                setLineSpacing(0f, 1.35f)
                setTextIsSelectable(true)
            }
        container.addView(titleView)
        container.addView(progress)
        container.addView(contentView)
        return ScrollView(this).apply { addView(container) }
    }

    private fun NovelDocument.asDisplayText(): CharSequence =
        when (contentType) {
            NovelContentType.Html -> HtmlCompat.fromHtml(content, HtmlCompat.FROM_HTML_MODE_LEGACY)
            NovelContentType.Markdown, NovelContentType.PlainText -> content
        }

    private data class LoadedDocument(
        val title: String,
        val document: NovelDocument,
    )

    companion object {
        private const val EXTRA_MANGA_ID = "hayai.manga_id"
        private const val EXTRA_CHAPTER_ID = "hayai.chapter_id"

        fun newIntent(
            context: Context,
            mangaId: Long,
            chapterId: Long,
        ): Intent =
            Intent(context, NovelReaderActivity::class.java).apply {
                putExtra(EXTRA_MANGA_ID, mangaId)
                putExtra(EXTRA_CHAPTER_ID, chapterId)
            }
    }
}
