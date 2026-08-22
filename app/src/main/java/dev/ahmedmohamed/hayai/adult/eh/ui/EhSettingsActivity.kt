package dev.ahmedmohamed.hayai.adult.eh.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.setPadding
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.viewbinding.ViewBinding
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import dev.ahmedmohamed.hayai.adult.eh.domain.EhCategory
import dev.ahmedmohamed.hayai.adult.eh.domain.EhSite
import dev.ahmedmohamed.hayai.adult.eh.favorites.EhConflictPolicy
import dev.ahmedmohamed.hayai.adult.eh.favorites.EhFavoriteOperation
import dev.ahmedmohamed.hayai.adult.eh.favorites.EhFavoriteSlot
import dev.ahmedmohamed.hayai.adult.eh.favorites.EhFavoritesStatus
import dev.ahmedmohamed.hayai.adult.eh.favorites.EhFavoritesSyncService
import dev.ahmedmohamed.hayai.adult.eh.settings.EhPreferences
import dev.ahmedmohamed.hayai.adult.eh.settings.EhHentaiAtHome
import dev.ahmedmohamed.hayai.adult.eh.settings.EhImageQuality
import dev.ahmedmohamed.hayai.adult.eh.settings.EhLanguage
import dev.ahmedmohamed.hayai.adult.eh.session.EhSessionMutationResult
import dev.ahmedmohamed.hayai.adult.eh.session.EhSessionState
import dev.ahmedmohamed.hayai.adult.eh.session.EhSessionStore
import dev.ahmedmohamed.hayai.adult.eh.session.EhSessionVerifier
import dev.ahmedmohamed.hayai.adult.eh.session.EhVerificationResult
import dev.ahmedmohamed.hayai.adult.eh.uconfig.EhRemoteSettingsUploader
import dev.ahmedmohamed.hayai.adult.eh.uconfig.EhSiteUploadResult
import dev.ahmedmohamed.hayai.adult.eh.uconfig.EhUploadProgress
import dev.ahmedmohamed.hayai.adult.eh.persistence.HayaiEhPersistenceStore
import dev.ahmedmohamed.hayai.adult.eh.presentation.displayNameResource
import dev.ahmedmohamed.hayai.adult.eh.presentation.EhTextResolver
import dev.ahmedmohamed.hayai.adult.eh.presentation.localizedMessage
import dev.ahmedmohamed.hayai.adult.eh.presentation.localizedEhMessage
import dev.ahmedmohamed.hayai.adult.eh.update.EhGalleryUpdatePolicy
import dev.ahmedmohamed.hayai.adult.eh.update.EhGalleryUpdateStateStore
import dev.ahmedmohamed.hayai.adult.eh.update.EhGalleryUpdateWorker
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.ui.base.activity.BaseActivity
import eu.kanade.tachiyomi.ui.webview.WebViewActivity
import eu.kanade.tachiyomi.util.system.dpToPx
import eu.kanade.tachiyomi.util.system.materialAlertDialog
import kotlinx.coroutines.launch
import uy.kohesive.injekt.injectLazy

class EhSettingsActivity : BaseActivity<ViewBinding>() {
    private val text by injectLazy<EhTextResolver>()
    private val sessionStore by injectLazy<EhSessionStore>()
    private val ehPreferences by injectLazy<EhPreferences>()
    private val network by injectLazy<NetworkHelper>()
    private val settingsUploader by injectLazy<EhRemoteSettingsUploader>()
    private val favoritesSync by injectLazy<EhFavoritesSyncService>()
    private val ehPersistence by injectLazy<HayaiEhPersistenceStore>()
    private val verifier by lazy { EhSessionVerifier(network.client) }
    private val galleryUpdateStore by lazy { EhGalleryUpdateStateStore(this, ehPersistence) }

