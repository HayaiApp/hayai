package eu.kanade.tachiyomi.ui.manga

import android.animation.ValueAnimator
import exh.source.isEhBasedSource
import android.annotation.SuppressLint
import android.app.Activity
import android.app.PendingIntent
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.service.chooser.ChooserAction
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.ColorInt
import androidx.annotation.FloatRange
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.ActionMode
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.widget.SearchView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.net.toFile
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsCompat.Type.systemBars
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePaddingRelative
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.palette.graphics.Palette
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import co.touchlab.kermit.Logger
import coil3.asDrawable
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.size.SizeResolver
import com.bluelinelabs.conductor.ControllerChangeHandler
import com.bluelinelabs.conductor.ControllerChangeType
import com.google.android.material.chip.Chip
import com.google.android.material.snackbar.BaseTransientBottomBar
import com.google.android.material.snackbar.Snackbar
import dev.icerock.moko.resources.StringResource
import eu.davidea.flexibleadapter.FlexibleAdapter
import eu.davidea.flexibleadapter.SelectableAdapter
import com.google.android.material.R as materialR
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.coil.getBestColor
import eu.kanade.tachiyomi.data.database.models.Category
import eu.kanade.tachiyomi.data.database.models.Chapter
import eu.kanade.tachiyomi.data.database.models.isNovel
import eu.kanade.tachiyomi.data.database.models.seriesType
import eu.kanade.tachiyomi.data.database.models.vibrantCoverColor
import eu.kanade.tachiyomi.data.download.DownloadJob
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.data.notification.NotificationReceiver
import eu.kanade.tachiyomi.data.track.model.TrackSearch
import eu.kanade.tachiyomi.databinding.MangaDetailsControllerBinding
import eu.kanade.tachiyomi.domain.manga.models.Manga
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.icon
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.base.SmallToolbarInterface
import eu.kanade.tachiyomi.ui.base.controller.BaseCoroutineController
import eu.kanade.tachiyomi.ui.base.controller.DialogController
import eu.kanade.tachiyomi.ui.base.holder.BaseFlexibleViewHolder
import eu.kanade.tachiyomi.ui.library.FilteredLibraryController
import eu.kanade.tachiyomi.ui.library.LibraryController
import eu.kanade.tachiyomi.ui.main.FloatingSearchInterface
import eu.kanade.tachiyomi.ui.main.HingeSupportedController
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.ui.main.SearchActivity
import eu.kanade.tachiyomi.ui.manga.chapter.ChapterActionBarHandlers
import eu.kanade.tachiyomi.ui.manga.chapter.ChapterActionBarState
import eu.kanade.tachiyomi.ui.manga.chapter.ChapterHolder
import eu.kanade.tachiyomi.ui.manga.chapter.ChapterItem
import eu.kanade.tachiyomi.ui.manga.chapter.ChaptersSortBottomSheet
import eu.kanade.tachiyomi.ui.manga.chapter.MangaChapterActionBar
import eu.kanade.tachiyomi.ui.manga.track.TrackItem
import eu.kanade.tachiyomi.ui.manga.track.TrackingBottomSheet
import eu.kanade.tachiyomi.ui.manga.translation.showAdvancedTranslationSheet
import eu.kanade.tachiyomi.ui.migration.manga.design.PreMigrationController
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.recents.RecentsController
import eu.kanade.tachiyomi.ui.security.SecureActivityDelegate
import eu.kanade.tachiyomi.ui.source.BrowseController
import eu.kanade.tachiyomi.ui.source.browse.BrowseSourceController
import eu.kanade.tachiyomi.ui.source.globalsearch.GlobalSearchController
import eu.kanade.tachiyomi.ui.webview.WebViewActivity
import eu.kanade.tachiyomi.util.addOrRemoveToFavorites
import eu.kanade.tachiyomi.util.chapter.updateTrackChapterMarkedAsRead
import eu.kanade.tachiyomi.util.isLocal
import eu.kanade.tachiyomi.util.moveCategories
import eu.kanade.tachiyomi.util.storage.getUriCompat
import eu.kanade.tachiyomi.util.system.addCheckBoxPrompt
import eu.kanade.tachiyomi.util.system.contextCompatColor
import eu.kanade.tachiyomi.util.system.dpToPx
import eu.kanade.tachiyomi.util.system.e
import eu.kanade.tachiyomi.util.system.getResourceColor
import eu.kanade.tachiyomi.util.system.ignoredSystemInsets
import eu.kanade.tachiyomi.util.system.isInNightMode
import eu.kanade.tachiyomi.util.system.isLandscape
import eu.kanade.tachiyomi.util.system.isOnline
import eu.kanade.tachiyomi.util.system.isPromptChecked
import eu.kanade.tachiyomi.util.system.isTablet
import eu.kanade.tachiyomi.util.system.launchIO
import eu.kanade.tachiyomi.util.system.launchUI
import eu.kanade.tachiyomi.util.system.materialAlertDialog
import eu.kanade.tachiyomi.util.system.rootWindowInsetsCompat
import eu.kanade.tachiyomi.util.system.setCustomTitleAndMessage
import eu.kanade.tachiyomi.util.system.toast
import eu.kanade.tachiyomi.util.system.withUIContext
import eu.kanade.tachiyomi.util.view.activityBinding
import eu.kanade.tachiyomi.util.view.copyToClipboard
import eu.kanade.tachiyomi.util.view.findChild
import eu.kanade.tachiyomi.util.view.getText
import eu.kanade.tachiyomi.util.view.isControllerVisible
import eu.kanade.tachiyomi.util.view.previousController
import eu.kanade.tachiyomi.util.view.scrollViewWith
import eu.kanade.tachiyomi.util.view.setAction
import eu.kanade.tachiyomi.util.view.setMessage
import eu.kanade.tachiyomi.util.view.setNegativeButton
import eu.kanade.tachiyomi.util.view.setOnQueryTextChangeListener
import eu.kanade.tachiyomi.util.view.setPositiveButton
import eu.kanade.tachiyomi.util.view.setStyle
import eu.kanade.tachiyomi.util.view.setTextColorAlpha
import eu.kanade.tachiyomi.util.view.setTitle
import eu.kanade.tachiyomi.util.view.snack
import eu.kanade.tachiyomi.util.view.toolbarHeight
import eu.kanade.tachiyomi.util.view.withFadeTransaction
import eu.kanade.tachiyomi.widget.LinearLayoutManagerAccurateOffset
import hayai.novel.reader.quote.showQuotesSheet
import java.io.File
import java.io.IOException
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import yokai.domain.manga.interactor.FetchInterval
import yokai.domain.manga.models.cover
import yokai.i18n.MR
import yokai.presentation.core.Constants
import yokai.util.lang.getString
import android.R as AR

