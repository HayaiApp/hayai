package eu.kanade.tachiyomi.ui.manga.track

import android.animation.LayoutTransition
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.graphics.Typeface
import android.os.Bundle
import android.text.Spannable
import android.text.SpannableString
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SearchOff
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.net.toUri
import androidx.core.view.WindowInsetsCompat.Type.systemBars
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.transition.TransitionManager
import co.touchlab.kermit.Logger
import com.google.android.material.datepicker.MaterialDatePicker
import com.mikepenz.fastadapter.FastAdapter
import com.mikepenz.fastadapter.adapters.ItemAdapter
import com.mikepenz.fastadapter.listeners.addClickListener
import eu.kanade.tachiyomi.data.track.EnhancedTrackService
import eu.kanade.tachiyomi.data.track.TrackService
import eu.kanade.tachiyomi.data.track.model.TrackSearch
import eu.kanade.tachiyomi.databinding.TrackChaptersDialogBinding
import eu.kanade.tachiyomi.databinding.TrackScoreDialogBinding
import eu.kanade.tachiyomi.databinding.TrackingBottomSheetBinding
import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.ui.manga.MangaDetailsController
import eu.kanade.tachiyomi.ui.setting.controllers.SettingsTrackingController
import eu.kanade.tachiyomi.util.lang.indexesOf
import eu.kanade.tachiyomi.util.system.addCheckBoxPrompt
import eu.kanade.tachiyomi.util.system.dpToPx
import eu.kanade.tachiyomi.util.system.e
import eu.kanade.tachiyomi.util.system.isOnline
import eu.kanade.tachiyomi.util.system.isPromptChecked
import eu.kanade.tachiyomi.util.system.launchIO
import eu.kanade.tachiyomi.util.system.materialAlertDialog
import eu.kanade.tachiyomi.util.system.openInBrowser
import eu.kanade.tachiyomi.util.system.rootWindowInsetsCompat
import eu.kanade.tachiyomi.util.system.toLocalCalendar
import eu.kanade.tachiyomi.util.system.toUtcCalendar
import eu.kanade.tachiyomi.util.system.toast
import eu.kanade.tachiyomi.util.system.withUIContext
import eu.kanade.tachiyomi.util.view.RecyclerWindowInsetsListener
import eu.kanade.tachiyomi.util.view.checkHeightThen
import eu.kanade.tachiyomi.util.view.expand
import eu.kanade.tachiyomi.util.view.setPositiveButton
import eu.kanade.tachiyomi.util.view.setTitle
import eu.kanade.tachiyomi.util.view.setTitleText
import eu.kanade.tachiyomi.util.view.withFadeTransaction
import eu.kanade.tachiyomi.widget.E2EBottomSheetDialog
import eu.kanade.tachiyomi.widget.EmptyView
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.text.DateFormat
import java.util.Calendar
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException
import yokai.i18n.MR
import yokai.util.lang.getString
import android.R as AR

