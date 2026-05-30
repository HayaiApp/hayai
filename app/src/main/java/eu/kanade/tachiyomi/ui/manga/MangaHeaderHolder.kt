package eu.kanade.tachiyomi.ui.manga

import android.animation.AnimatorInflater
import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.drawable.toDrawable
import androidx.core.text.buildSpannedString
import androidx.core.text.scale
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.widget.TextViewCompat
import androidx.transition.TransitionSet
import androidx.vectordrawable.graphics.drawable.AnimatedVectorDrawableCompat
import coil3.asDrawable
import coil3.request.CachePolicy
import coil3.request.error
import coil3.request.placeholder
import com.google.android.material.button.MaterialButton
import com.google.android.material.R as materialR
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.database.models.seriesType
import eu.kanade.tachiyomi.databinding.ChapterHeaderItemBinding
import eu.kanade.tachiyomi.databinding.MangaHeaderItemBinding
import eu.kanade.tachiyomi.domain.manga.models.Manga
import eu.kanade.tachiyomi.source.SourceManager
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.nameBasedOnEnabledLanguages
import eu.kanade.tachiyomi.ui.base.holder.BaseFlexibleViewHolder
import eu.kanade.tachiyomi.util.isLocal
import eu.kanade.tachiyomi.util.system.timeSpanFromNow
import eu.kanade.tachiyomi.util.lang.toNormalized
import eu.kanade.tachiyomi.util.system.getResourceColor
import eu.kanade.tachiyomi.util.system.isInNightMode
import eu.kanade.tachiyomi.util.system.isLTR
import eu.kanade.tachiyomi.util.view.resetStrokeColor
import io.noties.markwon.Markwon
import io.noties.markwon.SoftBreakAddsNewLinePlugin
import android.text.method.LinkMovementMethod
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.remember
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import yokai.i18n.MR
import yokai.util.coil.loadManga
import yokai.util.lang.getString
import eu.kanade.tachiyomi.source.PagePreviewSource
import eu.kanade.tachiyomi.source.online.NamespaceSource
import exh.source.getMainSource
import android.R as AR

