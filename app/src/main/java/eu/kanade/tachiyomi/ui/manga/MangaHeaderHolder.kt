package eu.kanade.tachiyomi.ui.manga

import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.text.method.LinkMovementMethod
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.drawable.toDrawable
import androidx.core.text.buildSpannedString
import androidx.core.text.color
import androidx.core.text.inSpans
import androidx.core.text.scale
import androidx.core.view.children
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.widget.TextViewCompat
import androidx.transition.TransitionSet
import androidx.vectordrawable.graphics.drawable.AnimatedVectorDrawableCompat
import coil.request.CachePolicy
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonGroup
import com.google.android.material.chip.Chip
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.database.models.Manga
import eu.kanade.tachiyomi.data.image.coil.LibraryMangaImageTarget
import eu.kanade.tachiyomi.data.image.coil.loadManga
import eu.kanade.tachiyomi.databinding.ChapterHeaderItemBinding
import eu.kanade.tachiyomi.databinding.MangaHeaderItemBinding
import eu.kanade.tachiyomi.source.SourceManager
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.ui.base.holder.BaseFlexibleViewHolder
import eu.kanade.tachiyomi.ui.reader.viewer.missingChaptersCount
import eu.kanade.tachiyomi.util.isLocal
import eu.kanade.tachiyomi.util.lang.toNormalized
import eu.kanade.tachiyomi.util.system.getResourceColor
import eu.kanade.tachiyomi.util.system.hueShiftedTo
import eu.kanade.tachiyomi.util.system.isInNightMode
import eu.kanade.tachiyomi.util.system.isLTR
import eu.kanade.tachiyomi.util.view.backgroundColor
import io.noties.markwon.Markwon
import io.noties.markwon.SoftBreakAddsNewLinePlugin
import io.noties.markwon.image.coil.CoilImagesPlugin
import io.noties.markwon.linkify.LinkifyPlugin
import kotlin.FloatArray

