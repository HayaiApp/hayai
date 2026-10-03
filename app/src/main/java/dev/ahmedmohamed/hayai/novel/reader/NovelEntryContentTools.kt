package dev.ahmedmohamed.hayai.novel.reader

import android.app.Activity
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.widget.LinearLayout
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.button.MaterialButton
import dev.ahmedmohamed.hayai.novel.search.NovelChapterTextLibrary
import dev.ahmedmohamed.hayai.novel.search.NovelChapterTextSearch
import dev.ahmedmohamed.hayai.novel.search.NovelTextSearchOptions
import dev.ahmedmohamed.hayai.novel.error.NovelFailure
import dev.ahmedmohamed.hayai.novel.error.novelFailureMessage
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.database.DatabaseHelper
import eu.kanade.tachiyomi.data.database.models.Chapter
import eu.kanade.tachiyomi.data.database.models.Manga
import eu.kanade.tachiyomi.source.SourceManager
import eu.kanade.tachiyomi.util.system.getResourceColor
import java.text.NumberFormat
import java.util.regex.PatternSyntaxException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

object NovelEntryContentTools {
    fun showSearch(activity: Activity, manga: Manga, openChapter: (Chapter) -> Unit) = showSearch(activity, manga, openChapter, "", NovelTextSearchOptions())

