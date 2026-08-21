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
import dev.ahmedmohamed.hayai.adult.eh.update.EhGalleryUpdatePolicy
import dev.ahmedmohamed.hayai.adult.eh.update.EhGalleryUpdateStateStore
import dev.ahmedmohamed.hayai.adult.eh.update.EhGalleryUpdateWorker
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.ui.base.activity.BaseActivity
import eu.kanade.tachiyomi.ui.webview.WebViewActivity
import eu.kanade.tachiyomi.util.system.dpToPx
import eu.kanade.tachiyomi.util.system.materialAlertDialog
import kotlinx.coroutines.launch
import uy.kohesive.injekt.injectLazy

class EhSettingsActivity : BaseActivity<ViewBinding>() {
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
                        EhLoginOutcome.Cloudflare.name -> "Cloudflare interrupted login. Reopen login and complete the challenge."
                        EhLoginOutcome.InvalidCredentials.name -> "The credentials were invalid or do not have ExHentai access."
                        else -> "Login was cancelled."
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
                title = "E-Hentai and ExHentai"
                setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
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
                text = "Hayai stores the SY-compatible private cookie keys. Credentials are not considered active until ExHentai verifies them."
                setPadding(0, 8.dpToPx, 0, 16.dpToPx)
            },
            matchWidth(),
        )
        content.addView(button("Login or replace credentials") { loginLauncher.launch(EhLoginActivity.newIntent(this)) })
        recheck = button("Recheck current credentials", ::recheckSession)
        content.addView(recheck)
        content.addView(button("Log out") { confirmLogout() })

        content.addView(sectionLabel("Server gallery settings"))
        remoteStatus = TextView(this).apply { setPadding(0, 0, 0, 8.dpToPx) }
        content.addView(remoteStatus, matchWidth())
        imageQuality = button(imageQualityText(), ::chooseImageQuality)
        content.addView(imageQuality)
        hentaiAtHome = button(hentaiAtHomeText(), ::chooseHentaiAtHome)
        content.addView(hentaiAtHome)
        content.addView(
            settingSwitch(
                title = "Use Japanese gallery titles",
                summary = "Prefer the Japanese title when a gallery provides one.",
                checked = ehPreferences.useJapaneseTitle.get(),
                onChanged = { ehPreferences.useJapaneseTitle.set(it); remoteChanged() },
            ),
        )
        content.addView(
            settingSwitch(
                title = "Use original images",
                summary = "Request original gallery images instead of resampled images.",
                checked = ehPreferences.useOriginalImages.get(),
                onChanged = { ehPreferences.useOriginalImages.set(it); remoteChanged() },
            ),
        )
        filterThreshold = button(filterThresholdText()) {
            editThreshold("Tag filtering threshold", ehPreferences.tagFilterThreshold.get(), -9999..0) {
                ehPreferences.tagFilterThreshold.set(it)
                filterThreshold.text = filterThresholdText()
                remoteChanged()
            }
        }
        content.addView(filterThreshold)
        watchingThreshold = button(watchingThresholdText()) {
            editThreshold("Tag watching threshold", ehPreferences.tagWatchingThreshold.get(), 0..9999) {
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
        uploadSettings = button("Apply to E-Hentai and ExHentai") { requestUpload(EhSite.entries.toSet()) }
        content.addView(uploadSettings)
        retryUpload = button("Retry failed sites") { requestUpload(retrySites) }.apply { isVisible = false }
        content.addView(retryUpload)

        content.addView(sectionLabel("Browsing and details"))
        content.addView(
            settingSwitch(
                title = "Open on watched list",
                summary = "Enable the Watched list filter by default on a fresh filter sheet.",
                checked = ehPreferences.watchedListDefault.get(),
                onChanged = ehPreferences.watchedListDefault::set,
            ),
        )
        content.addView(
            settingSwitch(
                title = "Enhanced gallery details",
                summary = "Load tappable gallery page previews in manga details.",
                checked = ehPreferences.enhancedView.get(),
                onChanged = ehPreferences.enhancedView::set,
            ),
        )
        watchedTags = button("Manage watched tags") {
            startActivity(
                WebViewActivity.newIntent(
                    this,
                    "https://exhentai.org/mytags",
                    EhSite.ExHentai.sourceId,
                    "ExHentai watched tags",
                ),
            )
        }
        content.addView(watchedTags)

        content.addView(sectionLabel("Favorites synchronization"))
        content.addView(
            settingSwitch(
                title = "Remote to device only",
                summary = "Never upload local favorite additions, moves, or removals.",
                checked = ehPreferences.favoritesReadOnly.get(),
                onChanged = ehPreferences.favoritesReadOnly::set,
            ),
        )
        content.addView(
            settingSwitch(
                title = "Continue independent errors",
                summary = "Continue unrelated galleries after an error, but never guess how to resolve a conflict.",
                checked = ehPreferences.favoritesLenient.get(),
                onChanged = ehPreferences.favoritesLenient::set,
            ),
        )
        conflictPolicy = button(conflictPolicyText(), ::chooseConflictPolicy)
        content.addView(conflictPolicy)
        categoryMappings = button("Edit E-Hentai category mappings", ::editCategoryMapping)
        content.addView(categoryMappings)
        content.addView(
            TextView(this).apply {
                text = "Category moves preserve existing remote favorite notes. Hayai does not edit note contents. Unrelated J2K categories are preserved."
                alpha = 0.72f
                setPadding(0, 4.dpToPx, 0, 8.dpToPx)
            },
            matchWidth(),
        )
        favoritesStatus = TextView(this)
        content.addView(favoritesStatus, matchWidth())
        content.addView(button("Preview favorites sync", ::previewFavoritesSync))
        content.addView(button("Start or resume favorites sync", ::startFavoritesSync))

        content.addView(sectionLabel("Gallery updater"))
        content.addView(
            TextView(this).apply {
                text = "Periodically checks favorite E-Hentai galleries for replacement revisions. Chapter state, history, categories, favorites, and downloaded copies are preserved when revisions are consolidated."
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
                title = "Wi-Fi only",
                summary = "Require an unmetered connection for gallery revision checks.",
                checked = updatePolicy.wifiOnly,
                onChanged = { updateGalleryPolicy(galleryUpdateStore.policy().copy(wifiOnly = it)) },
            ),
        )
        content.addView(
            settingSwitch(
                title = "Only while charging",
                summary = "Run periodic gallery revision checks only while the device is charging.",
                checked = updatePolicy.requiresCharging,
                onChanged = { updateGalleryPolicy(galleryUpdateStore.policy().copy(requiresCharging = it)) },
            ),
        )
        galleryUpdateStats = TextView(this).apply { setPadding(0, 4.dpToPx, 0, 8.dpToPx) }
        content.addView(galleryUpdateStats, matchWidth())
        content.addView(button("Run gallery updater now") {
            EhGalleryUpdateWorker.runNow(this, galleryUpdateStore.policy())
            galleryUpdateStats.text = "Gallery updater queued. Progress appears in notifications."
        })
        content.addView(button("Cancel gallery updater") {
            EhGalleryUpdateWorker.cancel(this)
            galleryUpdateStats.text = "Gallery updater work was cancelled. Completed gallery changes remain saved."
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
                EhSessionState.LoggedOut -> "Logged out. E-Hentai remains public, and ExHentai is unavailable."
                is EhSessionState.CredentialsAvailable -> "Credentials saved but not verified. Tap Recheck current credentials."
                is EhSessionState.Verified -> "Verified ExHentai session."
                is EhSessionState.InvalidCredentials -> "Invalid credentials. ${state.reason}"
            }
        recheck.isEnabled = state !is EhSessionState.LoggedOut
        watchedTags.isEnabled = state is EhSessionState.Verified
        uploadSettings.isEnabled = state is EhSessionState.Verified
        retryUpload.isEnabled = state is EhSessionState.Verified
        refreshRemoteState()
    }

    private fun recheckSession() {
        recheck.isEnabled = false
        status.text = "Verifying credentials with ExHentai…"
        lifecycleScope.launch {
            when (val result = verifier.verify(sessionStore)) {
                EhVerificationResult.Verified -> {
                    if (sessionStore.markVerified() is EhSessionMutationResult.Failure) {
                        status.text = "No complete credentials are available."
                    }
                }
                is EhVerificationResult.Cloudflare -> status.text = result.message
                is EhVerificationResult.InvalidCredentials -> sessionStore.markInvalid(result.message)
                is EhVerificationResult.NetworkFailure -> status.text = result.message
            }
            recheck.isEnabled = sessionStore.state.value !is EhSessionState.LoggedOut
        }
    }

    private fun confirmLogout() {
        materialAlertDialog()
            .setTitle("Log out of E-Hentai?")
            .setMessage("Hayai will remove only E-Hentai and ExHentai credentials and cookies. Other website sessions are untouched.")
            .setPositiveButton("Log out") { _, _ ->
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
            .setTitle("Categories excluded by default")
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
        return if (count == 0) "Default category filters: show all" else "Default category filters: hide $count"
    }

    private fun categoryName(category: EhCategory): String =
        category.name.replace("Cg", " CG").replace("NonH", "Non-H").replace("ImageSet", "Image Set").replace("AsianPorn", "Asian Porn")

    private fun chooseImageQuality() {
        val values = EhImageQuality.entries
        val labels = arrayOf("Automatic", "2400 px", "1600 px", "1280 px", "980 px", "780 px")
        val current = values.indexOf(EhImageQuality.fromPreference(ehPreferences.imageQuality.get()))
        materialAlertDialog()
            .setTitle("Image resolution")
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
        val labels = arrayOf("Any client", "Default client only", "Never")
        val current = values.indexOf(EhHentaiAtHome.fromPreference(ehPreferences.useHentaiAtHome.get()))
        materialAlertDialog()
            .setTitle("Hentai@Home")
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
            .setMessage("Allowed range: ${range.first} to ${range.last}")
            .setView(input)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val value = input.text?.toString()?.trim()?.toIntOrNull()
                if (value == null || value !in range) {
                    input.error = "Enter a value from ${range.first} to ${range.last}."
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
                if (language.originalCode != null) add(Option(language, 0, "${language.displayName} · Original"))
                add(Option(language, 1, "${language.displayName} · Translated"))
                add(Option(language, 2, "${language.displayName} · Rewrite"))
            }
        }
        val checked = BooleanArray(options.size) { index ->
            val value = selected.getValue(options[index].language)
            when (options[index].field) { 0 -> value.original; 1 -> value.translated; else -> value.rewritten }
        }
        materialAlertDialog()
            .setTitle("Language filtering")
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
            status.text = "Verify ExHentai access before uploading settings."
            return
        }
        if (ehPreferences.showSettingsUploadWarning.get()) {
            materialAlertDialog()
                .setTitle("Create remote Hayai profiles?")
                .setMessage("Hayai will create or reuse one application profile on each site and replace every setting in those profiles. Your other profiles are left untouched.")
                .setPositiveButton("Continue") { _, _ ->
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
                    if (progress is EhUploadProgress.Started) remoteStatus.text = "Uploading ${progress.site.displayName} settings…"
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
            add(if (pending.isEmpty()) "Remote settings match this device." else "Changes are pending for ${pending.joinToString { it.displayName }}.")
            EhSite.entries.forEach { site ->
                when (val result = uploadResults[site]) {
                    is EhSiteUploadResult.Applied -> add("${site.displayName}: applied in profile ${result.slot.value}.")
                    is EhSiteUploadResult.Failed -> add("${site.displayName}: ${result.failure.message}")
                    null -> Unit
                }
            }
        }
        remoteStatus.text = summary.joinToString("\n")
    }

    private fun imageQualityText(): String {
        val value = EhImageQuality.fromPreference(ehPreferences.imageQuality.get())
        val label = when (value) {
            EhImageQuality.Auto -> "Automatic"
            EhImageQuality.Size2400 -> "2400 px"
            EhImageQuality.Size1600 -> "1600 px"
            EhImageQuality.Size1280 -> "1280 px"
            EhImageQuality.Size980 -> "980 px"
            EhImageQuality.Size780 -> "780 px"
        }
        return "Image resolution: $label"
    }

    private fun hentaiAtHomeText(): String =
        "Hentai@Home: " + when (EhHentaiAtHome.fromPreference(ehPreferences.useHentaiAtHome.get())) {
            EhHentaiAtHome.Any -> "any client"
            EhHentaiAtHome.DefaultOnly -> "default client only"
            EhHentaiAtHome.Never -> "never"
        }

    private fun filterThresholdText() = "Tag filtering threshold: ${ehPreferences.tagFilterThreshold.get()}"
    private fun watchingThresholdText() = "Tag watching threshold: ${ehPreferences.tagWatchingThreshold.get()}"

    private fun languageButtonText(): String {
        val count = ehPreferences.languageSelections().values.sumOf { selection ->
            listOf(selection.original, selection.translated, selection.rewritten).count { it }
        }
        return if (count == 0) "Language filters: none" else "Language filters: $count enabled"
    }

    private fun chooseConflictPolicy() {
        val values = listOf("stop", "remote", "local")
        val labels = arrayOf("Stop and review", "Prefer remote", "Prefer local")
        val current = values.indexOf(ehPreferences.favoritesConflictPolicy.get()).coerceAtLeast(0)
        materialAlertDialog()
            .setTitle("Favorites conflict policy")
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
            favoritesStatus.text = "Run Preview favorites sync once to create safe dedicated category mappings."
            return
        }
        val labels = mappings.map { "Slot ${it.slot.value}: ${it.remoteName} → category ${it.categoryId}" }.toTypedArray()
        materialAlertDialog()
            .setTitle("Choose remote slot")
            .setItems(labels) { _, index -> chooseMappedCategory(mappings[index].slot) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun chooseMappedCategory(slot: EhFavoriteSlot) {
        val categories = favoritesSync.availableCategories()
        materialAlertDialog()
            .setTitle("J2K category for slot ${slot.value}")
            .setItems(categories.map { it.second }.toTypedArray()) { _, index ->
                runCatching { favoritesSync.remapCategory(slot, categories[index].first) }
                    .onSuccess { favoritesStatus.text = "Category mapping updated." }
                    .onFailure { favoritesStatus.text = it.message ?: "Category mapping failed." }
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
                    favoritesStatus.text = "Preview: $remote remote changes, $local device changes, $removals removals, ${plan.conflicts.size} conflicts."
                }
                .onFailure { favoritesStatus.text = it.message ?: "Favorites preview failed." }
        }
    }

    private fun startFavoritesSync() {
        lifecycleScope.launch {
            runCatching { favoritesSync.start() }
                .onFailure { favoritesStatus.text = it.message ?: "Favorites sync failed." }
        }
    }

    private fun renderFavoritesStatus(value: EhFavoritesStatus) {
        favoritesStatus.text = when (value) {
            EhFavoritesStatus.Idle -> "No favorites sync is running."
            is EhFavoritesStatus.Planning -> value.message
            is EhFavoritesStatus.NeedsReview -> "${value.conflicts.size} conflicts need review. Choose a conflict policy and start a new sync."
            is EhFavoritesStatus.Running -> "${value.completed}/${value.total}: ${value.title ?: "Updating favorite"}"
            is EhFavoritesStatus.Paused -> "Paused and resumable: ${value.reason}"
            is EhFavoritesStatus.Complete -> if (value.failures.isEmpty()) "Favorites sync complete." else "Favorites sync complete with ${value.failures.size} errors."
        }
    }

    private fun conflictPolicyText(): String = "Conflict policy: " + when (ehPreferences.favoritesConflictPolicy.get()) {
        "remote" -> "prefer remote"
        "local" -> "prefer local"
        else -> "stop and review"
    }

    private fun chooseGalleryUpdateInterval() {
        val values = intArrayOf(0, 12, 24, 48, 72, 168)
        val labels = arrayOf("Disabled", "Every 12 hours", "Every day", "Every 2 days", "Every 3 days", "Every week")
        val current = values.indexOf(galleryUpdateStore.policy().intervalHours).takeIf { it >= 0 } ?: 0
        materialAlertDialog()
            .setTitle("Gallery update interval")
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
            0 -> "Gallery updater: disabled"
            24 -> "Gallery updater: every day"
            48 -> "Gallery updater: every 2 days"
            72 -> "Gallery updater: every 3 days"
            168 -> "Gallery updater: every week"
            else -> "Gallery updater: every $hours hours"
        }

    private fun renderGalleryUpdateStats() {
        val stats = galleryUpdateStore.stats()
        galleryUpdateStats.text =
            if (stats == null) {
                "The gallery updater has not completed a run on this device."
            } else {
                "Last run checked ${stats.attempted} of ${stats.eligible} eligible galleries. " +
                    "${stats.updated} updated, ${stats.newRevisions} new revisions, ${stats.aged} aged, " +
                    "${stats.transientFailures} temporary failures, and ${stats.permanentFailures} permanent failures."
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
