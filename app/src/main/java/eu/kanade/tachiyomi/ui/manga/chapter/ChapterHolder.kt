package eu.kanade.tachiyomi.ui.manga.chapter

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.res.ColorStateList
import android.view.View
import androidx.core.animation.doOnEnd
import androidx.core.animation.doOnCancel
import androidx.core.animation.doOnStart
import androidx.core.view.isVisible
import androidx.core.widget.TextViewCompat
import com.google.android.material.R as materialR
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.database.models.hideChapterTitle
import eu.kanade.tachiyomi.data.database.models.isNovel
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.databinding.ChaptersItemBinding
import eu.kanade.tachiyomi.domain.manga.models.Manga
import eu.kanade.tachiyomi.ui.manga.MangaDetailsAdapter
import eu.kanade.tachiyomi.util.chapter.ChapterUtil
import eu.kanade.tachiyomi.util.chapter.ChapterUtil.Companion.preferredChapterName
import eu.kanade.tachiyomi.util.isLocal
import eu.kanade.tachiyomi.util.system.dpToPx
import eu.kanade.tachiyomi.util.system.getResourceColor
import yokai.i18n.MR
import yokai.util.lang.getString
import android.R as AR

class ChapterHolder(
    view: View,
    private val adapter: MangaDetailsAdapter,
) : BaseChapterHolder(view, adapter) {

    private val binding = ChaptersItemBinding.bind(view)
    private var localSource = false
    private var boundContent: BindingContent? = null
    private var statusVisual: StatusVisual? = null
    private var swipeTutorialAnimator: AnimatorSet? = null

    private data class BindingContent(
        val itemSignature: Int,
        val selected: Boolean,
        val accentColor: Int?,
        val hideChapterTitle: Boolean,
        val showTranslation: Boolean,
    )

    private data class StatusVisual(
        val status: Download.State,
        val locked: Boolean,
        val progress: Int,
        val accentColor: Int?,
        val localSource: Boolean,
        val animated: Boolean,
    )

    init {
        binding.downloadButton.downloadButton.setOnLongClickListener {
            adapter.delegate.startDownloadRange(flexibleAdapterPosition)
            true
        }
        binding.translationButton.setOnClickListener {
            adapter.delegate.toggleChapterTranslation(flexibleAdapterPosition)
        }
        binding.translationButton.setOnLongClickListener {
            adapter.delegate.openTranslationSettings()
            true
        }
    }

    fun bind(item: ChapterItem, manga: Manga) {
        val progress = item.progress
        val content = BindingContent(
            itemSignature = item.bindingContentSignature(progress),
            selected = adapter.isSelected(flexibleAdapterPosition),
            accentColor = adapter.delegate.accentColor(),
            hideChapterTitle = manga.hideChapterTitle(adapter.preferences),
            showTranslation = adapter.delegate.showChapterTranslationButton(),
        )
        if (content == boundContent) {
            resetFrontView()
            return
        }
        boundContent = content

        val chapter = item.chapter
        val isLocked = item.isLocked
        itemView.transitionName = "details chapter ${chapter.id ?: 0L} transition"
        binding.chapterTitle.text =
            chapter.preferredChapterName(itemView.context, manga, adapter.preferences)

        val isNovel = manga.isNovel()
        binding.downloadButton.downloadButton.isVisible = !manga.isLocal() && !isLocked
        localSource = manga.isLocal()
        binding.translationButton.isVisible = content.showTranslation && isNovel && !isLocked
        bindTranslationButton(item)

        ChapterUtil.setTextViewForChapter(binding.chapterTitle, item, hideStatus = isLocked)

        val statuses = mutableListOf<String>()

        ChapterUtil.relativeDate(chapter)?.let { statuses.add(it) }

        val showPagesLeft = !chapter.read && chapter.last_page_read > 0 && !isLocked

        if (showPagesLeft) {
            statuses.add(
                if (isNovel) {
                    itemView.context.getString(
                        MR.strings.resume_progress_percent,
                        chapter.last_page_read,
                    )
                } else {
                    itemView.context.getString(
                        MR.strings.page_x_of_y,
                        chapter.last_page_read + 1,
                        chapter.pages_left + chapter.last_page_read,
                    )
                },
            )
        }

        if (chapter.scanlator?.isNotBlank() == true) {
            statuses.add(chapter.scanlator!!)
        }

        if (binding.frontView.translationX == 0f) {
            binding.read.setImageResource(
                if (item.read) R.drawable.ic_eye_off_24dp else R.drawable.ic_eye_24dp,
            )
            binding.bookmark.setImageResource(
                if (item.bookmark) R.drawable.ic_bookmark_off_24dp else R.drawable.ic_bookmark_24dp,
            )
        }
        ChapterUtil.setTextViewForChapter(
            binding.chapterScanlator,
            item,
            showBookmark = false,
            hideStatus = isLocked,
            isDetails = true,
        )
        binding.chapterScanlator.text = statuses.joinToString(" • ")

        val isSelected = content.selected
        // Data-drive the row highlight so it survives notifyDataSetChanged instead of relying on
        // imperative toggleActivation push-only updates that desync from the selection.
        itemView.isActivated = isSelected
        val status = when {
            isSelected -> Download.State.CHECKED
            else -> item.status
        }

        notifyStatus(status, item.isLocked, progress)
        resetFrontView()
        if (flexibleAdapterPosition == 1) {
            if (!adapter.hasShownSwipeTut.get()) showSlideAnimation()
        }
    }

    private fun showSlideAnimation() {
        if (swipeTutorialAnimator?.isRunning == true) return
        val slide = 100f.dpToPx
        val animatorSet = AnimatorSet()
        val anim1 = slideAnimation(0f, slide)
        anim1.startDelay = 1000
        anim1.doOnStart { binding.startView.isVisible = true }
        val anim2 = slideAnimation(slide, 0f).apply { startDelay = 500 }
        val anim3 = slideAnimation(0f, -slide).apply {
            doOnStart {
                binding.startView.isVisible = false
                binding.endView.isVisible = true
            }
        }
        val anim4 = slideAnimation(-slide, 0f).apply { startDelay = 750 }
        var cancelled = false
        animatorSet.playSequentially(anim1, anim2, anim3, anim4)
        animatorSet.doOnCancel { cancelled = true }
        animatorSet.doOnEnd {
            if (!cancelled) adapter.hasShownSwipeTut.set(true)
            swipeTutorialAnimator = null
        }
        swipeTutorialAnimator = animatorSet
        animatorSet.start()
    }

    private fun slideAnimation(from: Float, to: Float): ObjectAnimator {
        return ObjectAnimator.ofFloat(binding.frontView, View.TRANSLATION_X, from, to)
            .setDuration(300)
    }

    override fun getFrontView(): View {
        return binding.frontView
    }

    override fun getRearEndView(): View {
        return binding.endView
    }

    override fun getRearStartView(): View {
        return binding.startView
    }

    private fun resetFrontView() {
        if (binding.frontView.translationX != 0f) {
            itemView.post {
                androidx.transition.TransitionManager.endTransitions(adapter.recyclerView)
                adapter.notifyItemChanged(flexibleAdapterPosition)
            }
        }
    }

    fun notifyStatus(status: Download.State, locked: Boolean, progress: Int, animated: Boolean = false): Unit = with(binding.downloadButton.downloadButton) {
        val delegateAccent = adapter.delegate.accentColor()
        val visual = StatusVisual(status, locked, progress, delegateAccent, localSource, animated)
        if (visual == statusVisual) return@with
        statusVisual = visual
        if (delegateAccent != null) {
            binding.startView.backgroundTintList = ColorStateList.valueOf(delegateAccent)
            binding.bookmark.imageTintList = ColorStateList.valueOf(
                context.getResourceColor(AR.attr.textColorPrimaryInverse),
            )
            TextViewCompat.setCompoundDrawableTintList(
                binding.chapterTitle,
                ColorStateList.valueOf(delegateAccent),
            )
            accentColor = delegateAccent
        } else {
            // Cover-color theming off: fall back to plain M3 defaults, no leftover accent tint.
            val defaultAccent = context.getResourceColor(materialR.attr.colorSecondary)
            binding.startView.backgroundTintList = ColorStateList.valueOf(defaultAccent)
            binding.bookmark.imageTintList = ColorStateList.valueOf(
                context.getResourceColor(AR.attr.textColorPrimaryInverse),
            )
            TextViewCompat.setCompoundDrawableTintList(binding.chapterTitle, null)
            accentColor = defaultAccent
        }
        if (locked) {
            isVisible = false
            return
        }
        isVisible = !localSource
        setDownloadStatus(status, progress, animated)
    }

    fun recycle() {
        swipeTutorialAnimator?.cancel()
        swipeTutorialAnimator = null
        binding.frontView.animate().cancel()
        binding.frontView.translationX = 0f
        binding.startView.isVisible = false
        binding.endView.isVisible = false
        boundContent = null
        statusVisual = null
    }

    private fun bindTranslationButton(item: ChapterItem) {
        val button = binding.translationButton
        if (!button.isVisible) return

        val context = button.context
        val icon = when {
            item.isTranslating -> R.drawable.ic_sync_24dp
            item.hasCachedTranslation -> R.drawable.ic_check_circle_24dp
            else -> R.drawable.ic_translate_24dp
        }
        val label = when {
            item.isTranslating -> MR.strings.chapter_translation_translating
            item.hasCachedTranslation -> MR.strings.chapter_translation_clear
            else -> MR.strings.chapter_translation_translate
        }
        val tint = when {
            item.hasCachedTranslation -> adapter.delegate.accentColor()
                ?: context.getResourceColor(materialR.attr.colorSecondary)
            else -> context.getResourceColor(materialR.attr.colorSecondary)
        }

        button.setImageResource(icon)
        button.contentDescription = context.getString(label)
        button.imageTintList = ColorStateList.valueOf(tint)
        button.isEnabled = !item.isTranslating
        button.alpha = if (item.isTranslating) 0.6f else 1f
    }
}