    private fun showSearch(activity: Activity, manga: Manga, openChapter: (Chapter) -> Unit, initialQuery: String, options: NovelTextSearchOptions) {
        val scope = (activity as? LifecycleOwner)?.lifecycleScope ?: return
        val sheet = NovelReaderContentSheet(activity, activity.getString(R.string.hayai_novel_entry_text_search))
        val query = sheet.input(R.string.hayai_novel_entry_search_query, initialQuery)
        val regex = sheet.option(R.string.hayai_novel_entry_search_regex, options.regex)
        val caseSensitive = sheet.option(R.string.hayai_novel_entry_search_case_sensitive, options.caseSensitive)
        val wholeWord = sheet.option(R.string.hayai_novel_entry_search_whole_word, options.wholeWord)
        sheet.text(activity.getString(R.string.hayai_novel_entry_offline_only))
        var searchJob: Job? = null
        var generation = 0
        var pendingGeneration: Int? = null
        var searchButton: MaterialButton? = null
        fun invalidateSearch() {
            generation++
            pendingGeneration = null
            searchJob?.cancel()
            searchButton?.apply { isEnabled = true; setText(R.string.hayai_novel_entry_search_run) }
        }
        sheet.setOnDismissListener { invalidateSearch() }
        query.doAfterTextChanged { invalidateSearch() }
        listOf(regex, caseSensitive, wholeWord).forEach { option -> option.setOnCheckedChangeListener { _, _ -> invalidateSearch() } }
        sheet.action(R.string.hayai_novel_entry_search_run, primary = true) { button ->
            searchButton = button
            val text = query.text?.toString().orEmpty().trim()
            if (text.isBlank()) {
                query.error = activity.getString(R.string.hayai_novel_entry_search_query_required)
                return@action
            }
            val selected = NovelTextSearchOptions(regex.isChecked, caseSensitive.isChecked, wholeWord.isChecked)
            val pattern = try {
                NovelChapterTextSearch.pattern(text, selected)
            } catch (_: PatternSyntaxException) {
                query.error = activity.getString(R.string.hayai_novel_entry_search_invalid_regex)
                return@action
            } catch (error: NovelFailure) {
                query.error = activity.novelFailureMessage(error, R.string.hayai_novel_entry_search_invalid_regex)
                return@action
            }
            query.error = null
            searchJob?.cancel()
            val requestGeneration = ++generation
            pendingGeneration = requestGeneration
            button.isEnabled = false
            button.setText(R.string.hayai_novel_entry_scanning)
            searchJob = scope.launch {
                try {
                    val result = withContext(Dispatchers.IO) { library(activity, manga).search(pattern) }
                    if (!sheet.isShowing || requestGeneration != generation) return@launch
                    sheet.back { sheet.dismiss(); showSearch(activity, manga, openChapter, text, selected) }
                    val number = NumberFormat.getIntegerInstance()
                    val color = activity.getResourceColor(R.attr.colorPrimary)
                    sheet.form.removeAllViews()
                    sheet.items(result.matches, R.string.hayai_novel_entry_search_no_results, { it.chapter.name }, { item ->
                        SpannableStringBuilder(activity.getString(R.string.hayai_novel_entry_search_matches, number.format(item.matches.count))).apply {
                            item.matches.snippets.forEach { snippet ->
                                append("\n")
                                val offset = length
                                append(snippet.text)
                                setSpan(BackgroundColorSpan((color and 0x00FFFFFF) or 0x33000000), offset + snippet.start, offset + snippet.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                            }
                        }
                    }) { item -> sheet.dismiss(); openChapter(item.chapter) }
                    sheet.action(R.string.hayai_novel_entry_search_coverage, primary = true) {
                        val coverage = NovelReaderContentSheet(activity, activity.getString(R.string.hayai_novel_entry_search_coverage))
                        coverage.text(activity.getString(R.string.hayai_novel_entry_coverage, number.format(result.searched), number.format(result.missing), number.format(result.unreadable)))
                        coverage.show()
                    }
                    button.setText(R.string.hayai_novel_entry_search_again)
                    button.setOnClickListener { sheet.dismiss(); showSearch(activity, manga, openChapter, text, selected) }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    if (sheet.isShowing && requestGeneration == generation) query.error = activity.getString(R.string.hayai_novel_entry_scan_failed)
                } finally {
                    if (sheet.isShowing && pendingGeneration == requestGeneration) {
                        pendingGeneration = null
                        button.isEnabled = true
                        if (requestGeneration != generation || button.text == activity.getString(R.string.hayai_novel_entry_scanning)) button.setText(R.string.hayai_novel_entry_search_run)
                    }
                }
            }
        }
        sheet.show()
    }

    fun showStatistics(activity: Activity, manga: Manga) {
        val scope = (activity as? LifecycleOwner)?.lifecycleScope ?: return
        val sheet = NovelReaderContentSheet(activity, activity.getString(R.string.hayai_novel_entry_word_statistics))
        sheet.text(activity.getString(R.string.hayai_novel_entry_scanning))
        val job = scope.launch {
            try {
                val stats = withContext(Dispatchers.IO) { library(activity, manga).statistics() }
                if (!sheet.isShowing) return@launch
                val number = NumberFormat.getIntegerInstance()
                sheet.form.removeAllViews()
                sheet.text(manga.title)
                sheet.text(activity.getString(R.string.hayai_novel_entry_word_totals, number.format(stats.totalWords), number.format(stats.averageWords), number.format(stats.countedChapters), number.format(stats.totalChapters)))
                if (stats.countedChapters > 0) sheet.text(activity.getString(R.string.hayai_novel_entry_density, stats.tier))
                sheet.text(activity.getString(R.string.hayai_novel_entry_coverage, number.format(stats.countedChapters), number.format(stats.notDownloadedChapters), number.format(stats.unreadableChapters)))
                sheet.text(activity.getString(R.string.hayai_novel_entry_offline_only))
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (sheet.isShowing) {
                    sheet.form.removeAllViews()
                    sheet.text(activity.getString(R.string.hayai_novel_entry_scan_failed))
                }
            }
        }
        sheet.setOnDismissListener { job.cancel() }
        sheet.show()
    }

    private fun library(activity: Activity, manga: Manga) = NovelChapterTextLibrary(activity.applicationContext, manga, Injekt.get<DatabaseHelper>(), Injekt.get<SourceManager>())

    private fun NovelReaderContentSheet.option(label: Int, selected: Boolean): MaterialSwitch = MaterialSwitch(context).apply {
        setText(label)
        isChecked = selected
        form.addView(this, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    }
}