class MangaDetailsController :
    BaseCoroutineController<MangaDetailsControllerBinding, MangaDetailsPresenter>,
    FlexibleAdapter.OnItemClickListener,
    FlexibleAdapter.OnItemLongClickListener,
    ActionMode.Callback,
    MangaDetailsAdapter.MangaDetailsInterface,
    SmallToolbarInterface,
    HingeSupportedController,
    FlexibleAdapter.OnItemMoveListener {

    constructor(
        manga: Manga?,
        fromCatalogue: Boolean = false,
        smartSearchConfig: BrowseController.SmartSearchConfig? = null,
        update: Boolean = false,
        shouldLockIfNeeded: Boolean = false,
    ) : super(bundle(manga?.id, fromCatalogue, smartSearchConfig, update)) {
        this.shouldLockIfNeeded = shouldLockIfNeeded
        this.presenter = MangaDetailsPresenter(manga?.id!!).apply { setCurrentManga(manga) }
    }

    constructor(
        mangaId: Long,
        fromCatalogue: Boolean = false,
        smartSearchConfig: BrowseController.SmartSearchConfig? = null,
        update: Boolean = false,
        shouldLockIfNeeded: Boolean = false,
    ) : super(bundle(mangaId, fromCatalogue, smartSearchConfig, update)) {
        this.shouldLockIfNeeded = shouldLockIfNeeded
        this.presenter = MangaDetailsPresenter(mangaId)
    }

    constructor(bundle: Bundle) : this(bundle.getLong(Constants.MANGA_EXTRA)) {
        val notificationId = bundle.getInt("notificationId", -1)
        val context = applicationContext ?: return
        if (notificationId > -1) {
            NotificationReceiver.dismissNotification(
                context,
                notificationId,
                bundle.getInt("groupId", 0),
            )
        }
    }

    private val manga: Manga? get() = if (presenter.isMangaLateInitInitialized()) presenter.manga else null
    private var colorAnimator: ValueAnimator? = null
    override val presenter: MangaDetailsPresenter
    private var coverColor: Int? = null
    private var accentColor: Int? = null
    private var headerColor: Int? = null
    private var toolbarIsColored = false
    private var snack: Snackbar? = null
    val shouldLockIfNeeded: Boolean
    val fromCatalogue = args.getBoolean(FROM_CATALOGUE_EXTRA, false)
    private var trackingBottomSheet: TrackingBottomSheet? = null
    private var startingRangeChapterPos: Int? = null
    private var rangeMode: RangeMode? = null

    // Mihon-style multi-select: coexists with the legacy range/long-press-sheet path. When true,
    // taps toggle selection instead of opening the chapter, with actions in the app/bottom bars.
    private var isSelectionMode = false

    // Adapter position of the last chapter touched in selection mode; used as the shift-style
    // range anchor for the next long-press (mihon toggleSelection fromLongPress semantics).
    private var lastSelectionPosition: Int? = null

    // Drives the mihon-style bottom action bar (ComposeView island). Recomposes on assignment.
    private var actionBarState by mutableStateOf(ChapterActionBarState())
    private var chapterActionBarBottomInsetPx by mutableIntStateOf(0)
    private var editMangaController: EditMangaController? = null
    var refreshTracker: Int? = null
    private var chapterPopupMenu: Pair<Int, PopupMenu>? = null
    private var isPushing = true

    // Tablet Layout
    var isTablet = false
    private var tabletAdapter: MangaDetailsAdapter? = null

    private var query = ""
    private var adapter: MangaDetailsAdapter? = null

    private var actionMode: ActionMode? = null

    private var headerHeight = 0
    private var fullCoverActive = false
    var returningFromReader = false
    private var floatingActionMode: android.view.ActionMode? = null

    // Bundle-restore: presenter.manga loads async, so onViewCreated can't touch it. We mark the
    // deferred bits here and replay them once the presenter wakes us via onMangaInitialized().
    private var pendingMangaInitSetup = false

    override fun getTitle(): String? {
        val selectedCount = selectedChapters().size
        if (isSelectionMode && selectedCount > 0) {
            return selectedCount.toString()
        }
        return manga?.title
    }

    override fun createBinding(inflater: LayoutInflater) =
        MangaDetailsControllerBinding.inflate(inflater)

    //region UI Methods
    override fun onViewCreated(view: View) {
        super.onViewCreated(view)
        coverColor = null
        fullCoverActive = false
        setAccentColorValue()
        setHeaderColorValue()

        setTabletMode(view)
        setRecycler(view)
        ViewCompat.setOnApplyWindowInsetsListener(binding.fab) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())

            view.updateLayoutParams {
                if (this is CoordinatorLayout.LayoutParams) {
                    bottomMargin = systemBars.bottom + 16.dpToPx
                }
            }

            insets
        }
        if (presenter.isMangaLateInitInitialized()) {
            setPaletteColor()
        } else {
            pendingMangaInitSetup = true
        }
        adapter?.fastScroller = binding.fastScroller
        binding.fastScroller.addOnScrollStateChangeListener {
            activityBinding?.appBar?.y = 0f
        }
        binding.recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)

                if (isTablet) return

                syncReadingFabVisibility()
            }
        })
        binding.fab.transitionName = "details start reading transition"
        binding.fab.setOnClickListener { readNextChapter(it) }
        setupChapterActionBar()

        if (presenter.isMangaLateInitInitialized()) {
            presenter.onCreateLate()
        } else {
            pendingMangaInitSetup = true
        }
        binding.swipeRefresh.isRefreshing = presenter.isLoading
        binding.swipeRefresh.setOnRefreshListener { presenter.refreshAll() }
        updateToolbarTitleAlpha()
        if (useCoverColorTheming()) {
            setItemColors()
        }
    }

    /**
     * Called by the presenter once the bundle-restore `refreshMangaFromDb()` finishes. Replays
     * any view setup that depends on `presenter.manga` which `onViewCreated` had to skip.
     */
    fun onMangaInitialized() {
        if (!pendingMangaInitSetup) return
        if (view == null) return
        pendingMangaInitSetup = false
        adapter?.notifyItemChanged(0)
        tabletAdapter?.notifyItemChanged(0)
        setPaletteColor()
        presenter.onCreateLate()
        if (isControllerVisible) setTitle()
        activity?.invalidateOptionsMenu()
        if (useCoverColorTheming()) {
            setItemColors()
        }
    }

    override fun useCoverColorTheming(): Boolean {
        val mangaId = manga?.id ?: if (presenter.isMangaLateInitInitialized()) presenter.manga.id else null
        return presenter.preferences.themeMangaDetailsFor(mangaId)
    }

    private fun setAccentColorValue(colorToUse: Int? = null) {
        val context = view?.context ?: return
        setCoverColorValue(colorToUse)
        accentColor = if (useCoverColorTheming()) {
            (colorToUse ?: manga?.vibrantCoverColor)?.let {
                val luminance = ColorUtils.calculateLuminance(it).toFloat()
                // Softer blend than before (0.5/0.33 → 0.35/0.22): the previous factors pulled
                // the accent too far toward the contrast text color, washing out covers that
                // were already close to legible. Keeps the original luminance gates so out-of-
                // theme accents still get corrected, just less aggressively.
                if (if (!context.isInNightMode()) luminance > 0.4 else luminance <= 0.6) {
                    ColorUtils.blendARGB(
                        it,
                        context.contextCompatColor(R.color.colorOnDownloadBadgeDayNight),
                        (if (!context.isInNightMode()) luminance else -(luminance - 1)) *
                            if (context.isInNightMode()) 0.22f else 0.35f,
                    )
                } else {
                    it
                }
            }
        } else {
            null
        }
    }

    private fun setCoverColorValue(colorToUse: Int? = null) {
        val context = view?.context ?: return
        val colorBack = context.getResourceColor(android.R.attr.colorBackground)
        // When cover-color theming is off, leave coverColor null so consumers fall back to the
        // plain M3 colorBackground; no half-tinted colorSecondary blend.
        coverColor =
            if (!useCoverColorTheming()) {
                null
            } else {
                (colorToUse ?: manga?.vibrantCoverColor)?.let {
                    // this makes the color more consistent regardless of theme
                    val dominant = it
                    val domLum = ColorUtils.calculateLuminance(dominant)
                    val lumWrongForTheme =
                        (if (context.isInNightMode()) domLum > 0.8 else domLum <= 0.2)
                    ColorUtils.blendARGB(
                        it,
                        colorBack,
                        if (lumWrongForTheme) 0.9f else 0.7f,
                    )
                }
            }
    }

    private fun setRefreshStyle() {
        with(binding.swipeRefresh) {
            if (useCoverColorTheming() && accentColor != null && headerColor != null) {
                val newColor = makeColorFrom(
                    hueOf = accentColor!!,
                    satAndLumOf = context.getResourceColor(R.attr.actionBarTintColor),
                )
                setColorSchemeColors(newColor)
                setProgressBackgroundColorSchemeColor(headerColor!!)
            } else {
                setStyle()
            }
        }
    }

    private fun setHeaderColorValue(colorToUse: Int? = null) {
        val context = view?.context ?: return
        headerColor = if (useCoverColorTheming()) {
            (colorToUse ?: manga?.vibrantCoverColor)?.let { color ->
                val newColor =
                    makeColorFrom(color, context.getResourceColor(materialR.attr.colorPrimaryVariant))
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1 || context.isInNightMode()) {
                    activity?.window?.navigationBarColor = ColorUtils.setAlphaComponent(
                        newColor,
                        Color.alpha(activity?.window?.navigationBarColor ?: Color.BLACK),
                    )
                }
                newColor
            }
        } else {
            null
        }
        setRefreshStyle()
    }

    @ColorInt
    private fun makeColorFrom(@ColorInt hueOf: Int, @ColorInt satAndLumOf: Int): Int {
        val satLumArray = FloatArray(3)
        val hueArray = FloatArray(3)
        ColorUtils.colorToHSL(satAndLumOf, satLumArray)
        ColorUtils.colorToHSL(hueOf, hueArray)
        return ColorUtils.HSLToColor(
            floatArrayOf(
                hueArray[0],
                satLumArray[1],
                satLumArray[2],
            ),
        )
    }

    private fun setItemColors() {
        getHeader()?.updateColors()

        if ((adapter?.itemCount ?: 0) > 1) {
            if (isTablet) {
                val chapterHolder = binding.recycler.findViewHolderForAdapterPosition(0) as? MangaHeaderHolder
                chapterHolder?.updateColors()
            }
            (presenter.chapters).forEach { chapter ->
                val chapterHolder =
                    binding.recycler.findViewHolderForItemId(chapter.id!!) as? ChapterHolder
                        ?: return@forEach
                chapterHolder.notifyStatus(
                    chapter.status,
                    isLocked(),
                    chapter.progress,
                )
            }
        }

        val context = view?.context ?: return
        val backgroundColor = accentColor ?: return

        val states = arrayOf(
            intArrayOf(-AR.attr.state_enabled),
            intArrayOf(),
        )
        val colors = intArrayOf(
            ColorUtils.setAlphaComponent(context.getResourceColor(R.attr.tabBarIconInactive), 43),
            backgroundColor,
        )
        binding.fab.backgroundTintList = ColorStateList(states, colors)
        val textColors = intArrayOf(
            ColorUtils.setAlphaComponent(context.getResourceColor(materialR.attr.colorOnSurface), 97),
            context.getResourceColor(AR.attr.textColorPrimaryInverse),
        )
        binding.fab.setTextColor(ColorStateList(states, textColors))
        binding.fab.iconTint = ColorStateList(states, textColors)
    }

    /** Re-applies cover-derived theming after the edit screen changes the local override. */
    fun refreshCoverColorTheming() {
        applyCoverColorTheming()
    }

    /** Re-applies or clears cover-derived theming after the toggle flips. */
    private fun applyCoverColorTheming() {
        val on = useCoverColorTheming()
        if (on) {
            // Recompute from the cached vibrant color; falls back to a fresh Palette pass if
            // the cover hasn't been decoded yet.
            setAccentColorValue()
            setHeaderColorValue()
            if (accentColor == null) {
                setPaletteColor()
            } else {
                setItemColors()
            }
        } else {
            // Drop to plain M3: null color values, default FAB tints (style defaults), neutral
            // backdrop, default swipe style, and recolored chapter rows.
            setAccentColorValue()
            setHeaderColorValue()
            setCoverColorValue()
            resetItemColorsToDefault()
        }
        // colorToolbar early-returns when the colored state is unchanged, so toggling while
        // scrolled-down keeps the stale tint. Preserve the colored/transparent decision but
        // repaint unconditionally with the new headerColor.
        toolbarIsColored = !isTablet && binding.recycler.canScrollVertically(-1)
        setStatusBarAndToolbar()
        if (isControllerVisible) setTitle()
    }

    /** Clears accent-tinted FAB/header/chapter colors back to M3 defaults (OFF path). */
    private fun resetItemColorsToDefault() {
        val context = view?.context ?: return
        val onPrimary = context.getResourceColor(materialR.attr.colorOnPrimary)
        binding.fab.backgroundTintList =
            ColorStateList.valueOf(context.getResourceColor(AR.attr.colorPrimary))
        binding.fab.setTextColor(ColorStateList.valueOf(onPrimary))
        binding.fab.iconTint = ColorStateList.valueOf(onPrimary)
        getHeader()?.setBackDrop(context.getResourceColor(android.R.attr.colorBackground))
        // Lightweight default-color reset on the header sub-views instead of a full bind(),
        // which would re-load the cover and re-run the deferred ComposeViews (flash/relayout).
        getHeader()?.resetColorsToDefault()
        if ((adapter?.itemCount ?: 0) > 1) {
            presenter.chapters.forEach { chapter ->
                val chapterHolder =
                    binding.recycler.findViewHolderForItemId(chapter.id!!) as? ChapterHolder
                        ?: return@forEach
                chapterHolder.notifyStatus(chapter.status, isLocked(), chapter.progress)
            }
        }
    }

    /** Check if device is tablet, and use a second recycler to hold the details header if so */
    private fun setTabletMode(view: View) {
        isTablet = view.context.isTablet() && view.context.isLandscape()
        binding.tabletOverlay.isVisible = isTablet
        binding.tabletRecycler.isVisible = isTablet
        binding.tabletDivider.isVisible = isTablet
        if (isTablet) {
            binding.tabletRecycler.itemAnimator = null
            binding.recycler.updateLayoutParams<ViewGroup.LayoutParams> { width = 0 }
            tabletAdapter = MangaDetailsAdapter(this)
            binding.tabletRecycler.adapter = tabletAdapter
            binding.tabletRecycler.layoutManager = LinearLayoutManager(view.context)
            updateForHinge()
        }
    }

    override fun updateForHinge() {
        if (isTablet) {
            val hingeGapSize = (activity as? MainActivity)?.hingeGapSize?.takeIf { it > 0 }
            if (hingeGapSize != null) {
                binding.tabletDivider.updateLayoutParams<ViewGroup.LayoutParams> {
                    width = hingeGapSize
                }
                binding.tabletRecycler.updateLayoutParams<ConstraintLayout.LayoutParams> {
                    matchConstraintPercentWidth = 1f
                    width = 0
                    matchConstraintDefaultWidth =
                        ConstraintLayout.LayoutParams.MATCH_CONSTRAINT_SPREAD
                }
                val swipeCircle = binding.swipeRefresh.findChild<ImageView>()
                swipeCircle?.translationX =
                    (activity!!.window.decorView.width / 2 + hingeGapSize) /
                    2f
            } else {
                binding.tabletRecycler.updateLayoutParams<ConstraintLayout.LayoutParams> { matchConstraintPercentWidth = 0.4f }
            }
        }
    }

    override fun onDestroyView(view: View) {
        snack?.dismiss()
        // Tear down any active selection/range state so it doesn't leak across view recreation.
        actionMode?.finish()
        actionMode = null
        isSelectionMode = false
        lastSelectionPosition = null
        actionBarState = ChapterActionBarState(visible = false)
        rangeMode = null
        startingRangeChapterPos = null
        adapter = null
        finishFloatingActionMode()
        trackingBottomSheet = null
        super.onDestroyView(view)
    }

    /** Set adapter, insets, and scroll listener for recycler view */
    @SuppressLint("ClickableViewAccessibility")
    private fun setRecycler(view: View) {
        adapter = MangaDetailsAdapter(this)

        binding.recycler.adapter = adapter
        adapter?.isSwipeEnabled = true
        binding.recycler.layoutManager = LinearLayoutManagerAccurateOffset(view.context)
        binding.recycler.addItemDecoration(
            MangaDetailsDivider(view.context),
        )
        binding.recycler.setHasFixedSize(true)
        binding.recycler.setItemViewCacheSize(8)
        binding.recycler.itemAnimator = null
        val appbarHeight = activityBinding?.appBar?.attrToolbarHeight ?: 0
        val offset = 10.dpToPx
        binding.swipeRefresh.setDistanceToTriggerSync(70.dpToPx)

        if (isTablet) {
            val tHeight = toolbarHeight.takeIf { (it ?: 0) > 0 } ?: appbarHeight
            val insetsCompat =
                view.rootWindowInsetsCompat ?: activityBinding?.root?.rootWindowInsetsCompat
            headerHeight = tHeight + (insetsCompat?.getInsets(systemBars())?.top ?: 0)
            binding.recycler.updatePaddingRelative(top = headerHeight + 4.dpToPx)
        }
        scrollViewWith(
            binding.recycler,
            padBottom = true,
            customPadding = true,
            swipeRefreshLayout = binding.swipeRefresh,
            afterInsets = { insets ->
                setInsets(insets, appbarHeight, offset)
            },
            liftOnScroll = {
                colorToolbar(it)
            },
        )
        binding.recycler.addOnScrollListener(
            object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    super.onScrolled(recyclerView, dx, dy)
                    if (!isTablet) {
                        updateToolbarTitleAlpha(isScrollingDown = dy > 0 && !binding.root.context.isTablet())
                        val atTop = !recyclerView.canScrollVertically(-1)
                        val tY = getHeader()?.binding?.backdrop?.translationY ?: 0f
                        getHeader()?.binding?.backdrop?.translationY = max(0f, tY + dy * 0.25f)
                        if (atTop) getHeader()?.binding?.backdrop?.translationY = 0f
                    }
                }

                override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                    val atTop = !recyclerView.canScrollVertically(-1)
                    updateToolbarTitleAlpha()
                    if (atTop) getHeader()?.binding?.backdrop?.translationY = 0f
                }
            },
        )

        binding.touchView.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                finishFloatingActionMode()
                val hingeGapSize = (activity as? MainActivity)?.hingeGapSize?.takeIf { it > 0 }
                if (hingeGapSize != null) {
                    val swipeCircle = binding.swipeRefresh.findChild<ImageView>()
                    swipeCircle?.translationX = (binding.root.width / 2 + hingeGapSize) / 2 *
                        (if (event.x > binding.root.width / 2) 1 else -1).toFloat()
                }
            }
            false
        }
    }

    private fun finishFloatingActionMode() {
        floatingActionMode ?: return
        floatingActionMode?.finish()
        floatingActionMode = null
    }

    private fun setInsets(insets: WindowInsetsCompat, appbarHeight: Int, offset: Int) {
        val systemInsets = insets.ignoredSystemInsets
        val fabPadding = 72.dpToPx // FAB height (56dp) + margin (16dp)
        binding.recycler.updatePaddingRelative(bottom = systemInsets.bottom + fabPadding)
        binding.tabletRecycler.updatePaddingRelative(bottom = systemInsets.bottom)
        chapterActionBarBottomInsetPx = systemInsets.bottom
        val tHeight = toolbarHeight.takeIf { it ?: 0 > 0 } ?: appbarHeight
        headerHeight = tHeight + systemInsets.top
        binding.swipeRefresh.setProgressViewOffset(false, (-40).dpToPx, headerHeight + offset)
        if (isTablet) {
            binding.tabletOverlay.updateLayoutParams<ViewGroup.LayoutParams> {
                height = headerHeight
            }
            // 4dp extra to line up chapter header and manga header
            binding.recycler.updatePaddingRelative(top = headerHeight + 4.dpToPx)
        }
        getHeader()?.setTopHeight(headerHeight)
        binding.fastScroller.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            topMargin = headerHeight
            bottomMargin = systemInsets.bottom
        }
        binding.fastScroller.scrollOffset = headerHeight
    }

    /** Set the toolbar to fully transparent or colored and translucent */
    private fun colorToolbar(isColor: Boolean, animate: Boolean = true) {
        if (isColor == toolbarIsColored || (isTablet && isColor)) return
        val activity = activity ?: return
        toolbarIsColored = isColor
        if (isControllerVisible) setTitle()
        if (actionMode != null) {
            return
        }
        val scrollingColor = headerColor ?: activity.getResourceColor(materialR.attr.colorPrimaryVariant)
        val topColor = ColorUtils.setAlphaComponent(scrollingColor, 0)
        val scrollingStatusColor =
            ColorUtils.setAlphaComponent(scrollingColor, (0.87f * 255).roundToInt())
        colorAnimator?.cancel()
        if (animate) {
            val cA = ValueAnimator.ofFloat(
                if (toolbarIsColored) 0f else 1f,
                if (toolbarIsColored) 1f else 0f,
            )
            colorAnimator = cA
            colorAnimator?.duration = 250 // milliseconds
            colorAnimator?.addUpdateListener { animator ->
                activityBinding?.appBar?.setBackgroundColor(
                    ColorUtils.blendARGB(
                        topColor,
                        scrollingColor,
                        animator.animatedValue as Float,
                    ),
                )
                activity.window?.statusBarColor = if (toolbarIsColored) {
                    ColorUtils.blendARGB(
                        topColor,
                        scrollingStatusColor,
                        animator.animatedValue as Float,
                    )
                } else {
                    Color.TRANSPARENT
                }
            }
            cA.start()
        } else {
            activityBinding?.appBar?.setBackgroundColor(if (toolbarIsColored) scrollingColor else topColor)
            activity.window?.statusBarColor =
                if (toolbarIsColored) scrollingStatusColor else topColor
        }
    }

    /** Get the color of the manga cover*/
    fun setPaletteColor() {
        val view = view ?: return
        // Bundle-restore can call into here before presenter.manga lateinit is loaded from DB.
        // onMangaInitialized() will re-invoke us once that completes.
        if (!presenter.isMangaLateInitInitialized()) return

        val request = ImageRequest.Builder(view.context)
            .data(presenter.manga.cover())
            .size(SizeResolver.ORIGINAL)
            .allowHardware(false)
            .target(
                onSuccess = { image ->
                    val drawable = image.asDrawable(view.context.resources)

                    val copy = (drawable as? BitmapDrawable)?.let {
                        BitmapDrawable(
                            view.context.resources,
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                                it.bitmap.copy(Bitmap.Config.HARDWARE, false)
                            else
                                it.bitmap.copy(it.bitmap.config!!, false),
                        )
                    } ?: drawable

                    // Don't use 'copy', Palette doesn't like its bitmap, could be caused by mutability is disabled,
                    // or perhaps because it's HARDWARE configured, not entirely sure why, the behaviour is not
                    // documented by Google.
                    val bitmap = (drawable as? BitmapDrawable)?.bitmap
                    // Skip Palette work on cache hit; cached color is set by MangaCoverMetadata.
                    val cachedVibrant = manga?.vibrantCoverColor
                    if (bitmap != null) {
                        if (cachedVibrant != null && useCoverColorTheming()) {
                            // Defer one frame so the palette-application work (color blends +
                            // setItemColors iterating every visible ChapterHolder) doesn't run
                            // mid-push inside Coil's main-thread onSuccess callback, stalling
                            // the push animator's frame budget.
                            launchUI {
                                setAccentColorValue(cachedVibrant)
                                setHeaderColorValue(cachedVibrant)
                                setItemColors()
                            }
                        } else {
                            Palette.from(bitmap).generate { palette ->
                                if (useCoverColorTheming()) {
                                    launchUI {
                                        val vibrantColor = palette?.getBestColor() ?: return@launchUI
                                        manga?.vibrantCoverColor = vibrantColor
                                        setAccentColorValue(vibrantColor)
                                        setHeaderColorValue(vibrantColor)
                                        setItemColors()
                                    }
                                } else {
                                    setCoverColorValue()
                                    coverColor?.let { color -> getHeader()?.setBackDrop(color) }
                                }
                            }
                        }
                    }
                    binding.mangaCoverFull.setImageDrawable(copy)
                    getHeader()?.updateCover(manga!!)
                },
                onError = {
                    val file = presenter.coverCache.getCoverFile(manga!!.thumbnail_url, !manga!!.favorite)
                    if (file != null && file.exists()) {
                        file.delete()
                        setPaletteColor()
                    }
                },
            ).build()
        view.context.imageLoader.enqueue(request)
    }

    private fun setStatusBarAndToolbar() {
        val topColor = Color.TRANSPARENT
        val scrollingColor = headerColor ?: activity!!.getResourceColor(materialR.attr.colorPrimaryVariant)
        val scrollingStatusColor =
            ColorUtils.setAlphaComponent(scrollingColor, (0.87f * 255).roundToInt())
        val forceColored = isSelectionMode
        activity?.window?.statusBarColor = if (forceColored || toolbarIsColored) scrollingStatusColor else topColor
        activityBinding?.appBar?.setBackgroundColor(
            if (forceColored || toolbarIsColored) scrollingColor else topColor,
        )
    }

    //endregion

    //region Lifecycle methods
    override fun onActivityResumed(activity: Activity) {
        super.onActivityResumed(activity)
        if (adapter != null && !isPushing) {
            presenter.isLockedFromSearch =
                shouldLockIfNeeded && SecureActivityDelegate.shouldBeLocked()
            presenter.headerItem.isLocked = presenter.isLockedFromSearch
            // Run the DB refresh + dependent calls off Main; ordering preserved by sequencing inside the launch.
            viewScope.launch {
                presenter.refreshMangaFromDb()
                presenter.syncData()
                presenter.fetchChapters(refreshTracker == null)
                if (refreshTracker != null) {
                    trackingBottomSheet?.refreshItem(refreshTracker ?: 0)
                    presenter.refreshTracking(trackIndex = refreshTracker)
                    refreshTracker = null
                }
                // fetch cover again in case the user set a new cover while reading
                setPaletteColor()
            }
        }
        if (isControllerVisible) {
            setStatusBarAndToolbar()
            val searchView =
                activityBinding?.toolbar?.menu?.findItem(R.id.action_search)?.actionView as? SearchView
            searchView?.post {
                setSearchViewListener(searchView)
            }
        }
    }

    override fun onAttach(view: View) {
        super.onAttach(view)
        if (!returningFromReader) return
        returningFromReader = false
        // DB await off Main so reader-back transition isn't blocked for up to 1s.
        viewScope.launch {
            val chapters = withTimeoutOrNull(1000) { presenter.getChaptersNow() } ?: return@launch
            tabletAdapter?.notifyItemChanged(0)
            addMangaHeader()
            adapter?.setChapters(chapters)
            updateFab()
        }
    }

    override fun onChangeStarted(handler: ControllerChangeHandler, type: ControllerChangeType) {
        super.onChangeStarted(handler, type)
        isPushing = true
        if (type.isEnter) {
            if (isControllerVisible) {
                // Apply MangaDetails-specific window/toolbar tint state on top of the
                // activity's chrome.
                updateToolbarTitleAlpha(0f)
                setStatusBarAndToolbar()
            }
        } else {
            if (router.backstack.lastOrNull()?.controller is DialogController) {
                return
            }
            colorAnimator?.cancel()

            getHeader()?.clearDescFocus()
            val colorSurface = activity?.getResourceColor(
                materialR.attr.colorSurface,
            ) ?: Color.BLACK
            if (router.backstackSize > 0 &&
                router.backstack.last().controller !is MangaDetailsController
            ) {
                if (router.backstack.last().controller !is FloatingSearchInterface) {
                    activityBinding?.appBar?.setBackgroundColor(colorSurface)
                }
                activity?.window?.statusBarColor = activity?.getResourceColor(
                    AR.attr.statusBarColor,
                ) ?: colorSurface
            }
        }
    }


    override fun onChangeEnded(
        changeHandler: ControllerChangeHandler,
        type: ControllerChangeType,
    ) {
        super.onChangeEnded(changeHandler, type)
        isPushing = false
        if (type == ControllerChangeType.PUSH_ENTER) {
            binding.swipeRefresh.isRefreshing = presenter.isLoading
        }
        if (!type.isEnter) {
            activityBinding?.root?.clearFocus()
        }
    }
    //endregion

    fun isNotOnline(showSnackbar: Boolean = true): Boolean {
        if (activity == null || !activity!!.isOnline()) {
            if (showSnackbar) view?.snack(MR.strings.no_network_connection)
            return true
        }
        return false
    }

    fun showError(message: String) {
        binding.swipeRefresh.isRefreshing = presenter.isLoading
        Logger.e { "MangaDetails showError: $message" }
        try {
            view?.snack(message)
        } catch (e: Exception) {
            Logger.e(e) { "Snackbar crash, falling back to toast" }
            activity?.toast(message)
        }
    }

    fun showChaptersRemovedPopup(deletedChapters: List<ChapterItem>) {
        val context = activity ?: return
        val deleteRemovedPref = presenter.preferences.deleteRemovedChapters()
        when (deleteRemovedPref.get()) {
            2 -> {
                presenter.deleteChapters(deletedChapters, false)
                return
            }
            1 -> return
            else -> {
                val chapterNames = deletedChapters.map { it.name }
                context.materialAlertDialog()
                    .setCustomTitleAndMessage(
                        MR.strings.chapters_removed,
                        context.getString(
                            MR.plurals.deleted_chapters,
                            deletedChapters.size,
                            deletedChapters.size,
                            if (deletedChapters.size > 5) {
                                "${chapterNames.take(5 - 1).joinToString(", ")}, " +
                                    context.getString(
                                        MR.plurals.notification_and_n_more,
                                        (chapterNames.size - (4 - 1)),
                                        (chapterNames.size - (4 - 1)),
                                    )
                            } else {
                                chapterNames.joinToString(", ")
                            },
                        ),
                    )
                    .addCheckBoxPrompt(MR.strings.remember_this_choice)
                    .setPositiveButton(MR.strings.delete) { dialog, _ ->
                        presenter.deleteChapters(deletedChapters, false)
                        if (dialog.isPromptChecked) deleteRemovedPref.set(2)
                    }
                    .setNegativeButton(MR.strings.keep) { dialog, _ ->
                        if (dialog.isPromptChecked) deleteRemovedPref.set(1)
                    }
                    .setCancelable(false)
                    .show()
            }
        }
    }

    fun setRefresh(enabled: Boolean) {
        binding.swipeRefresh.isRefreshing = enabled
    }

    fun updateChapterDownload(download: Download) {
        getHolder(download.chapter)?.notifyStatus(
            download.status,
            presenter.isLockedFromSearch,
            download.progress,
            true,
        )
    }

    private fun getHolder(chapter: Chapter): ChapterHolder? {
        return binding.recycler.findViewHolderForItemId(chapter.id!!) as? ChapterHolder
    }

    private fun getHeader(): MangaHeaderHolder? {
        if (!isBindingInitialized) return null

        return if (isTablet) {
            binding.tabletRecycler.findViewHolderForAdapterPosition(0) as? MangaHeaderHolder
        } else {
            binding.recycler.findViewHolderForAdapterPosition(0) as? MangaHeaderHolder
        }
    }

    fun updateHeader() {
        binding.swipeRefresh.isRefreshing = presenter.isLoading
        tabletAdapter?.notifyItemChanged(0)
        // addMangaHeader before setChapters so the header is in place before chapter rows
        // fill in; otherwise the header insert shifts every chapter row down ("jump from
        // their place"). addMangaHeader is a no-op when the header is already present.
        addMangaHeader()
        adapter?.setChapters(presenter.chapters)
        updateMenuVisibility(activityBinding?.toolbar?.menu)
    }

    fun updateFab() {
        val context = view?.context ?: return
        val nextChapter = presenter.getNextUnreadChapter()
        binding.fab.isEnabled = nextChapter != null
        if (nextChapter != null) {
            binding.fab.text = context.getString(
                if (nextChapter.last_page_read > 0) {
                    MR.strings.resume
                } else {
                    MR.strings.start_reading
                },
            )
        } else {
            binding.fab.isVisible = false
        }
        syncReadingFabVisibility()
    }

    private fun syncReadingFabVisibility() {
        if (!isBindingInitialized) return
        val shouldShow = shouldShowReadingFab()
        if (shouldShow) {
            binding.fab.show()
        } else if (binding.fab.isVisible) {
            binding.fab.hide()
        }
    }

    private fun shouldShowReadingFab(): Boolean {
        if (isTablet || !binding.fab.isEnabled || isSelectionMode) return false
        val headerBinding = getHeader()?.binding ?: return true
        if (headerBinding.buttonGroupCompose.isVisible != true) return false

        val bound = Rect()
        binding.recycler.getHitRect(bound)
        return !headerBinding.buttonGroupCompose.getLocalVisibleRect(bound)
    }

    fun updateChapters() {
        view ?: return
        binding.swipeRefresh.isRefreshing = presenter.isLoading
        tabletAdapter?.notifyItemChanged(0)
        addMangaHeader()
        adapter?.setChapters(presenter.chapters)
        updateFab()
        // Phone scrollable header binds once while chapters are empty; refresh the read button now.
        getHeader()?.refreshReadingButton()
        colorToolbar(binding.recycler.canScrollVertically(-1))
        updateMenuVisibility(activityBinding?.toolbar?.menu)
    }

    private fun addMangaHeader() {
        val tabletHeader = presenter.tabletChapterHeaderItem
        if (tabletHeader != null && tabletAdapter?.scrollableHeaders?.isEmpty() == true) {
            tabletAdapter?.removeAllScrollableHeaders()
            tabletAdapter?.addScrollableHeader(presenter.headerItem)
            adapter?.removeAllScrollableHeaders()
            adapter?.addScrollableHeader(tabletHeader)
        } else if (adapter?.scrollableHeaders?.isEmpty() == true) {
            adapter?.removeAllScrollableHeaders()
            adapter?.addScrollableHeader(presenter.headerItem)
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    fun refreshAdapter() = adapter?.notifyDataSetChanged()

    override fun onItemClick(view: View?, position: Int): Boolean {
        val chapterItem = (adapter?.getItem(position) as? ChapterItem) ?: return false
        val chapter = chapterItem.chapter
        // mihon-style multi-select: a tap toggles this chapter's selection (no range anchor).
        // Single source of truth: any selected chapter means taps toggle selection.
        if (isSelectionMode || selectedChapters().isNotEmpty()) {
            toggleChapterSelection(position)
            return false
        }
        // Legacy download-range gesture: startDownloadRange sets rangeMode before routing the tap here.
        if (!isSelectionMode && rangeMode != null) {
            if (startingRangeChapterPos == null) {
                adapter?.addSelection(position)
                (binding.recycler.findViewHolderForAdapterPosition(position) as? BaseFlexibleViewHolder)
                    ?.toggleActivation()
                (binding.recycler.findViewHolderForAdapterPosition(position) as? ChapterHolder)
                    ?.notifyStatus(Download.State.CHECKED, false, 0)
                startingRangeChapterPos = position
                actionMode?.invalidate()
            } else {
                val rangeMode = rangeMode ?: return false
                val startingPosition = startingRangeChapterPos ?: return false
                var chapterList = listOf<ChapterItem>()
                when {
                    startingPosition > position ->
                        chapterList = presenter.chapters.subList(position - 1, startingPosition)
                    startingPosition <= position ->
                        chapterList = presenter.chapters.subList(startingPosition - 1, position)
                }
                when (rangeMode) {
                    RangeMode.Download -> downloadChapters(chapterList)
                    RangeMode.RemoveDownload -> massDeleteChapters(
                        chapterList.filter { it.status != Download.State.NOT_DOWNLOADED },
                        false,
                    )
                }
                presenter.fetchChapters(false)
                adapter?.removeSelection(startingPosition)
                (binding.recycler.findViewHolderForAdapterPosition(startingPosition) as? BaseFlexibleViewHolder)
                    ?.toggleActivation()
                startingRangeChapterPos = null
                this.rangeMode = null
                destroyActionModeIfNeeded()
            }
            return false
        }
        openChapter(chapter, view)

        return false
    }

    override fun onItemLongClick(position: Int) {
        val adapter = adapter ?: return
        if (adapter.getItem(position) !is ChapterItem) return
        // Long-press is the entry point to mihon-style multi-select. The first long-press starts
        // selection mode; a later long-press range-selects from the previous anchor.
        enterSelectionMode(position)
    }

    override fun onActionStateChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
        binding.swipeRefresh.isEnabled = actionState != ItemTouchHelper.ACTION_STATE_SWIPE
    }

    override fun onItemMove(fromPosition: Int, toPosition: Int) {
    }

    override fun shouldMoveItem(fromPosition: Int, toPosition: Int): Boolean {
        return true
    }
    //endregion

    fun dismissPopup(position: Int) {
        if (chapterPopupMenu != null && chapterPopupMenu?.first == position) {
            chapterPopupMenu?.second?.dismiss()
            chapterPopupMenu = null
        }
    }

    private fun markPreviousAs(chapter: ChapterItem, read: Boolean) {
        val adapter = adapter ?: return
        val chapters = if (presenter.sortDescending()) adapter.items.reversed() else adapter.items
        val chapterPos = chapters.indexOf(chapter)
        if (chapterPos != -1) {
            if (read) {
                markAsRead(chapters.take(chapterPos))
            } else {
                markAsUnread(chapters.take(chapterPos))
            }
        }
    }

    fun bookmarkChapter(position: Int) {
        val item = adapter?.getItem(position) as? ChapterItem ?: return
        val bookmarked = item.bookmark
        bookmarkChapters(listOf(item), !bookmarked)
        snack?.dismiss()
        snack = view?.snack(
            if (bookmarked) {
                MR.strings.removed_bookmark
            } else {
                MR.strings.bookmarked
            },
            Snackbar.LENGTH_INDEFINITE,
        ) {
            setAction(MR.strings.undo) {
                bookmarkChapters(listOf(item), bookmarked)
            }
        }
        (activity as? MainActivity)?.setUndoSnackBar(snack)
    }

    fun toggleReadChapter(position: Int) {
        val preferences = presenter.preferences
        val item = adapter?.getItem(position) as? ChapterItem ?: return
        val chapter = item.chapter
        val lastRead = chapter.last_page_read
        val pagesLeft = chapter.pages_left
        val read = item.chapter.read
        presenter.markChaptersRead(listOf(item), !read, false)
        snack?.dismiss()
        snack = view?.snack(
            if (read) {
                MR.strings.marked_as_unread
            } else {
                MR.strings.marked_as_read
            },
            Snackbar.LENGTH_INDEFINITE,
        ) {
            var undoing = false
            setAction(MR.strings.undo) {
                presenter.markChaptersRead(listOf(item), read, true, lastRead, pagesLeft)
                undoing = true
            }
            addCallback(
                object : BaseTransientBottomBar.BaseCallback<Snackbar>() {
                    override fun onDismissed(transientBottomBar: Snackbar?, event: Int) {
                        super.onDismissed(transientBottomBar, event)
                        if (!undoing && !read) {
                            if (preferences.removeAfterMarkedAsRead().get()) {
                                presenter.deleteChapters(listOf(item))
                            }
                            updateTrackChapterMarkedAsRead(preferences, chapter, manga?.id) {
                                presenter.fetchTracks()
                            }
                        }
                    }
                },
            )
        }
        (activity as? MainActivity)?.setUndoSnackBar(snack)
    }

    private fun bookmarkChapters(chapters: List<ChapterItem>, bookmarked: Boolean) {
        presenter.bookmarkChapters(chapters, bookmarked)
    }

    private fun markAsRead(chapters: List<ChapterItem>) {
        presenter.markChaptersRead(chapters, true)
    }

    private fun markAsUnread(chapters: List<ChapterItem>) {
        presenter.markChaptersRead(chapters, false)
    }

    private fun openChapter(chapter: Chapter, sharedElement: View? = null) {
        // Single funnel for tap/FAB/swipe: tear down any live selection before launching the
        // reader so the opened row isn't left CHECKED/collapsed on the shared-element return.
        if (isSelectionMode || actionMode != null) {
            destroyActionModeIfNeeded()
        }
        (activity as? AppCompatActivity)?.apply {
            // Only use shared-element transition if the view has a transitionName;
            // ComposeView buttons (e.g. buttonGroupCompose) don't set one and
            // makeSceneTransitionAnimation throws if transitionName is null.
            val validSharedElement = sharedElement?.takeIf { it.transitionName != null }
            if (validSharedElement != null) {
                val (intent, bundle) = ReaderActivity
                    .newIntentWithTransitionOptions(this, manga!!, chapter, validSharedElement)
                val firstPos = (binding.recycler.layoutManager as LinearLayoutManager).findFirstVisibleItemPosition()
                val lastPos = (binding.recycler.layoutManager as LinearLayoutManager).findLastVisibleItemPosition()
                val chapterRange = if (firstPos > -1 && lastPos > -1) {
                    (firstPos..lastPos).mapNotNull {
                        (adapter?.getItem(it) as? ChapterItem)?.chapter?.id
                    }.toLongArray()
                } else {
                    longArrayOf()
                }
                returningFromReader = true
                intent.putExtra(ReaderActivity.VISIBLE_CHAPTERS, chapterRange)
                startActivity(intent, bundle)
            } else {
                startActivity(ReaderActivity.newIntent(this, manga!!, chapter))
            }
        }
    }

    //region action bar menu methods
    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.manga_details, menu)
        colorToolbar(binding.recycler.canScrollVertically(-1))
        updateMenuVisibility(menu)
        val manga = manga
        if (manga != null) {
            menu.findItem(R.id.action_migrate).title = view?.context?.getString(
                MR.strings.migrate_,
                manga.seriesType(view!!.context),
            )
        }
        menu.findItem(R.id.download_next).title =
            view?.context?.getString(MR.plurals.next_unread_chapters, 1, 1)
        menu.findItem(R.id.download_next_5).title =
            view?.context?.getString(MR.plurals.next_unread_chapters, 5, 5)

        val searchItem = menu.findItem(R.id.action_search)
        val searchView = searchItem.actionView as SearchView
        searchView.queryHint = activity?.getString(MR.strings.search_chapters)
        if (query.isNotEmpty() && (!searchItem.isActionViewExpanded || searchView.query != query)) {
            searchItem.expandActionView()
            setSearchViewListener(searchView)
            searchView.setQuery(query, true)
            searchView.clearFocus()
        } else {
            setSearchViewListener(searchView)
        }

        searchItem.fixExpand(onExpand = { invalidateMenuOnExpand() })
    }

    private fun setSearchViewListener(searchView: SearchView?) {
        setOnQueryTextChangeListener(searchView) {
            query = it ?: ""
            if (!isTablet) {
                if (query.isNotEmpty()) {
                    getHeader()?.collapse()
                } else {
                    getHeader()?.expand()
                }
            }

            adapter?.setFilter(query)
            adapter?.performFilter()
            true
        }
    }

    private fun updateMenuVisibility(menu: Menu?) {
        menu ?: return
        val isSelecting = isSelectionMode && selectedChapters().isNotEmpty()
        menu.findItem(R.id.action_select_all)?.isVisible = isSelecting
        menu.findItem(R.id.action_select_inverse)?.isVisible = isSelecting
        if (isSelecting) {
            for (i in 0 until menu.size()) {
                val item = menu.getItem(i)
                if (item.itemId != R.id.action_select_all && item.itemId != R.id.action_select_inverse) {
                    item.isVisible = false
                }
            }
            return
        }
        val manga = manga
        if (manga == null) {
            for (i in 0 until menu.size()) {
                val item = menu.getItem(i)
                item.isVisible = item.itemId == R.id.action_search
            }
            return
        }
        val editItem = menu.findItem(R.id.action_edit)
        editItem?.isVisible = (manga.favorite || manga.isLocal()) && !presenter.isLockedFromSearch
        val isNovel = manga.isNovel()
        menu.findItem(R.id.action_advanced_translation)?.isVisible =
            !presenter.isLockedFromSearch && manga.favorite && isNovel
        menu.findItem(R.id.action_download)?.isVisible = !presenter.isLockedFromSearch &&
            !manga.isLocal()
        menu.findItem(R.id.action_mark_all_as_read)?.isVisible =
            presenter.getNextUnreadChapter() != null && !presenter.isLockedFromSearch
        menu.findItem(R.id.action_mark_all_as_unread)?.isVisible =
            presenter.anyRead() && !presenter.isLockedFromSearch
        menu.findItem(R.id.action_remove_downloads)?.isVisible =
            presenter.hasDownloads() && !presenter.isLockedFromSearch &&
            !manga.isLocal()
        menu.findItem(R.id.remove_non_bookmarked)?.isVisible =
            presenter.hasBookmark() && !presenter.isLockedFromSearch
        menu.findItem(R.id.action_migrate)?.isVisible = !presenter.isLockedFromSearch &&
            !manga.isLocal() && manga.favorite
        menu.findItem(R.id.action_set_fetch_interval)?.isVisible = presenter.preferences.smartUpdateEnabled().get() &&
            !presenter.isLockedFromSearch && !manga.isLocal() && manga.favorite
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_select_all -> {
                selectAllChapters()
            }
            R.id.action_select_inverse -> {
                invertChapterSelection()
            }
            R.id.action_edit -> {
                router.pushController(
                    EditMangaController(
                        this,
                        presenter.manga,
                    ).withFadeTransaction(),
                )
            }
            R.id.action_advanced_translation -> showAdvancedTranslationSheet(this)
            R.id.action_open_in_web_view -> openInWebView()
            R.id.action_refresh_tracking -> presenter.refreshTracking(true)
            R.id.action_migrate ->
                if (!isNotOnline()) {
                    PreMigrationController.navigateToMigration(
                        presenter.preferences.skipPreMigration().get(),
                        router,
                        listOf(manga!!.id!!),
                    )
                }
            R.id.action_mark_all_as_read -> {
                activity!!.materialAlertDialog()
                    .setMessage(MR.strings.mark_all_chapters_as_read)
                    .setPositiveButton(MR.strings.mark_as_read) { _, _ ->
                        markAsRead(presenter.chapters)
                    }
                    .setNegativeButton(AR.string.cancel, null)
                    .show()
            }
            R.id.remove_all, R.id.remove_read, R.id.remove_non_bookmarked, R.id.remove_custom -> massDeleteChapters(item.itemId)
            R.id.action_mark_all_as_unread -> {
                activity!!.materialAlertDialog()
                    .setMessage(MR.strings.mark_all_chapters_as_unread)
                    .setPositiveButton(MR.strings.mark_as_unread) { _, _ ->
                        markAsUnread(presenter.chapters)
                    }
                    .setNegativeButton(AR.string.cancel, null)
                    .show()
            }
            R.id.download_next, R.id.download_next_5, R.id.download_custom, R.id.download_unread, R.id.download_all -> downloadChapters(
                item.itemId,
            )
            R.id.action_set_fetch_interval -> showFetchIntervalDialog()
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    override fun openTranslationSettings() {
        showAdvancedTranslationSheet(this)
    }

    override fun showChapterTranslationButton(): Boolean {
        return presenter.isChapterTranslationAvailable()
    }

    override fun toggleChapterTranslation(position: Int) {
        val item = adapter?.getItem(position) as? ChapterItem ?: return
        presenter.toggleChapterTranslation(item)
    }

    fun refreshChapterTranslationState(chapterId: Long) {
        val position = adapter?.indexOf(chapterId) ?: return
        if (position >= 0) adapter?.notifyItemChanged(position)
    }

    fun showChapterTranslationMessage(message: StringResource) {
        activity?.toast(message)
    }

    override fun openQuotesSheet() {
        val activity = activity ?: return
        val mangaId = presenter.manga.id ?: return
        showQuotesSheet(
            activity = activity,
            novelId = mangaId,
            novelName = presenter.manga.title,
        )
    }
    //endregion

    fun saveCover() {
        if (presenter.saveCover()) {
            activity?.toast(MR.strings.cover_saved)
        } else {
            activity?.toast(MR.strings.error_saving_cover)
        }
    }

    fun shareCover() {
        val cover = presenter.shareCover()
        if (cover != null) {
            val stream = cover.toFile().getUriCompat(activity!!)
            val intent = Intent(Intent.ACTION_SEND).apply {
                putExtra(Intent.EXTRA_STREAM, stream)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
                clipData = ClipData.newRawUri(null, stream)
                type = "image/*"
            }
            startActivity(Intent.createChooser(intent, activity?.getString(MR.strings.share)))
        } else {
            activity?.toast(MR.strings.error_sharing_cover)
        }
    }

    override fun prepareToShareManga() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            presenter.shareManga()
        } else {
            shareManga()
        }
    }

    fun shareManga(cover: File? = null) {
        val context = view?.context ?: return

        val source = presenter.source
        val stream = cover?.getUriCompat(context)
        try {
            val url = source.getMangaUrl(presenter.manga) ?: return
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/*"
                putExtra(Intent.EXTRA_TEXT, url)
                putExtra(Intent.EXTRA_TITLE, presenter.manga.title)
                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                if (stream != null) {
                    clipData = ClipData.newRawUri(null, stream)
                }
            }
            startActivity(
                Intent.createChooser(intent, context.getString(MR.strings.share)).apply {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && stream != null) {
                        val shareCoverIntent = Intent(Intent.ACTION_SEND).apply {
                            putExtra(Intent.EXTRA_STREAM, stream)
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
                            clipData = ClipData.newRawUri(null, stream)
                            type = "image/*"
                        }
                        val pendingIntent = PendingIntent.getActivity(
                            context,
                            manga?.id?.hashCode() ?: 0,
                            Intent.createChooser(shareCoverIntent, context.getString(MR.strings.share)),
                            PendingIntent.FLAG_IMMUTABLE,
                        )
                        val action = ChooserAction.Builder(
                            Icon.createWithResource(context, R.drawable.ic_photo_24dp),
                            context.getString(MR.strings.share_cover),
                            pendingIntent,
                        ).build()
                        putExtra(Intent.EXTRA_CHOOSER_CUSTOM_ACTIONS, arrayOf(action))
                    }
                },
            )
        } catch (e: Exception) {
            context.toast(e.message)
        }
    }

    override fun openInWebView() {
        if (isNotOnline()) return
        val url = try {
            presenter.source.getMangaUrl(presenter.manga)
        } catch (e: Exception) {
            return
        } ?: return

        val activity = activity ?: return
        val intent = WebViewActivity.newIntent(
            activity.applicationContext,
            url,
            presenter.source.id,
            presenter.manga
                .title,
        )
        startActivity(intent)
    }

    fun openChapterInWebView(item: ChapterItem) {
        if (isNotOnline()) return
        val url = presenter.getChapterUrl(item.chapter) ?: return

        val activity = activity ?: return
        val intent = WebViewActivity.newIntent(
            activity.applicationContext,
            url,
            presenter.source.id,
            presenter.manga
                .title,
        )
        startActivity(intent)
    }

    private fun massDeleteChapters(choice: Int) {
        val chaptersToDelete = when (choice) {
            R.id.remove_all -> presenter.allChapters
            R.id.remove_non_bookmarked -> presenter.allChapters.filter { !it.bookmark }
            R.id.remove_read -> presenter.allChapters.filter { it.read }
            R.id.remove_custom -> {
                createActionModeIfNeeded()
                rangeMode = RangeMode.RemoveDownload
                return
            }
            else -> emptyList()
        }.filter { it.isDownloaded }
        if (chaptersToDelete.isNotEmpty() || choice == R.id.remove_all) {
            massDeleteChapters(chaptersToDelete, choice == R.id.remove_all)
        } else {
            snack?.dismiss()
            snack = view?.snack(MR.strings.no_chapters_to_delete)
        }
    }

    private fun massDeleteChapters(chapters: List<ChapterItem>, isEverything: Boolean) {
        val context = view?.context ?: return
        context.materialAlertDialog()
            .setMessage(
                if (isEverything) {
                    context.getString(MR.strings.remove_all_downloads)
                } else {
                    context.getString(
                        MR.plurals.remove_n_chapters,
                        chapters.size,
                        chapters.size,
                    )
                },
            )
            .setPositiveButton(MR.strings.remove) { _, _ ->
                presenter.deleteChapters(chapters, isEverything = isEverything)
            }
            .setNegativeButton(AR.string.cancel, null)
            .show()
    }

    override fun showFetchIntervalDialog() {
        val context = view?.context ?: return
        // 0 = automatic prediction; the rest are user-locked overrides (in days).
        val intervalDays = intArrayOf(0, 1, 2, 3, 7, 14, FetchInterval.MAX_INTERVAL)
        val labels = intervalDays.map { days ->
            if (days == 0) {
                context.getString(MR.strings.manga_interval_mode_automatic)
            } else {
                context.getString(MR.plurals.day_plural, days, days)
            }
        }.toTypedArray()
        // Pre-select the current override, or "Automatic" when unlocked.
        val current = if (presenter.isUserIntervalMode) presenter.fetchIntervalDays else 0
        val checked = intervalDays.indexOf(current).coerceAtLeast(0)
        val currentInterval = presenter.fetchIntervalDays.takeIf { it > 0 }?.let { days ->
            context.getString(MR.plurals.day_plural, days, days)
        } ?: context.getString(MR.strings.manga_interval_mode_automatic)
        val releasePredictionApplies = presenter.manga.next_update.startOfDayMillis() >=
            System.currentTimeMillis().startOfDayMillis() &&
            presenter.manga.status in setOf(SManga.ONGOING, SManga.PUBLISHING_FINISHED)
        val predictionMessage = if (releasePredictionApplies) {
            context.getString(
                MR.strings.manga_interval_expected_update,
                presenter.manga.next_update.compactReleaseEstimate(context),
                currentInterval,
            )
        } else {
            context.getString(MR.strings.manga_interval_expected_update_null)
        }

        context.materialAlertDialog()
            .setTitle(MR.strings.manga_interval_custom_amount)
            .setMessage(predictionMessage)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                presenter.setFetchInterval(intervalDays[which])
                dialog.dismiss()
            }
            .setNegativeButton(AR.string.cancel, null)
            .show()
    }

    private fun Long.compactReleaseEstimate(context: Context): String {
        val todayStart = System.currentTimeMillis().startOfDayMillis()
        val targetStart = startOfDayMillis()
        val days = ((targetStart - todayStart) / DateUtils.DAY_IN_MILLIS).toInt()
        return when {
            days <= 0 -> context.getString(MR.strings.manga_interval_expected_update_soon)
            days <= 60 -> context.getString(
                MR.strings.manga_interval_expected_update_about,
                context.getString(MR.plurals.day_plural, days, days).lowercase(Locale.ROOT),
            )
            else -> {
                val yearFlag = if (isInCurrentYear()) {
                    DateUtils.FORMAT_NO_YEAR
                } else {
                    DateUtils.FORMAT_SHOW_YEAR
                }
                DateUtils.formatDateTime(
                    context,
                    this,
                    DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_NUMERIC_DATE or yearFlag,
                )
            }
        }
    }

    private fun Long.startOfDayMillis(): Long {
        return java.util.Calendar.getInstance().apply {
            timeInMillis = this@startOfDayMillis
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    private fun Long.isInCurrentYear(): Boolean {
        val current = java.util.Calendar.getInstance()
        val target = java.util.Calendar.getInstance().apply { timeInMillis = this@isInCurrentYear }
        return current.get(java.util.Calendar.YEAR) == target.get(java.util.Calendar.YEAR)
    }

    private fun updateToolbarTitleAlpha(@FloatRange(from = 0.0, to = 1.0) alpha: Float? = null, isScrollingDown: Boolean = false) {
        if ((!isControllerVisible && alpha == null) || isScrollingDown) return
        val scrolledList = binding.recycler
        val toolbarTextView = activityBinding?.toolbar?.toolbarTitle ?: return
        if (isSelectionMode) {
            toolbarTextView.setTextColorAlpha(255)
            return
        }
        val tbAlpha = when {
            isTablet -> 0f
            // Specific alpha provided
            alpha != null -> alpha

            // First item isn't in view, full opacity
            ((scrolledList.layoutManager as LinearLayoutManager).findFirstVisibleItemPosition() > 0) -> 1f
            ((scrolledList.layoutManager as LinearLayoutManager).findFirstCompletelyVisibleItemPosition() == 0) -> 0f

            // Based on scroll amount when first item is in view
            else -> (scrolledList.computeVerticalScrollOffset() - (20.dpToPx))
                .coerceIn(0, 255) / 255f
        }
        toolbarTextView.setTextColorAlpha((tbAlpha * 255).roundToInt())
    }

    private fun downloadChapters(choice: Int) {
        val chaptersToDownload = when (choice) {
            R.id.download_next -> presenter.getUnreadChaptersSorted().take(1)
            R.id.download_next_5 -> presenter.getUnreadChaptersSorted().take(5)
            R.id.download_custom -> {
                createActionModeIfNeeded()
                rangeMode = RangeMode.Download
                return
            }
            R.id.download_unread -> presenter.chapters.filter { !it.read }
            R.id.download_all -> presenter.allChapters
            else -> emptyList()
        }
        if (chaptersToDownload.isNotEmpty()) {
            downloadChapters(chaptersToDownload)
        }
    }

    private fun isLocked(): Boolean {
        if (presenter.isLockedFromSearch) {
            return SecureActivityDelegate.shouldBeLocked()
        }
        return false
    }

    private fun needsToBeUnlocked(): Boolean {
        if (presenter.isLockedFromSearch) {
            SecureActivityDelegate.promptLockIfNeeded(activity)
            return SecureActivityDelegate.shouldBeLocked()
        }
        return false
    }

    //region Interface methods
    override fun coverColor(): Int? = coverColor
    override fun accentColor(): Int? = accentColor
    override fun topCoverHeight(): Int = headerHeight

    override fun startDownloadNow(position: Int) {
        val chapter = (adapter?.getItem(position) as? ChapterItem) ?: return
        presenter.startDownloadingNow(chapter)
    }

    // In case the recycler is at the bottom and collapsing the header makes it unscrollable
    override fun updateScroll() {
        updateToolbarTitleAlpha()
        if (!binding.recycler.canScrollVertically(-1)) {
            getHeader()?.binding?.backdrop?.translationY = 0f
            activityBinding?.appBar?.y = 0f
            colorToolbar(isColor = false, animate = false)
        }
    }

    private fun downloadChapters(chapters: List<ChapterItem>) {
        val view = view ?: return
        presenter.downloadChapters(chapters)
        val text = view.context.getString(
            MR.strings.add_x_to_library,
            presenter.manga.seriesType(view.context).lowercase(Locale.ROOT),
        )
        if (!presenter.manga.favorite && (
            snack == null ||
                snack?.getText() != text
            )
        ) {
            snack = view.snack(text, Snackbar.LENGTH_INDEFINITE) {
                setAction(MR.strings.add) {
                    if (!presenter.manga.favorite) {
                        toggleMangaFavorite()
                    }
                }
                addCallback(
                    object : BaseTransientBottomBar.BaseCallback<Snackbar>() {
                        override fun onDismissed(transientBottomBar: Snackbar?, event: Int) {
                            super.onDismissed(transientBottomBar, event)
                            if (snack == transientBottomBar) snack = null
                        }
                    },
                )
            }
            (activity as? MainActivity)?.setUndoSnackBar(snack)
        }
    }

    override fun startDownloadRange(position: Int) {
        // Don't start the legacy download-range flow while a multi-select is active.
        if (isSelectionMode) return
        createActionModeIfNeeded()
        val chapterItem = (adapter?.getItem(position) as? ChapterItem) ?: return
        rangeMode = if (chapterItem.status in listOf(Download.State.NOT_DOWNLOADED, Download.State.ERROR)) {
            RangeMode.Download
        } else {
            RangeMode.RemoveDownload
        }
        onItemClick(null, position)
    }

    override fun readNextChapter(readingButton: View) {
        if (activity is SearchActivity && presenter.isLockedFromSearch) {
            SecureActivityDelegate.promptLockIfNeeded(activity)
            return
        }
        val item = presenter.getNextUnreadChapter()
        if (item != null) {
            openChapter(item.chapter, readingButton)
        } else if (snack == null ||
            snack?.getText() != view?.context?.getString(MR.strings.next_chapter_not_found)
        ) {
            snack = view?.snack(MR.strings.next_chapter_not_found, Snackbar.LENGTH_LONG) {
                addCallback(
                    object : BaseTransientBottomBar.BaseCallback<Snackbar>() {
                        override fun onDismissed(transientBottomBar: Snackbar?, event: Int) {
                            super.onDismissed(transientBottomBar, event)
                            if (snack == transientBottomBar) snack = null
                        }
                    },
                )
            }
        }
    }

    override fun downloadChapter(position: Int) {
        val view = view ?: return
        val chapter = (adapter?.getItem(position) as? ChapterItem) ?: return
        if (actionMode != null) {
            onItemClick(null, position)
            return
        }
        if (chapter.status != Download.State.NOT_DOWNLOADED && chapter.status != Download.State.ERROR) {
            presenter.deleteChapter(chapter)
        } else {
            if (chapter.status == Download.State.ERROR) {
                DownloadJob.start(view.context)
            } else {
                downloadChapters(listOf(chapter))
            }
        }
    }

    fun localSearch(text: String, isTag: Boolean) {
        // Route into the real Library tab's search bar instead of a one-off filtered list.
        // The library's fuzzy search already matches title/author/genre, so tag and plain
        // text both land as a single search query.
        (activity as? MainActivity)?.goToLibraryAndSearch(text)
    }

    fun sourceSearch(text: String) {
        when (
            val previousController =
                router.backstack.getOrNull(router.backstackSize - 2)?.controller
        ) {
            is BrowseSourceController -> {
                if (presenter.source is HttpSource) {
                    // Set presenter.query before popping so POP_ENTER's onCreateOptionsMenu
                    // rebuilds the search field with the query already populated.
                    previousController.searchWithGenre(text)
                    router.handleBack()
                }
            }
            else -> {
                if (presenter.source is CatalogueSource) {
                    val controller = BrowseSourceController(presenter.source as CatalogueSource)
                    router.pushController(controller.withFadeTransaction())
                    controller.searchWithGenre(text)
                }
            }
        }
    }

    fun globalSearch(text: String) {
        if (isNotOnline()) return
        router.pushController(GlobalSearchController(text).withFadeTransaction())
    }

    override fun showFloatingActionMode(view: TextView, content: String?, isTag: Boolean) {
        finishFloatingActionMode()
        val previousController = previousController
        val hasDifferentAuthors = view.id == R.id.manga_author &&
            manga?.hasSameAuthorAndArtist == false && manga?.author != null
        val isInSource = !isTag && previousController !is LibraryController &&
            previousController !is RecentsController
        if (!hasDifferentAuthors && isInSource) {
            val query = content ?: view.text.toString()
            if (previousController is BrowseSourceController) {
                sourceSearch(query)
            } else {
                globalSearch(query)
            }
            return
        }
        val actionModeCallback = if (content != null) {
            FloatingMangaDetailsActionModeCallback(
                content,
                showCopy = view is Chip,
                searchSource = isTag,
            )
        } else {
            FloatingMangaDetailsActionModeCallback(view, isTag = isTag)
        }
        if (hasDifferentAuthors) {
            actionModeCallback.authorText = manga?.author
            actionModeCallback.artistText = manga?.artist
            if (isInSource) {
                actionModeCallback.isGlobalSearch = true
            }
        }
        if (view is Chip) {
            view.isActivated = true
        }
        floatingActionMode =
            view.startActionMode(actionModeCallback, android.view.ActionMode.TYPE_FLOATING)
    }

    override fun customActionMode(view: TextView): android.view.ActionMode.Callback {
        return FloatingMangaDetailsActionModeCallback(view, false, closeMode = false)
    }

    override fun showChapterFilter() {
        ChaptersSortBottomSheet(this).show()
    }

    override fun favoriteManga(longPress: Boolean) {
        if (needsToBeUnlocked()) return
        val manga = presenter.manga
        if (longPress) {
            showCategoriesSheet()
        } else if (!manga.favorite) {
            toggleMangaFavorite()
        } else {
            val categories = presenter.getCategories()
            val favButton = getHeader()?.binding?.favoriteButton ?: return
            val popup = makeFavPopup(favButton, categories)
            popup?.show()
        }
    }

    override fun setFavButtonPopup(popupView: View) {
        if (presenter.isLockedFromSearch) {
            return
        }
        val manga = presenter.manga
        if (!manga.favorite) {
            popupView.setOnTouchListener(null)
            return
        }
        val popup = makeFavPopup(popupView, presenter.getCategories())
        popupView.setOnTouchListener(popup?.dragToOpenListener)
    }

    private fun makeFavPopup(popupView: View, categories: List<Category>): PopupMenu? {
        val view = view ?: return null
        val popup = PopupMenu(view.context, popupView)
        popup.menu.add(0, 1, 0, view.context.getString(MR.strings.remove_from_library))
        if (categories.isNotEmpty()) {
            popup.menu.add(0, 0, 1, view.context.getString(MR.strings.edit_categories))
        }

        // Set a listener so we are notified if a menu item is clicked
        popup.setOnMenuItemClickListener { menuItem ->
            if (menuItem.itemId == 0) {
                showCategoriesSheet()
            } else {
                toggleMangaFavorite()
            }
            true
        }
        return popup
    }

    private fun showCategoriesSheet() {
        val adding = !presenter.manga.favorite
        viewScope.launchIO {
            presenter.manga.moveCategories(activity!!, adding) {
                updateHeader()
                if (adding) {
                    showAddedSnack()
                }
            }
        }
    }

    private fun toggleMangaFavorite() {
        val view = view ?: return
        val activity = activity ?: return
        viewScope.launchIO {
            withUIContext { snack?.dismiss() }
            snack = presenter.manga.addOrRemoveToFavorites(
                presenter.preferences,
                view,
                activity,
                presenter.sourceManager,
                this@MangaDetailsController,
                onMangaAdded = { migrationInfo ->
                    migrationInfo?.let {
                        presenter.fetchChapters(andTracking = true)
                    }
                    updateHeader()
                    showAddedSnack()
                },
                onMangaMoved = {
                    updateHeader()
                    presenter.fetchChapters(andTracking = true)
                },
                onMangaDeleted = {
                    updateHeader()
                    presenter.confirmDeletion()
                },
                scope = viewScope,
            )
            if (snack?.duration == Snackbar.LENGTH_INDEFINITE) {
                withUIContext {
                    val favButton = getHeader()?.binding?.favoriteButton
                    (activity as? MainActivity)?.setUndoSnackBar(snack, favButton)
                }
            }
        }
    }

    private fun showAddedSnack() {
        val view = view ?: return
        snack?.dismiss()
        snack = view.snack(view.context.getString(MR.strings.added_to_library))
    }

    override fun mangaPresenter(): MangaDetailsPresenter = presenter

    /**
     * Copies a string to clipboard
     *
     * @param content the actual text to copy to the board
     * @param label Label to show to the user describing the content
     */
    override fun copyContentToClipboard(content: String, label: StringResource, useToast: Boolean) {
        val view = view ?: return
        val contentType = if (label.resourceId != 0) view.context.getString(label) else null
        copyContentToClipboard(content, contentType, useToast)
    }

    /**
     * Copies a string to clipboard
     *
     * @param content the actual text to copy to the board
     * @param label Label to show to the user describing the content
     */
    override fun copyContentToClipboard(content: String, label: Int, useToast: Boolean) {
        val view = view ?: return
        val contentType = if (label != 0) view.context.getString(label) else null
        copyContentToClipboard(content, contentType, useToast)
    }

    /**
     * Copies a string to clipboard
     *
     * @param content the actual text to copy to the board
     * @param label Label to show to the user describing the content
     */
    override fun copyContentToClipboard(content: String, label: String?, useToast: Boolean) {
        snack = copyToClipboard(content, label, useToast)
    }

    override fun showTrackingSheet() {
        if (needsToBeUnlocked()) return
        trackingBottomSheet =
            TrackingBottomSheet(this)
        trackingBottomSheet?.show()
    }

    override fun openRecommendations() {
        val manga = presenter.manga
        router.pushController(
            exh.recs.RecommendsViewController(
                exh.recs.RecommendsScreen.Args.SingleSourceManga(
                    mangaId = manga.id!!,
                    sourceId = manga.source,
                ),
            ).withFadeTransaction(),
        )
    }

    override fun openMetadataViewer() {
        val manga = presenter.manga
        router.pushController(
            exh.ui.metadata.MetadataViewController(manga.id!!, manga.source)
                .withFadeTransaction(),
        )
    }

    override fun openPagePreview() {
        val manga = presenter.manga
        router.pushController(
            exh.ui.pagepreview.PagePreviewViewController(manga.id!!)
                .withFadeTransaction(),
        )
    }

    override fun openReaderAtPage(page: Int) {
        val manga = presenter.manga
        val chapters = presenter.chapters
        val chapter = chapters.minByOrNull { it.chapter.source_order } ?: return
        startActivity(ReaderActivity.newIntent(activity!!, manga, chapter.chapter, page))
    }

    override fun searchFromMetadata(query: String) {
        val source = presenter.source as? CatalogueSource ?: return
        val normalizedQuery = if (source.isEhBasedSource()) {
            query.replace(": ", ":")
        } else {
            query
        }
        router.pushController(
            BrowseSourceController(source, normalizedQuery).withFadeTransaction(),
        )
    }
    //endregion

    //region Tracking methods
    fun refreshTracking(trackings: List<TrackItem>) {
        trackingBottomSheet?.onNextTrackersUpdate(trackings)
    }

    fun onTrackSearchResults(results: List<TrackSearch>) {
        trackingBottomSheet?.onSearchResults(results)
    }

    fun refreshTracker() {
        getHeader()?.updateTracking()
    }

    fun trackRefreshDone() {
        trackingBottomSheet?.onRefreshDone()
    }

    fun trackRefreshError(error: Exception) {
        Logger.e(error)
        trackingBottomSheet?.onRefreshError(error)
    }

    fun trackSearchError(error: Exception) {
        trackingBottomSheet?.onSearchResultsError(error)
    }
    //endregion

    //region Selection/action mode methods
    // Mihon parity: back cancels chapter multi-select before leaving the screen.
    override fun handleBack(): Boolean {
        if (isSelectionMode && selectedChapters().isNotEmpty()) {
            destroyActionModeIfNeeded()
            return true
        }
        return super.handleBack()
    }

    private fun createActionModeIfNeeded() {
        if (actionMode == null) {
            actionMode = (activity as AppCompatActivity).startSupportActionMode(this)
            // Re-run MainActivity's inset listener now the action_mode_bar exists so it gets its
            // topMargin (else it lays out under the status bar/cutout).
            activityBinding?.root?.requestApplyInsets()
            val view = activity?.window?.currentFocus ?: return
            val imm = activity?.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                ?: return
            imm.hideSoftInputFromWindow(view.windowToken, 0)
            if (adapter?.mode != SelectableAdapter.Mode.MULTI) {
                adapter?.mode = SelectableAdapter.Mode.MULTI
            }
        }
    }

    /**
     * Destroys the action mode.
     */
    private fun destroyActionModeIfNeeded() {
        if (isSelectionMode) {
            clearChapterSelectionMode()
            return
        }
        actionMode?.finish()
    }

    private fun clearChapterSelectionMode() {
        isSelectionMode = false
        lastSelectionPosition = null
        actionBarState = ChapterActionBarState(visible = false)
        adapter?.mode = SelectableAdapter.Mode.IDLE
        adapter?.clearSelection()
        refreshSelectionHighlights()
        updateSelectionChrome()
    }

    private fun updateSelectionChrome() {
        if (view == null) return
        setStatusBarAndToolbar()
        if (isControllerVisible) setTitle()
        updateToolbarTitleAlpha()
        updateSelectionNavigationIcon(isSelectionMode)
        binding.swipeRefresh.isEnabled = !isSelectionMode
        updateFab()
        activity?.invalidateOptionsMenu()
    }

    private fun updateSelectionNavigationIcon(isSelecting: Boolean) {
        val context = view?.context ?: return
        val toolbar = activityBinding?.toolbar
        val searchToolbar = activityBinding?.searchToolbar
        val tint = context.getResourceColor(R.attr.actionBarTintColor)

        fun icon(resId: Int) = ContextCompat.getDrawable(context, resId)?.mutate()?.apply {
            setTint(tint)
        }

        if (isSelecting) {
            toolbar?.navigationIcon = icon(R.drawable.ic_close_24dp)
            searchToolbar?.navigationIcon = icon(R.drawable.ic_close_24dp)
            return
        }

        val onRoot = router.backstackSize == 1 && activity !is SearchActivity
        val backIcon = icon(R.drawable.ic_arrow_back_24dp)
        val searchIcon = icon(R.drawable.ic_search_24dp)
        toolbar?.navigationIcon = if (onRoot) null else backIcon
        searchToolbar?.navigationIcon = if (activityBinding?.appBar?.useLargeToolbar == true) {
            searchIcon
        } else if (onRoot) {
            searchIcon
        } else {
            icon(R.drawable.ic_arrow_back_24dp)
        }
    }

    private fun collapseSearchForSelection() {
        activityBinding?.toolbar?.menu?.findItem(R.id.action_search)?.collapseActionView()
        activityBinding?.searchToolbar?.menu?.findItem(R.id.action_search)?.collapseActionView()
    }

    override fun onActionItemClicked(mode: ActionMode?, item: MenuItem?): Boolean {
        return false
    }

    override fun onCreateActionMode(mode: ActionMode?, menu: Menu?): Boolean {
        if (isSelectionMode) return false
        // Legacy custom-range download/delete prompt; multi-select uses the normal app toolbar.
        return true
    }

    override fun onPrepareActionMode(mode: ActionMode?, menu: Menu?): Boolean {
        mode?.title = view?.context?.getString(
            if (startingRangeChapterPos == null) {
                MR.strings.select_starting_chapter
            } else {
                MR.strings.select_ending_chapter
            },
        )
        return false
    }

    override fun onDestroyActionMode(mode: ActionMode?) {
        actionMode = null
        isSelectionMode = false
        lastSelectionPosition = null
        actionBarState = ChapterActionBarState(visible = false)
        if (startingRangeChapterPos != null && rangeMode in setOf(RangeMode.Download, RangeMode.RemoveDownload)) {
            val item = adapter?.getItem(startingRangeChapterPos!!) as? ChapterItem
            (binding.recycler.findViewHolderForAdapterPosition(startingRangeChapterPos!!) as? ChapterHolder)?.notifyStatus(
                item?.status ?: Download.State.NOT_DOWNLOADED,
                false,
                0,
            )
        }
        rangeMode = null
        startingRangeChapterPos = null
        adapter?.mode = SelectableAdapter.Mode.IDLE
        adapter?.clearSelection()
        // Repaint rows so the deselected highlight clears immediately.
        refreshSelectionHighlights()
        updateSelectionChrome()
        return
    }

    // mihon-style multi-select: enter selection mode and select [position]. A later long-press
    // range-selects from the previous anchor (toggleSelection fromLongPress semantics).
    private fun enterSelectionMode(position: Int) {
        val firstEntry = !isSelectionMode
        // A legacy download-range gesture may be mid-flight (startDownloadRange set rangeMode +
        // anchor with isSelectionMode=false). Clear it before selecting so the two modes don't
        // coexist and silently swallow the range gesture. Symmetric to startDownloadRange's guard.
        if (firstEntry && startingRangeChapterPos != null) {
            val anchor = startingRangeChapterPos!!
            val item = adapter?.getItem(anchor) as? ChapterItem
            (binding.recycler.findViewHolderForAdapterPosition(anchor) as? ChapterHolder)
                ?.notifyStatus(item?.status ?: Download.State.NOT_DOWNLOADED, isLocked(), item?.progress ?: 0)
            rangeMode = null
            startingRangeChapterPos = null
        }
        isSelectionMode = true
        // Put the adapter in MULTI mode before selecting so FlexibleAdapter paints checked rows.
        if (adapter?.mode != SelectableAdapter.Mode.MULTI) {
            adapter?.mode = SelectableAdapter.Mode.MULTI
        }
        if (firstEntry) {
            collapseSearchForSelection()
        }
        val previousAnchor = lastSelectionPosition
        if (previousAnchor == null || previousAnchor == position) {
            setChapterSelected(position, true)
        } else {
            // Select the inclusive range between the prior anchor and this long-press target.
            val range = if (previousAnchor < position) previousAnchor..position else position..previousAnchor
            for (pos in range) {
                if (adapter?.getItem(pos) is ChapterItem) setChapterSelected(pos, true)
            }
        }
        lastSelectionPosition = position
        refreshActionBar()
        updateSelectionChrome()
    }

    private fun toggleChapterSelection(position: Int) {
        if (adapter?.getItem(position) !is ChapterItem) return
        val nowSelected = !(adapter?.isSelected(position) ?: false)
        setChapterSelected(position, nowSelected)
        lastSelectionPosition = if (nowSelected) position else null
        if (selectedChapters().isEmpty()) {
            destroyActionModeIfNeeded()
        } else {
            refreshActionBar()
            updateSelectionChrome()
        }
    }

    private fun setChapterSelected(position: Int, selected: Boolean) {
        val adapter = adapter ?: return
        val alreadySelected = adapter.isSelected(position)
        if (selected == alreadySelected) return
        if (selected) adapter.addSelection(position) else adapter.removeSelection(position)
        (binding.recycler.findViewHolderForAdapterPosition(position) as? BaseFlexibleViewHolder)
            ?.itemView
            ?.isActivated = selected
        val item = adapter.getItem(position) as? ChapterItem
        (binding.recycler.findViewHolderForAdapterPosition(position) as? ChapterHolder)
            ?.notifyStatus(
                if (selected) Download.State.CHECKED else item?.status ?: Download.State.NOT_DOWNLOADED,
                false,
                item?.progress ?: 0,
            )
    }

    private fun selectAllChapters() {
        val adapter = adapter ?: return
        for (i in 0 until adapter.itemCount) {
            if (adapter.getItem(i) is ChapterItem) setChapterSelected(i, true)
        }
        refreshActionBar()
        updateSelectionChrome()
    }

    private fun invertChapterSelection() {
        val adapter = adapter ?: return
        for (i in 0 until adapter.itemCount) {
            if (adapter.getItem(i) is ChapterItem) setChapterSelected(i, !adapter.isSelected(i))
        }
        lastSelectionPosition = null
        if (selectedChapters().isEmpty()) {
            destroyActionModeIfNeeded()
        } else {
            refreshActionBar()
            updateSelectionChrome()
        }
    }

    /** Selected chapters mapped from the adapter's current selection positions. */
    private fun selectedChapters(): List<ChapterItem> {
        val adapter = adapter ?: return emptyList()
        return adapter.selectedPositions.mapNotNull { adapter.getItem(it) as? ChapterItem }
    }

    /** Repaints visible chapter rows to their non-selected status (clears CHECKED highlight). */
    private fun refreshSelectionHighlights() {
        presenter.chapters.forEach { chapter ->
            val holder = binding.recycler.findViewHolderForItemId(chapter.id!!) as? ChapterHolder
                ?: return@forEach
            holder.itemView.isActivated = false
            holder.notifyStatus(chapter.status, isLocked(), chapter.progress)
        }
    }

    /** Hosts the mihon-style bottom action bar in a ComposeView island wrapped in YokaiTheme. */
    private fun setupChapterActionBar() {
        val handlers = ChapterActionBarHandlers(
            onBookmark = {
                bookmarkChapters(selectedChapters(), true)
                destroyActionModeIfNeeded()
            },
            onRemoveBookmark = {
                bookmarkChapters(selectedChapters(), false)
                destroyActionModeIfNeeded()
            },
            onMarkRead = {
                markAsRead(selectedChapters())
                destroyActionModeIfNeeded()
            },
            onMarkUnread = {
                markAsUnread(selectedChapters())
                destroyActionModeIfNeeded()
            },
            onMarkPreviousRead = {
                markPreviousFromSelection()
                destroyActionModeIfNeeded()
            },
            onDownload = {
                downloadChapters(selectedChapters().filter { !it.isDownloaded })
                destroyActionModeIfNeeded()
            },
            onDelete = {
                massDeleteChapters(selectedChapters().filter { it.isDownloaded }, false)
                destroyActionModeIfNeeded()
            },
        )
        // Consume insets at the host so the CoordinatorLayout can't re-dispatch them into the
        // Compose subtree on every relayout. The bottom inset is passed as plain Dp content
        // padding, keeping the visible surface attached to the navigation edge without using
        // Compose WindowInsets inside AnimatedVisibility.
        ViewCompat.setOnApplyWindowInsetsListener(binding.actionBarCompose) { _, _ ->
            WindowInsetsCompat.CONSUMED
        }
        binding.actionBarCompose.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed,
        )
        binding.actionBarCompose.setContent {
            yokai.presentation.theme.YokaiTheme {
                val bottomInset = with(LocalDensity.current) {
                    chapterActionBarBottomInsetPx.toDp()
                }
                MangaChapterActionBar(
                    state = actionBarState,
                    handlers = handlers,
                    bottomInset = bottomInset,
                    containerColor = androidx.compose.ui.graphics.Color(selectionActionBarContainerColor()),
                    contentColor = androidx.compose.ui.graphics.Color(selectionActionBarContentColor()),
                )
            }
        }
    }

    private fun selectionActionBarContainerColor(): Int {
        val context = view?.context ?: activity ?: return Color.TRANSPARENT
        return headerColor ?: context.getResourceColor(materialR.attr.colorPrimaryVariant)
    }

    private fun selectionActionBarContentColor(): Int {
        val context = view?.context ?: activity ?: return Color.WHITE
        return context.getResourceColor(R.attr.actionBarTintColor)
    }

    /** Recomputes the bottom-bar contextual visibility from the current selection. */
    private fun refreshActionBar() {
        val selected = selectedChapters()
        val newState = if (!isSelectionMode || selected.isEmpty()) {
            ChapterActionBarState(visible = false)
        } else {
            ChapterActionBarState(
                visible = true,
                showBookmark = selected.any { !it.bookmark },
                showRemoveBookmark = selected.any { it.bookmark },
                showMarkAsRead = selected.any { !it.read },
                showMarkAsUnread = selected.any { it.read },
                showMarkPreviousAsRead = selected.size == 1,
                showDownload = !presenter.manga.isLocal() && selected.any { !it.isDownloaded },
                showDelete = !presenter.manga.isLocal() && selected.any { it.isDownloaded },
            )
        }
        // Idempotent: skip the reassignment when nothing changed (data-class equality) so redundant
        // invalidates don't allocate a fresh instance and force a needless recomposition.
        if (newState != actionBarState) actionBarState = newState
    }

    /** Mark every chapter before the single selected one as read (mihon mark-previous semantics). */
    private fun markPreviousFromSelection() {
        val item = selectedChapters().singleOrNull() ?: return
        markPreviousAs(item, true)
    }
    //endregion

    fun changeCover() {
        if (manga?.favorite == true) {
            val intent = Intent(Intent.ACTION_GET_CONTENT)
            intent.type = "image/*"
            startActivityForResult(
                Intent.createChooser(
                    intent,
                    activity?.getString(MR.strings.select_cover_image),
                ),
                101,
            )
        } else {
            activity?.toast(MR.strings.must_be_in_library_to_edit)
        }
    }

    fun setActiveEditController(controller: EditMangaController) {
        editMangaController = controller
    }

    fun clearActiveEditController(controller: EditMangaController) {
        if (editMangaController == controller) {
            editMangaController = null
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == 101) {
            if (data == null || resultCode != Activity.RESULT_OK) return
            val activity = activity ?: return
            try {
                val uri = data.data ?: return
                if (editMangaController != null) {
                    editMangaController?.updateCover(uri)
                } else {
                    presenter.editCoverWithStream(uri)
                }
            } catch (error: IOException) {
                activity.toast(MR.strings.failed_to_update_cover)
                Logger.e(error)
            }
        }
    }

    override fun zoomImageFromThumb(thumbView: View) {
        if (fullCoverActive) return
        val drawable = binding.mangaCoverFull.drawable ?: return
        fullCoverActive = true
        drawable.alpha = 255
        val fullCoverDialog = FullCoverDialog(this, drawable, thumbView)
        fullCoverDialog.setOnDismissListener {
            fullCoverActive = false
        }
        fullCoverDialog.setOnCancelListener {
            fullCoverActive = false
        }
        fullCoverDialog.show()
    }

    companion object {
        const val UPDATE_EXTRA = "update"
        const val SMART_SEARCH_CONFIG_EXTRA = "smartSearchConfig"

        const val FROM_CATALOGUE_EXTRA = "from_catalogue"

        private enum class RangeMode {
            Download,
            RemoveDownload,
        }

        fun bundle(
            mangaId: Long? = null,
            fromCatalogue: Boolean = false,
            smartSearchConfig: BrowseController.SmartSearchConfig? = null,
            update: Boolean = false,
        ) = Bundle().apply {
            putLong(Constants.MANGA_EXTRA, mangaId ?: 0)
            putBoolean(FROM_CATALOGUE_EXTRA, fromCatalogue)
            putParcelable(SMART_SEARCH_CONFIG_EXTRA, smartSearchConfig)
            putBoolean(UPDATE_EXTRA, update)
        }
    }

    inner class FloatingMangaDetailsActionModeCallback(
        private val textView: TextView?,
        private val showCopy: Boolean = true,
        private val isTag: Boolean = false,
        private val closeMode: Boolean = true,
    ) : android.view.ActionMode.Callback {
        constructor(
            text: String,
            showCopy: Boolean = true,
            searchSource: Boolean = false,
            closeMode: Boolean = true,
        ) : this(null, showCopy, searchSource, closeMode) {
            customText = text
        }

        private var customText: String? = null
        var authorText: String? = null
        var artistText: String? = null
        var isGlobalSearch: Boolean? = null
        val text: String
            get() {
                return customText ?: if (textView?.isTextSelectable == true) {
                    textView.text.subSequence(textView.selectionStart, textView.selectionEnd)
                        .toString()
                } else {
                    textView?.text?.toString() ?: ""
                }
            }
        override fun onCreateActionMode(mode: android.view.ActionMode?, menu: Menu?): Boolean {
            mode?.menuInflater?.inflate(
                if (isTag) R.menu.manga_details_tag else R.menu.manga_details_title,
                menu,
            )
            menu?.findItem(R.id.action_copy)?.isVisible = showCopy
            var sourceMenuItem = menu?.findItem(R.id.action_source_search)
            sourceMenuItem?.isVisible = isTag && presenter.source is CatalogueSource
            val context = view?.context ?: return false
            val localItem = menu?.findItem(R.id.action_local_search) ?: return true
            localItem.isVisible = previousController !is FilteredLibraryController
            val library = context.getString(MR.strings.library).lowercase(Locale.getDefault())
            localItem.title = context.getString(MR.strings.search_, library)
            sourceMenuItem?.title = context.getString(MR.strings.search_, presenter.source.name)
            menu.findItem(R.id.action_search_author)?.title = context.getString(
                MR.strings.search_,
                context.getString(MR.strings.author).lowercase(Locale.getDefault()),
            )
            menu.findItem(R.id.action_search_artist)?.title = context.getString(
                MR.strings.search_,
                context.getString(MR.strings.artist).lowercase(Locale.getDefault()),
            )
            if (isTag) {
                if (previousController is BrowseSourceController) {
                    menu.removeItem(R.id.action_source_search)
                    sourceMenuItem = menu.add(0, R.id.action_source_search, 1, sourceMenuItem?.title)
                    sourceMenuItem?.setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
                }
                sourceMenuItem?.icon = presenter.source.icon()
            }
            return true
        }

        override fun onPrepareActionMode(mode: android.view.ActionMode?, menu: Menu?): Boolean {
            return false
        }

        override fun onActionItemClicked(
            mode: android.view.ActionMode?,
            item: MenuItem?,
        ): Boolean {
            when (item?.itemId) {
                R.id.action_copy -> copyContentToClipboard(text, null)
                R.id.action_source_search -> sourceSearch(text)
                R.id.action_global_search, R.id.action_local_search -> {
                    if (authorText != null) {
                        mode?.menu?.findItem(R.id.action_copy)?.isVisible = false
                        mode?.menu?.findItem(R.id.action_local_search)?.isVisible = false
                        mode?.menu?.findItem(R.id.action_source_search)?.isVisible = false
                        mode?.menu?.findItem(R.id.action_global_search)?.isVisible = false
                        mode?.menu?.findItem(R.id.action_search_author)?.isVisible = true
                        mode?.menu?.findItem(R.id.action_search_artist)?.isVisible = true
                        isGlobalSearch = item.itemId == R.id.action_global_search
                        mode?.invalidate()
                        return true
                    } else if (item.itemId == R.id.action_global_search) {
                        globalSearch(text)
                    } else {
                        localSearch(text, isTag)
                    }
                }
                R.id.action_search_artist, R.id.action_search_author -> {
                    val subText =
                        (if (item.itemId == R.id.action_search_author) authorText else artistText)
                            ?: return false
                    if (isGlobalSearch == true) {
                        globalSearch(subText)
                    } else {
                        localSearch(subText, isTag)
                    }
                }
                else -> return false
            }
            if (closeMode) {
                mode?.finish()
            }
            return true
        }

        override fun onDestroyActionMode(mode: android.view.ActionMode?) {
            if (showCopy) {
                floatingActionMode = null
            }
            if (textView is Chip) {
                textView.isActivated = false
            }
        }
    }
}