class TrackingBottomSheet(private val controller: MangaDetailsController) :
    E2EBottomSheetDialog<TrackingBottomSheetBinding>(controller.activity!!),
    TrackAdapter.OnClickListener {

    val activity = controller.activity!!

    val presenter = controller.presenter
    private var searchingItem: TrackItem? = null

    private var adapter: TrackAdapter? = null
    private val searchItemAdapter = ItemAdapter<TrackSearchItem>()
    private val searchAdapter = FastAdapter.with(searchItemAdapter)
    private var suggestedStartDate: Long? = null
    private var suggestedFinishDate: Long? = null
    private var currentSearchQuery = ""
    private var searchStatePrimaryAction: (() -> Unit)? = null
    private var searchStateSecondaryAction: (() -> Unit)? = null
    private val dateFormat: DateFormat by lazy {
        presenter.preferences.dateFormat()
    }

    override fun createBinding(inflater: LayoutInflater) =
        TrackingBottomSheetBinding.inflate(inflater)

    override var recyclerView: RecyclerView? = binding.trackRecycler

    private val backCallback = object : OnBackPressedCallback(enabled = false) {
        override fun handleOnBackPressed() {
            if (searchingItem != null) {
                hideSearchView()
            }
        }
    }

    init {
        val insets = activity.window.decorView.rootWindowInsetsCompat?.getInsets(systemBars())
        val height = insets?.bottom ?: 0
        sheetBehavior.peekHeight = 525.dpToPx + height
        sheetBehavior.expand()
        sheetBehavior.skipCollapsed = true
        val contentBottomPadding = 32.dpToPx + height
        binding.trackRecycler.updatePadding(bottom = contentBottomPadding)
        binding.trackSearchRecycler.updatePadding(bottom = contentBottomPadding)

        binding.searchCloseButton.setOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        searchAdapter.onClickListener = { _, _, _, position ->
            trackItem(position)
            true
        }
        onBackPressedDispatcher.addCallback(backCallback)

        searchAdapter.addClickListener<TrackSearchItem.ViewHolder, TrackSearchItem>({ it.binding.linkButton }) { _, _, _, item ->
            activity.openInBrowser(item.trackSearch.tracking_url)
        }

        binding.textInputLayout.setEndIconOnClickListener {
            submitSearch()
        }

        binding.trackSearch.doAfterTextChanged {
            binding.textInputLayout.error = null
        }

        binding.searchStatePrimaryAction.setOnClickListener {
            searchStatePrimaryAction?.invoke()
        }
        binding.searchStateSecondaryAction.setOnClickListener {
            searchStateSecondaryAction?.invoke()
        }

        binding.trackSearch.setOnEditorActionListener { _, actionId, keyEvent ->
            val isSubmit = actionId == EditorInfo.IME_ACTION_DONE ||
                actionId == EditorInfo.IME_ACTION_SEARCH ||
                (keyEvent?.keyCode == KeyEvent.KEYCODE_ENTER && keyEvent.action == KeyEvent.ACTION_UP)
            if (isSubmit) {
                submitSearch()
            }
            isSubmit
        }

        binding.displayBottomSheet.checkHeightThen {
            val fullHeight = activity.window.decorView.height
            binding.trackRecycler.updateLayoutParams<ConstraintLayout.LayoutParams> {
                matchConstraintMaxHeight = fullHeight - (insets?.top ?: 0) - 30.dpToPx
            }
            binding.trackSearchConstraintLayout.updateLayoutParams<ConstraintLayout.LayoutParams> {
                matchConstraintMaxHeight = fullHeight - (insets?.top ?: 0) - 30.dpToPx
            }
        }

        controller.viewScope.launchIO {
            suggestedStartDate = presenter.getSuggestedDate(ReadingDate.Start)
            suggestedFinishDate = presenter.getSuggestedDate(ReadingDate.Finish)
        }
    }

    override fun onStart() {
        super.onStart()
        sheetBehavior.skipCollapsed = true
        val lTransition = LayoutTransition()
        lTransition.setAnimateParentHierarchy(false)
        binding.root.layoutTransition = lTransition
    }

    /**
     * Called when the sheet is created. It initializes the listeners and values of the preferences.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        adapter = TrackAdapter(this)
        binding.trackRecycler.layoutManager = LinearLayoutManager(context)
        binding.trackRecycler.adapter = adapter
        binding.trackRecycler.setOnApplyWindowInsetsListener(RecyclerWindowInsetsListener)
        // Search results are the bottom-most scroll area now, so they carry the nav-bar inset.
        binding.trackSearchRecycler.setOnApplyWindowInsetsListener(RecyclerWindowInsetsListener)

        binding.trackSearchRecycler.layoutManager = LinearLayoutManager(activity)
        binding.trackSearchRecycler.adapter = searchAdapter
        binding.trackSearchRecycler.setHasFixedSize(false)
        binding.trackSearchRecycler.itemAnimator = null

        adapter?.items = presenter.trackList
        updateTrackingEmptyState(presenter.trackList)
        // The sheet can be opened before MangaDetailsPresenter's initial async DB read finishes.
        // Refresh on every open so both online and offline flows receive the final service rows.
        controller.viewScope.launch {
            presenter.fetchTracks()
        }
    }

    fun onNextTrackersUpdate(trackers: List<TrackItem>) {
        onRefreshDone()
        adapter?.items = trackers
        updateTrackingEmptyState(trackers)
        controller.refreshTracker()
    }

    private fun updateTrackingEmptyState(trackers: List<TrackItem>) {
        val isEmpty = trackers.isEmpty()
        binding.trackRecycler.isVisible = !isEmpty
        if (isEmpty) {
            binding.trackingEmptyView.show(
                Icons.Filled.SearchOff,
                activity.getString(MR.strings.tracker_no_services_help),
                listOf(
                    EmptyView.Action(MR.strings.manage_tracking_services) {
                        dismiss()
                        controller.router.pushController(SettingsTrackingController().withFadeTransaction())
                    },
                ),
            )
        } else {
            binding.trackingEmptyView.hide()
        }
    }

    fun onRefreshDone() {
        for (i in adapter!!.items.indices) {
            (binding.trackRecycler.findViewHolderForAdapterPosition(i) as? TrackHolder)?.setProgress(false)
        }
    }

    fun onRefreshError(error: Throwable) {
        for (i in adapter!!.items.indices) {
            (binding.trackRecycler.findViewHolderForAdapterPosition(i) as? TrackHolder)?.setProgress(false)
        }
        activity.toast(error.message)
    }

    override fun onLogoClick(position: Int) {
        val track = adapter?.getItem(position)?.track ?: return
        if (controller.isNotOnline()) {
            dismiss()
            return
        }

        if (track.tracking_url.isNotBlank()) {
            activity.openInBrowser(track.tracking_url.toUri())
            controller.refreshTracker = position
        }
    }

    override fun onTitleClick(position: Int) {
        val item = adapter?.getItem(position) ?: return
        if (controller.isNotOnline()) {
            dismiss()
            return
        }

        if (item.service is EnhancedTrackService) {
            if (item.track != null) {
                controller.presenter.removeTracker(item, false)
                return
            }

            if (!item.service.accept(controller.presenter.source)) {
                controller.view?.context?.toast(MR.strings.source_unsupported)
                return
            }

            launchIO {
                try {
                    item.service.match(controller.presenter.manga)?.let { track ->
                        controller.presenter.registerTracking(track, item.service)
                    }
                        ?: withUIContext { controller.view?.context?.toast(MR.strings.no_match_found) }
                } catch (e: Exception) {
                    withUIContext { controller.view?.context?.toast(MR.strings.no_match_found) }
                }
            }
        } else {
            showSearchView(item)
        }
    }

    override fun onTitleLongClick(position: Int) {
        val title = adapter?.getItem(position)?.track?.title ?: return
        controller.copyContentToClipboard(title, MR.strings.title, true)
    }

    private fun startTransition(duration: Long = 100) {
        val transition = androidx.transition.TransitionSet()
            .addTransition(androidx.transition.ChangeBounds())
            .addTransition(androidx.transition.Fade())
        transition.duration = duration
        val mainView = binding.root.parent as ViewGroup
        TransitionManager.endTransitions(mainView)
        TransitionManager.beginDelayedTransition(mainView, transition)
    }

    private fun showSearchView(item: TrackItem) {
        searchingItem = item
        backCallback.isEnabled = true
        val title = presenter.manga.title
        val serviceName = activity.getString(item.service.nameRes())
        sheetBehavior.expand()
        sheetBehavior.isDraggable = false
        binding.trackingHeader.isVisible = false
        binding.trackingEmptyView.hide()
        binding.trackRecycler.isVisible = false
        binding.trackSearchConstraintLayout.isVisible = true
        binding.searchToolbarTitle.text = activity.getString(MR.strings.tracker_search_title, serviceName)
        binding.textInputLayout.hint = activity.getString(MR.strings.tracker_search_hint, serviceName)
        binding.searchServiceLogo.setImageResource(item.service.getLogo())
        binding.searchServiceLogo.contentDescription = serviceName
        binding.searchServiceLogoContainer.background = GradientDrawable().apply {
            cornerRadius = 12.dpToPx.toFloat()
            setColor(item.service.getLogoColor())
        }
        binding.trackSearch.setText(title, TextView.BufferType.EDITABLE)
        binding.trackSearch.setSelection(title.length)
        search(title)
    }

    private fun hideSearchView() {
        startTransition()
        presenter.cancelTrackSearch()
        searchItemAdapter.clear()
        searchAdapter.notifyAdapterDataSetChanged()
        searchStatePrimaryAction = null
        searchStateSecondaryAction = null
        sheetBehavior.isDraggable = true
        binding.trackingHeader.isVisible = true
        updateTrackingEmptyState(adapter?.items.orEmpty())
        binding.trackSearchConstraintLayout.isVisible = false
        searchingItem = null
        currentSearchQuery = ""
        backCallback.isEnabled = false
    }

    private fun submitSearch() {
        val text = binding.trackSearch.text?.toString()?.trim().orEmpty()
        if (text.isBlank()) {
            binding.textInputLayout.error = activity.getString(MR.strings.tracker_search_enter_title)
            binding.trackSearch.requestFocus()
            return
        }
        startTransition()
        val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(binding.trackSearch.windowToken, 0)
        binding.trackSearch.clearFocus()
        search(text)
    }

    private fun search(query: String) {
        val item = searchingItem ?: return
        currentSearchQuery = query
        startTransition()
        binding.textInputLayout.error = null
        binding.textInputLayout.setEndIconVisible(false)
        searchItemAdapter.clear()
        val serviceName = activity.getString(item.service.nameRes())
        showSearchState(
            iconRes = eu.kanade.tachiyomi.R.drawable.ic_search_24dp,
            title = activity.getString(MR.strings.tracker_search_loading_title, serviceName),
            message = activity.getString(MR.strings.tracker_search_loading_message, query),
            loading = true,
        )
        if (!presenter.trackSearch(query, item.service)) {
            showSearchError(SearchFailure.Offline)
        }
    }

    fun onSearchResults(results: List<TrackSearch>) {
        val item = searchingItem ?: return
        startTransition()
        binding.textInputLayout.setEndIconVisible(true)
        searchItemAdapter.set(
            results.map {
                TrackSearchItem(it).apply {
                    isSelected = it.tracking_url == searchingItem?.track?.tracking_url
                }
            },
        )
        if (results.isEmpty()) {
            showSearchState(
                iconRes = eu.kanade.tachiyomi.R.drawable.ic_search_off_24dp,
                title = activity.getString(MR.strings.tracker_search_empty_title),
                message = activity.getString(
                    MR.strings.tracker_search_empty,
                    activity.getString(item.service.nameRes()),
                    currentSearchQuery,
                ),
                primaryLabel = activity.getString(MR.strings.retry),
                primaryAction = { search(currentSearchQuery) },
                secondaryLabel = activity.getString(MR.strings.tracker_edit_search),
                secondaryAction = ::focusSearchField,
            )
        } else {
            binding.searchStateCard.isVisible = false
            binding.searchResultsLabel.text = activity.getString(
                MR.strings.tracker_search_results_from,
                activity.getString(item.service.nameRes()),
            )
            binding.searchResultsLabel.isVisible = true
            binding.trackSearchRecycler.isVisible = true
        }
    }

    fun onSearchResultsError(error: Throwable) {
        Logger.e(error)
        showSearchError(error.toSearchFailure())
    }

    private fun showSearchError(failure: SearchFailure) {
        val service = searchingItem?.service ?: return
        val serviceName = activity.getString(service.nameRes())
        val title: String
        val message: String
        val primaryLabel: String
        val primaryAction: () -> Unit
        val secondaryLabel: String?
        val secondaryAction: (() -> Unit)?
        when (failure) {
            SearchFailure.Authentication -> {
                title = activity.getString(MR.strings.tracker_search_error_title)
                message = activity.getString(MR.strings.tracker_session_expired, serviceName)
                primaryLabel = activity.getString(MR.strings.log_in)
                primaryAction = {
                    dismiss()
                    controller.router.pushController(SettingsTrackingController().withFadeTransaction())
                }
                secondaryLabel = activity.getString(MR.strings.retry)
                secondaryAction = { search(currentSearchQuery) }
            }
            SearchFailure.Offline -> {
                title = activity.getString(MR.strings.tracker_search_offline_title)
                message = activity.getString(MR.strings.no_network_connection)
                primaryLabel = activity.getString(MR.strings.retry)
                primaryAction = { search(currentSearchQuery) }
                secondaryLabel = activity.getString(MR.strings.tracker_edit_search)
                secondaryAction = ::focusSearchField
            }
            SearchFailure.Timeout -> {
                title = activity.getString(MR.strings.tracker_search_error_title)
                message = activity.getString(MR.strings.tracker_search_timeout, serviceName)
                primaryLabel = activity.getString(MR.strings.retry)
                primaryAction = { search(currentSearchQuery) }
                secondaryLabel = activity.getString(MR.strings.tracker_edit_search)
                secondaryAction = ::focusSearchField
            }
            SearchFailure.RateLimited -> {
                title = activity.getString(MR.strings.tracker_search_error_title)
                message = activity.getString(MR.strings.tracker_search_rate_limited, serviceName)
                primaryLabel = activity.getString(MR.strings.retry)
                primaryAction = { search(currentSearchQuery) }
                secondaryLabel = null
                secondaryAction = null
            }
            SearchFailure.ServiceUnavailable -> {
                title = activity.getString(MR.strings.tracker_search_error_title)
                message = activity.getString(MR.strings.tracker_search_service_unavailable, serviceName)
                primaryLabel = activity.getString(MR.strings.retry)
                primaryAction = { search(currentSearchQuery) }
                secondaryLabel = null
                secondaryAction = null
            }
            SearchFailure.UnexpectedResponse -> {
                title = activity.getString(MR.strings.tracker_search_error_title)
                message = activity.getString(MR.strings.tracker_search_unexpected_response, serviceName)
                primaryLabel = activity.getString(MR.strings.retry)
                primaryAction = { search(currentSearchQuery) }
                secondaryLabel = activity.getString(MR.strings.tracker_edit_search)
                secondaryAction = ::focusSearchField
            }
            SearchFailure.Other -> {
                title = activity.getString(MR.strings.tracker_search_error_title)
                message = activity.getString(MR.strings.tracker_search_failed, serviceName)
                primaryLabel = activity.getString(MR.strings.retry)
                primaryAction = { search(currentSearchQuery) }
                secondaryLabel = activity.getString(MR.strings.tracker_edit_search)
                secondaryAction = ::focusSearchField
            }
        }
        startTransition()
        binding.textInputLayout.setEndIconVisible(true)
        searchItemAdapter.clear()
        showSearchState(
            iconRes = eu.kanade.tachiyomi.R.drawable.ic_warning_white_24dp,
            title = title,
            message = message,
            primaryLabel = primaryLabel,
            primaryAction = primaryAction,
            secondaryLabel = secondaryLabel,
            secondaryAction = secondaryAction,
        )
    }

    private fun showSearchState(
        iconRes: Int,
        title: String,
        message: String,
        loading: Boolean = false,
        primaryLabel: String? = null,
        primaryAction: (() -> Unit)? = null,
        secondaryLabel: String? = null,
        secondaryAction: (() -> Unit)? = null,
    ) {
        binding.searchResultsLabel.isVisible = false
        binding.trackSearchRecycler.isVisible = false
        binding.searchStateCard.isVisible = true
        binding.searchStateIcon.setImageResource(iconRes)
        binding.searchStateIcon.isVisible = !loading
        binding.searchStateProgress.isVisible = loading
        binding.searchStateTitle.text = title
        binding.searchStateMessage.text = message
        searchStatePrimaryAction = primaryAction
        searchStateSecondaryAction = secondaryAction
        binding.searchStatePrimaryAction.text = primaryLabel
        binding.searchStatePrimaryAction.isVisible = primaryLabel != null && primaryAction != null
        binding.searchStateSecondaryAction.text = secondaryLabel
        binding.searchStateSecondaryAction.isVisible = secondaryLabel != null && secondaryAction != null
    }

    private fun focusSearchField() {
        binding.trackSearch.requestFocus()
        binding.trackSearch.setSelection(binding.trackSearch.text?.length ?: 0)
        val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.showSoftInput(binding.trackSearch, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun Throwable.toSearchFailure(): SearchFailure {
        val causes = generateSequence(this) { it.cause }.toList()
        causes.filterIsInstance<HttpException>().firstOrNull()?.let { http ->
            return when (http.code) {
                401, 403 -> SearchFailure.Authentication
                408 -> SearchFailure.Timeout
                429 -> SearchFailure.RateLimited
                in 500..599 -> SearchFailure.ServiceUnavailable
                else -> SearchFailure.Other
            }
        }
        if (causes.any { it is TimeoutCancellationException || it is SocketTimeoutException }) {
            return SearchFailure.Timeout
        }
        if (causes.any { it is UnknownHostException || it is ConnectException }) {
            return SearchFailure.Offline
        }
        if (causes.any { it is SerializationException }) {
            return SearchFailure.UnexpectedResponse
        }
        val details = generateSequence(this) { it.cause }
            .mapNotNull { it.message }
            .joinToString(" ")
            .lowercase()
        return when {
            listOf("unauthorized", "authentication", "session expired", "not logged", "log in")
                .any(details::contains) -> SearchFailure.Authentication
            listOf("cloudflare", "temporarily unavailable", "service unavailable")
                .any(details::contains) -> SearchFailure.ServiceUnavailable
            else -> SearchFailure.Other
        }
    }

    private enum class SearchFailure {
        Authentication,
        Offline,
        Timeout,
        RateLimited,
        ServiceUnavailable,
        UnexpectedResponse,
        Other,
    }

    private fun trackItem(position: Int) {
        val searchingItem = searchingItem
        val selectedItem = searchItemAdapter.getAdapterItem(position).trackSearch
        if (searchingItem != null) {
            if (searchingItem.track != null && searchingItem.service.canRemoveFromService() &&
                searchingItem.track.tracking_url != selectedItem.tracking_url
            ) {
                val ogTitle = searchingItem.track.title
                val newTitle = selectedItem.title

                val text = activity.getString(
                    MR.strings.remove_x_from_service_and_add_y,
                    ogTitle,
                    activity.getString(
                        searchingItem.service.nameRes(),
                    ),
                    newTitle,
                )

                val wordToSpan: Spannable = SpannableString(text)
                text.indexesOf(ogTitle).forEach {
                    wordToSpan.setSpan(StyleSpan(Typeface.ITALIC), it, it + ogTitle.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                text.indexesOf(newTitle).forEach {
                    wordToSpan.setSpan(StyleSpan(Typeface.ITALIC), it, it + newTitle.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }

                val text2 = activity.getString(
                    MR.strings.keep_both_on_service,
                    activity.getString(
                        searchingItem.service.nameRes(),
                    ),
                )
                activity.materialAlertDialog()
                    .setTitle(MR.strings.remove_previous_tracker)
                    .setItems(arrayOf(wordToSpan, text2)) { dialog, i ->
                        if (i == 0) {
                            removeTracker(searchingItem, true)
                        }
                        connectSelectedTrack(searchingItem, selectedItem)
                        dialog.dismiss()
                    }
                    .setNegativeButton(AR.string.cancel, null)
                    .show()
                return
            }
            connectSelectedTrack(searchingItem, selectedItem)
        }
    }

    private fun connectSelectedTrack(item: TrackItem, selectedItem: TrackSearch) {
        val serviceName = activity.getString(item.service.nameRes())
        binding.trackSearch.isEnabled = false
        binding.textInputLayout.setEndIconVisible(false)
        showSearchState(
            iconRes = eu.kanade.tachiyomi.R.drawable.ic_sync_24dp,
            title = activity.getString(MR.strings.tracker_linking_title),
            message = activity.getString(
                MR.strings.tracker_linking_message,
                selectedItem.title,
                serviceName,
            ),
            loading = true,
        )
        presenter.registerTracking(selectedItem, item.service) { result ->
            if (searchingItem?.service?.id != item.service.id) return@registerTracking
            binding.trackSearch.isEnabled = true
            binding.textInputLayout.setEndIconVisible(true)
            result.fold(
                onSuccess = {
                    showSearchState(
                        iconRes = eu.kanade.tachiyomi.R.drawable.ic_check_circle_24dp,
                        title = activity.getString(MR.strings.tracker_linked_title),
                        message = activity.getString(
                            MR.strings.tracker_linked_message,
                            selectedItem.title,
                            serviceName,
                        ),
                    )
                    controller.viewScope.launch {
                        delay(700)
                        if (searchingItem?.service?.id == item.service.id) {
                            hideSearchView()
                        }
                    }
                },
                onFailure = { error ->
                    Logger.e(error) { "Could not connect $serviceName tracking" }
                    showSearchState(
                        iconRes = eu.kanade.tachiyomi.R.drawable.ic_warning_white_24dp,
                        title = activity.getString(MR.strings.tracker_link_failed_title),
                        message = activity.getString(MR.strings.tracker_link_failed_message, serviceName),
                        primaryLabel = activity.getString(MR.strings.retry),
                        primaryAction = { connectSelectedTrack(item, selectedItem) },
                        secondaryLabel = activity.getString(MR.strings.tracker_back_to_results),
                        secondaryAction = ::showCurrentSearchResults,
                    )
                },
            )
        }
    }

    private fun showCurrentSearchResults() {
        binding.searchStateCard.isVisible = false
        binding.searchResultsLabel.isVisible = true
        binding.trackSearchRecycler.isVisible = true
        binding.textInputLayout.setEndIconVisible(true)
    }

    override fun onStatusClick(position: Int) {
        val item = adapter?.getItem(position) ?: return
        if (item.track == null) return
        if (controller.isNotOnline()) {
            dismiss()
            return
        }

        val statusList = item.service.getStatusList()
        val statusString = statusList.map { item.service.getStatus(it) }
        val selectedIndex = statusList.indexOf(item.track.status)

        activity.materialAlertDialog()
            .setTitle(MR.strings.status)
            .setNegativeButton(AR.string.cancel, null)
            .setSingleChoiceItems(
                statusString.toTypedArray(),
                selectedIndex,
            ) { dialog, itemPosition ->
                setStatus(item, itemPosition)
                dialog.dismiss()
            }
            .show()
    }

    override fun onRemoveClick(position: Int) {
        val item = adapter?.getItem(position) ?: return
        if (item.track == null) return

        val dialog = activity.materialAlertDialog()
            .setNegativeButton(AR.string.cancel, null)

        if (item.service.canRemoveFromService()) {
            val serviceName = activity.getString(item.service.nameRes())
            if (!activity.isOnline()) {
                dialog.setMessage(
                    activity.getString(
                        MR.strings.cannot_remove_tracking_while_offline,
                        serviceName,
                    ),
                )
                    .setPositiveButton(MR.strings.remove) { _, _ ->
                        removeTracker(item, false)
                    }
            } else {
                dialog.addCheckBoxPrompt(
                    activity.getString(MR.strings.remove_tracking_from_, serviceName),
                    true,
                )
                    .setPositiveButton(MR.strings.remove) { dialogI, _ ->
                        removeTracker(item, dialogI.isPromptChecked)
                    }
            }
        } else {
            dialog.setPositiveButton(MR.strings.remove) { _, _ ->
                removeTracker(item, false)
            }
        }
        dialog.setTitle(MR.strings.remove_tracking)
            .show()
    }

    override fun onChaptersClick(position: Int) {
        val item = adapter?.getItem(position) ?: return
        if (item.track == null) return
        if (controller.isNotOnline()) {
            dismiss()
            return
        }

        val binding = TrackChaptersDialogBinding.inflate(activity.layoutInflater)
        val dialog = activity.materialAlertDialog()
            .setTitle(MR.strings.chapters)
            .setView(binding.root)
            .setNegativeButton(AR.string.cancel, null)
            .setPositiveButton(AR.string.ok) { _, _ ->
                // Remove focus to update selected number
                val np = binding.chaptersPicker
                np.clearFocus()
                setChaptersRead(item, np.value)
            }

        val np = binding.chaptersPicker
        // Set initial value
        np.value = item.track.last_chapter_read.toInt()
        if (item.track.total_chapters > 0L) {
            np.wrapSelectorWheel = true
            np.maxValue = item.track.total_chapters.toInt()
        } else {
            // Don't allow to go from 0 to 9999
            np.wrapSelectorWheel = false
        }
        dialog.show()
    }

    override fun onScoreClick(position: Int) {
        val item = adapter?.getItem(position) ?: return
        if (item.track == null) return
        if (controller.isNotOnline()) {
            dismiss()
            return
        }

        val scores = item.service.getScoreList().toTypedArray()
        val binding = TrackScoreDialogBinding.inflate(activity.layoutInflater)
        val dialog = activity.materialAlertDialog()
            .setTitle(MR.strings.score)
            .setView(binding.root)
            .setNegativeButton(AR.string.cancel, null)
            .setPositiveButton(AR.string.ok) { _, _ ->
                val np = binding.scorePicker
                np.clearFocus()

                setScore(item, np.value)
            }

        val np = binding.scorePicker
        np.maxValue = scores.size - 1
        np.displayedValues = scores

        // Set initial value
        val displayedScore = item.service.displayScore(item.track)
        if (displayedScore != "-") {
            val index = scores.indexOf(displayedScore)
            np.value = if (index != -1) index else 0
        }

        dialog.show()
    }

    override fun onStartDateClick(view: View, position: Int) {
        val item = adapter?.getItem(position) ?: return
        if (item.track == null) return

        showMenuPicker(view, item, ReadingDate.Start, suggestedStartDate)
    }

    override fun onFinishDateClick(view: View, position: Int) {
        val item = adapter?.getItem(position) ?: return
        if (item.track == null) return

        showMenuPicker(view, item, ReadingDate.Finish, suggestedFinishDate)
    }

    private fun showMenuPicker(view: View, trackItem: TrackItem, readingDate: ReadingDate, suggestedDate: Long?) {
        val date = if (readingDate == ReadingDate.Start) {
            trackItem.track?.started_reading_date
        } else {
            trackItem.track?.finished_reading_date
        } ?: 0L
        if (date <= 0L) {
            showDatePicker(trackItem, readingDate, suggestedDate)
            return
        }
        val popup = PopupMenu(activity, view, Gravity.NO_GRAVITY)
        popup.menu.add(0, 0, 0, activity.getString(MR.strings.edit))
        getSuggestedDate(trackItem, readingDate, suggestedDate)?.let {
            val subMenu = popup.menu.addSubMenu(0, 1, 0, activity.getString(MR.strings.use_suggested_date))
            subMenu.add(0, 2, 0, it)
        }
        popup.menu.add(0, 3, 0, activity.getString(MR.strings.remove))

        popup.setOnMenuItemClickListener {
            when (it.itemId) {
                0 -> showDatePicker(trackItem, readingDate, suggestedDate)
                2 -> setReadingDate(trackItem, readingDate, suggestedDate!!)
                3 -> setReadingDate(trackItem, readingDate, -1L)
            }
            true
        }

        popup.show()
    }

    enum class ReadingDate {
        Start,
        Finish,
    }

    private fun showDatePicker(trackItem: TrackItem, readingDate: ReadingDate, suggestedDate: Long?) {
        val dialog = MaterialDatePicker.Builder.datePicker()
            .setTitleText(
                when (readingDate) {
                    ReadingDate.Start -> MR.strings.started_reading_date
                    ReadingDate.Finish -> MR.strings.finished_reading_date
                },
            )
            .setSelection(getCurrentDate(trackItem, readingDate, suggestedDate)?.timeInMillis).apply {
            }
            .build()

        dialog.addOnPositiveButtonClickListener { utcMillis ->
            val result = utcMillis.toLocalCalendar()?.timeInMillis
            if (result != null) {
                setReadingDate(trackItem, readingDate, result)
            }
        }
        dialog.show((activity as AppCompatActivity).supportFragmentManager, readingDate.toString())
    }

    private fun getSuggestedDate(trackItem: TrackItem, readingDate: ReadingDate, suggestedDate: Long?): String? {
        trackItem.track ?: return null
        val date = when (readingDate) {
            ReadingDate.Start -> trackItem.track.started_reading_date
            ReadingDate.Finish -> trackItem.track.finished_reading_date
        }
        if (date != 0L) {
            if (suggestedDate != null) {
                val calendar = Calendar.getInstance()
                calendar.timeInMillis = date
                val suggestedCalendar = Calendar.getInstance()
                suggestedCalendar.timeInMillis = suggestedDate
                return if (date > suggestedDate &&
                    (
                        suggestedCalendar.get(Calendar.YEAR) != calendar.get(Calendar.YEAR) ||
                            suggestedCalendar.get(Calendar.MONTH) != calendar.get(Calendar.MONTH) ||
                            suggestedCalendar.get(Calendar.DAY_OF_MONTH) != calendar.get(Calendar.DAY_OF_MONTH)
                        )
                ) {
                    dateFormat.format(suggestedDate)
                } else {
                    null
                }
            }
        }
        suggestedDate?.let {
            return dateFormat.format(suggestedDate)
        }
        return null
    }

    private fun getCurrentDate(trackItem: TrackItem, readingDate: ReadingDate, suggestedDate: Long?): Calendar? {
        // Today if no date is set, otherwise the already set date
        return Calendar.getInstance().apply {
            suggestedDate?.let {
                timeInMillis = it
            }
            trackItem.track?.let {
                val date = when (readingDate) {
                    ReadingDate.Start -> it.started_reading_date
                    ReadingDate.Finish -> it.finished_reading_date
                }
                if (date != 0L) {
                    timeInMillis = date
                }
            }
        }.timeInMillis.toUtcCalendar()
    }

    fun setStatus(item: TrackItem, selection: Int) {
        presenter.setStatus(item, selection)
        refreshItem(item)
    }

    private fun refreshItem(item: TrackItem) {
        refreshTrack(item.service)
    }

    fun refreshItem(index: Int) {
        (binding.trackRecycler.findViewHolderForAdapterPosition(index) as? TrackHolder)?.setProgress(true)
    }

    private fun refreshTrack(item: TrackService?) {
        val index = adapter?.indexOf(item) ?: -1
        if (index > -1) {
            (binding.trackRecycler.findViewHolderForAdapterPosition(index) as? TrackHolder)
                ?.setProgress(true)
        }
    }

    fun setScore(item: TrackItem, score: Int) {
        presenter.setScore(item, score)
        refreshItem(item)
    }

    private fun setChaptersRead(item: TrackItem, chaptersRead: Int) {
        presenter.setLastChapterRead(item, chaptersRead)
        refreshItem(item)
    }

    private fun removeTracker(item: TrackItem, fromServiceAlso: Boolean) {
        refreshTrack(item.service)
        presenter.removeTracker(item, fromServiceAlso)
    }

    private fun setReadingDate(item: TrackItem, type: ReadingDate, date: Long) {
        refreshTrack(item.service)
        when (type) {
            ReadingDate.Start -> controller.presenter.setTrackerStartDate(item, date)
            ReadingDate.Finish -> controller.presenter.setTrackerFinishDate(item, date)
        }
    }
}
