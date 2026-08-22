package dev.ahmedmohamed.hayai.source.metadata

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.database.DatabaseHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class SourceMetadataActivity : AppCompatActivity() {
    private val database: DatabaseHelper by lazy { Injekt.get() }
    private val metadata: SourceMetadataProviderRegistry by lazy { Injekt.get() }
    private lateinit var progress: ProgressBar
    private lateinit var message: TextView
    private lateinit var content: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mangaId = intent.getLongExtra(EXTRA_MANGA_ID, -1L)
        val manga = database.getManga(mangaId).executeAsBlocking() ?: run {
            finish()
            return
        }
        title = getString(R.string.hayai_source_metadata_title)
        setContentView(createContent())
        load(manga)
    }

    private fun createContent(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(
            MaterialToolbar(context).apply {
                title = getString(R.string.hayai_source_metadata_title)
                setNavigationIcon(R.drawable.ic_arrow_back_24dp)
                setNavigationOnClickListener { finish() }
            },
            matchWrap(),
        )
        progress = ProgressBar(context).apply { isIndeterminate = true }
        addView(progress, LinearLayout.LayoutParams(dp(48), dp(48)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        message = TextView(context).apply {
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(24), dp(24), dp(24))
            isVisible = false
        }
        addView(message, matchWrap())
        content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        addView(ScrollView(context).apply { addView(content) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    }

    private fun load(manga: eu.kanade.tachiyomi.data.database.models.Manga) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { metadata.load(manga) } }
            progress.isVisible = false
            result.onSuccess { SourceMetadataUi.renderFull(content, it) }
                .onFailure {
                    message.setText(R.string.hayai_source_metadata_failed)
                    message.isVisible = true
                }
        }
    }

    private fun matchWrap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val EXTRA_MANGA_ID = "manga_id"

        fun newIntent(context: Context, mangaId: Long): Intent =
            Intent(context, SourceMetadataActivity::class.java).putExtra(EXTRA_MANGA_ID, mangaId)
    }
}
