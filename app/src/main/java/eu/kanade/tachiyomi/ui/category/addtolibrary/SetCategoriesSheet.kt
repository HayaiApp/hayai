package eu.kanade.tachiyomi.ui.category.addtolibrary

import android.app.Activity
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.WindowInsetsCompat.Type.systemBars
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePaddingRelative
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.mikepenz.fastadapter.FastAdapter
import com.mikepenz.fastadapter.adapters.ItemAdapter
import eu.kanade.tachiyomi.data.database.models.Category
import eu.kanade.tachiyomi.data.database.models.MangaCategory
import eu.kanade.tachiyomi.data.database.models.seriesType
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.databinding.SetCategoriesSheetBinding
import eu.kanade.tachiyomi.domain.manga.models.Manga
import eu.kanade.tachiyomi.ui.category.ManageCategoryDialog
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.util.system.dpToPx
import eu.kanade.tachiyomi.util.system.rootWindowInsetsCompat
import eu.kanade.tachiyomi.util.view.checkHeightThen
import eu.kanade.tachiyomi.util.view.expand
import eu.kanade.tachiyomi.widget.E2EBottomSheetDialog
import eu.kanade.tachiyomi.widget.TriStateCheckBox
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import yokai.util.koin.injectLazy
import yokai.domain.category.interactor.GetCategories
import yokai.domain.category.interactor.SetMangaCategories
import yokai.domain.manga.interactor.UpdateManga
import yokai.domain.manga.models.MangaUpdate
import yokai.i18n.MR
import yokai.util.lang.getString