@SuppressLint("ClickableViewAccessibility")
class MangaHeaderHolder(
    view: View,
    private val adapter: MangaDetailsAdapter,
    startExpanded: Boolean,
    private val isTablet: Boolean = false,
) : BaseFlexibleViewHolder(view, adapter) {
    val binding: MangaHeaderItemBinding? =
        try {
            MangaHeaderItemBinding.bind(view)
        } catch (e: Exception) {
            null
        }
    private val chapterBinding: ChapterHeaderItemBinding? =
        try {
            ChapterHeaderItemBinding.bind(view)
        } catch (e: Exception) {
            null
        }

    // Captured once at inflation, before any tinting - the theme's own resolved text colors,
    // used as the base that applyTextColorTint() hue-shifts from on every recolor.
    private val baseTitleColor = binding?.title?.currentTextColor
    private val baseAuthorColor = binding?.mangaAuthor?.currentTextColor
    private val baseStatusColor = binding?.mangaStatus?.currentTextColor
    private val baseSourceColor = binding?.mangaSource?.currentTextColor
    private val baseSummaryLabelColor = binding?.mangaSummaryLabel?.currentTextColor
    private val baseSummaryColor = binding?.mangaSummary?.currentTextColor
    private val baseChaptersTitleColor = (binding?.chaptersTitle ?: chapterBinding?.chaptersTitle)?.currentTextColor
    private val baseFiltersTextColor = (binding?.filtersText ?: chapterBinding?.filtersText)?.currentTextColor
    private val baseWebviewIconColor = binding?.webviewButton?.iconTint?.defaultColor
    private val baseShareIconColor = binding?.shareButton?.iconTint?.defaultColor

    private var showReadingButton = true
    private var showMoreButton = true
    var hadSelection = false
    private var canCollapse = true
    private var forceFavoriteButtonResize = false

    init {

        if (binding == null) {
            with(chapterBinding) {
                this ?: return@with
                chapterLayout.setOnClickListener { adapter.delegate.showChapterFilter() }
            }
        }
        with(binding) {
            this ?: return@with
            startReadingButton.transitionName = "details start reading transition"
            chapterLayout.setOnClickListener { adapter.delegate.showChapterFilter() }
            startReadingButton.setOnClickListener { adapter.delegate.readNextChapter(it) }
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
                if (mangaSummary.isTextSelectable &&
                    !adapter.recyclerView.canScrollVertically(
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
                    adapter.delegate.copyContentToClipboard(it, R.string.title)
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
                    adapter.delegate.copyContentToClipboard(it, R.string.author)
                }
                true
            }
            mangaSummary.customSelectionActionModeCallback = adapter.delegate.customActionMode(mangaSummary)
            mangaSummary.movementMethod = LinkMovementMethod.getInstance()
            applyBlur()
            mangaCover.setOnClickListener { adapter.delegate.zoomImageFromThumb(coverCard) }
            mangaCover.setOnLongClickListener { view ->
                adapter.delegate.showCoverContextMenu(view)
                true
            }
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
        if (binding.moreButton.isVisible || isTablet) {
            androidx.transition.TransitionManager.endTransitions(adapter.controller.binding.recycler)
            binding.mangaSummary.maxLines = Integer.MAX_VALUE
            binding.mangaSummary.setTextIsSelectable(true)
            setDescription()
            binding.mangaGenresTags.isVisible = true
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
                val transition =
                    TransitionSet()
                        .addTransition(androidx.transition.ChangeBounds())
                        .addTransition(androidx.transition.Fade())
                        .addTransition(androidx.transition.Slide())
                transition.duration =
                    binding.root.resources
                        .getInteger(
                            android.R.integer.config_shortAnimTime,
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
        binding.moreButtonGroup.isVisible = true
        if (animated) {
            androidx.transition.TransitionManager.endTransitions(adapter.controller.binding.recycler)
            val animVector =
                AnimatedVectorDrawableCompat.create(
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
            val transition =
                TransitionSet()
                    .addTransition(androidx.transition.ChangeBounds())
                    .addTransition(androidx.transition.Fade())
            transition.duration =
                binding.root.resources
                    .getInteger(
                        android.R.integer.config_shortAnimTime,
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
        binding.mangaGenresTags.isVisible = false
        binding.lessButton.isVisible = false
        binding.title.maxLines = 4
        binding.mangaAuthor.maxLines = 2
        adapter.recyclerView.post {
            adapter.delegate.updateScroll()
        }
    }

    private fun markwon(): Markwon =
        Markwon
            .builder(itemView.context)
            .usePlugin(LinkifyPlugin.create())
            .usePlugin(SoftBreakAddsNewLinePlugin.create())
            .apply {
                if (adapter.preferences.renderDescriptionImages().get()) {
                    usePlugin(CoilImagesPlugin.create(itemView.context))
                }
            }.build()

    private fun setDescription() {
        if (binding != null) {
            val desc =
                adapter.controller
                    .mangaPresenter()
                    .manga.description
            binding.mangaSummary.text =
                when {
                    desc.isNullOrBlank() -> itemView.context.getString(R.string.no_description)
                    binding.mangaSummary.maxLines != Int.MAX_VALUE ->
                        desc
                            .replace(Regex("\\s+", setOf(RegexOption.MULTILINE)), " ")
                            .trim()
                    else -> markwon().toMarkdown(desc.trim())
                }
            // setTextIsSelectable() resets the movement method, so it must be re-applied
            // after every text/selectable change or link taps fall through to the row click.
            binding.mangaSummary.movementMethod = LinkMovementMethod.getInstance()
        }
    }

    fun bindChapters() {
        val presenter = adapter.delegate.mangaPresenter()
        val count = presenter.chapters.size
        val titleText = chaptersTitleText(count, presenter)
        if (binding != null) {
            binding.chaptersTitle.text = titleText
            binding.filtersText.text = presenter.currentFilters()
        } else if (chapterBinding != null) {
            chapterBinding.chaptersTitle.text = titleText
            chapterBinding.filtersText.text = presenter.currentFilters()
        }
    }

    private fun chaptersTitleText(
        count: Int,
        presenter: MangaDetailsPresenter,
    ): CharSequence {
        val base = itemView.resources.getQuantityString(R.plurals.chapters_plural, count, count)
        val missingCount = missingChapterCount(presenter)
        if (missingCount <= 0 || !adapter.preferences.showChapterMissingWarnings().get()) return base
        return buildSpannedString {
            append(base)
            append(" ")
            inSpans(StyleSpan(Typeface.NORMAL)) {
                scale(0.75f) {
                    color(itemView.context.getResourceColor(R.attr.colorError)) {
                        append(
                            "(" +
                                itemView.context.getString(
                                    R.string.missing_chapters_count,
                                    missingCount,
                                ) + ")",
                        )
                    }
                }
            }
        }
    }

    private fun missingChapterCount(presenter: MangaDetailsPresenter): Int = missingChaptersCount(presenter.chapters.map { it.chapter })

    @SuppressLint("SetTextI18n")
    fun bind(
        item: MangaHeaderItem,
        manga: Manga,
    ) {
        val presenter = adapter.delegate.mangaPresenter()
        if (binding == null) {
            if (chapterBinding != null) {
                val count = presenter.chapters.size
                chapterBinding.chaptersTitle.text = chaptersTitleText(count, presenter)
                chapterBinding.filtersText.text = presenter.currentFilters()
                applyTextColorTint()
                adapter.delegate.themeColors().accent?.let {
                    chapterBinding.filterButton.imageTintList = ColorStateList.valueOf(it)
                }
            }
            return
        }
        binding.title.text = manga.title
        binding.hayaiSourceDetailsFeaturesContent?.let(adapter.delegate::bindSourceDetailsFeatures)

        setGenreTags(binding, manga)

        if (manga.hasSameAuthorAndArtist) {
            binding.mangaAuthor.text = manga.author?.trim()
        } else {
            binding.mangaAuthor.text = listOfNotNull(manga.author?.trim(), manga.artist?.trim()).joinToString(", ")
        }
        setDescription()

        binding.mangaSummary.post {
            if (binding.subItemGroup.isVisible) {
                if (binding.mangaSummary.lineCount < 3 &&
                    manga.genre.isNullOrBlank() &&
                    binding.moreButton.isVisible &&
                    manga.initialized
                ) {
                    expandDesc()
                    binding.lessButton.isVisible = false
                    showMoreButton = binding.lessButton.isVisible
                    canCollapse = false
                }
            }
            if (adapter.hasFilter()) {
                collapse()
            } else {
                expand()
            }
        }
        binding.mangaSummaryLabel.text =
            itemView.context.getString(
                R.string.about_this_,
                manga.seriesType(itemView.context),
            )
        with(binding.favoriteButton) {
            icon =
                ContextCompat.getDrawable(
                    itemView.context,
                    when {
                        item.isLocked -> R.drawable.ic_lock_24dp
                        manga.favorite -> R.drawable.ic_heart_24dp
                        else -> R.drawable.ic_heart_outline_24dp
                    },
                )
            text =
                itemView.resources.getString(
                    when {
                        item.isLocked -> R.string.unlock
                        manga.favorite -> R.string.in_library
                        else -> R.string.add_to_library
                    },
                )
            isChecked = !item.isLocked && manga.favorite
            adapter.delegate.setFavButtonPopup(this)
            // MaterialButtonGroup locks the button's width on first pass, so resetting the width
            // forces a readjustment
            if (forceFavoriteButtonResize) {
                forceFavoriteButtonResize = false
                updateLayoutParams<ViewGroup.LayoutParams> { width = ViewGroup.LayoutParams.WRAP_CONTENT }
            }
        }
        binding.trueBackdrop.setBackgroundColor(
            adapter.delegate.themeColors().cover
                ?: itemView.context.getResourceColor(R.attr.background),
        )
        applyBackgroundTint(binding)
        applyTextColorTint()

        val tracked = presenter.isTracked() && !item.isLocked

        with(binding.trackButton) {
            isVisible = presenter.hasTrackers()
            text =
                itemView.context.getString(
                    if (tracked) {
                        R.string.tracked
                    } else {
                        R.string.tracking
                    },
                )

            icon =
                ContextCompat.getDrawable(
                    itemView.context,
                    if (tracked) R.drawable.ic_check_24dp else R.drawable.ic_sync_24dp,
                )
            isChecked = tracked
        }

        with(binding.startReadingButton) {
            val nextChapter = presenter.getNextUnreadChapter()
            isVisible = presenter.chapters.isNotEmpty() && !item.isLocked && !adapter.hasFilter()
            showReadingButton = isVisible
            isEnabled = (nextChapter != null)
            text =
                if (nextChapter != null) {
                    val number = adapter.decimalFormat.format(nextChapter.chapter_number.toDouble())
                    if (nextChapter.chapter_number > 0) {
                        resources.getString(
                            if (nextChapter.last_page_read > 0) {
                                R.string.continue_reading_chapter_
                            } else {
                                R.string.start_reading_chapter_
                            },
                            number,
                        )
                    } else {
                        resources.getString(
                            if (nextChapter.last_page_read > 0) {
                                R.string.continue_reading
                            } else {
                                R.string.start_reading
                            },
                        )
                    }
                } else {
                    resources.getString(R.string.all_chapters_read)
                }
        }

        val count = presenter.chapters.size
        binding.chaptersTitle.text = chaptersTitleText(count, presenter)

        binding.topView.updateLayoutParams<ConstraintLayout.LayoutParams> {
            height = adapter.delegate.topCoverHeight()
        }

        binding.mangaStatus.isVisible = manga.status != 0
        binding.mangaStatus.text = (
            itemView.context.getString(
                when (manga.status) {
                    SManga.ONGOING -> R.string.ongoing
                    SManga.COMPLETED -> R.string.completed
                    SManga.LICENSED -> R.string.licensed
                    SManga.PUBLISHING_FINISHED -> R.string.publishing_finished
                    SManga.CANCELLED -> R.string.cancelled
                    SManga.ON_HIATUS -> R.string.on_hiatus
                    else -> R.string.unknown_status
                },
            )
        )
        with(binding.mangaSource) {
            val enabledLanguages = presenter.preferences.enabledLanguages().get()

            text =
                buildSpannedString {
                    append(presenter.source.nameBasedOnEnabledLanguages(enabledLanguages))
                    if (presenter.source is SourceManager.StubSource &&
                        presenter.source.name != presenter.source.id.toString()
                    ) {
                        scale(0.9f) {
                            append(" (${context.getString(R.string.source_not_installed)})")
                        }
                    }
                }
        }

        binding.filtersText.text = presenter.currentFilters()

        if (manga.isLocal()) {
            (binding.buttonLayout as ViewGroup).removeView(binding.webviewButton)
            binding.buttonLayout.removeView(binding.shareButton)
        }

        if (!manga.initialized) return
        updateCover(manga)
        if (adapter.preferences.themeMangaDetails()) {
            updateColors(false)
        }
    }

    private fun setGenreTags(
        binding: MangaHeaderItemBinding,
        manga: Manga,
    ) {
        with(binding.mangaGenresTags) {
            removeAllViews()
            val dark = context.isInNightMode()
            val amoled =
                adapter.delegate
                    .mangaPresenter()
                    .preferences
                    .themeDarkAmoled()
                    .get()
            val baseTagColor = context.getResourceColor(R.attr.background)
            val tagAccentColor = adapter.delegate.themeColors().accent
            val bgArray = FloatArray(3)
            val accentArray = FloatArray(3)

            ColorUtils.colorToHSL(baseTagColor, bgArray)
            ColorUtils.colorToHSL(tagAccentColor ?: context.getResourceColor(R.attr.colorPrimary), accentArray)
            val downloadedColor =
                ColorUtils.setAlphaComponent(
                    ColorUtils.HSLToColor(
                        floatArrayOf(
                            if (tagAccentColor != null) accentArray[0] else bgArray[0],
                            bgArray[1],
                            (
                                when {
                                    amoled && dark -> 0.1f
                                    dark -> 0.225f
                                    else -> 0.85f
                                }
                            ),
                        ),
                    ),
                    199,
                )
            val textColor =
                ColorUtils.HSLToColor(
                    floatArrayOf(
                        accentArray[0],
                        accentArray[1],
                        if (dark) 0.945f else 0.175f,
                    ),
                )
            val states =
                arrayOf(
                    intArrayOf(-android.R.attr.state_activated),
                    intArrayOf(),
                )
            val colors =
                intArrayOf(
                    downloadedColor,
                    ColorUtils.blendARGB(
                        downloadedColor,
                        context.getResourceColor(R.attr.colorControlNormal),
                        0.25f,
                    ),
                )
            val colorStateList = ColorStateList(states, colors)
            if (manga.genre.isNullOrBlank().not()) {
                (manga.getGenres() ?: emptyList()).map { genreText ->
                    val chip =
                        LayoutInflater.from(binding.root.context).inflate(
                            R.layout.genre_chip,
                            this,
                            false,
                        ) as Chip
                    val id = View.generateViewId()
                    chip.id = id
                    chip.chipBackgroundColor = colorStateList
                    chip.setTextColor(textColor)
                    chip.text = genreText
                    chip.setOnClickListener {
                        adapter.delegate.showFloatingActionMode(chip, isTag = true)
                    }
                    chip.setOnLongClickListener {
                        adapter.delegate.copyContentToClipboard(genreText, genreText)
                        true
                    }
                    this.addView(chip)
                }
            }
        }
    }

    /** Marks that the next bind() should force the favorite button to re-measure its width */
    fun requestFavoriteButtonResize() {
        forceFavoriteButtonResize = true
    }

    fun clearDescFocus() {
        binding ?: return
        binding.mangaSummary.setTextIsSelectable(false)
        binding.mangaSummary.movementMethod = LinkMovementMethod.getInstance()
        binding.mangaSummary.clearFocus()
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

    /** Shifts the header/chapter-list text colors' hue to match the accent color, keeping their own saturation/lightness */
    private fun applyTextColorTint() {
        val accent = adapter.delegate.themeColors().accent
        binding?.let {
            baseTitleColor?.let { c -> it.title.setTextColor(c.hueShiftedTo(accent)) }
            baseAuthorColor?.let { c -> it.mangaAuthor.setTextColor(c.hueShiftedTo(accent)) }
            baseStatusColor?.let { c -> it.mangaStatus.setTextColor(c.hueShiftedTo(accent)) }
            baseSourceColor?.let { c -> it.mangaSource.setTextColor(c.hueShiftedTo(accent)) }
            baseSummaryLabelColor?.let { c -> it.mangaSummaryLabel.setTextColor(c.hueShiftedTo(accent)) }
            baseSummaryColor?.let { c -> it.mangaSummary.setTextColor(c.hueShiftedTo(accent)) }
            baseChaptersTitleColor?.let { c -> it.chaptersTitle.setTextColor(c.hueShiftedTo(accent)) }
            baseFiltersTextColor?.let { c -> it.filtersText.setTextColor(c.hueShiftedTo(accent)) }
        }
        chapterBinding?.let {
            baseChaptersTitleColor?.let { c -> it.chaptersTitle.setTextColor(c.hueShiftedTo(accent)) }
            baseFiltersTextColor?.let { c -> it.filtersText.setTextColor(c.hueShiftedTo(accent)) }
        }
    }

    /** Tints the plain-background fill areas around the backdrop and "more" fade with the page's themed background */
    private fun applyBackgroundTint(binding: MangaHeaderItemBinding) {
        val bgColor =
            adapter.delegate.themeColors().background
                ?: itemView.context.getResourceColor(R.attr.background)
        binding.backdropGradient.backgroundTintList = ColorStateList.valueOf(bgColor)
        binding.backdropFill.setBackgroundColor(bgColor)
        binding.moreBgGradient.backgroundTintList = ColorStateList.valueOf(bgColor)
        binding.moreBgSolid.setBackgroundColor(bgColor)
    }

    fun updateColors(updateAll: Boolean = true) {
        val accentColor = adapter.delegate.themeColors().accent ?: return
        if (binding == null) {
            if (chapterBinding != null) {
                chapterBinding.filterButton.imageTintList = ColorStateList.valueOf(accentColor)
                applyTextColorTint()
            }
            return
        }
        val manga = adapter.presenter.manga
        with(binding) {
            applyTextColorTint()
            trueBackdrop.setBackgroundColor(
                adapter.delegate.themeColors().cover
                    ?: trueBackdrop.context.getResourceColor(R.attr.background),
            )
            applyBackgroundTint(binding)
            TextViewCompat.setCompoundDrawableTintList(
                moreButton,
                ColorStateList.valueOf(accentColor),
            )
            moreButton.setTextColor(accentColor)
            TextViewCompat.setCompoundDrawableTintList(
                lessButton,
                ColorStateList.valueOf(accentColor),
            )
            lessButton.setTextColor(accentColor)

            filterButton.imageTintList = ColorStateList.valueOf(accentColor)

            val states =
                arrayOf(
                    intArrayOf(-android.R.attr.state_enabled),
                    intArrayOf(),
                )

            val colors =
                intArrayOf(
                    ColorUtils.setAlphaComponent(
                        root.context.getResourceColor(R.attr.tabBarIconInactive),
                        43,
                    ),
                    accentColor,
                )

            startReadingButton.backgroundTintList = ColorStateList(states, colors)

            val textColors =
                intArrayOf(
                    ColorUtils.setAlphaComponent(
                        root.context.getResourceColor(R.attr.colorOnSurface),
                        97,
                    ),
                    contrastingTextColor(accentColor),
                )
            val overflowButton =
                buttonLayout
                    .children
                    .first { it.tag == MaterialButtonGroup.OVERFLOW_BUTTON_TAG } as? MaterialButton
            overflowButton ?.iconTint = ColorStateList.valueOf(accentColor)
            startReadingButton.setTextColor(ColorStateList(states, textColors))
            baseWebviewIconColor?.let { webviewButton.iconTint = ColorStateList.valueOf(it.hueShiftedTo(accentColor)) }
            baseShareIconColor?.let { shareButton.iconTint = ColorStateList.valueOf(it.hueShiftedTo(accentColor)) }

            val checkedStates =
                arrayOf(
                    intArrayOf(android.R.attr.state_checked),
                    intArrayOf(),
                )
            val buttonOutlineColor =
                contrastSafeOutlineColor(
                    root.context.getResourceColor(R.attr.colorOutlineVariant).hueShiftedTo(accentColor),
                    binding.backdropFill.backgroundColor ?: root.context.getResourceColor(R.attr.background),
                )
            // Checked favorite/track buttons already get a filled background, so the outline
            // would be redundant there - only the icon-only buttons keep it in every state.
            val checkableOutline = ColorStateList(checkedStates, intArrayOf(Color.TRANSPARENT, buttonOutlineColor))
            val outlineColorList = ColorStateList.valueOf(buttonOutlineColor)
            favoriteButton.strokeColor = checkableOutline
            trackButton.strokeColor = checkableOutline
            webviewButton.strokeColor = outlineColorList
            shareButton.strokeColor = outlineColorList

            val onBGHsl = FloatArray(3)
            val accentHsl = FloatArray(3)
            ColorUtils.colorToHSL(root.context.getResourceColor(R.attr.colorOnBackground), onBGHsl)
            ColorUtils.colorToHSL(accentColor, accentHsl)

            var checkedIconColor =
                ColorUtils.HSLToColor(
                    floatArrayOf(
                        accentHsl[0],
                        onBGHsl[1],
                        onBGHsl[2],
                    ),
                )
            val bgCheckedColor =
                ColorUtils.setAlphaComponent(
                    ColorUtils.blendARGB(
                        accentColor,
                        binding.backdropFill.backgroundColor ?: root.context.getResourceColor(R.attr.background),
                        0.706f,
                    ),
                    255,
                )
            val bgCheckedColors =
                intArrayOf(
                    bgCheckedColor,
                    Color.TRANSPARENT,
                )
            val checkedTextColors =
                intArrayOf(
                    contrastingTextColor(bgCheckedColor),
                    root.context.getResourceColor(R.attr.colorOnBackground).hueShiftedTo(accentColor),
                )
            // Some themes have colorful text/icons by default with not good contrast
            if (ColorUtils.calculateContrast(checkedIconColor, bgCheckedColor) < 5) {
                val dark = root.context.isInNightMode()
                checkedIconColor =
                    ColorUtils.HSLToColor(
                        floatArrayOf(
                            accentHsl[0],
                            onBGHsl[1],
                            onBGHsl[2] + (0.25f * if (dark) 1 else -1),
                        ),
                    )
            }
            val checkedColors =
                intArrayOf(
                    checkedIconColor,
                    root.context.getResourceColor(R.attr.colorOnSurfaceVariant).hueShiftedTo(accentColor),
                )
            trackButton.setTextColor(ColorStateList(checkedStates, checkedTextColors))
            favoriteButton.setTextColor(ColorStateList(checkedStates, checkedTextColors))
            trackButton.iconTint = ColorStateList(checkedStates, checkedColors)
            favoriteButton.iconTint = ColorStateList(checkedStates, checkedColors)
            trackButton.backgroundTintList = ColorStateList(checkedStates, bgCheckedColors)
            favoriteButton.backgroundTintList = ColorStateList(checkedStates, bgCheckedColors)
            if (updateAll) {
                setGenreTags(this, manga)
            }
        }
    }

    /** Picks whichever of black/white has higher contrast against [background], guaranteeing WCAG AA-level legibility regardless of the cover's extracted accent hue. */
    private fun contrastingTextColor(background: Int): Int {
        val fullBackground = ColorUtils.setAlphaComponent(background, 255)
        val whiteContrast = ColorUtils.calculateContrast(Color.WHITE, fullBackground)
        val blackContrast = ColorUtils.calculateContrast(Color.BLACK, fullBackground)
        return if (whiteContrast >= blackContrast) Color.WHITE else Color.BLACK
    }

    /**
     * A themed [outline] hue-shifted to the accent, nudged away from [background]'s own lightness
     * if that leaves too little contrast to read as a border (e.g. a highly saturated background).
     */
    private fun contrastSafeOutlineColor(
        outline: Int,
        background: Int,
    ): Int {
        if (ColorUtils.calculateContrast(outline, ColorUtils.setAlphaComponent(background, 255)) >= 1.2f) return outline
        val bgHsl = FloatArray(3)
        ColorUtils.colorToHSL(background, bgHsl)
        val outlineHsl = FloatArray(3)
        ColorUtils.colorToHSL(outline, outlineHsl)
        outlineHsl[2] = (outlineHsl[2] + (0.25f * if (bgHsl[2] > 0.5f) -1 else 1)).coerceIn(0f, 1f)
        return ColorUtils.HSLToColor(outlineHsl)
    }

    fun updateTracking() {
        binding ?: return
        val presenter = adapter.delegate.mangaPresenter()
        val tracked = presenter.isTracked()
        with(binding.trackButton) {
            text =
                itemView.context.getString(
                    if (tracked) {
                        R.string.tracked
                    } else {
                        R.string.tracking
                    },
                )

            icon =
                ContextCompat.getDrawable(
                    itemView.context,
                    if (tracked) {
                        R.drawable
                            .ic_check_24dp
                    } else {
                        R.drawable.ic_sync_24dp
                    },
                )
            isChecked = tracked
        }
    }

    fun collapse() {
        binding ?: return
        if (!canCollapse) return
        binding.subItemGroup.isVisible = false
        binding.startReadingButton.isVisible = false
        if (binding.moreButton.isVisible || binding.moreButton.isInvisible) {
            binding.moreButtonGroup.isInvisible = !isTablet
        } else {
            binding.lessButton.isVisible = false
            binding.mangaGenresTags.isVisible = isTablet
        }
    }

    fun updateCover(manga: Manga) {
        binding ?: return
        if (!manga.initialized) return
        val drawable = adapter.controller.binding.mangaCoverFull.drawable
        // mangaCover and backdrop show the same cover, decoded once and applied to both together
        // instead of two separate requests finishing at two separate, unsynced moments.
        binding.mangaCover.loadManga(
            manga,
            builder = {
                placeholder(drawable)
                error(drawable)
                // The placeholder is this same cover already, so refreshing shouldn't fade into
                // itself just because the memory cache entry it reads was invalidated
                crossfade(false)
                if (manga.favorite) networkCachePolicy(CachePolicy.READ_ONLY)
                diskCachePolicy(CachePolicy.READ_ONLY)
                // A plain lambda target doesn't start Animatable results or observe the lifecycle
                // to pause/resume them, so an animated cover needs the real target class for that.
                target(
                    object : LibraryMangaImageTarget(binding.mangaCover, manga) {
                        override fun onStart(placeholder: Drawable?) {
                            super.onStart(placeholder)
                            placeholder?.let { setBackdrop(it) }
                        }

                        override fun onSuccess(result: Drawable) {
                            super.onSuccess(result)
                            setBackdrop(result)
                        }
                    },
                )
            },
        )
    }

    private fun setBackdrop(drawable: Drawable) {
        val bitmap = (drawable as? BitmapDrawable)?.bitmap
        if (bitmap == null) {
            binding?.backdrop?.setImageDrawable(drawable)
            return
        }
        val yOffset = (bitmap.height / 2 * 0.33).toInt()
        binding?.backdrop?.setImageDrawable(
            Bitmap
                .createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height - yOffset)
                .toDrawable(itemView.resources),
        )
        applyBlur()
    }

    fun expand() {
        binding ?: return
        binding.subItemGroup.isVisible = true
        if (!showMoreButton) {
            binding.moreButtonGroup.isVisible = false
        } else {
            if (binding.mangaSummary.maxLines != Integer.MAX_VALUE) {
                binding.moreButtonGroup.isVisible = !isTablet
            } else {
                binding.lessButton.isVisible = !isTablet
                binding.mangaGenresTags.isVisible = true
            }
        }
        binding.startReadingButton.isVisible = showReadingButton
    }

    override fun onLongClick(view: View?): Boolean {
        super.onLongClick(view)
        return false
    }
}