@SuppressLint("ClickableViewAccessibility")
class MangaHeaderHolder(
    view: View,
    private val adapter: MangaDetailsAdapter,
    startExpanded: Boolean,
    private val isTablet: Boolean = false,
) : BaseFlexibleViewHolder(view, adapter) {

    val binding: MangaHeaderItemBinding? = try {
        MangaHeaderItemBinding.bind(view)
    } catch (e: Exception) {
        null
    }
    private val chapterBinding: ChapterHeaderItemBinding? = try {
        ChapterHeaderItemBinding.bind(view)
    } catch (e: Exception) {
        null
    }

    private val markwon by lazy { Markwon.builder(itemView.context).usePlugin(SoftBreakAddsNewLinePlugin.create()).build() }

    private var showReadingButton = true
    private var showMoreButton = true
    var hadSelection = false
    private var canCollapse = true
    private val accentColorState = mutableStateOf<Int?>(null)
    private val descriptionExpandedState = mutableStateOf(false)
    // Cache the (description, genre) the post-layout lineCount probe last ran against.
    // bind() is invoked on every notifyDataSetChanged; without a cache the probe re-runs
    // after layout each time, costing an extra layout pass per bind even when the text
    // hasn't changed. Same pattern as RecentMangaHolder's holder-level cache.
    private var lastBoundDescSignature: Int = 0

    init {

        if (binding == null) {
            with(chapterBinding) {
                this ?: return@with
                chapterLayout.setOnClickListener { adapter.delegate.showChapterFilter() }
            }
        }
        with(binding) {
            this ?: return@with
            chapterLayout.setOnClickListener { adapter.delegate.showChapterFilter() }
            topView.updateLayoutParams<ConstraintLayout.LayoutParams> {
                height = adapter.delegate.topCoverHeight()
            }
            moreButton.setOnClickListener {
                expandDesc(true)
            }
            mangaSummary.setOnClickListener {
                if (moreButton.isVisible) {
                    expandDesc(true)
                } else if (!hadSelection) {
                    collapseDesc(true)
                } else {
                    hadSelection = false
                }
            }
            mangaSummary.setOnLongClickListener {
                if (mangaSummary.isTextSelectable && !adapter.recyclerView.canScrollVertically(
                        -1,
                    )
                ) {
                    (adapter.delegate as MangaDetailsController).binding.swipeRefresh.isEnabled =
                        false
                }
                false
            }
            mangaSummary.setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_DOWN) {
                    view.requestFocus()
                }
                if (event.actionMasked == MotionEvent.ACTION_UP) {
                    hadSelection = mangaSummary.hasSelection()
                    (adapter.delegate as MangaDetailsController).binding.swipeRefresh.isEnabled =
                        true
                }
                false
            }
            if (!itemView.resources.isLTR) {
                moreBgGradient.rotation = 180f
            }
            lessButton.setOnClickListener {
                collapseDesc(true)
            }

            webviewButton.setOnClickListener { adapter.delegate.openInWebView() }
            shareButton.setOnClickListener { adapter.delegate.prepareToShareManga() }
            recsButton?.setOnClickListener { adapter.delegate.openRecommendations() }
            favoriteButton.setOnClickListener {
                adapter.delegate.favoriteManga(false)
            }
            favoriteButton.setOnLongClickListener {
                adapter.delegate.favoriteManga(true)
                true
            }
            title.setOnClickListener { view ->
                title.text?.toString()?.toNormalized()?.let {
                    adapter.delegate.showFloatingActionMode(view as TextView, it)
                }
            }
            title.setOnLongClickListener {
                title.text?.toString()?.toNormalized()?.let {
                    adapter.delegate.copyContentToClipboard(it, MR.strings.title)
                }
                true
            }
            mangaAuthor.setOnClickListener { view ->
                mangaAuthor.text?.toString()?.let {
                    adapter.delegate.showFloatingActionMode(view as TextView, it)
                }
            }
            mangaAuthor.setOnLongClickListener {
                mangaAuthor.text?.toString()?.let {
                    adapter.delegate.copyContentToClipboard(it, MR.strings.author)
                }
                true
            }
            mangaSummary.customSelectionActionModeCallback = adapter.delegate.customActionMode(mangaSummary)
            // applyBlur() is deferred to updateCover.onSuccess — the backdrop ImageView has no
            // bitmap at init, so calling it here only set alpha/renderEffect on an empty view.
            mangaCover.setOnClickListener { adapter.delegate.zoomImageFromThumb(coverCard) }
            trackButton.setOnClickListener { adapter.delegate.showTrackingSheet() }
            if (startExpanded) {
                expandDesc()
            } else {
                collapseDesc()
            }
            if (isTablet) {
                chapterLayout.isVisible = false
                expandDesc()
            }
        }
    }

    private fun applyBlur() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            binding?.backdrop?.alpha = 0.2f
            binding?.backdrop?.setRenderEffect(
                RenderEffect.createBlurEffect(
                    20f,
                    20f,
                    Shader.TileMode.MIRROR,
                ),
            )
        }
    }

    private fun expandDesc(animated: Boolean = false) {
        binding ?: return
        if (binding.moreButton.visibility == View.VISIBLE || isTablet) {
            androidx.transition.TransitionManager.endTransitions(adapter.controller.binding.recycler)
            binding.mangaSummary.maxLines = Integer.MAX_VALUE
            binding.mangaSummary.setTextIsSelectable(true)
            setDescription()
            descriptionExpandedState.value = true
            binding.lessButton.isVisible = !isTablet
            binding.moreButtonGroup.isVisible = false
            if (animated) {
                val animVector = AnimatedVectorDrawableCompat.create(binding.root.context, R.drawable.anim_expand_more_to_less)
                binding.lessButton.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, animVector, null)
                animVector?.start()
            }
            binding.title.maxLines = Integer.MAX_VALUE
            binding.mangaAuthor.maxLines = Integer.MAX_VALUE
            binding.mangaSummary.requestFocus()
            if (animated) {
                val transition = TransitionSet()
                    .addTransition(androidx.transition.ChangeBounds())
                    .addTransition(androidx.transition.Fade())
                    .addTransition(androidx.transition.Slide())
                transition.duration = binding.root.resources.getInteger(
                    AR.integer.config_shortAnimTime,
                ).toLong()
                androidx.transition.TransitionManager.beginDelayedTransition(
                    adapter.controller.binding.recycler,
                    transition,
                )
            }
        }
    }

    private fun collapseDesc(animated: Boolean = false) {
        binding ?: return
        if (isTablet || !canCollapse) return
        binding.moreButtonGroup.isVisible = !isTablet
        if (animated) {
            androidx.transition.TransitionManager.endTransitions(adapter.controller.binding.recycler)
            val animVector = AnimatedVectorDrawableCompat.create(
                binding.root.context,
                R.drawable.anim_expand_less_to_more,
            )
            binding.moreButton.setCompoundDrawablesRelativeWithIntrinsicBounds(
                null,
                null,
                animVector,
                null,
            )
            animVector?.start()
            val transition = TransitionSet()
                .addTransition(androidx.transition.ChangeBounds())
                .addTransition(androidx.transition.Fade())
            transition.duration = binding.root.resources.getInteger(
                AR.integer.config_shortAnimTime,
            ).toLong()
            androidx.transition.TransitionManager.beginDelayedTransition(
                adapter.controller.binding.recycler,
                transition,
            )
        }
        binding.mangaSummary.setTextIsSelectable(false)
        binding.mangaSummary.isClickable = true
        binding.mangaSummary.maxLines = 3
        setDescription()
        descriptionExpandedState.value = false
        binding.lessButton.isVisible = false
        binding.title.maxLines = 4
        binding.mangaAuthor.maxLines = 2
        adapter.recyclerView.post {
            adapter.delegate.updateScroll()
        }
    }

    private fun setDescription() {
        if (binding != null) {
            val desc = adapter.controller.mangaPresenter().manga.description?.replace("<", "&lt;")?.replace(">", "&gt;")?.replace(Regex("""(?m)^\s*-\s*$"""), "\\-")?.replace(Regex("""(?m)^\s*\*\s*$"""), "\\*")
            binding.mangaSummary.movementMethod = LinkMovementMethod.getInstance()
            binding.mangaSummary.text = when {
                desc.isNullOrBlank() -> itemView.context.getString(MR.strings.no_description)
                else -> markwon.toMarkdown(desc.trim())
            }
        }
    }

    fun bindChapters() {
        val presenter = adapter.delegate.mangaPresenter()
        val count = presenter.chapters.size
        if (binding != null) {
            binding.chaptersTitle.text =
                itemView.context.getString(MR.plurals.chapters_plural, count, count)
            binding.filtersText.text = presenter.currentFilters()
        } else if (chapterBinding != null) {
            chapterBinding.chaptersTitle.text =
                itemView.context.getString(MR.plurals.chapters_plural, count, count)
            chapterBinding.filtersText.text = presenter.currentFilters()
        }
    }

    @SuppressLint("SetTextI18n", "StringFormatInvalid")
    fun bind(item: MangaHeaderItem) {
        val presenter = adapter.delegate.mangaPresenter()
        val manga = presenter.manga

        if (binding == null) {
            if (chapterBinding != null) {
                val count = presenter.chapters.size
                chapterBinding.chaptersTitle.text =
                    itemView.context.getString(MR.plurals.chapters_plural, count, count)
                chapterBinding.filtersText.text = presenter.currentFilters()
                if (adapter.preferences.themeMangaDetails().get()) {
                    val accentColor = adapter.delegate.accentColor() ?: return
                    chapterBinding.filterButton.imageTintList = ColorStateList.valueOf(accentColor)
                }
            }
            return
        }
        binding.title.text = manga.title

        setGenreTags(binding, manga)

        if (manga.hasSameAuthorAndArtist) {
            binding.mangaAuthor.text = manga.author?.trim()
        } else {
            binding.mangaAuthor.text = listOfNotNull(manga.author?.trim(), manga.artist?.trim()).joinToString(", ")
        }
        setDescription()

        // Only re-run the post-layout lineCount probe when the description or genres actually
        // changed; expand/collapse based on adapter filter state is cheap and must run every
        // bind, so it stays outside the cache gate.
        val descSignature = (manga.description ?: "").hashCode() xor
            (manga.genre ?: "").hashCode() xor
            (if (manga.initialized) 1 else 0)
        if (descSignature != lastBoundDescSignature) {
            lastBoundDescSignature = descSignature
            binding.mangaSummary.post {
                if (binding.subItemGroup.isVisible) {
                    if (binding.mangaSummary.lineCount < 3 && manga.genre.isNullOrBlank() &&
                        binding.moreButton.isVisible && manga.initialized
                    ) {
                        expandDesc()
                        binding.lessButton.isVisible = false
                        showMoreButton = binding.lessButton.isVisible
                        canCollapse = false
                    }
                }
            }
        }
        if (adapter.hasFilter()) {
            collapse()
        } else {
            expand()
        }
        binding.mangaSummaryLabel.text = itemView.context.getString(
            MR.strings.about_this_,
            manga.seriesType(itemView.context),
        )
        with(binding.favoriteButton) {
            icon = ContextCompat.getDrawable(
                itemView.context,
                when {
                    item.isLocked -> R.drawable.ic_lock_24dp
                    manga.favorite -> R.drawable.ic_heart_24dp
                    else -> R.drawable.ic_heart_outline_24dp
                },
            )
            text = itemView.context.getString(
                when {
                    item.isLocked -> MR.strings.unlock
                    manga.favorite -> MR.strings.in_library
                    else -> MR.strings.add_to_library
                },
            )
            checked(!item.isLocked && manga.favorite)
            adapter.delegate.setFavButtonPopup(this)
        }
        binding.trueBackdrop.setBackgroundColor(
            adapter.delegate.coverColor()
                ?: itemView.context.getResourceColor(android.R.attr.colorBackground),
        )

        val tracked = presenter.isTracked() && !item.isLocked

        with(binding.trackButton) {
            isVisible = presenter.hasTrackers()
            text = itemView.context.getString(
                if (tracked) {
                    MR.strings.tracked
                } else {
                    MR.strings.tracking
                },
            )

            icon = ContextCompat.getDrawable(
                itemView.context,
                if (tracked) R.drawable.ic_check_24dp else R.drawable.ic_sync_24dp,
            )
            checked(tracked)
        }

        run {
            val nextChapter = presenter.getNextUnreadChapter()
            val showButtons = presenter.chapters.isNotEmpty() && !item.isLocked && !adapter.hasFilter()
            showReadingButton = showButtons
            val readEnabled = nextChapter != null
            val readText = if (nextChapter != null) {
                val number = adapter.decimalFormat.format(nextChapter.chapter_number.toDouble())
                if (nextChapter.chapter_number > 0) {
                    itemView.context.getString(
                        if (nextChapter.last_page_read > 0) {
                            MR.strings.continue_reading_chapter_
                        } else {
                            MR.strings.start_reading_chapter_
                        },
                        number,
                    )
                } else {
                    itemView.context.getString(
                        if (nextChapter.last_page_read > 0) {
                            MR.strings.continue_reading
                        } else {
                            MR.strings.start_reading
                        },
                    )
                }
            } else {
                itemView.context.getString(MR.strings.all_chapters_read)
            }
            // Defer first composition to the next animation frame so the push transition isn't
            // charged for Compose runtime startup + first-composition cost. Layout space is
            // reserved by android:minHeight=56dp on the ComposeView so no shift on landing.
            binding?.buttonGroupCompose?.apply {
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
                postOnAnimation {
                    setContent {
                        yokai.presentation.theme.YokaiTheme {
                            MangaContinueReadingButton(
                                readButtonText = readText,
                                readEnabled = readEnabled,
                                showButton = showButtons,
                                accentColorInt = accentColorState.value,
                                onReadClick = { adapter.delegate.readNextChapter(binding?.buttonGroupCompose ?: itemView) },
                            )
                        }
                    }
                }
            }
        }

        // Metadata section (EH info, etc.) — for non-EH sources MangaMetadataSection emits
        // nothing, so the ComposeView stays 0-height permanently. Either way, defer
        // setContent so it doesn't charge the push frame.
        binding?.metadataCompose?.apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            postOnAnimation {
                setContent {
                    yokai.presentation.theme.YokaiTheme {
                        MangaMetadataSection(
                            mangaId = manga.id ?: -1L,
                            sourceId = manga.source,
                            isExpanded = descriptionExpandedState.value,
                            openMetadataViewer = { adapter.delegate.openMetadataViewer() },
                            onSearch = { query -> adapter.delegate.searchFromMetadata(query) },
                        )
                    }
                }
            }
        }

        // Page preview strip — only inflate Compose if the source actually implements
        // PagePreviewSource. Otherwise PagePreviewInlineSection would render a 150dp
        // shimmer skeleton for 1-2 frames, then collapse to 0 when its LaunchedEffect
        // resolves Unavailable — visibly shifting chapter rows down then back up.
        binding?.pagePreviewCompose?.apply {
            val previewSource = presenter.source.getMainSource<PagePreviewSource>()
            if (previewSource == null) {
                isVisible = false
            } else {
                isVisible = true
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
                postOnAnimation {
                    setContent {
                        yokai.presentation.theme.YokaiTheme {
                            exh.ui.pagepreview.components.PagePreviewInlineSection(
                                mangaId = manga.id ?: -1L,
                                sourceId = manga.source,
                                onOpenPagePreview = { adapter.delegate.openPagePreview() },
                                onOpenReaderAtPage = { page -> adapter.delegate.openReaderAtPage(page) },
                            )
                        }
                    }
                }
            }
        }

        val count = presenter.chapters.size
        binding.chaptersTitle.text = itemView.context.getString(MR.plurals.chapters_plural, count, count)

        binding.topView.updateLayoutParams<ConstraintLayout.LayoutParams> {
            height = adapter.delegate.topCoverHeight()
        }

        val statusText = itemView.context.getString(
            when (manga.status) {
                SManga.ONGOING -> MR.strings.ongoing
                SManga.COMPLETED -> MR.strings.completed
                SManga.LICENSED -> MR.strings.licensed
                SManga.PUBLISHING_FINISHED -> MR.strings.publishing_finished
                SManga.CANCELLED -> MR.strings.cancelled
                SManga.ON_HIATUS -> MR.strings.on_hiatus
                else -> MR.strings.unknown_status
            },
        )
        // Surface the predicted next chapter release for favorited, ongoing-ish series.
        val showNextUpdate = manga.favorite && manga.next_update > 0L &&
            manga.status != SManga.COMPLETED && manga.status != SManga.CANCELLED &&
            manga.status != SManga.PUBLISHING_FINISHED
        binding.mangaStatus.isVisible = manga.status != 0 || showNextUpdate
        binding.mangaStatus.text = if (showNextUpdate) {
            itemView.context.getString(
                MR.strings.manga_next_update_,
                manga.next_update.timeSpanFromNow(itemView.context),
            ).let { nextText ->
                if (manga.status != 0) "$statusText • $nextText" else nextText
            }
        } else {
            statusText
        }
        with(binding.mangaSource) {
            val enabledLanguages = presenter.preferences.enabledLanguages().get()

            text = buildSpannedString {
                append(presenter.source.nameBasedOnEnabledLanguages(enabledLanguages))
                if (presenter.source is SourceManager.StubSource &&
                    presenter.source.name != presenter.source.id.toString()
                ) {
                    scale(0.9f) {
                        append(" (${context.getString(MR.strings.source_not_installed)})")
                    }
                }
            }
        }

        binding.filtersText.text = presenter.currentFilters()

        if (manga.isLocal()) {
            binding.webviewButton.isVisible = false
            binding.shareButton.isVisible = false
        }

        if (!manga.initialized) return
        updateCover(manga)
        if (adapter.preferences.themeMangaDetails().get()) {
            updateColors(false)
        }
    }

    private fun setGenreTags(binding: MangaHeaderItemBinding, manga: Manga) {
        val genres = if (manga.genre.isNullOrBlank()) emptyList() else (manga.getGenres() ?: emptyList())
        val delegate = adapter.delegate
        val context = binding.root.context
        val dark = context.isInNightMode()
        val amoled = adapter.delegate.mangaPresenter().preferences.themeDarkAmoled().get()
        val baseTagColor = context.getResourceColor(android.R.attr.colorBackground)
        val bgArray = FloatArray(3)
        val accentArray = FloatArray(3)
        ColorUtils.colorToHSL(baseTagColor, bgArray)
        ColorUtils.colorToHSL(
            adapter.delegate.accentColor() ?: context.getResourceColor(materialR.attr.colorSecondary),
            accentArray,
        )
        val containerColorInt = ColorUtils.setAlphaComponent(
            ColorUtils.HSLToColor(
                floatArrayOf(
                    if (adapter.delegate.accentColor() != null) accentArray[0] else bgArray[0],
                    bgArray[1],
                    when {
                        amoled && dark -> 0.1f
                        dark -> 0.225f
                        else -> 0.85f
                    },
                ),
            ),
            199,
        )
        val labelColorInt = ColorUtils.HSLToColor(
            floatArrayOf(
                accentArray[0],
                accentArray[1],
                if (dark) 0.945f else 0.175f,
            ),
        )
        val isNamespaceSource = adapter.delegate.mangaPresenter().source.getMainSource<NamespaceSource>() != null
        binding.mangaGenresTags.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        // Defer like buttonGroupCompose / metadataCompose so first composition doesn't stall
        // the push animation frame. minHeight on the ComposeView reserves space.
        binding.mangaGenresTags.postOnAnimation {
            binding.mangaGenresTags.setContent {
                yokai.presentation.theme.YokaiTheme {
                    if (isNamespaceSource) {
                        NamespaceGenreTagsSection(
                            genres = genres,
                            containerColor = ComposeColor(containerColorInt),
                            labelColor = ComposeColor(labelColorInt),
                            isExpanded = descriptionExpandedState.value,
                            onTagClick = { genre -> delegate.searchFromMetadata(genre) },
                            onTagLongClick = { genre -> delegate.copyContentToClipboard(genre, genre) },
                        )
                    } else {
                        GenreTagsSection(
                            genres = genres,
                            containerColor = ComposeColor(containerColorInt),
                            labelColor = ComposeColor(labelColorInt),
                            isExpanded = descriptionExpandedState.value,
                            onTagClick = { genre -> delegate.searchFromMetadata(genre) },
                            onTagLongClick = { genre -> delegate.copyContentToClipboard(genre, genre) },
                        )
                    }
                }
            }
        }
    }

    fun clearDescFocus() {
        binding ?: return
        binding.mangaSummary.setTextIsSelectable(false)
        binding.mangaSummary.clearFocus()
    }

    private fun MaterialButton.checked(checked: Boolean) {
        if (checked) {
            stateListAnimator = AnimatorInflater.loadStateListAnimator(context, R.animator.icon_btn_state_list_anim)
            backgroundTintList = ColorStateList.valueOf(
                ColorUtils.blendARGB(
                    adapter.delegate.accentColor() ?: context.getResourceColor(materialR.attr.colorSecondary),
                    context.getResourceColor(android.R.attr.colorBackground),
                    0.706f,
                ),
            )
            strokeColor = ColorStateList.valueOf(Color.TRANSPARENT)
        } else {
            stateListAnimator = null
            resetStrokeColor()
            backgroundTintList =
                ColorStateList.valueOf(context.getResourceColor(android.R.attr.colorBackground))
        }
    }

    fun setTopHeight(newHeight: Int) {
        binding ?: return
        if (newHeight == binding.topView.height) return
        binding.topView.updateLayoutParams<ConstraintLayout.LayoutParams> {
            height = newHeight
        }
    }

    fun setBackDrop(color: Int) {
        binding ?: return
        binding.trueBackdrop.setBackgroundColor(color)
    }

    fun updateColors(updateAll: Boolean = true) {
        val accentColor = adapter.delegate.accentColor() ?: return
        accentColorState.value = accentColor
        if (binding == null) {
            if (chapterBinding != null) {
                chapterBinding.filterButton.imageTintList = ColorStateList.valueOf(accentColor)
            }
            return
        }
        val manga = adapter.presenter.manga
        with(binding) {
            trueBackdrop.setBackgroundColor(
                adapter.delegate.coverColor()
                    ?: trueBackdrop.context.getResourceColor(android.R.attr.colorBackground),
            )
            TextViewCompat.setCompoundDrawableTintList(moreButton, ColorStateList.valueOf(accentColor))
            moreButton.setTextColor(accentColor)
            TextViewCompat.setCompoundDrawableTintList(lessButton, ColorStateList.valueOf(accentColor))
            lessButton.setTextColor(accentColor)
            shareButton.imageTintList = ColorStateList.valueOf(accentColor)
            webviewButton.imageTintList = ColorStateList.valueOf(accentColor)
            recsButton?.imageTintList = ColorStateList.valueOf(accentColor)
            filterButton.imageTintList = ColorStateList.valueOf(accentColor)

            val states = arrayOf(
                intArrayOf(-AR.attr.state_enabled),
                intArrayOf(),
            )

            val colors = intArrayOf(
                ColorUtils.setAlphaComponent(root.context.getResourceColor(R.attr.tabBarIconInactive), 43),
                accentColor,
            )

            trackButton.iconTint = ColorStateList.valueOf(accentColor)
            favoriteButton.iconTint = ColorStateList.valueOf(accentColor)
            if (updateAll) {
                trackButton.checked(trackButton.stateListAnimator != null)
                favoriteButton.checked(favoriteButton.stateListAnimator != null)
                setGenreTags(this, manga)
            }
        }
    }

    fun updateTracking() {
        binding ?: return
        val presenter = adapter.delegate.mangaPresenter()
        val tracked = presenter.isTracked()
        with(binding.trackButton) {
            text = itemView.context.getString(
                if (tracked) {
                    MR.strings.tracked
                } else {
                    MR.strings.tracking
                },
            )

            icon = ContextCompat.getDrawable(
                itemView.context,
                if (tracked) {
                    R.drawable
                        .ic_check_24dp
                } else {
                    R.drawable.ic_sync_24dp
                },
            )
            checked(tracked)
        }
    }

    fun collapse() {
        binding ?: return
        if (!canCollapse) return
        binding.subItemGroup.isVisible = false
        binding.buttonGroupCompose?.isVisible = false
        binding.metadataCompose?.isVisible = false
        binding.mangaGenresTags.isVisible = false
        if (binding.moreButton.isVisible || binding.moreButton.isInvisible) {
            binding.moreButtonGroup.isInvisible = !isTablet
        } else {
            binding.lessButton.isVisible = false
        }
    }

    fun updateCover(manga: Manga) {
        binding ?: return
        if (!manga.initialized) return
        val drawable = adapter.controller.binding.mangaCoverFull.drawable
        binding.mangaCover.loadManga(manga) {
            placeholder(drawable)
            error(drawable)
            if (manga.favorite) networkCachePolicy(CachePolicy.READ_ONLY)
            diskCachePolicy(CachePolicy.READ_ONLY)
        }
        binding.backdrop.loadManga(manga) {
            placeholder(drawable)
            error(drawable)
            if (manga.favorite) networkCachePolicy(CachePolicy.READ_ONLY)
            diskCachePolicy(CachePolicy.READ_ONLY)
            target(
                onSuccess = {
                    val result = it.asDrawable(itemView.resources)
                    val bitmap = (result as? BitmapDrawable)?.bitmap
                    if (bitmap == null) {
                        binding.backdrop.setImageDrawable(result)
                        return@target
                    }
                    val yOffset = (bitmap.height / 2 * 0.33).toInt()

                    binding.backdrop.setImageDrawable(
                        Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height - yOffset)
                            .toDrawable(itemView.resources),
                    )
                    applyBlur()
                },
            )
        }
    }

    fun expand() {
        binding ?: return
        binding.subItemGroup.isVisible = true
        binding.mangaGenresTags.isVisible = true
        if (!showMoreButton) {
            binding.moreButtonGroup.isVisible = false
        } else {
            if (binding.mangaSummary.maxLines != Integer.MAX_VALUE) {
                binding.moreButtonGroup.isVisible = !isTablet
            } else {
                binding.lessButton.isVisible = !isTablet
            }
        }
        binding.buttonGroupCompose?.isVisible = showReadingButton
        binding.metadataCompose?.isVisible = true
    }

    override fun onLongClick(view: View?): Boolean {
        super.onLongClick(view)
        return false
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GenreTagsSection(
    genres: List<String>,
    containerColor: ComposeColor,
    labelColor: ComposeColor,
    isExpanded: Boolean,
    onTagClick: (String) -> Unit,
    onTagLongClick: (String) -> Unit,
) {
    @Composable
    fun Chip(genre: String) {
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
            Surface(
                modifier = Modifier
                    .padding(vertical = 4.dp)
                    .combinedClickable(
                        onClick = { onTagClick(genre) },
                        onLongClick = { onTagLongClick(genre) },
                    ),
                shape = CircleShape,
                color = containerColor,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            ) {
                Text(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    text = genre,
                    style = MaterialTheme.typography.bodySmall,
                    color = labelColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }

    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        if (isExpanded) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                genres.forEach { genre -> Chip(genre) }
            }
        } else {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                items(genres) { genre -> Chip(genre) }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NamespaceGenreTagsSection(
    genres: List<String>,
    containerColor: ComposeColor,
    labelColor: ComposeColor,
    isExpanded: Boolean,
    onTagClick: (String) -> Unit,
    onTagLongClick: (String) -> Unit,
) {
    // Parse "namespace: name" format into grouped map
    val grouped = remember(genres) {
        val map = linkedMapOf<String, MutableList<String>>()
        genres.forEach { genre ->
            val colonIdx = genre.indexOf(": ")
            if (colonIdx > 0) {
                val ns = genre.substring(0, colonIdx)
                val name = genre.substring(colonIdx + 2)
                map.getOrPut(ns) { mutableListOf() }.add(name)
            } else {
                map.getOrPut("") { mutableListOf() }.add(genre)
            }
        }
        map.toMap()
    }

    @Composable
    fun Chip(text: String, searchQuery: String, isLabel: Boolean = false) {
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
            Surface(
                modifier = Modifier
                    .padding(vertical = 4.dp)
                    .combinedClickable(
                        onClick = { if (!isLabel) onTagClick(searchQuery) },
                        onLongClick = { if (!isLabel) onTagLongClick(text) },
                    ),
                shape = CircleShape,
                color = if (isLabel) ComposeColor.Transparent else containerColor,
                border = if (isLabel) {
                    BorderStroke(1.dp, labelColor.copy(alpha = 0.5f))
                } else {
                    BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                },
            ) {
                Text(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    text = text,
                    style = MaterialTheme.typography.bodySmall,
                    color = labelColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }

    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        if (isExpanded) {
            grouped.forEach { (namespace, tags) ->
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (namespace.isNotEmpty()) {
                        Chip(text = namespace, searchQuery = "", isLabel = true)
                    }
                    tags.forEach { tag ->
                        val searchQuery = if (namespace.isNotEmpty()) "$namespace:\"$tag\"" else tag
                        Chip(text = tag, searchQuery = searchQuery)
                    }
                }
            }
        } else {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                grouped.forEach { (namespace, tags) ->
                    if (namespace.isNotEmpty()) {
                        item(key = "ns_$namespace") {
                            Chip(text = namespace, searchQuery = "", isLabel = true)
                        }
                    }
                    items(tags, key = { "${namespace}_$it" }) { tag ->
                        val searchQuery = if (namespace.isNotEmpty()) "$namespace:\"$tag\"" else tag
                        Chip(text = tag, searchQuery = searchQuery)
                    }
                }
            }
        }
    }
}