class SetCategoriesSheet(
    private val activity: Activity,
    private val listManga: List<Manga>,
    var categories: MutableList<Category>,
    var preselected: Array<TriStateCheckBox.State>,
    private val addingToLibrary: Boolean,
    val onMangaAdded: (() -> Unit) = { },
) : E2EBottomSheetDialog<SetCategoriesSheetBinding>(activity) {

    constructor(
        activity: Activity,
        manga: Manga,
        categories: MutableList<Category>,
        preselected: Array<Int>,
        addingToLibrary: Boolean,
        onMangaAdded: () -> Unit,
    ) : this(
        activity,
        listOf(manga),
        categories,
        categories.map {
            if (it.id in preselected) {
                TriStateCheckBox.State.CHECKED
            } else {
                TriStateCheckBox.State.UNCHECKED
            }
        }.toTypedArray(),
        addingToLibrary,
        onMangaAdded,
    )

    private val fastAdapter: FastAdapter<AddCategoryItem>
    private val itemAdapter = ItemAdapter<AddCategoryItem>()

    private val getCategories: GetCategories by injectLazy()
    private val setMangaCategories: SetMangaCategories by injectLazy()
    private val updateManga: UpdateManga by injectLazy()

    // Process-scope: pending DB writes (favorite, setMangaCategories) need to complete even
    // if the sheet is dismissed mid-save.
    private val sheetScope = CoroutineScope(Job() + Dispatchers.Main)

    private val preferences: PreferencesHelper by injectLazy()
    override var recyclerView: RecyclerView? = binding.categoryRecyclerView

    private val preCheckedCategories = categories.mapIndexedNotNull { index, category ->
        category.takeIf { preselected[index] == TriStateCheckBox.State.CHECKED }
    }
    private val preIndeterminateCategories = categories.mapIndexedNotNull { index, category ->
        category.takeIf { preselected[index] == TriStateCheckBox.State.IGNORE }
    }
    private val selectedCategories = preIndeterminateCategories + preCheckedCategories

    private val selectedItems: Set<AddCategoryItem>
        get() = itemAdapter.adapterItems.filter { it.isSelected }.toSet()

    private val checkedItems: Set<AddCategoryItem>
        get() = itemAdapter.adapterItems.filter { it.state == TriStateCheckBox.State.CHECKED }.toSet()

    private val indeterminateItems: Set<AddCategoryItem>
        get() = itemAdapter.adapterItems.filter { it.state == TriStateCheckBox.State.IGNORE }.toSet()

    private val uncheckedItems: Set<AddCategoryItem>
        get() = itemAdapter.adapterItems.filter { !it.isSelected }.toSet()

    override fun createBinding(inflater: LayoutInflater) =
        SetCategoriesSheetBinding.inflate(inflater)

    init {
        binding.toolbarTitle.text = context.getString(
            if (addingToLibrary) MR.strings.add_x_to else MR.strings.move_x_to,
            if (listManga.size == 1) {
                listManga.first().seriesType(context)
            } else {
                context.getString(MR.strings.selection).lowercase(Locale.ROOT)
            },
        )

        setOnShowListener {
            updateBottomButtons()
        }
        sheetBehavior.addBottomSheetCallback(
            object : BottomSheetBehavior.BottomSheetCallback() {

                override fun onSlide(bottomSheet: View, slideOffset: Float) {
                    updateBottomButtons()
                }

                override fun onStateChanged(bottomSheet: View, newState: Int) {
                    updateBottomButtons()
                }
            },
        )

        binding.titleLayout.checkHeightThen {
            binding.categoryRecyclerView.updateLayoutParams<ConstraintLayout.LayoutParams> {
                val fullHeight = activity.window.decorView.height
                val insets = activity.window.decorView.rootWindowInsetsCompat
                matchConstraintMaxHeight =
                    fullHeight - (insets?.getInsets(systemBars())?.top ?: 0) -
                    binding.titleLayout.height - binding.buttonLayout.height - 45.dpToPx
            }
        }

        fastAdapter = FastAdapter.with(itemAdapter)
        fastAdapter.setHasStableIds(true)
        binding.categoryRecyclerView.layoutManager = LinearLayoutManager(context)
        binding.categoryRecyclerView.adapter = fastAdapter
        itemAdapter.set(
            categories.mapIndexed { index, category ->
                AddCategoryItem(category).apply {
                    skipInversed = preselected[index] != TriStateCheckBox.State.IGNORE
                    state = preselected[index]
                }
            },
        )
        setCategoriesButtons()
        fastAdapter.onClickListener = onClickListener@{ view, _, item, _ ->
            val checkBox = view as? TriStateCheckBox ?: return@onClickListener true
            checkBox.goToNextStep()
            item.state = checkBox.state
            setCategoriesButtons()
            true
        }
    }

    private fun setCategoriesButtons() {
        val addingMore = checkedItems.isNotEmpty() &&
            selectedCategories.isNotEmpty() &&
            selectedItems.map { it.category }
                .containsAll(selectedCategories) &&
            checkedItems.size > preCheckedCategories.size
        val nothingChanged = itemAdapter.adapterItems.map { it.state }
            .toTypedArray()
            .contentEquals(preselected)
        val removing = selectedItems.isNotEmpty() && (
            // Check that selected items has the previous delta items
            (
                selectedCategories.containsAll(indeterminateItems.map { it.category }) &&
                    preIndeterminateCategories.size > indeterminateItems.size
                ) ||
                // or check that checked items has the previous checked items
                (
                    preCheckedCategories.containsAll(checkedItems.map { it.category }) &&
                        preCheckedCategories.size > checkedItems.size
                    )
            ) &&
            // Additional checks in case a delta item is now fully checked
            preCheckedCategories.size >= checkedItems.size &&
            preIndeterminateCategories.size >= indeterminateItems.size

        val items = when {
            addingToLibrary -> checkedItems.map { it.category }
            addingMore -> checkedItems.map { it.category }.subtract(preCheckedCategories.toSet())
            removing -> selectedCategories.subtract(selectedItems.map { it.category }.toSet())
            nothingChanged -> selectedItems.map { it.category }
            else -> checkedItems.map { it.category }
        }
        binding.addToCategoriesButton.text = context.getString(
            when {
                addingToLibrary || (addingMore && !nothingChanged) -> MR.strings.add_to_
                removing -> MR.strings.remove_from_
                nothingChanged -> MR.strings.keep_in_
                else -> MR.strings.move_to_
            },
            when (items.size) {
                0 -> context.getString(MR.strings.default_category).lowercase(Locale.ROOT)
                1 -> items.firstOrNull()?.name ?: ""
                else -> context.getString(
                    MR.plurals.category_plural,
                    items.size,
                    items.size,
                )
            },
        )
    }

    override fun onStart() {
        super.onStart()
        sheetBehavior.expand()
        sheetBehavior.skipCollapsed = true
        updateBottomButtons()
        binding.root.post {
            binding.categoryRecyclerView.scrollToPosition(
                max(0, itemAdapter.adapterItems.indexOf(selectedItems.firstOrNull())),
            )
        }
    }

    fun updateBottomButtons() {
        val bottomSheet = binding.root.parent as? View ?: return
        val bottomSheetVisibleHeight = -bottomSheet.top + (activity.window.decorView.height - bottomSheet.height)
        binding.buttonLayout.translationY = bottomSheetVisibleHeight.toFloat()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val headerHeight = (activity as? MainActivity)?.toolbarHeight ?: 0
        binding.buttonLayout.updatePaddingRelative(
            bottom = activity.window.decorView.rootWindowInsetsCompat
                ?.getInsets(systemBars())?.bottom ?: 0,
        )

        binding.buttonLayout.updateLayoutParams<ConstraintLayout.LayoutParams> {
            height = headerHeight + binding.buttonLayout.paddingBottom
        }

        binding.cancelButton.setOnClickListener { dismiss() }
        binding.newCategoryButton.setOnClickListener {
            ManageCategoryDialog(null) {
                sheetScope.launch {
                    val fresh = withContext(Dispatchers.IO) { getCategories.await() }
                    categories = fresh.toMutableList()
                    val map = itemAdapter.adapterItems.associate { it.category.id to it.state }
                    itemAdapter.set(
                        categories.mapIndexed { index, category ->
                            AddCategoryItem(category).apply {
                                skipInversed =
                                    preselected.getOrElse(index) { TriStateCheckBox.State.UNCHECKED } != TriStateCheckBox.State.IGNORE
                                state = map[category.id] ?: TriStateCheckBox.State.CHECKED
                            }
                        },
                    )
                    setCategoriesButtons()
                }
            }.show(activity)
        }

        binding.addToCategoriesButton.setOnClickListener {
            addMangaToCategories()
            dismiss()
        }
    }

    private fun addMangaToCategories() {
        val addCategories = checkedItems.map(AddCategoryItem::category)
        val removeCategories = uncheckedItems.map(AddCategoryItem::category)
        // Set the "last added to" hint synchronously so subsequent dialog opens see it
        // immediately. The DB writes below run async and the sheet dismisses while they
        // finish — onMangaAdded() fires when the final write resolves.
        if (addCategories.isNotEmpty() || listManga.size == 1) {
            Category.lastCategoriesAddedTo =
                addCategories.mapNotNull { it.id }.toSet().ifEmpty { setOf(0) }
        }

        val singleManga = listManga.singleOrNull()
        val flipToFavorite = singleManga != null && !singleManga.favorite
        if (flipToFavorite) {
            singleManga.favorite = true
            singleManga.date_added = Date().time
        }
        // Snapshot of the data we need on IO — capture now in case the sheet view is gone
        // by the time the launched work runs.
        val mangaIds = listManga.mapNotNull { it.id }
        val mangaList = listManga.toList()

        sheetScope.launch {
            withContext(Dispatchers.IO) {
                if (flipToFavorite) {
                    updateManga.await(
                        MangaUpdate(
                            id = singleManga.id!!,
                            favorite = singleManga.favorite,
                            dateAdded = singleManga.date_added,
                        )
                    )
                }
                val mangaCategories = mangaList.map { manga ->
                    getCategories.awaitByMangaId(manga.id!!)
                        .subtract(removeCategories.toSet())
                        .plus(addCategories)
                        .distinct()
                        .map { MangaCategory.create(manga, it) }
                }.flatten()
                setMangaCategories.awaitAll(mangaIds, mangaCategories)
            }
            onMangaAdded()
        }
    }
}
