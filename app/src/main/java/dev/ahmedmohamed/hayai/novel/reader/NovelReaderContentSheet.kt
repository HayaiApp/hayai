package dev.ahmedmohamed.hayai.novel.reader

import android.app.Activity
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.core.widget.NestedScrollView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.databinding.HayaiNovelContentSheetBinding
import eu.kanade.tachiyomi.widget.E2EBottomSheetDialog

internal class NovelReaderContentSheet(private val activity: Activity, title: CharSequence) :
    E2EBottomSheetDialog<HayaiNovelContentSheetBinding>(activity) {
    override fun createBinding(inflater: LayoutInflater) = HayaiNovelContentSheetBinding.inflate(inflater)

    val form = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(20.dp, 8.dp, 20.dp, 16.dp)
    }

    init {
        binding.toolbar.title = title
        binding.toolbar.setNavigationOnClickListener { dismiss() }
        binding.content.addView(NestedScrollView(context).apply { addView(form) })
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bottom = maxOf(insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom, insets.getInsets(WindowInsetsCompat.Type.ime()).bottom)
            view.updatePadding(bottom = bottom)
            val height = if (insets.isVisible(WindowInsetsCompat.Type.ime())) {
                activity.window.decorView.height - insets.getInsets(WindowInsetsCompat.Type.systemBars()).top
            } else {
                (activity.window.decorView.height * 0.8f).toInt()
            }
            if (view.layoutParams.height != height) view.layoutParams = view.layoutParams.apply { this.height = height }
            insets
        }
    }

    override fun onStart() {
        super.onStart()
        binding.root.layoutParams = binding.root.layoutParams.apply { height = (activity.window.decorView.height * 0.8f).toInt() }
        sheetBehavior.skipCollapsed = true
        sheetBehavior.state = BottomSheetBehavior.STATE_EXPANDED
        ViewCompat.requestApplyInsets(binding.root)
    }

    fun back(action: () -> Unit) {
        binding.toolbar.setNavigationIcon(R.drawable.ic_arrow_back_24dp)
        binding.toolbar.setNavigationContentDescription(R.string.hayai_novel_sheet_back)
        binding.toolbar.setNavigationOnClickListener { action() }
    }

    fun overflow(@StringRes label: Int, action: () -> Unit) {
        binding.toolbar.menu.add(label).apply {
            setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
            setOnMenuItemClickListener { action(); true }
        }
    }

    fun text(value: CharSequence, selectable: Boolean = false) {
        form.addView(TextView(context).apply {
            text = value
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge)
            setTextIsSelectable(selectable)
            setPadding(0, 8.dp, 0, 16.dp)
        })
    }

    fun input(@StringRes label: Int, value: String, multiline: Boolean = false): TextInputEditText {
        val field = TextInputEditText(context).apply {
            setText(value)
            setSingleLine(!multiline)
            if (multiline) { minLines = 4; gravity = android.view.Gravity.TOP }
        }
        form.addView(TextInputLayout(context).apply {
            hint = context.getString(label)
            addView(field, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 12.dp })
        return field
    }

    fun action(@StringRes label: Int, primary: Boolean = false, action: (MaterialButton) -> Unit) {
        val button = MaterialButton(context, null, if (primary) com.google.android.material.R.attr.materialButtonStyle else com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            setText(label)
            setOnClickListener { action(this) }
        }
        (if (primary) binding.actions else form).addView(button, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    fun <T> items(values: List<T>, @StringRes empty: Int, label: (T) -> CharSequence, detail: (T) -> CharSequence = { "" }, selected: (T) -> Unit) {
        if (values.isEmpty()) { text(context.getString(empty)); return }
        binding.content.removeAllViews()
        val list = RecyclerView(context).apply { layoutManager = LinearLayoutManager(context); clipToPadding = false; setPadding(8.dp, 0, 8.dp, 16.dp) }
        recyclerView = list
        list.adapter = object : RecyclerView.Adapter<Row>() {
            override fun getItemCount() = values.size
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Row = Row(
                LayoutInflater.from(parent.context).inflate(android.R.layout.simple_list_item_2, parent, false).apply {
                    minimumHeight = 64.dp
                    setPadding(12.dp, 12.dp, 12.dp, 12.dp)
                    val attributes = context.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
                    setBackgroundResource(attributes.getResourceId(0, 0))
                    attributes.recycle()
                },
            )
            override fun onBindViewHolder(holder: Row, position: Int) {
                val item = values[position]
                holder.title.text = label(item)
                holder.title.maxLines = 2
                holder.title.ellipsize = android.text.TextUtils.TruncateAt.END
                holder.detail.text = detail(item)
                holder.detail.maxLines = 3
                holder.detail.ellipsize = android.text.TextUtils.TruncateAt.END
                holder.itemView.setOnClickListener { selected(item) }
            }
        }
        binding.content.addView(list)
    }

    private class Row(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(android.R.id.text1)
        val detail: TextView = view.findViewById(android.R.id.text2)
    }

    private val Int.dp: Int get() = (this * context.resources.displayMetrics.density).toInt()
}
