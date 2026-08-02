package eu.kanade.tachiyomi.ui.manga

import yokai.util.koin.get
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.view.WindowInsetsCompat.Type.ime
import androidx.core.graphics.ColorUtils
import androidx.core.view.WindowInsetsCompat.Type.systemBars
import androidx.core.view.children
import androidx.core.view.doOnLayout
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import coil3.load
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.R as materialR
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.coil.useCustomCover
import eu.kanade.tachiyomi.data.database.models.isNovel
import eu.kanade.tachiyomi.data.database.models.seriesType
import eu.kanade.tachiyomi.databinding.EditMangaDialogBinding
import eu.kanade.tachiyomi.domain.manga.models.Manga
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.source.LocalSource
import eu.kanade.tachiyomi.source.icon
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.ui.base.SmallToolbarInterface
import eu.kanade.tachiyomi.ui.base.controller.BaseLegacyController
import eu.kanade.tachiyomi.util.isLocal
import eu.kanade.tachiyomi.util.lang.chop
import eu.kanade.tachiyomi.util.system.ImageUtil
import eu.kanade.tachiyomi.util.system.LocaleHelper
import eu.kanade.tachiyomi.util.system.dpToPx
import eu.kanade.tachiyomi.util.system.getResourceColor
import eu.kanade.tachiyomi.util.system.isInNightMode
import eu.kanade.tachiyomi.util.system.launchNow
import eu.kanade.tachiyomi.util.system.rootWindowInsetsCompat
import eu.kanade.tachiyomi.util.view.doOnApplyWindowInsetsCompat
import eu.kanade.tachiyomi.util.view.liftAppbarWith
import eu.kanade.tachiyomi.widget.TachiyomiTextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import yokai.domain.series.SeriesKnowledgeRepository
import yokai.domain.series.SeriesPreferences
import yokai.domain.series.model.MetadataProviderType
import yokai.domain.series.model.SeriesDisplayOption
import yokai.domain.series.model.SeriesDisplaySection
import yokai.domain.series.model.SeriesKnowledgeBundle
import yokai.domain.series.model.SeriesMetadataChoice
import yokai.domain.series.model.SeriesMetadataField
import yokai.domain.series.model.SeriesMetadataValue
import yokai.domain.series.model.sourceMetadataValues
import yokai.util.koin.injectLazy
import yokai.domain.manga.interactor.GetManga
import yokai.domain.manga.models.cover
import yokai.i18n.MR
import yokai.util.coil.asTarget
import yokai.util.coil.loadManga
import yokai.util.lang.getString

class EditMangaController : BaseLegacyController<EditMangaDialogBinding>, SmallToolbarInterface {

    private val manga: Manga

    private var customCoverUri: Uri? = null

    private var willResetCover = false

    private val languages = mutableListOf<String>()
    private var metadataSourceOptions: Map<String, List<MetadataSourceOption>> = emptyMap()
    private var resetDisplayOptionsToGlobal = false
    private var coverColorThemeOverride: Boolean? = null
    private val metadataSourceFields = SeriesMetadataField.editable + listOf(
        SeriesMetadataField.COVER,
        SeriesMetadataField.BANNER,
    )

    private val infoController: MangaDetailsController
        get() = infoControllerOrNull()
            ?: error("EditMangaController requires a MangaDetailsController target")

    private val seriesKnowledgeRepository: SeriesKnowledgeRepository by injectLazy()
    private val seriesPreferences: SeriesPreferences by injectLazy()

    private fun infoControllerOrNull(): MangaDetailsController? =
        targetController as? MangaDetailsController
            ?: router.backstack
                .map { it.controller }
                .filterIsInstance<MangaDetailsController>()
                .lastOrNull()

    constructor(target: MangaDetailsController, manga: Manga) : super(
        Bundle()
            .apply {
                putLong(KEY_MANGA, manga.id!!)
            },
    ) {
        targetController = target
        this.manga = manga
    }

    @Suppress("unused")
    constructor(bundle: Bundle) : super(bundle) {
        manga = runBlocking { get<GetManga>().awaitById(bundle.getLong(KEY_MANGA))!! }
    }

    override fun createBinding(inflater: LayoutInflater) = EditMangaDialogBinding.inflate(inflater)

    override fun getTitle(): String? = view?.context?.getString(MR.strings.edit)

