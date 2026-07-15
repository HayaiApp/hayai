package eu.kanade.tachiyomi.ui.recents

import android.animation.LayoutTransition
import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.graphics.ColorUtils
import androidx.core.view.children
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePaddingRelative
import androidx.transition.TransitionManager
import androidx.transition.TransitionSet
import com.google.android.material.R as materialR
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.database.models.Chapter
import eu.kanade.tachiyomi.data.database.models.ChapterHistory
import eu.kanade.tachiyomi.data.database.models.isNovel
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.databinding.RecentMangaItemBinding
import eu.kanade.tachiyomi.databinding.RecentSubChapterItemBinding
import eu.kanade.tachiyomi.ui.download.DownloadButton
import eu.kanade.tachiyomi.ui.manga.chapter.BaseChapterHolder
import eu.kanade.tachiyomi.util.chapter.ChapterUtil
import eu.kanade.tachiyomi.util.chapter.ChapterUtil.Companion.preferredChapterName
import eu.kanade.tachiyomi.util.isLocal
import eu.kanade.tachiyomi.util.system.contextCompatColor
import eu.kanade.tachiyomi.util.system.dpToPx
import eu.kanade.tachiyomi.util.system.getResourceColor
import eu.kanade.tachiyomi.util.system.timeSpanFromNow
import eu.kanade.tachiyomi.util.view.setAnimVectorCompat
import eu.kanade.tachiyomi.util.view.setCards
import java.util.Date
import java.util.concurrent.TimeUnit
import yokai.i18n.MR
import yokai.util.coil.loadManga
import yokai.util.lang.getString
import android.R as AR

