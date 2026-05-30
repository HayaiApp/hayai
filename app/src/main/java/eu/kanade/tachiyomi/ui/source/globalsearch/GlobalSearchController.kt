package eu.kanade.tachiyomi.ui.source.globalsearch

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import androidx.core.view.WindowInsetsCompat.Type.systemBars
import androidx.core.view.isInvisible
import androidx.core.view.updatePaddingRelative
import com.bluelinelabs.conductor.ControllerChangeHandler
import com.bluelinelabs.conductor.ControllerChangeType
import com.google.android.material.snackbar.Snackbar
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.databinding.SourceGlobalSearchControllerBinding
import eu.kanade.tachiyomi.domain.manga.models.Manga
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.ui.base.ExpandedAppBarLayout
import eu.kanade.tachiyomi.ui.base.LocalAppBarOwner
import eu.kanade.tachiyomi.ui.base.controller.BaseCoroutineController
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.ui.main.SearchActivity
import eu.kanade.tachiyomi.ui.main.SearchControllerInterface
import eu.kanade.tachiyomi.ui.manga.MangaDetailsController
import eu.kanade.tachiyomi.ui.source.browse.BrowseSourceController
import eu.kanade.tachiyomi.util.addOrRemoveToFavorites
import eu.kanade.tachiyomi.util.system.extensionIntentForText
import eu.kanade.tachiyomi.util.system.launchIO
import eu.kanade.tachiyomi.util.system.rootWindowInsetsCompat
import eu.kanade.tachiyomi.util.system.withUIContext
import eu.kanade.tachiyomi.util.view.activityBinding
import eu.kanade.tachiyomi.util.view.appBar
import eu.kanade.tachiyomi.util.view.isControllerVisible
import eu.kanade.tachiyomi.util.view.searchToolbar
import eu.kanade.tachiyomi.util.view.scrollViewWith
import eu.kanade.tachiyomi.util.view.setOnQueryTextChangeListener
import eu.kanade.tachiyomi.util.view.snack
import eu.kanade.tachiyomi.util.view.toolbarHeight
import eu.kanade.tachiyomi.util.view.withFadeTransaction
import yokai.util.koin.injectLazy
import yokai.i18n.MR
import yokai.util.lang.getString

/**
 * This controller shows and manages the different search result in global search.
 * This controller should only handle UI actions, IO actions should be done by [GlobalSearchPresenter]
 * [GlobalSearchCardAdapter.OnMangaClickListener] called when manga is clicked in global search
 */