    override fun onViewCreated(view: View) {
        super.onViewCreated(view)
        liftAppbarWith(binding.scrollView, true)
        bindBottomActionInsets()
        infoController.setActiveEditController(this)
        binding.cancelEdit.setOnClickListener { router.popCurrentController() }
        binding.saveEdit.setOnClickListener { onPositiveButtonClick() }

        val updateScrollIndicators = {
            binding.scrollIndicatorDown.isVisible = binding.scrollView.canScrollVertically(1)
        }
        binding.scrollView.setOnScrollChangeListener { _, _, _, _, _ ->
            updateScrollIndicators()
        }
        binding.scrollView.post {
            updateScrollIndicators()
        }

        val context = binding.root.context

        binding.mangaCover.loadManga(manga)
        val isLocal = manga.isLocal()

        binding.mangaLang.isVisible = isLocal
        if (isLocal) {
            if (manga.title != manga.url) {
                binding.title.append(manga.title)
            }
            binding.title.hint = context.getString(MR.strings.original_value_hint_format, context.getString(MR.strings.title), manga.url)
            binding.mangaAuthor.append(manga.author ?: "")
            binding.mangaArtist.append(manga.artist ?: "")
            binding.mangaDescription.append(manga.description ?: "")
            val preferences = infoController.presenter.preferences
            val extensionManager: ExtensionManager by injectLazy()
            val activeLangs = preferences.enabledLanguages().get()

            languages.add("")
            languages.addAll(
                extensionManager.availableExtensionsFlow.value.groupBy { it.lang }.keys
                    .sortedWith(
                        compareBy(
                            { it !in activeLangs },
                            { LocaleHelper.getSourceDisplayName(it, binding.root.context) },
                        ),
                    )
                    .filter { it != "all" && it != "other" },
            )
            binding.mangaLang.setEntries(
                languages.map {
                    LocaleHelper.getSourceDisplayName(it, binding.root.context)
                },
            )
            binding.mangaLang.setSelection(
                languages.indexOf(LocalSource.getMangaLang(manga))
                    .takeIf { it > -1 } ?: 0,
            )
        } else {
            if (manga.title != manga.ogTitle) {
                binding.title.append(manga.title)
            }
            if (manga.author != manga.originalAuthor) {
                binding.mangaAuthor.append(manga.author ?: "")
            }
            if (manga.artist != manga.originalArtist) {
                binding.mangaArtist.append(manga.artist ?: "")
            }
            if (manga.description != manga.originalDescription) {
                binding.mangaDescription.append(manga.description ?: "")
            }
            binding.title.appendOriginalTextOnLongClick(manga.originalTitle)
            binding.mangaAuthor.appendOriginalTextOnLongClick(manga.originalAuthor)
            binding.mangaArtist.appendOriginalTextOnLongClick(manga.originalArtist)
            binding.mangaDescription.appendOriginalTextOnLongClick(manga.originalDescription)
            binding.title.hint = context.getString(MR.strings.original_value_hint_format, context.getString(MR.strings.title), manga.originalTitle)
            manga.originalAuthor?.let { originalAuthor ->
                binding.mangaAuthor.hint = context.getString(MR.strings.original_value_hint_format, context.getString(MR.strings.author), originalAuthor)
            }
            manga.originalArtist?.let { originalArtist ->
                binding.mangaArtist.hint = context.getString(MR.strings.original_value_hint_format, context.getString(MR.strings.artist), originalArtist)
            }
            manga.originalDescription?.let { originalDescription ->
                binding.mangaDescription.hint =
                    context.getString(
                        MR.strings.original_value_hint_format,
                        context.getString(MR.strings.description),
                        originalDescription.replace("\n", " ").chop(20),
                    )
            }
        }
        setGenreTags(manga.getGenres().orEmpty())
        if (!isLocal) {
            binding.mangaStatus.originalPosition = manga.originalStatus
            binding.seriesType.originalPosition = manga.seriesType(true) - 1
            infoController.presenter.source.icon()?.let { icon ->
                val bitD = ImageUtil.resizeBitMapDrawable(icon, resources, 24.dpToPx)
                binding.mangaStatus.originalIcon = bitD ?: icon
                binding.seriesType.originalIcon = bitD ?: icon
            }
        }
        binding.mangaStatus.setSelection(manga.status.coerceIn(SManga.UNKNOWN, SManga.ON_HIATUS))
        val oldType = manga.seriesType()
        binding.seriesType.setSelection(oldType - 1)
        binding.seriesType.onItemSelectedListener = {
            binding.resetsReadingMode.isVisible = it + 1 != oldType
        }
        binding.mangaGenresTags.clearFocus()
        binding.coverLayout.setOnClickListener {
            infoController.changeCover()
        }
        binding.resetTags.setOnClickListener { resetTags() }
        binding.resetTags.text = context.getString(
            if (manga.originalGenre.isNullOrBlank() || isLocal) {
                MR.strings.clear_tags
            } else {
                MR.strings.reset_tags
            },
        )
        binding.addTagChip.setOnClickListener {
            binding.addTagChip.isVisible = false
            binding.addTagEditText.isVisible = true
            binding.addTagEditText.requestFocus()
            showKeyboard()
        }
        binding.addTagEditText.setOnFocusChangeListener { v, hasFocus ->
            if (!hasFocus && v.parent != null) {
                addTags()
            }
        }
        binding.addTagEditText.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                addTags(true)
                binding.addTagEditText.clearFocus()
                hideKeyboard()
            } else {
                binding.addTagChip.isVisible = true
                binding.addTagEditText.isVisible = false
            }
            true
        }

        binding.resetCover.isVisible = !isLocal
        binding.resetCover.setOnClickListener {
            binding.mangaCover.loadManga(
                manga.cover(),
                target = binding.mangaCover.asTarget(),
            ) {
                useCustomCover(false)
            }
            customCoverUri = null
            willResetCover = true
        }
        setupSeriesEnrichmentControls(context)
        bindCoverColorThemeControl(context)
    }

    private fun bindBottomActionInsets() {
        fun updateScrollIndicators() {
            binding.scrollIndicatorDown.isVisible = binding.scrollView.canScrollVertically(1)
        }

        fun sync(bottomInset: Int) {
            binding.editActionBar.updatePadding(bottom = 16.dpToPx + bottomInset)
            binding.editActionBar.doOnLayout {
                val actionBarHeight = binding.editActionBar.height
                binding.scrollView.updatePadding(bottom = actionBarHeight + 48.dpToPx)
                binding.scrollIndicatorDown.updateLayoutParams<FrameLayout.LayoutParams> {
                    bottomMargin = actionBarHeight
                }
                updateScrollIndicators()
                ensureFocusedFieldVisible(actionBarHeight)
            }
        }

        binding.root.doOnApplyWindowInsetsCompat { _, insets, _ ->
            sync(insets.getInsets(systemBars() or ime()).bottom)
        }
        binding.editActionBar.doOnLayout {
            val bottomInset = binding.root.rootWindowInsetsCompat
                ?.getInsets(systemBars() or ime())?.bottom ?: 0
            sync(bottomInset)
        }
    }

    private fun ensureFocusedFieldVisible(actionBarHeight: Int) {
        val focused = binding.root.findFocus()
            ?.takeIf { it.isDescendantOf(binding.scrollView) }
            ?: return
        binding.scrollView.post {
            val rect = Rect()
            focused.getDrawingRect(rect)
            binding.scrollView.offsetDescendantRectToMyCoords(focused, rect)
            val visibleBottom = binding.scrollView.scrollY + binding.scrollView.height - actionBarHeight - 16.dpToPx
            if (rect.bottom > visibleBottom) {
                val targetScroll = rect.bottom - (binding.scrollView.height - actionBarHeight - 16.dpToPx)
                binding.scrollView.smoothScrollTo(0, targetScroll.coerceAtLeast(0))
            }
        }
    }

    private fun bindCoverColorThemeControl(context: Context) {
        coverColorThemeOverride = infoController.presenter.preferences.themeMangaDetailsOverride(manga.id)
        binding.coverColorTheme.setEntries(
            listOf(
                context.getString(MR.strings.cover_color_follow_global),
                context.getString(MR.strings.cover_color_enabled),
                context.getString(MR.strings.cover_color_disabled),
            ),
        )
        binding.coverColorTheme.setSelection(
            when (coverColorThemeOverride) {
                true -> 1
                false -> 2
                null -> 0
            },
        )
        binding.coverColorTheme.onItemSelectedListener = { position ->
            coverColorThemeOverride = when (position) {
                1 -> true
                2 -> false
                else -> null
            }
        }
    }

    override fun onDestroyView(view: View) {
        infoControllerOrNull()?.clearActiveEditController(this)
        super.onDestroyView(view)
    }

    private fun setupSeriesEnrichmentControls(context: Context) {
        val mangaId = manga.id ?: return
        bindSeriesEnrichmentControls(context, SeriesKnowledgeBundle.Empty)
        viewScope.launch {
            val bundle = withContext(Dispatchers.IO) {
                seriesKnowledgeRepository.get(mangaId)
            }
            bindSeriesEnrichmentControls(context, bundle)
        }
    }

    private fun bindSeriesEnrichmentControls(context: Context, bundle: SeriesKnowledgeBundle) {
        val now = System.currentTimeMillis()
        val values = manga.sourceMetadataValues(now) + userMetadataValues(now) + bundle.metadataValues
        metadataSourceOptions = metadataSourceFields.associate { field ->
            field.key to buildOptionsForField(context, field, values, bundle)
        }
        bindSourcePicker(binding.coverSource, binding.coverSourcePreview, binding.coverSourceReset, SeriesMetadataField.COVER)
        bindSourcePicker(binding.bannerSource, binding.bannerSourcePreview, binding.bannerSourceReset, SeriesMetadataField.BANNER)
        bindSourcePicker(binding.titleSource, binding.titleSourcePreview, binding.titleSourceReset, SeriesMetadataField.TITLE)
        bindSourcePicker(binding.authorSource, binding.authorSourcePreview, binding.authorSourceReset, SeriesMetadataField.AUTHOR)
        bindSourcePicker(binding.artistSource, binding.artistSourcePreview, binding.artistSourceReset, SeriesMetadataField.ARTIST)
        bindSourcePicker(binding.descriptionSource, binding.descriptionSourcePreview, binding.descriptionSourceReset, SeriesMetadataField.DESCRIPTION)
        bindSourcePicker(binding.genresSource, binding.genresSourcePreview, binding.genresSourceReset, SeriesMetadataField.GENRES)
        bindSourcePicker(binding.statusSource, binding.statusSourcePreview, binding.statusSourceReset, SeriesMetadataField.STATUS)

        val localDisplay = bundle.displayOptions.associateBy { it.optionKey }
        fun resolved(section: SeriesDisplaySection): Boolean =
            localDisplay[section.key]?.visible ?: seriesPreferences.displaySection(section).get()

        binding.displayTitle.isChecked = resolved(SeriesDisplaySection.TITLE)
        binding.displayCoverBanner.isChecked = resolved(SeriesDisplaySection.COVER_BANNER)
        binding.displayAuthors.isChecked = resolved(SeriesDisplaySection.AUTHORS)
        binding.displayDescription.isChecked = resolved(SeriesDisplaySection.DESCRIPTION)
        binding.displayGenres.isChecked = resolved(SeriesDisplaySection.GENRES)
        binding.displayAliases.isChecked = resolved(SeriesDisplaySection.ALIASES)
        binding.displayCharacters.isChecked = resolved(SeriesDisplaySection.CHARACTERS)
        binding.displayTrackers.isChecked = resolved(SeriesDisplaySection.TRACKERS)
        binding.displayQuotesTranslation.isChecked = resolved(SeriesDisplaySection.QUOTES_TRANSLATION)
        binding.displayQuotesTranslation.isVisible = manga.isNovel()
        binding.displayExtraImages.isChecked = resolved(SeriesDisplaySection.EXTRA_IMAGES)
        binding.resetDisplayOptions.setOnClickListener {
            resetDisplayOptionsToGlobal = true
            binding.displayTitle.isChecked = seriesPreferences.displaySection(SeriesDisplaySection.TITLE).get()
            binding.displayCoverBanner.isChecked = seriesPreferences.displaySection(SeriesDisplaySection.COVER_BANNER).get()
            binding.displayAuthors.isChecked = seriesPreferences.displaySection(SeriesDisplaySection.AUTHORS).get()
            binding.displayDescription.isChecked = seriesPreferences.displaySection(SeriesDisplaySection.DESCRIPTION).get()
            binding.displayGenres.isChecked = seriesPreferences.displaySection(SeriesDisplaySection.GENRES).get()
            binding.displayAliases.isChecked = seriesPreferences.displaySection(SeriesDisplaySection.ALIASES).get()
            binding.displayCharacters.isChecked = seriesPreferences.displaySection(SeriesDisplaySection.CHARACTERS).get()
            binding.displayTrackers.isChecked = seriesPreferences.displaySection(SeriesDisplaySection.TRACKERS).get()
            binding.displayQuotesTranslation.isChecked = seriesPreferences.displaySection(SeriesDisplaySection.QUOTES_TRANSLATION).get()
            binding.displayExtraImages.isChecked = seriesPreferences.displaySection(SeriesDisplaySection.EXTRA_IMAGES).get()
        }
    }

    private fun buildOptionsForField(
        context: Context,
        field: SeriesMetadataField,
        values: List<SeriesMetadataValue>,
        bundle: SeriesKnowledgeBundle,
    ): List<MetadataSourceOption> {
        val fieldValues = values
            .filter { it.field == field.key && it.value.isNotBlank() }
            .distinctBy { "${it.providerType}:${it.providerId}:${it.value}" }
        val choices = bundle.metadataChoices.associateBy { it.field }
        val selected = choices[field.key]
        val sorted = fieldValues.sortedWith(compareBy<SeriesMetadataValue> {
            when (it.providerType) {
                MetadataProviderType.SOURCE -> 0
                MetadataProviderType.USER -> 1
                MetadataProviderType.TRACKER -> 2
                else -> 3
            }
        }.thenBy { it.providerName })
        val options = sorted.map { value ->
            MetadataSourceOption(
                field = field,
                providerType = value.providerType,
                providerId = value.providerId,
                label = providerLabel(context, value.providerType, value.providerName),
                value = value.value,
                updatedAt = value.updatedAt,
            )
        }
        if (selected == null) return options
        val selectedIndex = options.indexOfFirst {
            it.providerType == selected.providerType && it.providerId == selected.providerId
        }
        return if (selectedIndex <= 0) options else listOf(options[selectedIndex]) + options.filterIndexed { index, _ -> index != selectedIndex }
    }

    private fun bindSourcePicker(
        spinner: eu.kanade.tachiyomi.widget.MaterialSpinnerView,
        preview: TextView,
        resetButton: MaterialButton,
        field: SeriesMetadataField,
    ) {
        val options = metadataSourceOptions[field.key].orEmpty()
        if (options.isEmpty()) {
            spinner.setEntries(listOf(preview.context.getString(MR.strings.series_metadata_source_source)))
            spinner.setSelection(0)
            spinner.setDisabledState()
            preview.text = preview.context.getString(MR.strings.series_metadata_no_preview)
            resetButton.isEnabled = false
            return
        }
        spinner.alpha = 1f
        spinner.setEntries(options.map { it.label })
        spinner.setSelection(0)
        fun sourceIndex(): Int = options.indexOfFirst { it.providerType == MetadataProviderType.SOURCE }.takeIf { it >= 0 } ?: 0
        fun updatePreview(position: Int) {
            val option = options.getOrNull(position)
            preview.text = option?.previewText(preview.context) ?: preview.context.getString(MR.strings.series_metadata_no_preview)
            resetButton.isEnabled = option != null && position != sourceIndex()
        }
        spinner.onItemSelectedListener = { position ->
            options.getOrNull(position)?.let(::applyMetadataSourceOption)
            updatePreview(position)
        }
        resetButton.setOnClickListener {
            val index = sourceIndex()
            spinner.setSelection(index)
            options.getOrNull(index)?.let(::applyMetadataSourceOption)
            updatePreview(index)
        }
        updatePreview(0)
    }

    private fun applyMetadataSourceOption(option: MetadataSourceOption) {
        if (option.providerType == MetadataProviderType.SOURCE) {
            when (option.field) {
                SeriesMetadataField.TITLE -> binding.title.setText("")
                SeriesMetadataField.AUTHOR -> binding.mangaAuthor.setText("")
                SeriesMetadataField.ARTIST -> binding.mangaArtist.setText("")
                SeriesMetadataField.DESCRIPTION -> binding.mangaDescription.setText("")
                SeriesMetadataField.GENRES -> resetTags()
                SeriesMetadataField.STATUS -> binding.mangaStatus.setSelection(manga.originalStatus.coerceIn(SManga.UNKNOWN, SManga.ON_HIATUS))
                else -> Unit
            }
            return
        }
        when (option.field) {
            SeriesMetadataField.TITLE -> binding.title.setText(option.value)
            SeriesMetadataField.AUTHOR -> binding.mangaAuthor.setText(option.value)
            SeriesMetadataField.ARTIST -> binding.mangaArtist.setText(option.value)
            SeriesMetadataField.DESCRIPTION -> binding.mangaDescription.setText(option.value)
            SeriesMetadataField.GENRES -> setGenreTags(option.value.split(",").mapNotNull { it.trim().takeUnless(String::isBlank) })
            SeriesMetadataField.STATUS -> binding.mangaStatus.setSelection(option.value.toIntOrNull()?.coerceIn(SManga.UNKNOWN, SManga.ON_HIATUS) ?: manga.status)
            else -> Unit
        }
    }

    private fun providerLabel(context: Context, type: String, providerName: String): String {
        val typeLabel = when (type) {
            MetadataProviderType.SOURCE -> return context.getString(MR.strings.series_metadata_source_source)
            MetadataProviderType.USER -> return context.getString(MR.strings.series_metadata_source_user)
            MetadataProviderType.TRACKER -> context.getString(MR.strings.series_metadata_source_tracker)
            else -> providerName
        }
        return context.getString(MR.strings.series_metadata_source_provider_format, typeLabel, providerName)
    }

    private fun userMetadataValues(now: Long): List<SeriesMetadataValue> {
        val mangaId = manga.id ?: return emptyList()
        fun value(field: SeriesMetadataField, text: String?): SeriesMetadataValue? =
            text?.takeIf { it.isNotBlank() }?.let {
                SeriesMetadataValue(
                    mangaId = mangaId,
                    field = field.key,
                    providerType = MetadataProviderType.USER,
                    providerId = "manual",
                    providerName = MetadataProviderType.USER,
                    value = it,
                    extraJson = null,
                    confidence = 1.0,
                    userLocked = true,
                    updatedAt = now,
                )
            }
        return listOfNotNull(
            value(SeriesMetadataField.TITLE, manga.title.takeIf { it != manga.originalTitle }),
            value(SeriesMetadataField.AUTHOR, manga.author.takeIf { it != manga.originalAuthor }),
            value(SeriesMetadataField.ARTIST, manga.artist.takeIf { it != manga.originalArtist }),
            value(SeriesMetadataField.DESCRIPTION, manga.description.takeIf { it != manga.originalDescription }),
            value(SeriesMetadataField.GENRES, manga.genre.takeIf { it != manga.originalGenre }),
            value(SeriesMetadataField.STATUS, manga.status.takeIf { it != manga.originalStatus }?.toString()),
        )
    }

    private fun addTags(textCanBeBlank: Boolean = false) {
        if ((textCanBeBlank || !binding.addTagEditText.text.isNullOrBlank()) &&
            binding.addTagEditText.isVisible
        ) {
            val newTags = binding.addTagEditText.text.toString().split(",")
                .mapNotNull { tag -> tag.trim().takeUnless { it.isBlank() } }
            val tags: List<String> = binding.mangaGenresTags.tags.toList() + newTags
            binding.addTagEditText.setText("")
            setGenreTags(tags)
            binding.seriesType.setSelection(manga.seriesType(customTags = tags.joinToString(", ")) - 1)
            binding.addTagChip.isVisible = true
            binding.addTagEditText.isVisible = false
        }
    }

    private fun TachiyomiTextInputEditText.appendOriginalTextOnLongClick(originalText: String?) {
        setOnLongClickListener {
            if (this.text.isNullOrBlank()) {
                this.append(originalText ?: "")
                true
            } else {
                false
            }
        }
    }

    private fun showKeyboard() {
        val inputMethodManager: InputMethodManager =
            binding.root.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        inputMethodManager.showSoftInput(
            binding.addTagEditText,
            InputMethodManager.SHOW_IMPLICIT,
        )
    }

    private fun hideKeyboard() {
        val inputMethodManager: InputMethodManager =
            binding.root.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        inputMethodManager.hideSoftInputFromWindow(binding.addTagEditText.windowToken, 0)
    }

    private fun setGenreTags(genres: List<String>) {
        with(binding.mangaGenresTags) {
            val addTagChip = binding.addTagChip
            val addTagEditText = binding.addTagEditText
            removeAllViews()
            val dark = context.isInNightMode()
            val amoled = infoController.presenter.preferences.themeDarkAmoled().get()
            val baseTagColor = context.getResourceColor(android.R.attr.colorBackground)
            val bgArray = FloatArray(3)
            val accentArray = FloatArray(3)

            ColorUtils.colorToHSL(baseTagColor, bgArray)
            ColorUtils.colorToHSL(context.getResourceColor(materialR.attr.colorSecondary), accentArray)
            val downloadedColor = ColorUtils.setAlphaComponent(
                ColorUtils.HSLToColor(
                    floatArrayOf(
                        bgArray[0],
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
            val textColor = ColorUtils.HSLToColor(
                floatArrayOf(
                    accentArray[0],
                    accentArray[1],
                    if (dark) 0.945f else 0.175f,
                ),
            )
            genres.map { genreText ->
                val chip = LayoutInflater.from(binding.root.context).inflate(
                    R.layout.genre_chip,
                    this,
                    false,
                ) as Chip
                val id = View.generateViewId()
                chip.id = id
                chip.chipBackgroundColor = ColorStateList.valueOf(downloadedColor)
                chip.setTextColor(textColor)
                chip.text = genreText
                chip.isCloseIconVisible = true
                chip.setOnCloseIconClickListener { view ->
                    this.removeView(view)
                    val tags: List<String> = tags.toList() - (view as Chip).text.toString()
                    binding.seriesType.setSelection(
                        manga.seriesType(
                            customTags = tags.joinToString(
                                ", ",
                            ),
                        ) - 1,
                    )
                }
                this.addView(chip)
            }
            addView(addTagChip)
            addView(addTagEditText)
        }
    }

    private val ChipGroup.tags: Array<String>
        get() = children
            .toList()
            .filterIsInstance<Chip>()
            .filter { it.isCloseIconVisible }
            .map { it.text.toString() }
            .toTypedArray()

    private fun resetTags() {
        if (manga.genre.isNullOrBlank() || manga.isLocal()) {
            setGenreTags(emptyList())
        } else {
            setGenreTags(manga.getOriginalGenres().orEmpty())
            binding.seriesType.setSelection(manga.seriesType(true) - 1)
            binding.resetsReadingMode.isVisible = false
        }
    }

    fun updateCover(uri: Uri) {
        willResetCover = false
        binding.mangaCover.load(uri)
        customCoverUri = uri
    }

    private fun onPositiveButtonClick() {
        addTags()
        persistSeriesEnrichmentChoices()
        infoController.presenter.preferences.setThemeMangaDetailsOverride(manga.id, coverColorThemeOverride)
        infoController.presenter.updateManga(
            resolvedTextForSave(SeriesMetadataField.TITLE, binding.title.text?.toString()),
            resolvedTextForSave(SeriesMetadataField.AUTHOR, binding.mangaAuthor.text?.toString()),
            resolvedTextForSave(SeriesMetadataField.ARTIST, binding.mangaArtist.text?.toString()),
            customCoverUri,
            resolvedTextForSave(SeriesMetadataField.DESCRIPTION, binding.mangaDescription.text?.toString()),
            resolvedGenresForSave(),
            resolvedStatusForSave(),
            if (binding.resetsReadingMode.isVisible) binding.seriesType.selectedPosition + 1 else null,
            languages.getOrNull(binding.mangaLang.selectedPosition),
            selectedCoverUrlForSave(),
            willResetCover,
        )
        infoController.refreshCoverColorTheming()
        router.popCurrentController()
    }

    private fun resolvedTextForSave(field: SeriesMetadataField, fallback: String?): String? {
        val position = when (field) {
            SeriesMetadataField.TITLE -> binding.titleSource.selectedPosition
            SeriesMetadataField.AUTHOR -> binding.authorSource.selectedPosition
            SeriesMetadataField.ARTIST -> binding.artistSource.selectedPosition
            SeriesMetadataField.DESCRIPTION -> binding.descriptionSource.selectedPosition
            else -> return fallback
        }
        val option = selectedOption(field, position) ?: return fallback
        return if (option.providerType == MetadataProviderType.SOURCE) "" else option.value
    }

    private fun resolvedGenresForSave(): Array<String>? {
        val option = selectedOption(SeriesMetadataField.GENRES, binding.genresSource.selectedPosition)
            ?: return binding.mangaGenresTags.tags
        return if (option.providerType == MetadataProviderType.SOURCE) {
            manga.getOriginalGenres().orEmpty().toTypedArray()
        } else {
            option.value
                .split(",", ";", "\n")
                .mapNotNull { it.trim().takeUnless(String::isBlank) }
                .toTypedArray()
        }
    }

    private fun resolvedStatusForSave(): Int {
        val option = selectedOption(SeriesMetadataField.STATUS, binding.statusSource.selectedPosition)
            ?: return binding.mangaStatus.selectedPosition
        return if (option.providerType == MetadataProviderType.SOURCE) {
            manga.originalStatus
        } else {
            option.value.toIntOrNull()?.coerceIn(SManga.UNKNOWN, SManga.ON_HIATUS)
                ?: binding.mangaStatus.selectedPosition
        }
    }

    private fun selectedCoverUrlForSave(): String? {
        if (customCoverUri != null || willResetCover) return null
        val option = selectedOption(SeriesMetadataField.COVER, binding.coverSource.selectedPosition)
            ?: return null
        return option.value
            .trim()
            .takeIf { option.providerType != MetadataProviderType.SOURCE }
            ?.takeIf(String::isSupportedPersistedCoverUrl)
            ?.takeIf { it != manga.thumbnail_url }
    }

    private fun persistSeriesEnrichmentChoices() {
        val mangaId = manga.id ?: return
        val now = System.currentTimeMillis()
        val selected = listOfNotNull(
            selectedOption(SeriesMetadataField.COVER, binding.coverSource.selectedPosition),
            selectedOption(SeriesMetadataField.BANNER, binding.bannerSource.selectedPosition),
            selectedOption(SeriesMetadataField.TITLE, binding.titleSource.selectedPosition),
            selectedOption(SeriesMetadataField.AUTHOR, binding.authorSource.selectedPosition),
            selectedOption(SeriesMetadataField.ARTIST, binding.artistSource.selectedPosition),
            selectedOption(SeriesMetadataField.DESCRIPTION, binding.descriptionSource.selectedPosition),
            selectedOption(SeriesMetadataField.GENRES, binding.genresSource.selectedPosition),
            selectedOption(SeriesMetadataField.STATUS, binding.statusSource.selectedPosition),
        )
        val inputValues = inputMetadataValues(now)
        val displayOptions = mapOf(
            SeriesDisplaySection.TITLE to binding.displayTitle.isChecked,
            SeriesDisplaySection.COVER_BANNER to binding.displayCoverBanner.isChecked,
            SeriesDisplaySection.AUTHORS to binding.displayAuthors.isChecked,
            SeriesDisplaySection.DESCRIPTION to binding.displayDescription.isChecked,
            SeriesDisplaySection.GENRES to binding.displayGenres.isChecked,
            SeriesDisplaySection.ALIASES to binding.displayAliases.isChecked,
            SeriesDisplaySection.CHARACTERS to binding.displayCharacters.isChecked,
            SeriesDisplaySection.TRACKERS to binding.displayTrackers.isChecked,
            SeriesDisplaySection.QUOTES_TRANSLATION to binding.displayQuotesTranslation.isChecked,
            SeriesDisplaySection.EXTRA_IMAGES to binding.displayExtraImages.isChecked,
        )
        launchNow {
            seriesKnowledgeRepository.upsertMetadataValues(manga.sourceMetadataValues(now) + inputValues)
            selected.forEach { option ->
                if (option.providerType == MetadataProviderType.SOURCE) {
                    seriesKnowledgeRepository.deleteMetadataChoice(mangaId, option.field.key)
                } else {
                    seriesKnowledgeRepository.upsertMetadataChoice(
                        SeriesMetadataChoice(
                            mangaId = mangaId,
                            field = option.field.key,
                            providerType = option.providerType,
                            providerId = option.providerId,
                            updatedAt = now,
                        ),
                    )
                }
            }
            if (resetDisplayOptionsToGlobal) {
                displayOptions.keys.forEach { section ->
                    seriesKnowledgeRepository.deleteDisplayOption(mangaId, section.key)
                }
            } else {
                displayOptions.forEach { (section, visible) ->
                    seriesKnowledgeRepository.upsertDisplayOption(
                        SeriesDisplayOption(
                            mangaId = mangaId,
                            optionKey = section.key,
                            visible = visible,
                            updatedAt = now,
                        ),
                    )
                }
            }
        }
    }

    private fun selectedOption(field: SeriesMetadataField, position: Int): MetadataSourceOption? =
        metadataSourceOptions[field.key]?.getOrNull(position)

    private fun inputMetadataValues(now: Long): List<SeriesMetadataValue> {
        val mangaId = manga.id ?: return emptyList()
        fun value(field: SeriesMetadataField, text: String?): SeriesMetadataValue? =
            text?.trim()?.takeIf { it.isNotBlank() }?.let {
                SeriesMetadataValue(
                    mangaId = mangaId,
                    field = field.key,
                    providerType = MetadataProviderType.USER,
                    providerId = "manual",
                    providerName = MetadataProviderType.USER,
                    value = it,
                    extraJson = null,
                    confidence = 1.0,
                    userLocked = true,
                    updatedAt = now,
                )
            }
        return listOfNotNull(
            value(SeriesMetadataField.TITLE, binding.title.text?.toString()),
            value(SeriesMetadataField.AUTHOR, binding.mangaAuthor.text?.toString()),
            value(SeriesMetadataField.ARTIST, binding.mangaArtist.text?.toString()),
            value(SeriesMetadataField.DESCRIPTION, binding.mangaDescription.text?.toString()),
            value(SeriesMetadataField.GENRES, binding.mangaGenresTags.tags.joinToString(", ")),
            value(SeriesMetadataField.STATUS, binding.mangaStatus.selectedPosition.toString()),
        )
    }

    private companion object {
        const val KEY_MANGA = "manga_id"
    }

    private data class MetadataSourceOption(
        val field: SeriesMetadataField,
        val providerType: String,
        val providerId: String,
        val label: String,
        val value: String,
        val updatedAt: Long,
    )

    private fun MetadataSourceOption.previewText(context: Context): String {
        val updated = context.getString(
            MR.strings.series_metadata_updated_format,
            DateUtils.getRelativeTimeSpanString(updatedAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS),
        )
        return context.getString(
            MR.strings.series_metadata_preview_format,
            label,
            value.chop(160),
            updated,
        )
    }
}

private fun String.isSupportedPersistedCoverUrl(): Boolean {
    val normalized = trim().lowercase()
    return normalized.startsWith("http://") ||
        normalized.startsWith("https://") ||
        normalized.startsWith("file://") ||
        normalized.startsWith("content://") ||
        normalized.startsWith("/")
}

private fun View.isDescendantOf(parent: View): Boolean {
    var current: View? = this
    while (current != null) {
        if (current == parent) return true
        current = current.parent as? View
    }
    return false
}