    private lateinit var status: TextView
    private lateinit var recheck: MaterialButton
    private lateinit var watchedTags: MaterialButton
    private lateinit var categories: MaterialButton
    private lateinit var imageQuality: MaterialButton
    private lateinit var hentaiAtHome: MaterialButton
    private lateinit var filterThreshold: MaterialButton
    private lateinit var watchingThreshold: MaterialButton
    private lateinit var languages: MaterialButton
    private lateinit var remoteStatus: TextView
    private lateinit var uploadSettings: MaterialButton
    private lateinit var retryUpload: MaterialButton
    private lateinit var favoritesStatus: TextView
    private lateinit var conflictPolicy: MaterialButton
    private lateinit var categoryMappings: MaterialButton
    private lateinit var galleryUpdateInterval: MaterialButton
    private lateinit var galleryUpdateStats: TextView
    private val uploadResults = linkedMapOf<EhSite, EhSiteUploadResult>()
    private var retrySites = emptySet<EhSite>()

    private val loginLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val outcome = result.data?.getStringExtra(EhLoginActivity.EXTRA_OUTCOME)
            if (outcome == EhLoginOutcome.Success.name) {
                ehPreferences.clearRemoteSettingsApplied()
                remoteChanged()
            } else if (outcome != null) {
                status.text =
                    when (outcome) {
                        EhLoginOutcome.Cloudflare.name -> getString(R.string.hayai_eh_login_cloudflare_interrupted)
                        EhLoginOutcome.InvalidCredentials.name -> getString(R.string.hayai_eh_login_invalid_credentials)
                        else -> getString(R.string.hayai_eh_login_cancelled)
                    }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = buildContent()
        binding = ViewBinding { root }
        setContentView(root)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                sessionStore.state.collect(::renderState)
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                favoritesSync.status.collect(::renderFavoritesStatus)
            }
        }
    }

    private fun buildContent(): LinearLayout {
        val root =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                fitsSystemWindows = true
            }
        root.addView(
            MaterialToolbar(this).apply {
                title = getString(R.string.hayai_eh_settings_title)
                setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
                navigationContentDescription = getString(R.string.hayai_eh_navigate_up)
                setNavigationOnClickListener { finish() }
            },
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        val content =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(20.dpToPx)
            }
        status = TextView(this).apply { textSize = 16f }
        content.addView(status, matchWidth())
        content.addView(
            TextView(this).apply {
                text = getString(R.string.hayai_eh_credentials_explanation)
                setPadding(0, 8.dpToPx, 0, 16.dpToPx)
            },
            matchWidth(),
        )
        content.addView(button(getString(R.string.hayai_eh_login_or_replace)) { loginLauncher.launch(EhLoginActivity.newIntent(this)) })
        recheck = button(getString(R.string.hayai_eh_recheck_credentials), ::recheckSession)
        content.addView(recheck)
        content.addView(button(getString(R.string.log_out)) { confirmLogout() })

        content.addView(sectionLabel(getString(R.string.hayai_eh_server_gallery_settings)))
        remoteStatus = TextView(this).apply { setPadding(0, 0, 0, 8.dpToPx) }
        content.addView(remoteStatus, matchWidth())
        imageQuality = button(imageQualityText(), ::chooseImageQuality)
        content.addView(imageQuality)
        hentaiAtHome = button(hentaiAtHomeText(), ::chooseHentaiAtHome)
        content.addView(hentaiAtHome)
        content.addView(
            settingSwitch(
                title = getString(R.string.hayai_eh_use_japanese_titles),
                summary = getString(R.string.hayai_eh_use_japanese_titles_summary),
                checked = ehPreferences.useJapaneseTitle.get(),
                onChanged = { ehPreferences.useJapaneseTitle.set(it); remoteChanged() },
            ),
        )
        content.addView(
            settingSwitch(
                title = getString(R.string.hayai_eh_use_original_images),
                summary = getString(R.string.hayai_eh_use_original_images_summary),
                checked = ehPreferences.useOriginalImages.get(),
                onChanged = { ehPreferences.useOriginalImages.set(it); remoteChanged() },
            ),
        )
        filterThreshold = button(filterThresholdText()) {
            editThreshold(getString(R.string.hayai_eh_tag_filtering_threshold), ehPreferences.tagFilterThreshold.get(), -9999..0) {
                ehPreferences.tagFilterThreshold.set(it)
                filterThreshold.text = filterThresholdText()
                remoteChanged()
            }
        }
        content.addView(filterThreshold)
        watchingThreshold = button(watchingThresholdText()) {
            editThreshold(getString(R.string.hayai_eh_tag_watching_threshold), ehPreferences.tagWatchingThreshold.get(), 0..9999) {
                ehPreferences.tagWatchingThreshold.set(it)
                watchingThreshold.text = watchingThresholdText()
                remoteChanged()
            }
        }
        content.addView(watchingThreshold)
        languages = button(languageButtonText(), ::editLanguages)
        content.addView(languages)
        categories = button(categoryButtonText(), ::editDefaultCategories)
        content.addView(categories)
        uploadSettings = button(getString(R.string.hayai_eh_apply_remote_settings)) { requestUpload(EhSite.entries.toSet()) }
        content.addView(uploadSettings)
        retryUpload = button(getString(R.string.hayai_eh_retry_failed_sites)) { requestUpload(retrySites) }.apply { isVisible = false }
        content.addView(retryUpload)

        content.addView(sectionLabel(getString(R.string.hayai_eh_browsing_and_details)))
        content.addView(
            settingSwitch(
                title = getString(R.string.hayai_eh_open_on_watched_list),
                summary = getString(R.string.hayai_eh_open_on_watched_list_summary),
                checked = ehPreferences.watchedListDefault.get(),
                onChanged = ehPreferences.watchedListDefault::set,
            ),
        )
        content.addView(
            settingSwitch(
                title = getString(R.string.hayai_eh_enhanced_gallery_details),
                summary = getString(R.string.hayai_eh_enhanced_gallery_details_summary),
                checked = ehPreferences.enhancedView.get(),
                onChanged = ehPreferences.enhancedView::set,
            ),
        )
        watchedTags = button(getString(R.string.hayai_eh_manage_watched_tags)) {
            startActivity(
                WebViewActivity.newIntent(
                    this,
                    "https://exhentai.org/mytags",
                    EhSite.ExHentai.sourceId,
                    getString(R.string.hayai_eh_watched_tags_web_title),
                ),
            )
        }
        content.addView(watchedTags)

        content.addView(sectionLabel(getString(R.string.hayai_eh_favorites_sync)))
        content.addView(
            settingSwitch(
                title = getString(R.string.hayai_eh_remote_to_device_only),
                summary = getString(R.string.hayai_eh_remote_to_device_only_summary),
                checked = ehPreferences.favoritesReadOnly.get(),
                onChanged = ehPreferences.favoritesReadOnly::set,
            ),
        )
        content.addView(
            settingSwitch(
                title = getString(R.string.hayai_eh_continue_independent_errors),
                summary = getString(R.string.hayai_eh_continue_independent_errors_summary),
                checked = ehPreferences.favoritesLenient.get(),
                onChanged = ehPreferences.favoritesLenient::set,
            ),
        )
        conflictPolicy = button(conflictPolicyText(), ::chooseConflictPolicy)
        content.addView(conflictPolicy)
        categoryMappings = button(getString(R.string.hayai_eh_edit_category_mappings), ::editCategoryMapping)
        content.addView(categoryMappings)
        content.addView(
            TextView(this).apply {
                text = getString(R.string.hayai_eh_category_notes_explanation)
                alpha = 0.72f
                setPadding(0, 4.dpToPx, 0, 8.dpToPx)
            },
            matchWidth(),
        )
        favoritesStatus = TextView(this)
        content.addView(favoritesStatus, matchWidth())
        content.addView(button(getString(R.string.hayai_eh_preview_favorites_sync), ::previewFavoritesSync))
        content.addView(button(getString(R.string.hayai_eh_start_favorites_sync), ::startFavoritesSync))

        content.addView(sectionLabel(getString(R.string.hayai_eh_gallery_updater)))
        content.addView(
            TextView(this).apply {
                text = getString(R.string.hayai_eh_gallery_updater_summary)
                alpha = 0.72f
                setPadding(0, 0, 0, 8.dpToPx)
            },
            matchWidth(),
        )
        galleryUpdateInterval = button(galleryUpdateIntervalText(), ::chooseGalleryUpdateInterval)
        content.addView(galleryUpdateInterval)
        val updatePolicy = galleryUpdateStore.policy()
        content.addView(
            settingSwitch(
                title = getString(R.string.hayai_eh_wifi_only),
                summary = getString(R.string.hayai_eh_wifi_only_summary),
                checked = updatePolicy.wifiOnly,
                onChanged = { updateGalleryPolicy(galleryUpdateStore.policy().copy(wifiOnly = it)) },
            ),
        )
        content.addView(
            settingSwitch(
                title = getString(R.string.hayai_eh_charging_only),
                summary = getString(R.string.hayai_eh_charging_only_summary),
                checked = updatePolicy.requiresCharging,
                onChanged = { updateGalleryPolicy(galleryUpdateStore.policy().copy(requiresCharging = it)) },
            ),
        )
        galleryUpdateStats = TextView(this).apply { setPadding(0, 4.dpToPx, 0, 8.dpToPx) }
        content.addView(galleryUpdateStats, matchWidth())
        content.addView(button(getString(R.string.hayai_eh_run_updater_now)) {
            EhGalleryUpdateWorker.runNow(this, galleryUpdateStore.policy())
            galleryUpdateStats.text = getString(R.string.hayai_eh_updater_queued)
        })
        content.addView(button(getString(R.string.hayai_eh_cancel_updater)) {
            EhGalleryUpdateWorker.cancel(this)
            galleryUpdateStats.text = getString(R.string.hayai_eh_updater_cancelled)
        })
        renderGalleryUpdateStats()

        root.addView(
            ScrollView(this).apply { addView(content) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
        )
        return root
    }

    private fun renderState(state: EhSessionState) {
        status.text =
            when (state) {
                EhSessionState.LoggedOut -> getString(R.string.hayai_eh_session_logged_out)
                is EhSessionState.CredentialsAvailable -> getString(R.string.hayai_eh_session_unverified)
                is EhSessionState.Verified -> getString(R.string.hayai_eh_session_verified)
                is EhSessionState.InvalidCredentials -> getString(R.string.hayai_eh_session_invalid, state.reason.localizedMessage(text))
            }
        recheck.isEnabled = state !is EhSessionState.LoggedOut
        watchedTags.isEnabled = state is EhSessionState.Verified
        uploadSettings.isEnabled = state is EhSessionState.Verified
        retryUpload.isEnabled = state is EhSessionState.Verified
        refreshRemoteState()
    }

    private fun recheckSession() {
        recheck.isEnabled = false
        status.text = getString(R.string.hayai_eh_verifying_credentials)
        lifecycleScope.launch {
            when (val result = verifier.verify(sessionStore)) {
                EhVerificationResult.Verified -> {
                    if (sessionStore.markVerified() is EhSessionMutationResult.Failure) {
                        status.text = getString(R.string.hayai_eh_no_complete_credentials)
                    }
                }
                EhVerificationResult.Cloudflare -> status.text = getString(R.string.hayai_eh_login_cloudflare_failure)
                EhVerificationResult.InvalidCredentials -> sessionStore.markInvalid()
                is EhVerificationResult.NetworkFailure -> status.text = getString(R.string.hayai_eh_login_network_failure)
            }
            recheck.isEnabled = sessionStore.state.value !is EhSessionState.LoggedOut
        }
    }

    private fun confirmLogout() {
        materialAlertDialog()
            .setTitle(R.string.hayai_eh_logout_title)
            .setMessage(R.string.hayai_eh_logout_message)
            .setPositiveButton(R.string.log_out) { _, _ ->
                ehPreferences.clearRemoteSettingsApplied()
                sessionStore.logout()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun sectionLabel(text: String) =
        TextView(this).apply {
            this.text = text
            textSize = 18f
            setPadding(0, 24.dpToPx, 0, 8.dpToPx)
        }

    private fun settingSwitch(
        title: String,
        summary: String,
        checked: Boolean,
        onChanged: (Boolean) -> Unit,
    ): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 8.dpToPx, 0, 8.dpToPx)
            addView(
                SwitchMaterial(context).apply {
                    text = title
                    isChecked = checked
                    setOnCheckedChangeListener { _, value -> onChanged(value) }
                },
                matchWidth(),
            )
            addView(
                TextView(context).apply {
                    text = summary
                    alpha = 0.72f
                    setPadding(48.dpToPx, 0, 0, 0)
                },
                matchWidth(),
            )
        }

    private fun editDefaultCategories() {
        val all = EhCategory.entries
        val selected = ehPreferences.excludedCategories().toMutableSet()
        materialAlertDialog()
            .setTitle(R.string.hayai_eh_categories_excluded_title)
            .setMultiChoiceItems(
                all.map(::categoryName).toTypedArray(),
                BooleanArray(all.size) { all[it] in selected },
            ) { _, index, checked ->
                if (checked) selected += all[index] else selected -= all[index]
            }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                ehPreferences.setExcludedCategories(selected)
                categories.text = categoryButtonText()
                remoteChanged()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun categoryButtonText(): String {
        val count = ehPreferences.excludedCategories().size
        return if (count == 0) {
            getString(R.string.hayai_eh_category_filters_show_all)
        } else {
            getString(R.string.hayai_eh_category_filters_hide, count)
        }
    }

    private fun categoryName(category: EhCategory): String =
        category.name
            .replace("Cg", getString(R.string.hayai_eh_category_cg))
            .replace("NonH", getString(R.string.hayai_eh_category_non_h))
            .replace("ImageSet", getString(R.string.hayai_eh_category_image_set))
            .replace("AsianPorn", getString(R.string.hayai_eh_category_asian_porn))

    private fun chooseImageQuality() {
        val values = EhImageQuality.entries
        val labels = imageQualityLabels()
        val current = values.indexOf(EhImageQuality.fromPreference(ehPreferences.imageQuality.get()))
        materialAlertDialog()
            .setTitle(R.string.hayai_eh_image_resolution)
            .setSingleChoiceItems(labels, current) { dialog, index ->
                ehPreferences.imageQuality.set(values[index].preferenceValue)
                imageQuality.text = imageQualityText()
                remoteChanged()
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun chooseHentaiAtHome() {
        val values = EhHentaiAtHome.entries
        val labels = arrayOf(
            getString(R.string.hayai_eh_hath_any),
            getString(R.string.hayai_eh_hath_default),
            getString(R.string.never),
        )
        val current = values.indexOf(EhHentaiAtHome.fromPreference(ehPreferences.useHentaiAtHome.get()))
        materialAlertDialog()
            .setTitle(R.string.hayai_eh_hath_title)
            .setSingleChoiceItems(labels, current) { dialog, index ->
                ehPreferences.useHentaiAtHome.set(values[index].preferenceValue)
                hentaiAtHome.text = hentaiAtHomeText()
                remoteChanged()
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun editThreshold(
        title: String,
        current: Int,
        range: IntRange,
        save: (Int) -> Unit,
    ) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
            setText(current.toString())
            selectAll()
        }
        val dialog = materialAlertDialog()
            .setTitle(title)
            .setMessage(getString(R.string.hayai_eh_allowed_range, range.first, range.last))
            .setView(input)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val value = input.text?.toString()?.trim()?.toIntOrNull()
                if (value == null || value !in range) {
                    input.error = getString(R.string.hayai_eh_enter_range, range.first, range.last)
                } else {
                    save(value)
                    dialog.dismiss()
                }
            }
        }
        dialog.show()
    }

    private fun editLanguages() {
        data class Option(val language: EhLanguage, val field: Int, val label: String)

        val selected = ehPreferences.languageSelections().toMutableMap()
        val options = buildList {
            EhLanguage.entries.forEach { language ->
                val displayName = getString(language.displayNameResource())
                if (language.originalCode != null) add(Option(language, 0, getString(R.string.hayai_eh_language_original, displayName)))
                add(Option(language, 1, getString(R.string.hayai_eh_language_translated, displayName)))
                add(Option(language, 2, getString(R.string.hayai_eh_language_rewrite, displayName)))
            }
        }
        val checked = BooleanArray(options.size) { index ->
            val value = selected.getValue(options[index].language)
            when (options[index].field) { 0 -> value.original; 1 -> value.translated; else -> value.rewritten }
        }
        materialAlertDialog()
            .setTitle(R.string.hayai_eh_language_filtering)
            .setMultiChoiceItems(options.map(Option::label).toTypedArray(), checked) { _, index, enabled ->
                val option = options[index]
                val old = selected.getValue(option.language)
                selected[option.language] = when (option.field) {
                    0 -> old.copy(original = enabled)
                    1 -> old.copy(translated = enabled)
                    else -> old.copy(rewritten = enabled)
                }
            }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                ehPreferences.setLanguageSelections(selected)
                languages.text = languageButtonText()
                remoteChanged()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun requestUpload(targets: Set<EhSite>) {
        if (targets.isEmpty()) return
        if (sessionStore.state.value !is EhSessionState.Verified) {
            status.text = getString(R.string.hayai_eh_verify_before_upload)
            return
        }
        if (ehPreferences.showSettingsUploadWarning.get()) {
            materialAlertDialog()
                .setTitle(R.string.hayai_eh_create_remote_profiles_title)
                .setMessage(R.string.hayai_eh_create_remote_profiles_message)
                .setPositiveButton(R.string.hayai_eh_continue) { _, _ ->
                    ehPreferences.showSettingsUploadWarning.set(false)
                    performUpload(targets)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        } else {
            performUpload(targets)
        }
    }

    private fun performUpload(targets: Set<EhSite>) {
        uploadSettings.isEnabled = false
        retryUpload.isEnabled = false
        lifecycleScope.launch {
            try {
                val report = settingsUploader.upload(ehPreferences.remoteSettings(), targets) { progress ->
                    if (progress is EhUploadProgress.Started) {
                        remoteStatus.text = getString(R.string.hayai_eh_uploading_site, progress.site.displayName)
                    }
                }
                uploadResults.putAll(report.results)
                retrySites = uploadResults.values.filterIsInstance<EhSiteUploadResult.Failed>().mapTo(linkedSetOf()) { it.site }
                retryUpload.isVisible = retrySites.isNotEmpty()
                refreshRemoteState()
            } finally {
                val verified = sessionStore.state.value is EhSessionState.Verified
                uploadSettings.isEnabled = verified
                retryUpload.isEnabled = verified
            }
        }
    }

    private fun remoteChanged() {
        retrySites = emptySet()
        retryUpload.isVisible = false
        uploadResults.clear()
        refreshRemoteState()
    }

    private fun refreshRemoteState() {
        val pending = EhSite.entries.filter(ehPreferences::hasPendingRemoteSettings)
        val summary = buildList {
            add(
                if (pending.isEmpty()) {
                    getString(R.string.hayai_eh_remote_matches)
                } else {
                    getString(R.string.hayai_eh_remote_pending, pending.joinToString { it.displayName })
                },
            )
            EhSite.entries.forEach { site ->
                when (val result = uploadResults[site]) {
                    is EhSiteUploadResult.Applied -> add(getString(R.string.hayai_eh_remote_applied, site.displayName, result.slot.value))
                    is EhSiteUploadResult.Failed -> add(getString(R.string.hayai_eh_remote_failed, site.displayName, result.failure.localizedMessage(text)))
                    null -> Unit
                }
            }
        }
        remoteStatus.text = summary.joinToString("\n")
    }

    private fun imageQualityText(): String {
        val value = EhImageQuality.fromPreference(ehPreferences.imageQuality.get())
        val label = imageQualityLabels()[value.ordinal]
        return getString(R.string.hayai_eh_image_resolution_value, label)
    }

    private fun imageQualityLabels() = arrayOf(
        getString(R.string.automatic),
        getString(R.string.hayai_eh_resolution_2400),
        getString(R.string.hayai_eh_resolution_1600),
        getString(R.string.hayai_eh_resolution_1280),
        getString(R.string.hayai_eh_resolution_980),
        getString(R.string.hayai_eh_resolution_780),
    )

    private fun hentaiAtHomeText(): String =
        getString(
            R.string.hayai_eh_hath_value,
            when (EhHentaiAtHome.fromPreference(ehPreferences.useHentaiAtHome.get())) {
                EhHentaiAtHome.Any -> getString(R.string.hayai_eh_hath_any_value)
                EhHentaiAtHome.DefaultOnly -> getString(R.string.hayai_eh_hath_default_value)
                EhHentaiAtHome.Never -> getString(R.string.never)
            },
        )

    private fun filterThresholdText() =
        getString(R.string.hayai_eh_threshold_value, getString(R.string.hayai_eh_tag_filtering_threshold), ehPreferences.tagFilterThreshold.get())

    private fun watchingThresholdText() =
        getString(R.string.hayai_eh_threshold_value, getString(R.string.hayai_eh_tag_watching_threshold), ehPreferences.tagWatchingThreshold.get())

    private fun languageButtonText(): String {
        val count = ehPreferences.languageSelections().values.sumOf { selection ->
            listOf(selection.original, selection.translated, selection.rewritten).count { it }
        }
        return if (count == 0) getString(R.string.hayai_eh_language_filters_none) else getString(R.string.hayai_eh_language_filters_enabled, count)
    }

    private fun chooseConflictPolicy() {
        val values = listOf("stop", "remote", "local")
        val labels = arrayOf(
            getString(R.string.hayai_eh_conflict_stop),
            getString(R.string.hayai_eh_conflict_remote),
            getString(R.string.hayai_eh_conflict_local),
        )
        val current = values.indexOf(ehPreferences.favoritesConflictPolicy.get()).coerceAtLeast(0)
        materialAlertDialog()
            .setTitle(R.string.hayai_eh_conflict_policy)
            .setSingleChoiceItems(labels, current) { dialog, index ->
                ehPreferences.favoritesConflictPolicy.set(values[index])
                conflictPolicy.text = conflictPolicyText()
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun editCategoryMapping() {
        val mappings = favoritesSync.categoryMappings()
        if (mappings.isEmpty()) {
            favoritesStatus.text = getString(R.string.hayai_eh_create_mappings_first)
            return
        }
        val labels = mappings.map { getString(R.string.hayai_eh_mapping_label, it.slot.value, it.remoteName, it.categoryId) }.toTypedArray()
        materialAlertDialog()
            .setTitle(R.string.hayai_eh_choose_remote_slot)
            .setItems(labels) { _, index -> chooseMappedCategory(mappings[index].slot) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun chooseMappedCategory(slot: EhFavoriteSlot) {
        val categories = favoritesSync.availableCategories()
        materialAlertDialog()
            .setTitle(getString(R.string.hayai_eh_choose_j2k_category, slot.value))
            .setItems(categories.map { it.second }.toTypedArray()) { _, index ->
                runCatching { favoritesSync.remapCategory(slot, categories[index].first) }
                    .onSuccess { favoritesStatus.text = getString(R.string.hayai_eh_mapping_updated) }
                    .onFailure { favoritesStatus.text = it.localizedEhMessage(text, R.string.hayai_eh_mapping_failed) }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun previewFavoritesSync() {
        lifecycleScope.launch {
            runCatching { favoritesSync.preview() }
                .onSuccess { plan ->
                    val remote = plan.operations.count { it is EhFavoriteOperation.SetRemote || it is EhFavoriteOperation.RemoveRemote }
                    val local = plan.operations.size - remote
                    val removals = plan.operations.count { it is EhFavoriteOperation.RemoveRemote || it is EhFavoriteOperation.RemoveLocal }
                    favoritesStatus.text = getString(R.string.hayai_eh_favorites_preview_result, remote, local, removals, plan.conflicts.size)
                }
                .onFailure { favoritesStatus.text = it.localizedEhMessage(text, R.string.hayai_eh_favorites_preview_failed) }
        }
    }

    private fun startFavoritesSync() {
        lifecycleScope.launch {
            runCatching { favoritesSync.start() }
                .onFailure { favoritesStatus.text = it.localizedEhMessage(text, R.string.hayai_eh_favorites_sync_failed) }
        }
    }

    private fun renderFavoritesStatus(value: EhFavoritesStatus) {
        favoritesStatus.text = when (value) {
            EhFavoritesStatus.Idle -> getString(R.string.hayai_eh_favorites_idle)
            is EhFavoritesStatus.Planning -> value.message
            is EhFavoritesStatus.NeedsReview -> getString(R.string.hayai_eh_favorites_needs_review, value.conflicts.size)
            is EhFavoritesStatus.Running -> getString(
                R.string.hayai_eh_favorites_running,
                value.completed,
                value.total,
                value.title ?: getString(R.string.hayai_eh_updating_favorite),
            )
            is EhFavoritesStatus.Paused -> getString(R.string.hayai_eh_favorites_paused, value.reason)
            is EhFavoritesStatus.Complete -> if (value.failures.isEmpty()) {
                getString(R.string.hayai_eh_favorites_complete)
            } else {
                getString(R.string.hayai_eh_favorites_complete_errors, value.failures.size)
            }
        }
    }

    private fun conflictPolicyText(): String =
        getString(
            R.string.hayai_eh_conflict_policy_value,
            when (ehPreferences.favoritesConflictPolicy.get()) {
                "remote" -> getString(R.string.hayai_eh_conflict_remote_value)
                "local" -> getString(R.string.hayai_eh_conflict_local_value)
                else -> getString(R.string.hayai_eh_conflict_stop_value)
            },
        )

    private fun chooseGalleryUpdateInterval() {
        val values = intArrayOf(0, 12, 24, 48, 72, 168)
        val labels = arrayOf(
            getString(R.string.disabled),
            getString(R.string.every_12_hours),
            getString(R.string.hayai_eh_every_day),
            getString(R.string.every_2_days),
            getString(R.string.every_3_days),
            getString(R.string.hayai_eh_every_week),
        )
        val current = values.indexOf(galleryUpdateStore.policy().intervalHours).takeIf { it >= 0 } ?: 0
        materialAlertDialog()
            .setTitle(R.string.hayai_eh_gallery_update_interval)
            .setSingleChoiceItems(labels, current) { dialog, index ->
                updateGalleryPolicy(galleryUpdateStore.policy().copy(intervalHours = values[index]))
                galleryUpdateInterval.text = galleryUpdateIntervalText()
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun updateGalleryPolicy(policy: EhGalleryUpdatePolicy) {
        EhGalleryUpdateWorker.schedule(this, policy)
        galleryUpdateInterval.text = galleryUpdateIntervalText()
    }

    private fun galleryUpdateIntervalText(): String =
        when (val hours = galleryUpdateStore.policy().intervalHours) {
            0 -> getString(R.string.hayai_eh_updater_disabled)
            24 -> getString(R.string.hayai_eh_updater_daily)
            48 -> getString(R.string.hayai_eh_updater_2_days)
            72 -> getString(R.string.hayai_eh_updater_3_days)
            168 -> getString(R.string.hayai_eh_updater_weekly)
            else -> getString(R.string.hayai_eh_updater_hours, hours)
        }

    private fun renderGalleryUpdateStats() {
        val stats = galleryUpdateStore.stats()
        galleryUpdateStats.text =
            if (stats == null) {
                getString(R.string.hayai_eh_updater_never_run)
            } else {
                getString(
                    R.string.hayai_eh_updater_stats,
                    stats.attempted,
                    stats.eligible,
                    stats.updated,
                    stats.newRevisions,
                    stats.aged,
                    stats.transientFailures,
                    stats.permanentFailures,
                )
            }
    }

    private fun button(
        text: String,
        action: () -> Unit,
    ) = MaterialButton(this).apply {
        this.text = text
        setOnClickListener {
            action()
        }
    }

    private fun matchWidth() =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    companion object {
        fun newIntent(context: Context): Intent = Intent(context, EhSettingsActivity::class.java)
    }
}
