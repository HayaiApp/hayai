package eu.kanade.tachiyomi.ui.manga

import android.animation.AnimatorInflater
import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.text.format.DateUtils
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.text.buildSpannedString
import androidx.core.text.scale
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.widget.TextViewCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.transition.TransitionSet
import androidx.vectordrawable.graphics.drawable.AnimatedVectorDrawableCompat
import coil3.asDrawable
import coil3.compose.AsyncImage
import coil3.imageLoader
import coil3.request.CachePolicy
import coil3.request.Disposable
import coil3.request.ImageRequest
import coil3.request.error
import coil3.request.placeholder
import coil3.size.Precision
import coil3.size.ViewSizeResolver
import com.google.android.material.button.MaterialButton
import com.google.android.material.R as materialR
import dev.icerock.moko.resources.compose.stringResource
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.database.models.isNovel
import eu.kanade.tachiyomi.data.database.models.seriesType
import eu.kanade.tachiyomi.databinding.ChapterHeaderItemBinding
import eu.kanade.tachiyomi.databinding.MangaHeaderItemBinding
import eu.kanade.tachiyomi.domain.manga.models.Manga
import eu.kanade.tachiyomi.source.SourceManager
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.nameBasedOnEnabledLanguages
import eu.kanade.tachiyomi.ui.base.holder.BaseFlexibleViewHolder
import eu.kanade.tachiyomi.ui.manga.related.RelatedMangaCardAdapter
import eu.kanade.tachiyomi.ui.manga.related.RelatedMangaCardItem
import eu.kanade.tachiyomi.util.isLocal
import eu.kanade.tachiyomi.util.system.timeSpanFromNow
import eu.kanade.tachiyomi.util.lang.toNormalized
import eu.kanade.tachiyomi.util.system.getResourceColor
import eu.kanade.tachiyomi.util.system.isInNightMode
import eu.kanade.tachiyomi.util.system.isLTR
import eu.kanade.tachiyomi.util.system.openInBrowser
import eu.kanade.tachiyomi.util.view.resetStrokeColor
import io.noties.markwon.Markwon
import io.noties.markwon.SoftBreakAddsNewLinePlugin
import android.text.method.LinkMovementMethod
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import yokai.i18n.MR
import yokai.presentation.theme.ReducedMotion
import yokai.util.lang.getString
import eu.kanade.tachiyomi.source.PagePreviewSource
import eu.kanade.tachiyomi.source.online.NamespaceSource
import exh.source.getMainSource
import hayai.novel.reader.quote.quoteManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import tachiyomi.domain.translation.service.TranslationPreferences
import yokai.domain.series.SeriesKnowledgeRepository
import yokai.domain.series.SeriesPreferences
import yokai.domain.series.model.MetadataProviderType
import yokai.domain.series.model.SeriesDisplaySection
import yokai.domain.series.model.SeriesKnowledgeBundle
import yokai.domain.series.model.SeriesMetadataField
import yokai.domain.series.model.SeriesMetadataValue
import yokai.domain.series.model.TranslationMode
import yokai.domain.series.model.sourceMetadataValues
import yokai.presentation.theme.motionAwareCrossfadeMillis
import yokai.util.koin.injectLazy
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
    private var boundHeaderItem: MangaHeaderItem? = null
    private val readingButtonTextState = mutableStateOf("")
    private val readingButtonEnabledState = mutableStateOf(false)
    private val readingButtonVisibleState = mutableStateOf(false)
    private var readingButtonContentScheduled = false
    private var readingButtonContentInstalled = false
    private var showMoreButton = true
    var hadSelection = false
    private var canCollapse = true
    private var showTitleSection = true
    private var showCoverSection = true
    private var showAuthorSection = true
    private var showDescriptionSection = true
    private var showGenresSection = true
    private var showAliasesSection = true
    private var showCharactersSection = false
    private var showMetadataSection = true
    private var showTrackersSection = true
    private var showQuotesTranslationSection = true
    private var showExtraImagesSection = true
    private var seriesQuoteCount = 0
    private var seriesTranslationEnabled = false
    private var seriesKnowledgeBundle: SeriesKnowledgeBundle? = null
    private var resolvedSeriesMetadata: ResolvedSeriesMetadata? = null
    private var seriesKnowledgeJob: Job? = null
    private var metadataContentInstalled = false
    private var boundMangaId: Long? = null
    private var boundMangaIsNovel = false
    private var hasBoundManga = false
    private var boundQuoteCount = 0
    private val accentColorState = mutableStateOf<Int?>(null)
    private val descriptionExpandedState = mutableStateOf(false)
    private val metadataRenderState = mutableStateOf<MetadataRenderState?>(null)
    private var descriptionContent: DescriptionContent? = null
    private var descriptionLayoutKey: DescriptionLayoutKey? = null
    private var descriptionLayoutProbe: Runnable? = null
    private val genreRenderState = mutableStateOf<GenreRenderState?>(null)
    private var genreContentInstalled = false
    private var genreContentScheduled = false
    private val pagePreviewRenderState = mutableStateOf<PagePreviewRenderState?>(null)
    private var pagePreviewContentInstalled = false
    private var pagePreviewContentScheduled = false
    private var coverRenderState: HeaderCoverRenderState? = null
    private var coverRequest: Disposable? = null
    private var backdropRequest: Disposable? = null
    private val seriesKnowledgeRepository: SeriesKnowledgeRepository by injectLazy()
    private val seriesPreferences: SeriesPreferences by injectLazy()
    private val translationPreferences: TranslationPreferences by injectLazy()

    private val relatedCardAdapter = RelatedMangaCardAdapter(
        object : RelatedMangaCardAdapter.OnMangaClickListener {
            override fun onMangaClick(manga: Manga) {
                adapter.delegate.openRelatedManga(manga)
            }
        },
        compact = true,
    )

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
            recsButton.setOnClickListener { adapter.delegate.openRecommendations() }
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
            predictedUpdateButton?.setOnClickListener { adapter.delegate.showFetchIntervalDialog() }
            relatedRecycler?.layoutManager = LinearLayoutManager(itemView.context, LinearLayoutManager.HORIZONTAL, false)
            relatedRecycler?.adapter = relatedCardAdapter
            relatedTitleWrapper?.setOnClickListener { adapter.delegate.openRelatedMangaScreen() }
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
            val shouldAnimate = animated && !ReducedMotion.isEnabled()
            if (shouldAnimate) {
                androidx.transition.TransitionManager.endTransitions(binding.root)
                val transition = TransitionSet()
                    .addTransition(androidx.transition.ChangeBounds())
                    .addTransition(androidx.transition.Fade())
                transition.duration = binding.root.resources.getInteger(
                    AR.integer.config_shortAnimTime,
                ).toLong()
                androidx.transition.TransitionManager.beginDelayedTransition(binding.root, transition)
            }
            binding.mangaSummary.maxLines = Integer.MAX_VALUE
            binding.mangaSummary.setTextIsSelectable(true)
            descriptionExpandedState.value = true
            binding.lessButton.isVisible = !isTablet
            binding.moreButtonGroup.isVisible = false
            if (shouldAnimate) {
                val animVector = AnimatedVectorDrawableCompat.create(binding.root.context, R.drawable.anim_expand_more_to_less)
                binding.lessButton.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, animVector, null)
                animVector?.start()
            }
            binding.title.maxLines = Integer.MAX_VALUE
            binding.mangaAuthor.maxLines = Integer.MAX_VALUE
            binding.mangaSummary.requestFocus()
        }
    }

    private fun collapseDesc(animated: Boolean = false) {
        binding ?: return
        if (isTablet || !canCollapse) return
        val shouldAnimate = animated && !ReducedMotion.isEnabled()
        if (shouldAnimate) {
            androidx.transition.TransitionManager.endTransitions(binding.root)
            val transition = TransitionSet()
                .addTransition(androidx.transition.ChangeBounds())
                .addTransition(androidx.transition.Fade())
            transition.duration = binding.root.resources.getInteger(
                AR.integer.config_shortAnimTime,
            ).toLong()
            androidx.transition.TransitionManager.beginDelayedTransition(binding.root, transition)
        }
        binding.moreButtonGroup.isVisible = !isTablet
        if (shouldAnimate) {
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
        }
        binding.mangaSummary.setTextIsSelectable(false)
        binding.mangaSummary.isClickable = true
        binding.mangaSummary.maxLines = 3
        descriptionExpandedState.value = false
        binding.lessButton.isVisible = false
        binding.title.maxLines = 4
        binding.mangaAuthor.maxLines = 2
        adapter.recyclerView.post {
            adapter.delegate.updateScroll()
        }
    }

    private fun bindDescription(description: String?) {
        val binding = binding ?: return
        val content = DescriptionContent(
            description
                ?.replace("<", "&lt;")
                ?.replace(">", "&gt;")
                ?.replace(EMPTY_DASH_LINE, "\\-")
                ?.replace(EMPTY_ASTERISK_LINE, "\\*")
                ?.trim()
                ?.takeIf { it.isNotBlank() },
        )
        if (content == descriptionContent) return

        descriptionContent = content
        binding.mangaSummary.movementMethod = LinkMovementMethod.getInstance()
        binding.mangaSummary.text = content.markdown?.let(markwon::toMarkdown)
            ?: itemView.context.getString(MR.strings.no_description)
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
        val manga = presenter.mangaOrNull
        if (manga == null) {
            itemView.isVisible = false
            return
        }
        itemView.isVisible = true

        if (binding == null) {
            if (chapterBinding != null) {
                val count = presenter.chapters.size
                chapterBinding.chaptersTitle.text =
                    itemView.context.getString(MR.plurals.chapters_plural, count, count)
                chapterBinding.filtersText.text = presenter.currentFilters()
                if (adapter.delegate.useCoverColorTheming()) {
                    val accentColor = adapter.delegate.accentColor() ?: return
                    chapterBinding.filterButton.imageTintList = ColorStateList.valueOf(accentColor)
                }
            }
            return
        }
        val mangaChanged = !hasBoundManga || boundMangaId != manga.id
        if (mangaChanged) {
            hasBoundManga = true
            boundMangaId = manga.id
            boundMangaIsNovel = manga.isNovel()
            boundQuoteCount = 0
            seriesKnowledgeJob?.cancel()
            seriesKnowledgeBundle = null
            resolvedSeriesMetadata = null
            descriptionContent = null
            descriptionLayoutKey = null
            descriptionLayoutProbe?.let(binding.mangaSummary::removeCallbacks)
            descriptionLayoutProbe = null
            coverRenderState = null
            coverRequest?.dispose()
            coverRequest = null
            backdropRequest?.dispose()
            backdropRequest = null
            observeSeriesKnowledge(manga.id)
        }
        resolveDisplayOptions(seriesKnowledgeBundle, boundQuoteCount)
        applyResolvedMetadata(manga, seriesKnowledgeBundle)
        applyDisplayVisibility()
        renderMetadataSection(manga)
        scheduleDescriptionLayoutProbe(manga)
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

        bindTrackingButton(presenter, item.isLocked)

        boundHeaderItem = item
        bindReadingButton(item)
        bindPagePreview(manga, presenter)

        val count = presenter.chapters.size
        binding.chaptersTitle.text = itemView.context.getString(MR.plurals.chapters_plural, count, count)

        binding.topView.updateLayoutParams<ConstraintLayout.LayoutParams> {
            height = adapter.delegate.topCoverHeight()
        }

        bindRelatedManga(presenter)
        bindStatus(manga, presenter)
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
        if (adapter.delegate.useCoverColorTheming()) {
            updateColors(false)
        }
    }

    private fun scheduleDescriptionLayoutProbe(manga: Manga) {
        val binding = binding ?: return
        val key = DescriptionLayoutKey(
            mangaId = manga.id,
            content = descriptionContent,
            genres = resolvedSeriesMetadata?.genres.orEmpty(),
            initialized = manga.initialized,
        )
        if (key == descriptionLayoutKey) return

        descriptionLayoutKey = key
        canCollapse = true
        descriptionLayoutProbe?.let(binding.mangaSummary::removeCallbacks)
        val probe = Runnable {
            if (descriptionLayoutKey != key || boundMangaId != manga.id) return@Runnable
            if (
                binding.subItemGroup.isVisible &&
                binding.mangaSummary.lineCount < 3 &&
                key.genres.isEmpty() &&
                binding.moreButton.isVisible &&
                key.initialized
            ) {
                expandDesc()
                binding.lessButton.isVisible = false
                showMoreButton = false
                canCollapse = false
            }
        }
        descriptionLayoutProbe = probe
        binding.mangaSummary.post(probe)
    }

    private fun bindPagePreview(manga: Manga, presenter: MangaDetailsPresenter) {
        val pagePreviewCompose = binding?.pagePreviewCompose ?: return
        val state = manga.id?.takeIf {
            presenter.source.getMainSource<PagePreviewSource>() != null
        }?.let { mangaId ->
            PagePreviewRenderState(mangaId, manga.source)
        }
        pagePreviewRenderState.value = state
        pagePreviewCompose.isVisible = state != null
        if (state == null || pagePreviewContentInstalled || pagePreviewContentScheduled) return

        pagePreviewContentScheduled = true
        pagePreviewCompose.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        pagePreviewCompose.postOnAnimation {
            pagePreviewContentScheduled = false
            if (pagePreviewRenderState.value == null || binding.pagePreviewCompose !== pagePreviewCompose) {
                return@postOnAnimation
            }
            pagePreviewContentInstalled = true
            pagePreviewCompose.setContent {
                val renderState = pagePreviewRenderState.value ?: return@setContent
                yokai.presentation.theme.YokaiTheme {
                    exh.ui.pagepreview.components.PagePreviewInlineSection(
                        mangaId = renderState.mangaId,
                        sourceId = renderState.sourceId,
                        onOpenPagePreview = adapter.delegate::openPagePreview,
                        onOpenReaderAtPage = adapter.delegate::openReaderAtPage,
                    )
                }
            }
        }
    }

    private fun bindTrackingButton(presenter: MangaDetailsPresenter, isLocked: Boolean) {
        val binding = binding ?: return
        val tracked = presenter.isTracked() && !isLocked
        binding.trackButton.apply {
            isVisible = presenter.hasTrackers() && showTrackersSection
            text = itemView.context.getString(
                if (tracked) MR.strings.tracked else MR.strings.tracking,
            )
            icon = ContextCompat.getDrawable(
                itemView.context,
                if (tracked) R.drawable.ic_check_24dp else R.drawable.ic_sync_24dp,
            )
            checked(tracked)
        }
    }

    private fun bindStatus(manga: Manga, presenter: MangaDetailsPresenter) {
        val binding = binding ?: return
        val resolvedStatus = resolvedSeriesMetadata?.status?.trim()?.takeIf { it.isNotBlank() }
        val statusText = resolvedStatus?.toIntOrNull()?.let { status ->
            itemView.context.getString(status.stringResourceForMangaStatus())
        } ?: resolvedStatus ?: itemView.context.getString(manga.status.stringResourceForMangaStatus())
        val showNextUpdate = presenter.preferences.smartUpdateEnabled().get() &&
            manga.favorite && !manga.isLocal() && manga.next_update > 0L &&
            manga.next_update.isTodayOrFutureDay() &&
            manga.status in setOf(SManga.ONGOING, SManga.PUBLISHING_FINISHED)
        binding.mangaStatus.isVisible = statusText.isNotBlank() && (resolvedStatus != null || manga.status != 0)
        binding.mangaStatus.text = statusText
        binding.predictedUpdateButton?.apply {
            isVisible = showNextUpdate
            text = if (showNextUpdate) {
                val relativeUpdate = if (DateUtils.isToday(manga.next_update)) {
                    itemView.context.getString(MR.strings.manga_interval_expected_update_soon)
                } else {
                    manga.next_update.timeSpanFromNow(itemView.context)
                }
                itemView.context.getString(MR.strings.manga_next_update_, relativeUpdate)
            } else {
                itemView.context.getString(MR.strings.manga_interval_custom_amount)
            }
            icon = ContextCompat.getDrawable(itemView.context, R.drawable.ic_hourglass_empty_24dp)
            checked(showNextUpdate)
        }
    }

    private fun observeSeriesKnowledge(mangaId: Long?) {
        mangaId ?: return
        val quoteCounts = if (boundMangaIsNovel) {
            itemView.context.quoteManager.subscribeQuoteCount(mangaId)
        } else {
            flowOf(0)
        }
        seriesKnowledgeJob = adapter.controller.viewScope.launch {
            combine(seriesKnowledgeRepository.subscribe(mangaId), quoteCounts) { bundle, quoteCount ->
                bundle to quoteCount
            }.conflate().collect { (bundle, quoteCount) ->
                if (boundMangaId != mangaId || binding == null) return@collect
                val presenter = adapter.delegate.mangaPresenter()
                val manga = presenter.mangaOrNull ?: return@collect
                seriesKnowledgeBundle = bundle
                boundQuoteCount = quoteCount
                resolveDisplayOptions(bundle, quoteCount)
                applyResolvedMetadata(manga, bundle)
                applyDisplayVisibility()
                bindTrackingButton(presenter, boundHeaderItem?.isLocked == true)
                bindStatus(manga, presenter)
                renderMetadataSection(manga)
                scheduleDescriptionLayoutProbe(manga)
                if (manga.initialized) updateCover(manga)
            }
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    fun bindRelatedManga(presenter: MangaDetailsPresenter) {
        binding ?: return
        if (!presenter.isRelatedMangaEnabled()) {
            binding.relatedMangaGroup?.isVisible = false
            return
        }
        binding.relatedMangaGroup?.isVisible = true

        val item = presenter.relatedMangaItem
        binding.relatedProgress?.isVisible = item.isLoading
        binding.relatedNoResults?.isVisible = !item.isLoading && item.mangas.isEmpty()
        binding.relatedCard?.isVisible = item.isLoading || item.mangas.isNotEmpty()
        relatedCardAdapter.updateDataSet(item.mangas.map { RelatedMangaCardItem(it) })
    }

    private fun resolveDisplayOptions(bundle: SeriesKnowledgeBundle?, quoteCount: Int) {
        val local = bundle?.displayOptions?.associateBy { it.optionKey }.orEmpty()

        fun visible(section: SeriesDisplaySection): Boolean =
            local[section.key]?.visible ?: seriesPreferences.displaySection(section).get()

        showTitleSection = visible(SeriesDisplaySection.TITLE)
        showCoverSection = visible(SeriesDisplaySection.COVER_BANNER)
        showAuthorSection = visible(SeriesDisplaySection.AUTHORS)
        showDescriptionSection = visible(SeriesDisplaySection.DESCRIPTION)
        showGenresSection = visible(SeriesDisplaySection.GENRES)
        showAliasesSection = visible(SeriesDisplaySection.ALIASES)
        showCharactersSection = visible(SeriesDisplaySection.CHARACTERS)
        showTrackersSection = visible(SeriesDisplaySection.TRACKERS)
        seriesTranslationEnabled = translationPreferences.translationEnabled().get()
        showQuotesTranslationSection = boundMangaIsNovel &&
            visible(SeriesDisplaySection.QUOTES_TRANSLATION) &&
            (seriesTranslationEnabled || quoteCount > 0)
        showExtraImagesSection = visible(SeriesDisplaySection.EXTRA_IMAGES)
        seriesQuoteCount = if (showQuotesTranslationSection) quoteCount else 0
        showMetadataSection = showAliasesSection || showCharactersSection ||
            showTrackersSection || showQuotesTranslationSection || showExtraImagesSection
    }

    private fun applyResolvedMetadata(manga: Manga, bundle: SeriesKnowledgeBundle?) {
        val resolved = resolveSeriesMetadata(
            manga = manga,
            knowledge = bundle,
            includeTrackers = showTrackersSection,
        )
        if (resolved == resolvedSeriesMetadata) return
        resolvedSeriesMetadata = resolved
        val binding = binding ?: return
        binding.title.text = resolved.title
        binding.mangaAuthor.text = listOfNotNull(
            resolved.author?.trim()?.takeIf { it.isNotBlank() },
            resolved.artist?.trim()?.takeIf { it.isNotBlank() && it != resolved.author },
        ).joinToString(", ")
        bindDescription(resolved.description)
        setGenreTags(binding, manga, resolved.genres)
    }

    private fun renderMetadataSection(manga: Manga) {
        metadataRenderState.value = MetadataRenderState(
            mangaId = manga.id ?: -1L,
            sourceId = manga.source,
            knowledge = seriesKnowledgeBundle,
            showMetadataSection = showMetadataSection,
            showAliases = showAliasesSection,
            showCharacters = showCharactersSection,
            showTrackers = showTrackersSection,
            showExtraImages = showExtraImagesSection,
            showQuotesTranslation = showQuotesTranslationSection,
            quoteCount = seriesQuoteCount,
            translationEnabled = seriesTranslationEnabled,
            translationMode = TranslationMode.fromDbKey(seriesPreferences.translationMode().get()),
        )

        val metadataCompose = binding?.metadataCompose ?: return
        if (metadataContentInstalled) return
        metadataContentInstalled = true
        metadataCompose.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        metadataCompose.setContent {
            yokai.presentation.theme.YokaiTheme {
                MetadataSectionHost(
                    state = metadataRenderState.value,
                    isExpanded = descriptionExpandedState.value,
                    onOpenQuotes = { adapter.delegate.openQuotesSheet() },
                    onOpenTranslationSettings = { adapter.delegate.openTranslationSettings() },
                    onSearch = { query -> adapter.delegate.searchFromMetadata(query) },
                    onOpenLink = { url -> itemView.context.openInBrowser(url) },
                    openMetadataViewer = { adapter.delegate.openMetadataViewer() },
                )
            }
        }
    }

    private fun applyDisplayVisibility() {
        binding ?: return
        binding.title.isVisible = showTitleSection
        binding.coverCard.isVisible = showCoverSection
        binding.backdrop.isVisible = showCoverSection
        binding.mangaAuthor.isVisible = showAuthorSection
        binding.mangaSummaryLabel.isVisible = showDescriptionSection
        binding.mangaSummary.isVisible = showDescriptionSection
        binding.moreButtonGroup.isVisible = showDescriptionSection && binding.moreButtonGroup.isVisible
        binding.lessButton.isVisible = showDescriptionSection && binding.lessButton.isVisible
        binding.mangaGenresTags.isVisible = showGenresSection && binding.mangaGenresTags.isVisible
        binding.metadataCompose.isVisible = showMetadataSection && binding.subItemGroup.isVisible
        binding.trackButton.isVisible = showTrackersSection && binding.trackButton.isVisible
    }

    private fun setGenreTags(
        binding: MangaHeaderItemBinding,
        manga: Manga,
        resolvedGenres: List<String>? = resolvedSeriesMetadata?.genres,
    ) {
        val genres = resolvedGenres ?: if (manga.genre.isNullOrBlank()) emptyList() else (manga.getGenres() ?: emptyList())
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
        genreRenderState.value = GenreRenderState(
            genres = genres.toList(),
            containerColor = containerColorInt,
            labelColor = labelColorInt,
            isNamespaceSource = adapter.delegate.mangaPresenter().source.getMainSource<NamespaceSource>() != null,
        )
        installGenreTagsContent()
    }

    private fun installGenreTagsContent() {
        if (genreContentInstalled || genreContentScheduled) return
        val genreCompose = binding?.mangaGenresTags ?: return
        genreContentScheduled = true
        genreCompose.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        genreCompose.postOnAnimation {
            genreContentScheduled = false
            if (binding.mangaGenresTags !== genreCompose) return@postOnAnimation
            genreContentInstalled = true
            genreCompose.setContent {
                val state = genreRenderState.value ?: return@setContent
                yokai.presentation.theme.YokaiTheme {
                    if (state.isNamespaceSource) {
                        NamespaceGenreTagsSection(
                            genres = state.genres,
                            containerColor = ComposeColor(state.containerColor),
                            labelColor = ComposeColor(state.labelColor),
                            isExpanded = descriptionExpandedState.value,
                            onTagClick = adapter.delegate::searchFromMetadata,
                            onTagLongClick = { genre -> adapter.delegate.copyContentToClipboard(genre, genre) },
                        )
                    } else {
                        GenreTagsSection(
                            genres = state.genres,
                            containerColor = ComposeColor(state.containerColor),
                            labelColor = ComposeColor(state.labelColor),
                            isExpanded = descriptionExpandedState.value,
                            onTagClick = adapter.delegate::searchFromMetadata,
                            onTagLongClick = { genre -> adapter.delegate.copyContentToClipboard(genre, genre) },
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
            recsButton.imageTintList = ColorStateList.valueOf(accentColor)
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
            predictedUpdateButton?.iconTint = ColorStateList.valueOf(accentColor)
            if (updateAll) {
                trackButton.checked(trackButton.stateListAnimator != null)
                favoriteButton.checked(favoriteButton.stateListAnimator != null)
                predictedUpdateButton?.let { it.checked(it.isVisible) }
                setGenreTags(this, manga)
            }
        }
    }

    /**
     * Reverts the sub-views [updateColors] tinted back to M3 defaults (cover-theming OFF path)
     * WITHOUT a full bind()/cover reload. updateColors() early-returns when accentColor is null,
     * so the default reset can't route through it — replicate just the default-color assignments.
     */
    fun resetColorsToDefault() {
        accentColorState.value = null
        if (binding == null) {
            chapterBinding?.filterButton?.imageTintList =
                ColorStateList.valueOf(itemView.context.getResourceColor(materialR.attr.colorSecondary))
            return
        }
        val default = itemView.context.getResourceColor(materialR.attr.colorSecondary)
        val defaultTint = ColorStateList.valueOf(default)
        with(binding) {
            trueBackdrop.setBackgroundColor(itemView.context.getResourceColor(android.R.attr.colorBackground))
            TextViewCompat.setCompoundDrawableTintList(moreButton, defaultTint)
            moreButton.setTextColor(default)
            TextViewCompat.setCompoundDrawableTintList(lessButton, defaultTint)
            lessButton.setTextColor(default)
            shareButton.imageTintList = defaultTint
            webviewButton.imageTintList = defaultTint
            recsButton.imageTintList = defaultTint
            filterButton.imageTintList = defaultTint
            trackButton.iconTint = defaultTint
            favoriteButton.iconTint = defaultTint
            predictedUpdateButton?.iconTint = defaultTint
            trackButton.checked(trackButton.stateListAnimator != null)
            favoriteButton.checked(favoriteButton.stateListAnimator != null)
            predictedUpdateButton?.let { it.checked(it.isVisible) }
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

    private fun bindReadingButton(item: MangaHeaderItem) {
        val presenter = adapter.delegate.mangaPresenter()
        val readingState = presenter.getReadingButtonState()
        val showButtons = presenter.allChapters.isNotEmpty() && !item.isLocked
        showReadingButton = showButtons
        val readEnabled = readingState != null
        val readText = readingState?.let {
            itemView.context.getReadingButtonText(it, adapter.decimalFormat)
        } ?: run {
            itemView.context.getString(MR.strings.all_chapters_read)
        }
        readingButtonTextState.value = readText
        readingButtonEnabledState.value = readEnabled
        readingButtonVisibleState.value = showButtons

        val composeView = binding?.buttonGroupCompose ?: return
        if (readingButtonContentInstalled || readingButtonContentScheduled) return
        readingButtonContentScheduled = true
        // Defer first composition to the next frame so the push transition isn't charged for
        // Compose startup; subsequent chapter refreshes update state instead of reinstalling the
        // entire composition. minHeight=56dp reserves layout space so there's no landing shift.
        composeView.apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            postOnAnimation {
                readingButtonContentScheduled = false
                if (binding.buttonGroupCompose !== composeView) {
                    return@postOnAnimation
                }
                readingButtonContentInstalled = true
                setContent {
                    yokai.presentation.theme.YokaiTheme {
                        MangaContinueReadingButton(
                            readButtonText = readingButtonTextState.value,
                            readEnabled = readingButtonEnabledState.value,
                            showButton = readingButtonVisibleState.value,
                            accentColorInt = accentColorState.value,
                            onReadClick = { adapter.delegate.readNextChapter(composeView) },
                        )
                    }
                }
            }
        }
    }

    /** Re-render the continue-reading button after chapters load: the phone scrollable header is
     *  bound once while chapters are still empty (showButtons false), so it must refresh on load. */
    fun refreshReadingButton() {
        val item = boundHeaderItem ?: return
        bindReadingButton(item)
        if (binding?.subItemGroup?.isVisible == true) {
            binding.buttonGroupCompose.isVisible = showReadingButton
        }
    }

    fun collapse() {
        binding ?: return
        if (!canCollapse) return
        binding.subItemGroup.isVisible = false
        binding.buttonGroupCompose.isVisible = false
        binding.metadataCompose.isVisible = false
        binding.mangaGenresTags.isVisible = false
        if (binding.moreButton.isVisible || binding.moreButton.isInvisible) {
            binding.moreButtonGroup.isInvisible = !isTablet
        } else {
            binding.lessButton.isVisible = false
        }
    }

    fun updateCover(manga: Manga) {
        val binding = binding ?: return
        if (!manga.initialized) return
        val sharedDrawable = adapter.controller.binding.mangaCoverFull.drawable
        val resolved = resolvedSeriesMetadata
        val coverUrl = resolved?.coverUrl
            ?.takeIf { it.isSupportedImageModel() && it != manga.thumbnail_url }
        val backdropUrl = resolved?.bannerUrl?.takeIf { it.isSupportedImageModel() } ?: coverUrl
        val renderState = HeaderCoverRenderState(manga.id, coverUrl, backdropUrl)
        val previousState = coverRenderState
        coverRenderState = renderState

        if (coverUrl == null) {
            coverRequest?.dispose()
            coverRequest = null
            if (binding.mangaCover.drawable !== sharedDrawable) {
                binding.mangaCover.setImageDrawable(sharedDrawable)
            }
        } else if (previousState?.mangaId != renderState.mangaId || previousState?.coverUrl != coverUrl) {
            coverRequest?.dispose()
            coverRequest = ImageRequest.Builder(itemView.context)
                .data(coverUrl)
                .placeholder(sharedDrawable)
                .error(sharedDrawable)
                .diskCachePolicy(CachePolicy.READ_ONLY)
                .size(ViewSizeResolver(binding.mangaCover))
                .precision(Precision.INEXACT)
                .target(
                    onSuccess = { image ->
                        if (coverRenderState != renderState) return@target
                        binding.mangaCover.setImageDrawable(image.asDrawable(itemView.resources))
                    },
                )
                .build()
                .let(itemView.context.imageLoader::enqueue)
        }

        if (backdropUrl == null) {
            backdropRequest?.dispose()
            backdropRequest = null
            if (binding.backdrop.drawable !== sharedDrawable) {
                binding.backdrop.setImageDrawable(sharedDrawable)
            }
            applyBlur()
        } else if (previousState?.mangaId != renderState.mangaId || previousState?.backdropUrl != backdropUrl) {
            loadBackdrop(backdropUrl, sharedDrawable, renderState)
        }
    }

    private fun loadBackdrop(
        url: String,
        placeholder: android.graphics.drawable.Drawable?,
        renderState: HeaderCoverRenderState,
    ) {
        val binding = binding ?: return
        backdropRequest?.dispose()
        backdropRequest = ImageRequest.Builder(itemView.context)
            .data(url)
            .placeholder(placeholder)
            .error(placeholder)
            .diskCachePolicy(CachePolicy.READ_ONLY)
            .size(ViewSizeResolver(binding.backdrop))
            .precision(Precision.INEXACT)
            .target(
                onSuccess = { image ->
                    if (coverRenderState != renderState) return@target
                    binding.backdrop.setImageDrawable(image.asDrawable(itemView.resources))
                    applyBlur()
                },
            )
            .build()
            .let(itemView.context.imageLoader::enqueue)
    }

    fun expand() {
        binding ?: return
        binding.subItemGroup.isVisible = true
        binding.mangaGenresTags.isVisible = showGenresSection
        if (!showMoreButton) {
            binding.moreButtonGroup.isVisible = false
        } else {
            if (binding.mangaSummary.maxLines != Integer.MAX_VALUE) {
                binding.moreButtonGroup.isVisible = showDescriptionSection && !isTablet
            } else {
                binding.lessButton.isVisible = showDescriptionSection && !isTablet
            }
        }
        binding.buttonGroupCompose.isVisible = showReadingButton
        binding.metadataCompose.isVisible = showMetadataSection
        applyDisplayVisibility()
    }

    override fun onLongClick(view: View?): Boolean {
        super.onLongClick(view)
        return false
    }
}

private const val CHARACTER_PREVIEW_LIMIT = 8
private const val EXTRA_IMAGE_LIMIT = 12
private val enrichmentJson = Json { ignoreUnknownKeys = true }

private data class ResolvedSeriesMetadata(
    val title: String,
    val author: String?,
    val artist: String?,
    val description: String?,
    val genres: List<String>,
    val status: String?,
    val coverUrl: String?,
    val bannerUrl: String?,
)

private data class MetadataRenderState(
    val mangaId: Long,
    val sourceId: Long,
    val knowledge: SeriesKnowledgeBundle?,
    val showMetadataSection: Boolean,
    val showAliases: Boolean,
    val showCharacters: Boolean,
    val showTrackers: Boolean,
    val showExtraImages: Boolean,
    val showQuotesTranslation: Boolean,
    val quoteCount: Int,
    val translationEnabled: Boolean,
    val translationMode: TranslationMode,
)

private data class DescriptionContent(
    val markdown: String?,
)

private data class DescriptionLayoutKey(
    val mangaId: Long?,
    val content: DescriptionContent?,
    val genres: List<String>,
    val initialized: Boolean,
)

private data class GenreRenderState(
    val genres: List<String>,
    val containerColor: Int,
    val labelColor: Int,
    val isNamespaceSource: Boolean,
)

private data class PagePreviewRenderState(
    val mangaId: Long,
    val sourceId: Long,
)

private data class HeaderCoverRenderState(
    val mangaId: Long?,
    val coverUrl: String?,
    val backdropUrl: String?,
)

private val EMPTY_DASH_LINE = Regex("""(?m)^\s*-\s*$""")
private val EMPTY_ASTERISK_LINE = Regex("""(?m)^\s*\*\s*$""")

internal data class SeriesCharacterCard(
    val name: String,
    val role: String?,
    val imageUrl: String?,
    val url: String?,
    val sourceLabel: String?,
) {
    val subtitle: String?
        get() = role?.takeIf { it.isNotBlank() } ?: sourceLabel?.takeIf { it.isNotBlank() }
}

internal data class SeriesExtraImage(
    val url: String,
    val label: String?,
)

@Composable
private fun MetadataSectionHost(
    state: MetadataRenderState?,
    isExpanded: Boolean,
    onOpenQuotes: () -> Unit,
    onOpenTranslationSettings: () -> Unit,
    onSearch: (String) -> Unit,
    onOpenLink: (String) -> Unit,
    openMetadataViewer: () -> Unit,
) {
    val renderState = state ?: return
    val animationMillis = motionAwareCrossfadeMillis(220)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(animationSpec = tween(durationMillis = animationMillis)),
    ) {
        AnimatedVisibility(
            visible = renderState.showMetadataSection,
            enter = fadeIn(animationSpec = tween(durationMillis = animationMillis)) +
                expandVertically(animationSpec = tween(durationMillis = animationMillis)),
            exit = fadeOut(animationSpec = tween(durationMillis = animationMillis)) +
                shrinkVertically(animationSpec = tween(durationMillis = animationMillis)),
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                SeriesEnrichmentSection(
                    knowledge = renderState.knowledge,
                    showAliases = renderState.showAliases,
                    showCharacters = renderState.showCharacters,
                    showTrackers = renderState.showTrackers,
                    showExtraImages = renderState.showExtraImages,
                    showQuotesTranslation = renderState.showQuotesTranslation,
                    quoteCount = renderState.quoteCount,
                    translationEnabled = renderState.translationEnabled,
                    translationMode = renderState.translationMode,
                    onOpenQuotes = onOpenQuotes,
                    onOpenTranslationSettings = onOpenTranslationSettings,
                    onSearch = onSearch,
                    onOpenLink = onOpenLink,
                )
                MangaMetadataSection(
                    mangaId = renderState.mangaId,
                    sourceId = renderState.sourceId,
                    isExpanded = isExpanded,
                    openMetadataViewer = openMetadataViewer,
                    onSearch = onSearch,
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SeriesEnrichmentSection(
    knowledge: SeriesKnowledgeBundle?,
    showAliases: Boolean,
    showCharacters: Boolean,
    showTrackers: Boolean,
    showExtraImages: Boolean,
    showQuotesTranslation: Boolean,
    quoteCount: Int,
    translationEnabled: Boolean,
    translationMode: TranslationMode,
    onOpenQuotes: () -> Unit,
    onOpenTranslationSettings: () -> Unit,
    onSearch: (String) -> Unit,
    onOpenLink: (String) -> Unit,
) {
    val displayValues = remember(knowledge, showTrackers) {
        knowledge?.metadataValues
            .orEmpty()
            .filter { value -> showTrackers || value.providerType != MetadataProviderType.TRACKER }
    }
    val aliases = remember(knowledge, showAliases) {
        if (!showAliases) {
            emptyList()
        } else {
            displayValues
                .filter { it.field == SeriesMetadataField.ALIASES.key }
                .flatMap { it.value.split(",") }
                .mapNotNull { it.trim().takeUnless(String::isBlank) }
                .distinct()
        }
    }
    val characters = remember(knowledge, showCharacters, showTrackers) {
        if (!showCharacters) {
            emptyList()
        } else {
            parseSeriesCharacters(knowledge, includeTrackers = showTrackers)
        }
    }
    val extraImages = remember(knowledge, showExtraImages) {
        if (!showExtraImages) {
            emptyList()
        } else {
            parseSeriesExtraImages(displayValues)
        }
    }
    if (
        aliases.isEmpty() &&
        characters.isEmpty() &&
        extraImages.isEmpty() &&
        !showQuotesTranslation
    ) {
        return
    }

    var charactersExpanded by remember(characters) { mutableStateOf(false) }
    val visibleCharacters = if (charactersExpanded) {
        characters
    } else {
        characters.take(CHARACTER_PREVIEW_LIMIT)
    }
    val hiddenCharacterCount = (characters.size - visibleCharacters.size).coerceAtLeast(0)
    val translationEntityCount = knowledge?.entities.orEmpty().size
    val translationEventCount = knowledge?.events.orEmpty().size
    val activeNudgeCount = knowledge?.nudges.orEmpty().count { it.active }

    @Composable
    fun Chip(
        text: String,
        onClick: (() -> Unit)? = null,
        prominent: Boolean = false,
    ) {
        val containerColor = when {
            prominent -> MaterialTheme.colorScheme.primaryContainer
            onClick != null -> MaterialTheme.colorScheme.secondaryContainer
            else -> MaterialTheme.colorScheme.surfaceVariant
        }
        val contentColor = when {
            prominent -> MaterialTheme.colorScheme.onPrimaryContainer
            onClick != null -> MaterialTheme.colorScheme.onSecondaryContainer
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        }
        val borderColor = when {
            prominent -> MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)
            onClick != null -> MaterialTheme.colorScheme.secondary.copy(alpha = 0.32f)
            else -> MaterialTheme.colorScheme.outlineVariant
        }
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
            Surface(
                modifier = Modifier
                    .padding(vertical = 3.dp)
                    .then(if (onClick != null) Modifier.combinedClickable(onClick = onClick) else Modifier),
                shape = CircleShape,
                color = containerColor,
                border = BorderStroke(1.dp, borderColor),
            ) {
                Text(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    text = text,
                    style = if (prominent) {
                        MaterialTheme.typography.labelMedium
                    } else {
                        MaterialTheme.typography.bodySmall
                    },
                    color = contentColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }

    @Composable
    fun SectionLabel(text: String) {
        Text(
            modifier = Modifier.padding(top = 2.dp),
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    @Composable
    fun QuotesTranslationBlock() {
        if (!showQuotesTranslation) return
        val modeLabel = stringResource(
            if (translationMode == TranslationMode.ADVANCED) {
                MR.strings.pref_translation_mode_advanced
            } else {
                MR.strings.pref_translation_mode_simple
            },
        )
        SectionLabel(stringResource(MR.strings.series_display_quotes_translation))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Chip(
                text = stringResource(MR.strings.series_translation_quote_count_format, quoteCount),
                onClick = onOpenQuotes,
                prominent = true,
            )
            if (translationEnabled) {
                Chip(
                    text = stringResource(MR.strings.series_translation_mode_status_format, modeLabel),
                    onClick = onOpenTranslationSettings,
                )
                if (translationMode == TranslationMode.ADVANCED && translationEntityCount > 0) {
                    Chip(
                        text = stringResource(
                            MR.strings.series_translation_entities_count_format,
                            translationEntityCount,
                        ),
                        onClick = onOpenTranslationSettings,
                    )
                }
                if (translationMode == TranslationMode.ADVANCED && translationEventCount > 0) {
                    Chip(
                        text = stringResource(
                            MR.strings.advanced_translation_event_count_format,
                            translationEventCount,
                        ),
                        onClick = onOpenTranslationSettings,
                    )
                }
                if (translationMode == TranslationMode.ADVANCED && activeNudgeCount > 0) {
                    Chip(
                        text = stringResource(
                            MR.strings.series_translation_nudges_count_format,
                            activeNudgeCount,
                        ),
                        onClick = onOpenTranslationSettings,
                    )
                }
            }
        }
    }

    @Composable
    fun CharacterCard(character: SeriesCharacterCard) {
        Surface(
            modifier = Modifier
                .width(218.dp)
                .height(92.dp)
                .combinedClickable(
                    onClick = {
                        character.url?.let(onOpenLink) ?: onSearch(character.name)
                    },
                ),
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.58f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            tonalElevation = 0.dp,
        ) {
            Row(
                modifier = Modifier.padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                val imageModifier = Modifier
                    .size(64.dp)
                    .clip(MaterialTheme.shapes.small)
                val imageUrl = character.imageUrl?.takeIf { it.isNotBlank() }
                if (imageUrl != null) {
                    AsyncImage(
                        modifier = imageModifier,
                        model = imageUrl,
                        contentDescription = character.name,
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Box(
                        modifier = imageModifier
                            .background(MaterialTheme.colorScheme.secondaryContainer),
                        contentAlignment = androidx.compose.ui.Alignment.Center,
                    ) {
                        Text(
                            text = character.name
                                .split(Regex("\\s+"))
                                .mapNotNull { it.firstOrNull()?.uppercaseChar()?.toString() }
                                .take(2)
                                .joinToString(""),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }
                Column(
                    modifier = Modifier.fillMaxHeight(),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = character.name,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    character.role?.takeIf { it.isNotBlank() }?.let { role ->
                        Text(
                            modifier = Modifier.padding(top = 4.dp),
                            text = role,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    character.sourceLabel?.takeIf { it.isNotBlank() && it != character.role }?.let { source ->
                        Text(
                            modifier = Modifier.padding(top = 2.dp),
                            text = source,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }

    @Composable
    fun CharacterActionCard(text: String, onClick: () -> Unit) {
        Surface(
            modifier = Modifier
                .width(104.dp)
                .height(92.dp)
                .combinedClickable(onClick = onClick),
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.secondaryContainer,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.28f)),
        ) {
            Box(
                modifier = Modifier.padding(12.dp),
                contentAlignment = androidx.compose.ui.Alignment.Center,
            ) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }

    @Composable
    fun CharacterSectionLabel() {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            SectionLabel(stringResource(MR.strings.series_display_characters))
            if (characters.size > 1) {
                Text(
                    modifier = Modifier.padding(top = 2.dp),
                    text = stringResource(MR.strings.series_metadata_character_count_format, characters.size),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }

    @Composable
    fun ExtraImagesBlock(images: List<SeriesExtraImage>) {
        if (images.isEmpty()) return
        SectionLabel(stringResource(MR.strings.series_display_extra_images))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(images, key = { _, image -> image.url }) { index, image ->
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    AsyncImage(
                        modifier = Modifier
                            .height(104.dp)
                            .aspectRatio(16f / 10f)
                            .clip(MaterialTheme.shapes.small),
                        model = image.url,
                        contentDescription = image.label
                            ?: stringResource(MR.strings.series_metadata_extra_image_description_format, index + 1),
                        contentScale = ContentScale.Crop,
                    )
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (aliases.isNotEmpty()) {
            SectionLabel(stringResource(MR.strings.series_display_aliases))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                aliases.forEach { alias ->
                    Chip(text = alias, onClick = { onSearch(alias) })
                }
            }
        }
        if (characters.isNotEmpty()) {
            CharacterSectionLabel()
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(visibleCharacters) { character ->
                    CharacterCard(character)
                }
                if (hiddenCharacterCount > 0) {
                    item {
                        CharacterActionCard(
                            text = stringResource(MR.strings.series_enrichment_more_count_format, hiddenCharacterCount),
                            onClick = { charactersExpanded = true },
                        )
                    }
                }
                if (charactersExpanded && characters.size > CHARACTER_PREVIEW_LIMIT) {
                    item {
                        CharacterActionCard(
                            text = stringResource(MR.strings.series_details_show_less),
                            onClick = { charactersExpanded = false },
                        )
                    }
                }
            }
        }
        QuotesTranslationBlock()
        ExtraImagesBlock(extraImages)
    }
}

private fun resolveSeriesMetadata(
    manga: Manga,
    knowledge: SeriesKnowledgeBundle?,
    includeTrackers: Boolean,
): ResolvedSeriesMetadata {
    val metadataValues = (
        manga.sourceMetadataValues() +
            knowledge?.metadataValues.orEmpty()
        )
        .filter { value ->
            includeTrackers || value.providerType != MetadataProviderType.TRACKER
        }
    val choices = knowledge?.metadataChoices.orEmpty().associateBy { it.field }

    fun chosenValue(field: SeriesMetadataField): SeriesMetadataValue? {
        val choice = choices[field.key] ?: return null
        return metadataValues
            .filter { it.field == field.key }
            .filter { it.providerType == choice.providerType && it.providerId == choice.providerId }
            .maxByOrNull { it.updatedAt }
    }

    fun chosenText(field: SeriesMetadataField, fallback: String?): String? =
        chosenValue(field)?.value?.trim()?.takeIf { it.isNotBlank() }
            ?: fallback?.trim()?.takeIf { it.isNotBlank() }

    val sourceGenres = manga.getGenres().orEmpty()
    val enrichedGenres = metadataValues
        .filter { it.field == SeriesMetadataField.GENRES.key }
        .flatMap { it.value.split(",", ";", "\n") }
        .mapNotNull { it.trim().takeUnless(String::isBlank) }
    val chosenGenres = chosenValue(SeriesMetadataField.GENRES)
        ?.value
        ?.split(",", ";", "\n")
        ?.mapNotNull { it.trim().takeUnless(String::isBlank) }
    val genres = (chosenGenres ?: (sourceGenres + enrichedGenres))
        .distinctBy { it.lowercase() }

    return ResolvedSeriesMetadata(
        title = chosenText(SeriesMetadataField.TITLE, manga.title) ?: manga.title,
        author = chosenText(SeriesMetadataField.AUTHOR, manga.author),
        artist = chosenText(SeriesMetadataField.ARTIST, manga.artist),
        description = chosenText(SeriesMetadataField.DESCRIPTION, manga.description),
        genres = genres,
        status = chosenText(SeriesMetadataField.STATUS, manga.status.toString()),
        coverUrl = chosenText(SeriesMetadataField.COVER, manga.thumbnail_url),
        bannerUrl = chosenText(
            SeriesMetadataField.BANNER,
            metadataValues
                .filter { it.field == SeriesMetadataField.BANNER.key }
                .maxByOrNull { it.updatedAt }
                ?.value,
        ),
    )
}

private fun Int.stringResourceForMangaStatus() = when (this) {
    SManga.ONGOING -> MR.strings.ongoing
    SManga.COMPLETED -> MR.strings.completed
    SManga.LICENSED -> MR.strings.licensed
    SManga.PUBLISHING_FINISHED -> MR.strings.publishing_finished
    SManga.CANCELLED -> MR.strings.cancelled
    SManga.ON_HIATUS -> MR.strings.on_hiatus
    else -> MR.strings.unknown_status
}

internal fun parseSeriesCharacters(
    knowledge: SeriesKnowledgeBundle?,
    includeTrackers: Boolean,
): List<SeriesCharacterCard> {
    val metadataCharacters = knowledge?.metadataValues
        .orEmpty()
        .filter { it.field == SeriesMetadataField.CHARACTERS.key }
        .filter { value -> includeTrackers || value.providerType != MetadataProviderType.TRACKER }
        .flatMap { value ->
            parseCharacterMetadata(value.extraJson, value.providerName) +
                parseCharacterMetadata(value.value, value.providerName) +
                parseDelimitedCharacterValues(value.value, value.providerName)
        }

    return metadataCharacters
        .mapNotNull { character ->
            character.name.trim().takeUnless(String::isBlank)?.let { character.copy(name = it) }
        }
        .sortedWith(
            compareByDescending<SeriesCharacterCard> { !it.url.isNullOrBlank() }
                .thenByDescending { !it.imageUrl.isNullOrBlank() },
        )
        .distinctBy { it.name.lowercase() }
}

private fun parseCharacterMetadata(raw: String?, providerName: String): List<SeriesCharacterCard> {
    val text = raw?.trim()?.takeIf { it.isNotBlank() } ?: return emptyList()
    val element = runCatching { enrichmentJson.parseToJsonElement(text) }.getOrNull() ?: return emptyList()
    return when (element) {
        is JsonArray -> element.toSeriesCharacters(providerName)
        is JsonObject -> element.toSeriesCharacters(providerName)
        else -> emptyList()
    }
}

private fun parseDelimitedCharacterValues(raw: String?, providerName: String): List<SeriesCharacterCard> =
    raw
        ?.split(Regex("[,;\\n]"))
        ?.mapNotNull { it.trim().takeUnless(String::isBlank) }
        ?.filterNot { it.startsWith("[") || it.startsWith("{") }
        ?.map {
            SeriesCharacterCard(
                name = it,
                role = null,
                imageUrl = null,
                url = null,
                sourceLabel = providerName,
            )
        }
        .orEmpty()

private fun JsonArray.toSeriesCharacters(providerName: String): List<SeriesCharacterCard> = buildList {
    for (element in this@toSeriesCharacters) {
        val nestedObject = element as? JsonObject
        if (nestedObject != null) {
            addAll(nestedObject.toSeriesCharacters(providerName))
        } else {
            element.cleanString()?.let {
                add(
                    SeriesCharacterCard(
                        name = it,
                        role = null,
                        imageUrl = null,
                        url = null,
                        sourceLabel = providerName,
                    ),
                )
            }
        }
    }
}

private fun JsonObject.toSeriesCharacters(providerName: String): List<SeriesCharacterCard> =
    listOfNotNull(characterEntry(providerName)) +
        listOf("characters", "items", "data").flatMap { key ->
            (get(key) as? JsonArray)?.toSeriesCharacters(providerName).orEmpty()
        }

private fun JsonObject.characterEntry(providerName: String): SeriesCharacterCard? {
    val name = firstNonBlank("name", "title", "full", "userPreferred", "native") ?: return null
    val imageUrl = firstNonBlank("imageUrl", "image", "thumbnailUrl", "thumbnail", "cover")
    val url = firstNonBlank("url", "siteUrl", "pageUrl", "trackingUrl", "tracking_url")
    val role = firstNonBlank("role", "type")
    val source = firstNonBlank("source", "provider", "site") ?: providerName
    return SeriesCharacterCard(
        name = name,
        role = role,
        imageUrl = imageUrl,
        url = url,
        sourceLabel = source,
    )
}

internal fun parseSeriesExtraImages(values: List<SeriesMetadataValue>): List<SeriesExtraImage> =
    values
        .asSequence()
        .filter { it.field == SeriesMetadataField.IMAGES.key }
        .flatMap { value ->
            (
                parseImageMetadata(value.extraJson) +
                    parseImageMetadata(value.value) +
                    parseDelimitedImageValues(value.value)
                )
                .asSequence()
                .map { image ->
                    if (image.label.isNullOrBlank()) {
                        image.copy(label = value.providerName.takeIf { it.isNotBlank() })
                    } else {
                        image
                    }
                }
        }
        .filter { it.url.isSupportedImageModel() }
        .distinctBy { it.url }
        .take(EXTRA_IMAGE_LIMIT)
        .toList()

private fun parseImageMetadata(raw: String?): List<SeriesExtraImage> {
    val text = raw?.trim()?.takeIf { it.isNotBlank() } ?: return emptyList()
    val element = runCatching { enrichmentJson.parseToJsonElement(text) }.getOrNull() ?: return emptyList()
    return when (element) {
        is JsonArray -> element.toSeriesExtraImages()
        is JsonObject -> element.toSeriesExtraImages()
        else -> emptyList()
    }
}

private fun parseDelimitedImageValues(raw: String?): List<SeriesExtraImage> =
    raw
        ?.split(Regex("[,;\\n]"))
        ?.mapNotNull { it.trim().takeUnless(String::isBlank) }
        ?.map { SeriesExtraImage(url = it, label = null) }
        .orEmpty()

private fun JsonArray.toSeriesExtraImages(): List<SeriesExtraImage> = buildList {
    for (element in this@toSeriesExtraImages) {
        val nestedObject = element as? JsonObject
        if (nestedObject != null) {
            addAll(nestedObject.toSeriesExtraImages())
        } else {
            element.cleanString()
                ?.let { add(SeriesExtraImage(url = it, label = null)) }
        }
    }
}

private fun JsonObject.toSeriesExtraImages(): List<SeriesExtraImage> = buildList {
    imageEntry()?.let(::add)
    listOf("images", "items", "extraImages").forEach { key ->
        (get(key) as? JsonArray)?.let { addAll(it.toSeriesExtraImages()) }
    }
}

private fun JsonObject.imageEntry(): SeriesExtraImage? {
    val url = firstNonBlank("url", "imageUrl", "image", "src", "thumbnailUrl", "thumbnail", "cover", "banner")
        ?: return null
    val label = firstNonBlank("title", "label", "caption", "alt", "description")
    return SeriesExtraImage(url = url, label = label)
}

private fun JsonObject.firstNonBlank(vararg keys: String): String? =
    keys.firstNotNullOfOrNull { key ->
        get(key)?.cleanString()
    }

private fun JsonElement.cleanString(): String? =
    runCatching { jsonPrimitive.content }
        .getOrNull()
        ?.trim()
        ?.takeUnless { it.isBlank() || it == "null" }

private fun String.isSupportedImageModel(): Boolean {
    val normalized = trim().lowercase()
    return normalized.startsWith("http://") ||
        normalized.startsWith("https://") ||
        normalized.startsWith("file://") ||
        normalized.startsWith("content://") ||
        normalized.startsWith("android.resource://") ||
        normalized.startsWith("data:image/")
}

private fun Long.isTodayOrFutureDay(): Boolean {
    fun startOfDay(timeMillis: Long): Long {
        return java.util.Calendar.getInstance().apply {
            this.timeInMillis = timeMillis
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    return startOfDay(this) >= startOfDay(System.currentTimeMillis())
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