class RecentMangaHolder(
    view: View,
    val adapter: RecentMangaAdapter,
) : BaseChapterHolder(view, adapter) {

    private val binding = RecentMangaItemBinding.bind(view)
    var chapterId: Long? = null

    private val isUpdates get() = adapter.viewType.isUpdates
    private val isSmallUpdates get() = isUpdates && !adapter.showUpdatedTime

    // Layout-param cascade in bind() touches ~12 constraint/dimension properties via
    // updateLayoutParams { ... }, each firing requestLayout() unconditionally. The values
    // only depend on (isSmallUpdates, freeformCovers), which are constant for all rows of
    // a given tab. Cache the last-applied pair; skip the cascade when unchanged.
    private var lastSmallUpdates: Boolean? = null
    private var lastFreeformCovers: Boolean? = null

    /** Tinted overlay for selected rows; mirrors LibraryHolder's secondary-alpha treatment. */
    private val selectedBackground by lazy {
        val base = itemView.context.getResourceColor(materialR.attr.colorSecondary)
        ColorDrawable(ColorUtils.setAlphaComponent(base, 75))
    }

    init {
        binding.cardLayout.setOnClickListener {
            // While in multi-select, treat cover taps as selection toggles
            // instead of opening MangaDetailsController.
            if (adapter.isInSelectionMode) {
                adapter.delegate.onItemSelectionToggled(flexibleAdapterPosition)
            } else {
                adapter.delegate.onCoverClick(flexibleAdapterPosition)
            }
        }
        binding.removeHistory.setOnClickListener { adapter.delegate.onRemoveHistoryClicked(flexibleAdapterPosition) }
        binding.showMoreChapters.setOnClickListener { _ ->
            val moreVisible = !binding.moreChaptersLayout.isVisible
            // Lazy inflate: if expanding and we haven't populated rows yet, do it now.
            if (moreVisible) {
                val pending = adapter.getItem(flexibleAdapterPosition) as? RecentMangaItem
                if (pending != null) bindExtraChapters(pending)
            }
            binding.moreChaptersLayout.isVisible = moreVisible
            adapter.delegate.updateExpandedExtraChapters(flexibleAdapterPosition, moreVisible)
            binding.showMoreChapters.setAnimVectorCompat(
                if (moreVisible) {
                    R.drawable.anim_expand_more_to_less
                } else {
                    R.drawable.anim_expand_less_to_more
                },
            )
            if (moreVisible) {
                binding.moreChaptersLayout.children.forEach { view ->
                    RecentSubChapterItemBinding.bind(view).updateDivider()
                }
            }
            if (isUpdates && binding.moreChaptersLayout.children.any { view ->
                !RecentSubChapterItemBinding.bind(view).subtitle.text.isNullOrBlank()
            }
            ) {
                showScanlatorInBody(moreVisible)
            } else {
                addMoreUpdatesText(!moreVisible)
            }
            if (adapter.viewType.isHistory) {
                readLastText(!moreVisible).takeIf { it.isNotEmpty() }
                    ?.let { binding.body.text = it }
            }
            binding.endView.updateLayoutParams<ViewGroup.LayoutParams> {
                height = binding.mainView.height
            }
            val transition = TransitionSet()
                .addTransition(androidx.transition.ChangeBounds())
                .addTransition(androidx.transition.Slide())
            transition.duration =
                itemView.resources.getInteger(AR.integer.config_shortAnimTime).toLong()
            TransitionManager.beginDelayedTransition(adapter.recyclerView, transition)
        }
        updateCards()
        binding.frontView.layoutTransition?.enableTransitionType(LayoutTransition.APPEARING)
    }

    fun updateCards() {
        setCards(adapter.showOutline, binding.card, null)
    }

    @SuppressLint("ClickableViewAccessibility")
    fun bind(item: RecentMangaItem) {
        val showDLs = adapter.showDownloads
        binding.mainView.transitionName = "recents chapter $bindingAdapterPosition transition"
        val showRemoveHistory = adapter.showRemoveHistory
        val showTitleFirst = adapter.showTitleFirst
        binding.downloadButton.downloadButton.isVisible = when (showDLs) {
            RecentMangaAdapter.ShowRecentsDLs.None -> false
            RecentMangaAdapter.ShowRecentsDLs.OnlyUnread, RecentMangaAdapter.ShowRecentsDLs.UnreadOrDownloaded -> !item.chapter.read
            RecentMangaAdapter.ShowRecentsDLs.OnlyDownloaded -> true
            RecentMangaAdapter.ShowRecentsDLs.All -> true
        } && !item.mch.manga.isLocal()

        val small = isSmallUpdates
        val freeformCovers = !small && !adapter.uniformCovers
        if (small != lastSmallUpdates || freeformCovers != lastFreeformCovers) {
            binding.cardLayout.updateLayoutParams<ConstraintLayout.LayoutParams> {
                height = (if (small) 40 else 80).dpToPx
                width = (if (small) 40 else 60).dpToPx
            }
            listOf(binding.title, binding.subtitle).forEach {
                it.updateLayoutParams<ConstraintLayout.LayoutParams> {
                    if (small) {
                        if (it == binding.title) topMargin = 5.dpToPx
                        endToStart = R.id.button_layout
                        endToEnd = -1
                    } else {
                        if (it == binding.title) topMargin = 2.dpToPx
                        endToStart = -1
                        endToEnd = R.id.main_view
                    }
                }
            }
            binding.buttonLayout.updateLayoutParams<ConstraintLayout.LayoutParams> {
                if (small) {
                    topToBottom = -1
                    topToTop = R.id.card_layout
                    bottomToBottom = R.id.card_layout
                    topMargin = 4.dpToPx
                } else {
                    topToTop = -1
                    topToBottom = R.id.subtitle
                    bottomToBottom = R.id.main_view
                    topMargin = 0
                }
            }
            with(binding.coverThumbnail) {
                adjustViewBounds = freeformCovers
                scaleType = if (!freeformCovers) ImageView.ScaleType.CENTER_CROP else ImageView.ScaleType.FIT_CENTER
            }
            listOf(binding.coverThumbnail, binding.card).forEach {
                it.updateLayoutParams<ViewGroup.LayoutParams> {
                    width = if (!freeformCovers) {
                        ViewGroup.LayoutParams.MATCH_PARENT
                    } else {
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    }
                }
            }
            lastSmallUpdates = small
            lastFreeformCovers = freeformCovers
        }

        binding.removeHistory.isVisible = item.mch.history.id != null && showRemoveHistory
        val context = itemView.context
        val chapterName =
            item.chapter.preferredChapterName(context, item.mch.manga, adapter.preferences)

        listOf(binding.title, binding.subtitle).forEach {
            it.apply {
                setCompoundDrawablesRelative(null, null, null, null)
                translationX = 0f
                text = if (!showTitleFirst.xor(it === binding.subtitle)) {
                    ChapterUtil.setTextViewForChapter(this, item)
                    chapterName
                } else {
                    setTextColor(ChapterUtil.readColor(context, item))
                    item.mch.manga.title
                }
            }
        }
        if (binding.frontView.translationX == 0f) {
            binding.read.setImageResource(
                if (item.read) R.drawable.ic_eye_off_24dp else R.drawable.ic_eye_24dp,
            )
        }

        binding.showMoreChapters.isVisible = item.mch.extraChapters.isNotEmpty() &&
            !adapter.delegate.alwaysExpanded()
        binding.moreChaptersLayout.isVisible = item.mch.extraChapters.isNotEmpty() &&
            adapter.delegate.areExtraChaptersExpanded(flexibleAdapterPosition)
        val moreVisible = binding.moreChaptersLayout.isVisible

        binding.body.isVisible = !isSmallUpdates
        binding.body.text = when {
            item.mch.chapter.id == null -> context.timeSpanFromNow(MR.strings.added_, item.mch.manga.date_added)
            isSmallUpdates -> ""
            item.mch.history.id == null -> {
                if (isUpdates) {
                    if (adapter.sortByFetched) {
                        context.timeSpanFromNow(MR.strings.fetched_, item.chapter.date_fetch)
                    } else {
                        context.timeSpanFromNow(MR.strings.updated_, item.chapter.date_upload)
                    }
                } else {
                    context.timeSpanFromNow(MR.strings.fetched_, item.chapter.date_fetch) + "\n" +
                        context.timeSpanFromNow(MR.strings.updated_, item.chapter.date_upload)
                }
            }
            item.chapter.id != item.mch.chapter.id -> readLastText(!moreVisible)
            item.chapter.last_page_read > 0 && !item.chapter.read -> {
                val progressText = if (item.mch.manga.isNovel()) {
                    itemView.context.getString(
                        MR.strings.resume_progress_percent,
                        item.chapter.last_page_read,
                    )
                } else {
                    itemView.context.getString(
                        MR.strings.page_x_of_y,
                        item.chapter.last_page_read + 1,
                        item.chapter.pages_left + item.chapter.last_page_read,
                    )
                }
                context.timeSpanFromNow(MR.strings.read_, item.mch.history.last_read) + "\n" + progressText
            }
            else -> context.timeSpanFromNow(MR.strings.read_, item.mch.history.last_read)
        }
        if ((context as? Activity)?.isDestroyed != true) {
            binding.coverThumbnail.loadManga(item.mch.manga)
        }
        if (!item.mch.manga.isLocal()) {
            notifyStatus(
                if (adapter.isSelected(flexibleAdapterPosition)) Download.State.CHECKED else item.status,
                item.progress,
                item.chapter.read,
            )
        }
        updateSelectedBackground()

        binding.showMoreChapters.setImageResource(
            if (moreVisible) {
                R.drawable.ic_expand_less_24dp
            } else {
                R.drawable.ic_expand_more_24dp
            },
        )
        // Sub-chapter rows are expensive (RecentSubChapterItemBinding.inflate is ~10ms;
        // a row can have up to 10). Only do the work when the user has expanded the row
        // — otherwise the inflate happens on demand from the showMoreChapters click.
        // Drives the 138–191ms binds for recent_manga_item (0x7F0D03A1) we saw in perfetto.
        if (moreVisible) {
            bindExtraChapters(item)
        } else {
            // Hide any rows left over from a previous bind without inflating new ones.
            for (i in 0 until binding.moreChaptersLayout.childCount) {
                binding.moreChaptersLayout.getChildAt(i).isVisible = false
            }
            if (binding.moreChaptersLayout.childCount == 0) {
                chapterId = null
            }
            // Show "and N more" text in the body when rows are hidden but exist.
            if (item.mch.extraChapters.isNotEmpty()) addMoreUpdatesText(true, item)
        }
        listOf(binding.mainView, binding.downloadButton.root, binding.showMoreChapters, binding.cardLayout).forEach {
            it.setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_DOWN) {
                    binding.endView.translationY = binding.mainView.y
                    binding.endView.updateLayoutParams<ViewGroup.LayoutParams> {
                        height = binding.mainView.height
                    }
                    binding.read.setImageResource(
                        if (item.read) R.drawable.ic_eye_off_24dp else R.drawable.ic_eye_24dp,
                    )
                    chapterId = null
                }
                false
            }
        }
    }

    // Bumped each bind so async inflate callbacks can detect when their target is stale.
    private var bindGeneration = 0

    private fun bindExtraChapters(item: RecentMangaItem) {
        val context = itemView.context
        val targetChapters = item.mch.extraChapters.shorterList()
        val needed = targetChapters.size
        val currentChildren = binding.moreChaptersLayout.childCount
        val deficit = (needed - currentChildren).coerceAtLeast(0)
        val generation = ++bindGeneration

        // Configure rows we already have (no inflate — just rebind data).
        var hasSameChapter = false
        val syncCount = currentChildren.coerceAtMost(needed)
        for (i in 0 until syncCount) {
            val child = binding.moreChaptersLayout.getChildAt(i)
            child.isVisible = true
            val subBinding = RecentSubChapterItemBinding.bind(child)
            subBinding.configureView(targetChapters[i], item)
            if (isUpdates && !subBinding.subtitle.text.isNullOrBlank() && !hasSameChapter) {
                showScanlatorInBody(true, item)
                hasSameChapter = true
            }
        }

        // Hide surplus from previous binds.
        for (i in needed until currentChildren) {
            binding.moreChaptersLayout.getChildAt(i).isVisible = false
        }

        if (needed == 0) {
            if (currentChildren == 0) chapterId = null
            return
        }

        // Strip "and N more" since at least some rows are about to show.
        addMoreUpdatesText(false, item)

        if (deficit == 0) return

        // Inflate missing rows synchronously on the UI thread. AsyncLayoutInflater is
        // unsafe for recent_sub_chapter_item because the nested DownloadButton owns an
        // Animator, and AnimatorInflater throws "Animators may only be run on Looper
        // threads" off the main thread — the row would silently fail to render, making
        // Recents look empty after a tab swap. RecyclerView pools these holders across
        // every Recents re-entry while its persistent root view remains alive, so the inflate cost is paid
        // once on first bind and never again.
        val layoutInflater = LayoutInflater.from(context)
        for (offset in 0 until deficit) {
            val targetIdx = currentChildren + offset
            val chapter = targetChapters.getOrNull(targetIdx) ?: break
            val view = layoutInflater.inflate(R.layout.recent_sub_chapter_item, binding.moreChaptersLayout, false)
            binding.moreChaptersLayout.addView(view)
            val subBinding = RecentSubChapterItemBinding.bind(view)
            subBinding.configureView(chapter, item)
            if (isUpdates && !subBinding.subtitle.text.isNullOrBlank()) {
                showScanlatorInBody(true, item)
            }
        }
    }

    private fun addMoreUpdatesText(add: Boolean, originalItem: RecentMangaItem? = null) {
        val item = originalItem ?: adapter.getItem(bindingAdapterPosition) as? RecentMangaItem ?: return
        val originalText = binding.body.text.toString()
        val andMoreText = itemView.context.getString(
            MR.plurals.notification_and_n_more,
            (item.mch.extraChapters.size),
            (item.mch.extraChapters.size),
        )
        if (add && item.mch.extraChapters.isNotEmpty() && isUpdates &&
            !isSmallUpdates && !originalText.contains(andMoreText)
        ) {
            val text = "${originalText.substringBefore("\n")}\n$andMoreText"
            binding.body.text = text
        } else if (!add && originalText.contains(andMoreText)) {
            binding.body.text = originalText.removeSuffix("\n$andMoreText")
        }
    }

    private fun readLastText(show: Boolean, originalItem: RecentMangaItem? = null): String {
        val item = originalItem ?: adapter.getItem(bindingAdapterPosition) as? RecentMangaItem ?: return ""
        val notValidNum = item.mch.chapter.chapter_number <= 0
        return if (item.chapter.id != item.mch.chapter.id) {
            if (show) {
                itemView.context.timeSpanFromNow(MR.strings.read_, item.mch.history.last_read) + "\n"
            } else {
                ""
            } + itemView.context.getString(
                if (notValidNum) MR.strings.last_read_ else MR.strings.last_read_chapter_,
                if (notValidNum) item.mch.chapter.name else adapter.decimalFormat.format(item.mch.chapter.chapter_number),
            )
        } else { "" }
    }

    private fun showScanlatorInBody(add: Boolean, originalItem: RecentMangaItem? = null) {
        val item = originalItem ?: adapter.getItem(bindingAdapterPosition) as? RecentMangaItem ?: return
        val originalText = binding.body.text.toString()
        binding.body.maxLines = 2
        val scanlator = item.chapter.scanlator ?: return
        if (add) {
            if (isSmallUpdates) {
                binding.body.maxLines = 1
                binding.body.text = item.chapter.scanlator
                binding.body.isVisible = true
            } else if (!originalText.contains(scanlator)) {
                val text = "${originalText.substringBefore("\n")}\n$scanlator"
                binding.body.text = text
            }
        } else {
            if (isSmallUpdates) {
                binding.body.isVisible = false
            } else {
                binding.body.text = originalText.removeSuffix("\n$scanlator")
                addMoreUpdatesText(true, item)
            }
        }
    }

    private fun <T> List<T>.shorterList(): List<T?> =
        if (size > 21) take(10) + null + takeLast(10) else this

    @SuppressLint("ClickableViewAccessibility")
    private fun RecentSubChapterItemBinding.configureBlankView(count: Int) {
        val context = itemView.context
        title.text =
            context.getString(MR.plurals.notification_and_n_more, count, count)
        downloadButton.root.isVisible = false
        downloadButton.root.tag = null
        title.textSize = 13f
        title.setTextColor(context.contextCompatColor(R.color.read_chapter))
        textLayout.updateLayoutParams<ConstraintLayout.LayoutParams> {
            matchConstraintMinHeight = 16.dpToPx
        }
        root.tag = "sub ${-1L}"
        root.setOnLongClickListener { false }
        root.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                chapterId = -1L
            }
            false
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun RecentSubChapterItemBinding.configureView(chapter: ChapterHistory?, item: RecentMangaItem) {
        if (chapter?.id == null) {
            configureBlankView(item.mch.extraChapters.size - 20)
            return
        }
        textLayout.updateLayoutParams<ConstraintLayout.LayoutParams> {
            matchConstraintMinHeight = 48.dpToPx
        }
        val context = itemView.context
        val showDLs = adapter.showDownloads
        title.text = chapter.preferredChapterName(context, item.mch.manga, adapter.preferences)
        ChapterUtil.setTextViewForChapter(title, chapter)
        val notReadYet = item.chapter.id != item.mch.chapter.id && item.mch.history.id != null
        subtitle.text = chapter.history?.let { history ->
            context.timeSpanFromNow(MR.strings.read_, history.last_read)
                .takeIf {
                    Date().time - history.last_read < TimeUnit.DAYS.toMillis(1) || notReadYet ||
                        adapter.dateFormat.run {
                            format(history.last_read) != format(item.mch.history.last_read)
                        }
                }
        } ?: ""
        if (isUpdates && chapter.isRecognizedNumber &&
            chapter.chapter_number == item.chapter.chapter_number &&
            !chapter.scanlator.isNullOrBlank()
        ) {
            subtitle.text = chapter.scanlator
        }
        subtitle.isVisible = subtitle.text.isNotBlank()
        title.textSize = (if (subtitle.isVisible) 14f else 14.5f)
        root.setOnClickListener {
            adapter.delegate.onSubChapterClicked(
                bindingAdapterPosition,
                chapter,
                it,
            )
        }
        root.setOnLongClickListener {
            adapter.delegate.onItemLongClick(bindingAdapterPosition, chapter)
        }
        listOf(root, downloadButton.root).forEach {
            it.setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_DOWN) {
                    binding.read.setImageResource(
                        if (chapter.read) R.drawable.ic_eye_off_24dp else R.drawable.ic_eye_24dp,
                    )
                    binding.endView.translationY = binding.moreChaptersLayout.y + root.y
                    binding.endView.updateLayoutParams<ViewGroup.LayoutParams> {
                        height = root.height
                    }
                    chapterId = chapter.id
                }
                false
            }
        }
        textLayout.updatePaddingRelative(start = if (isSmallUpdates) 64.dpToPx else 84.dpToPx)
        updateDivider()
        root.transitionName = "recents sub chapter ${chapter.id ?: 0L} transition"
        root.tag = "sub ${chapter.id}"
        downloadButton.root.tag = chapter.id
        val downloadInfo =
            item.downloadInfo.find { it.chapterId == chapter.id } ?: return
        downloadButton.downloadButton.setOnClickListener {
            downloadOrRemoveMenu(it, chapter, downloadInfo.status)
        }
        downloadButton.downloadButton.isVisible = when (showDLs) {
            RecentMangaAdapter.ShowRecentsDLs.None -> false
            RecentMangaAdapter.ShowRecentsDLs.OnlyUnread, RecentMangaAdapter.ShowRecentsDLs.UnreadOrDownloaded -> !chapter.read
            RecentMangaAdapter.ShowRecentsDLs.OnlyDownloaded -> true
            RecentMangaAdapter.ShowRecentsDLs.All -> true
        } && !item.mch.manga.isLocal()
        notifySubStatus(
            chapter,
            if (adapter.isSelected(flexibleAdapterPosition)) {
                Download.State.CHECKED
            } else {
                downloadInfo.status
            },
            downloadInfo.progress,
            chapter.read,
        )
    }

    private fun RecentSubChapterItemBinding.updateDivider() {
        divider.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            marginStart = if (isSmallUpdates) 64.dpToPx else 84.dpToPx
        }
    }

    override fun onLongClick(view: View?): Boolean {
        super.onLongClick(view)
        val item = adapter.getItem(flexibleAdapterPosition) as? RecentMangaItem ?: return false
        // Within History/Updates the long-press has to be "consumed" so the
        // click listener doesn't also fire — otherwise the user would enter
        // selection mode AND open the reader in the same gesture.
        return adapter.selectionEnabled || item.mch.history.id != null
    }

    /**
     * Tint the row's foreground while the holder represents a selected entry.
     * Uses the same alpha-secondary tint as [LibraryHolder] for visual parity
     * with the library multi-select UI.
     */
    fun updateSelectedBackground() {
        binding.mainView.foreground = if (adapter.isSelected(flexibleAdapterPosition)) {
            selectedBackground
        } else {
            ColorDrawable(Color.TRANSPARENT)
        }
    }

    fun notifyStatus(status: Download.State, progress: Int, isRead: Boolean, animated: Boolean = false) {
        binding.downloadButton.downloadButton.setDownloadStatus(status, progress, animated)
        val isChapterRead =
            if (adapter.showDownloads == RecentMangaAdapter.ShowRecentsDLs.UnreadOrDownloaded) isRead else true
        binding.downloadButton.downloadButton.isVisible =
            when (adapter.showDownloads) {
                RecentMangaAdapter.ShowRecentsDLs.UnreadOrDownloaded,
                RecentMangaAdapter.ShowRecentsDLs.OnlyDownloaded,
                ->
                    status !in Download.State.CHECKED..Download.State.NOT_DOWNLOADED || !isChapterRead
                else -> binding.downloadButton.downloadButton.isVisible
            }
    }

    fun notifySubStatus(chapter: Chapter, status: Download.State, progress: Int, isRead: Boolean, animated: Boolean = false) {
        val downloadButton = binding.moreChaptersLayout.findViewWithTag<DownloadButton>(chapter.id) ?: return
        downloadButton.setDownloadStatus(status, progress, animated)
        val isChapterRead =
            if (adapter.showDownloads == RecentMangaAdapter.ShowRecentsDLs.UnreadOrDownloaded) isRead else true
        downloadButton.isVisible =
            when (adapter.showDownloads) {
                RecentMangaAdapter.ShowRecentsDLs.UnreadOrDownloaded,
                RecentMangaAdapter.ShowRecentsDLs.OnlyDownloaded,
                ->
                    status !in Download.State.CHECKED..Download.State.NOT_DOWNLOADED || !isChapterRead
                else -> downloadButton.isVisible
            }
    }

    override fun getFrontView(): View {
        return if (chapterId == null) { binding.mainView } else {
            binding.moreChaptersLayout.children.find { it.tag == "sub $chapterId" }
                ?: binding.mainView
        }
    }

    override fun getRearEndView(): View? {
        return if (chapterId == -1L) null else binding.endView
    }
}