open class GlobalSearchController(
    protected val initialQuery: String? = null,
    val extensionFilter: String? = null,
    bundle: Bundle? = null,
) : BaseCoroutineController<SourceGlobalSearchControllerBinding, GlobalSearchPresenter>(bundle),
    SearchControllerInterface,
    GlobalSearchAdapter.OnTitleClickListener,
    LocalAppBarOwner,
    GlobalSearchCardAdapter.OnMangaClickListener {

    override val hostsOwnAppBar: Boolean = true

    override fun localAppBar(): ExpandedAppBarLayout? = if (isAttached) binding.appBar else null

    override fun onSetupLocalChrome() {
        // appBarVisible=true, useSmallToolbar=false, no tabs — same intent as the
        // Configure the layout-local appBar.
        val appBar = binding.appBar
        appBar.alpha = 1f
        appBar.isInvisible = false
        appBar.lockYPos = false
        appBar.hideBigView(useSmall = false)
        appBar.setToolbarModeBy(this)
        appBar.clearTabs()
        // Scroll-source sync — snap appBar Y to current recycler offset on each enter.
        appBar.y = 0f
        appBar.updateAppBarAfterY(binding.recycler)
        // Menu inflation lives on the local CenteredToolbar; the activity action bar
        // is no longer this controller's host.
        val toolbar = appBar.mainToolbar ?: return
        if (toolbar.menu.size() == 0) {
            toolbar.inflateMenu(R.menu.catalogue_new_list)
        }
        toolbar.setOnMenuItemClickListener { item -> onOptionsItemSelected(item) }
        wireSearchToolbar()
    }

    private fun wireSearchToolbar() {
        val search = binding.appBar.searchToolbar ?: return
        search.setQueryHint(view?.context?.getString(MR.strings.global_search), false)
        // Collapsing the local pill's search dismisses this screen — mirror upstream, where the
        // activity-global pill drove onActionViewCollapse. As a LocalAppBarOwner we own this pill,
        // so wire the dismiss here (the activity-global dispatch no longer reaches us). Set before
        // expandActionView so the listener is in place; gate on visible+attached so navigation
        // teardown collapses don't self-pop.
        search.searchItem?.setOnActionExpandListener(
            object : MenuItem.OnActionExpandListener {
                override fun onMenuItemActionExpand(item: MenuItem): Boolean = true
                override fun onMenuItemActionCollapse(item: MenuItem): Boolean {
                    if (isControllerVisible && isAttached) onActionViewCollapse(item)
                    return true
                }
            },
        )
        search.searchItem?.expandActionView()
        search.searchView?.setQuery(presenter.query, false)
        if (presenter.query.isBlank()) {
            search.searchView?.requestFocus()
        }
        setOnQueryTextChangeListener(search.searchView, onlyOnSubmit = true, hideKbOnSubmit = true) {
            applicationContext?.extensionIntentForText(it ?: "")?.let { intent ->
                safeStartActivity(intent)
            }
            presenter.search(it ?: "")
            setTitle()
            true
        }
    }

    /**
     * Start activity in a safe way: post to view and use activity context.
     */
    private fun safeStartActivity(intent: Intent) {
        val activity = activity ?: return

        try {
            activity.startActivity(intent)
        } catch (e: Exception) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                activity.applicationContext.startActivity(intent)
            } catch (_: Exception) {}
        }
    }

    /**
     * Preferences helper.
     */
    private val preferences: PreferencesHelper by injectLazy()

    /**
     * Adapter containing search results grouped by lang.
     */
    protected var adapter: GlobalSearchAdapter? = null

    private var customTitle: String? = null

    /**
     * Snackbar containing an error message when a request fails.
     */
    private var snack: Snackbar? = null
    private var lastPosition: Int = -1

    override fun createBinding(inflater: LayoutInflater) = SourceGlobalSearchControllerBinding.inflate(inflater)

    override fun getSearchTitle(): String? {
        return customTitle ?: presenter.query
    }

    override val presenter = GlobalSearchPresenter(initialQuery, extensionFilter)

    override fun onTitleClick(position: Int) {
        val source = adapter?.getItem(position)?.source ?: return
        preferences.lastUsedCatalogueSource().set(source.id)
        router.pushController(BrowseSourceController(source, presenter.query).withFadeTransaction())
        lastPosition = position
    }

    /**
     * Called when manga in global search is clicked, opens manga.
     *
     * @param manga clicked item containing manga information.
     */
    override fun onMangaClick(manga: Manga) {
        // Open MangaController.
        lastPosition = adapter?.currentItems?.indexOfFirst { it.source.id == manga.source } ?: -1
        router.pushController(
            MangaDetailsController(manga, true, shouldLockIfNeeded = activity is SearchActivity)
                .withFadeTransaction(),
        )
    }

    /**
     * Called when manga in global search is long clicked.
     *
     * @param position clicked item containing manga information.
     */
    override fun onMangaLongClick(position: Int, adapter: GlobalSearchCardAdapter) {
        val manga = adapter.getItem(position)?.manga ?: return

        val view = view ?: return
        val activity = activity ?: return
        viewScope.launchIO {
            withUIContext { snack?.dismiss() }
            snack = manga.addOrRemoveToFavorites(
                preferences,
                view,
                activity,
                presenter.sourceManager,
                this@GlobalSearchController,
                onMangaAdded = { migrationInfo ->
                    migrationInfo?.let { (source, stillFaved) ->
                        val index = this@GlobalSearchController.adapter
                            ?.currentItems?.indexOfFirst { it.source.id == source } ?: return@let
                        val item = this@GlobalSearchController.adapter?.getItem(index) ?: return@let
                        val oldMangaIndex = item.results?.indexOfFirst {
                            it.manga.title.lowercase() == manga.title.lowercase()
                        } ?: return@let
                        val oldMangaItem = item.results.getOrNull(oldMangaIndex)
                        oldMangaItem?.manga?.favorite = stillFaved
                        val holder = binding.recycler.findViewHolderForAdapterPosition(index) as? GlobalSearchHolder
                        holder?.updateManga(oldMangaIndex)
                    }
                    adapter.notifyItemChanged(position)
                    snack = view.snack(MR.strings.added_to_library)
                },
                onMangaMoved = { adapter.notifyItemChanged(position) },
                onMangaDeleted = { presenter.confirmDeletion(manga) },
                scope = viewScope,
            )
            if (snack?.duration == Snackbar.LENGTH_INDEFINITE) {
                withUIContext {
                    (activity as? MainActivity)?.setUndoSnackBar(snack)
                }
            }
        }
    }

    override fun showFloatingBar() =
        activity !is SearchActivity ||
            customTitle == null ||
            extensionFilter == null

    override fun onChangeStarted(handler: ControllerChangeHandler, type: ControllerChangeType) {
        super.onChangeStarted(handler, type)
        if (type.isEnter && isControllerVisible) {
            // BaseController.onChangeStarted already called onSetupLocalChrome.
            val searchView = searchToolbar()?.searchView ?: return
            val searchItem = searchToolbar()?.searchItem ?: return
            searchItem.expandActionView()
            searchView.setQuery(presenter.query, false)
            if (presenter.query.isNotBlank()) {
                searchView.clearFocus()
            }
        }
        if (type == ControllerChangeType.POP_ENTER && lastPosition > -1) {
            val holder = binding.recycler.findViewHolderForAdapterPosition(lastPosition) as? GlobalSearchHolder
            holder?.updateAll()
            lastPosition = -1
        }
    }

    override fun onActionViewExpand(item: MenuItem?) {
        val searchView = searchToolbar()?.searchView ?: return
        searchView.setQuery(presenter.query, false)
    }

    override fun onActionViewCollapse(item: MenuItem?) {
        if (activity is SearchActivity) {
            (activity as? SearchActivity)?.onBackPressedDispatcher?.onBackPressed()
        } else if (customTitle == null) {
            router.popCurrentController()
        }
    }

    /**
     * Called when the view is created
     *
     * @param view view of controller
     */
    override fun onViewCreated(view: View) {
        super.onViewCreated(view)
        adapter = GlobalSearchAdapter(this)

        binding.recycler.updatePaddingRelative(
            top = (toolbarHeight ?: 0) +
                (activityBinding?.root?.rootWindowInsetsCompat?.getInsets(systemBars())?.top ?: 0),
        )

        // Create recycler and set adapter.
        binding.recycler.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(view.context)
        binding.recycler.adapter = adapter
        scrollViewWith(binding.recycler, padBottom = true)
        if (extensionFilter != null) {
            customTitle = view.context?.getString(MR.strings.loading)
            setTitle()
        }

        // try to handle the query as a manga URL
        // Prevent infinite loop: only launch deep link if this is the first controller creation
        if (initialQuery == null) {
            applicationContext?.extensionIntentForText(presenter.query)?.let { intent ->
                safeStartActivity(intent)
            }
        }
    }

    override fun onDestroyView(view: View) {
        adapter = null
        super.onDestroyView(view)
    }

    override fun onSaveViewState(view: View, outState: Bundle) {
        super.onSaveViewState(view, outState)
        adapter?.onSaveInstanceState(outState)
    }

    override fun onRestoreViewState(view: View, savedViewState: Bundle) {
        super.onRestoreViewState(view, savedViewState)
        adapter?.onRestoreInstanceState(savedViewState)
    }

    /**
     * Returns the view holder for the given manga.
     *
     * @param source used to find holder containing source
     * @return the holder of the manga or null if it's not bound.
     */
    private fun getHolder(source: CatalogueSource): GlobalSearchHolder? {
        val adapter = adapter ?: return null

        adapter.allBoundViewHolders.forEach { holder ->
            val item = adapter.getItem(holder.flexibleAdapterPosition)
            if (item != null && source.id == item.source.id) {
                return holder as GlobalSearchHolder
            }
        }

        return null
    }

    /**
     * Add search result to adapter.
     *
     * @param searchResult result of search.
     */
    fun setItems(searchResult: List<GlobalSearchItem>) {
        if (extensionFilter != null) {
            val results = searchResult.firstOrNull()?.results
            if (results != null && searchResult.size == 1 && results.size == 1) {
                val manga = results.first().manga
                router.replaceTopController(
                    MangaDetailsController(manga, true, shouldLockIfNeeded = true)
                        .withFadeTransaction(),
                )
                return
            } else if (results != null) {
                (activity as? SearchActivity)?.setFloatingToolbar(true)
                customTitle = null
                setTitle()
                activity?.invalidateOptionsMenu()
                appBar()?.updateAppBarAfterY(binding.recycler)
            }
        }
        adapter?.updateDataSet(searchResult)
    }

    /**
     * Called from the presenter when a manga is initialized.
     *
     * @param manga the initialized manga.
     */
    fun onMangaInitialized(source: CatalogueSource, manga: Manga) {
        getHolder(source)?.setImage(manga)
    }
}
