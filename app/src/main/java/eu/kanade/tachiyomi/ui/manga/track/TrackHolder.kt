package eu.kanade.tachiyomi.ui.manga.track

import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.view.View
import androidx.core.view.isVisible
import androidx.core.widget.TextViewCompat
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.database.models.Track
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.databinding.TrackItemBinding
import eu.kanade.tachiyomi.ui.base.holder.BaseViewHolder
import eu.kanade.tachiyomi.util.system.dpToPx
import eu.kanade.tachiyomi.util.system.getResourceColor
import eu.kanade.tachiyomi.util.view.setText
import java.text.DateFormat
import yokai.util.koin.injectLazy
import yokai.i18n.MR
import yokai.util.lang.getString
import android.R as AR

class TrackHolder(view: View, adapter: TrackAdapter) : BaseViewHolder(view) {

    private val preferences: PreferencesHelper by injectLazy()
    private val binding = TrackItemBinding.bind(view)
    private val dateFormat: DateFormat by lazy {
        preferences.dateFormat()
    }

    init {
        val listener = adapter.rowClickListener
        binding.logoContainer.setOnClickListener { listener.onLogoClick(bindingAdapterPosition) }
        binding.addTracking.setOnClickListener { listener.onTitleClick(bindingAdapterPosition) }
        binding.trackTitle.setOnClickListener { listener.onTitleClick(bindingAdapterPosition) }
        binding.trackTitle.setOnLongClickListener {
            listener.onTitleLongClick(bindingAdapterPosition)
            true
        }
        binding.trackRemove.setOnClickListener { listener.onRemoveClick(bindingAdapterPosition) }
        binding.trackStatus.setOnClickListener { listener.onStatusClick(bindingAdapterPosition) }
        binding.trackChapters.setOnClickListener { listener.onChaptersClick(bindingAdapterPosition) }
        binding.scoreContainer.setOnClickListener { listener.onScoreClick(bindingAdapterPosition) }
        binding.trackStartDate.setOnClickListener { listener.onStartDateClick(it, bindingAdapterPosition) }
        binding.trackFinishDate.setOnClickListener { listener.onFinishDateClick(it, bindingAdapterPosition) }
    }

    @SuppressLint("SetTextI18n")
    fun bind(item: TrackItem) {
        val track = item.track
        binding.trackLogo.setImageResource(item.service.getLogo())
        binding.logoContainer.background = GradientDrawable().apply {
            cornerRadius = 12.dpToPx.toFloat()
            setColor(item.service.getLogoColor())
        }
        val serviceName = binding.trackLogo.context.getString(item.service.nameRes())
        binding.trackLogo.contentDescription = serviceName
        binding.trackServiceName.text = serviceName
        binding.addTracking.text = binding.root.context.getString(MR.strings.find_tracker_entry_on, serviceName)
        binding.trackGroup.isVisible = track != null
        binding.addTracking.isVisible = track == null
        if (track != null) {
            binding.trackTitle.text = track.title
            with(binding.trackChapters) {
                text = when {
                    track.total_chapters > 0L && track.last_chapter_read.toLong() == track.total_chapters -> context.getString(
                        MR.strings.all_chapters_read,
                    )
                    track.total_chapters > 0L -> context.getString(
                        MR.strings.chapter_x_of_y,
                        track.last_chapter_read.toInt(),
                        track.total_chapters,
                    )
                    track.last_chapter_read > 0 -> context.getString(
                        MR.strings.chapter_,
                        track.last_chapter_read.toInt().toString(),
                    )
                    else -> context.getString(MR.strings.not_started)
                }
                setTextColor(enabledTextColor(true))
            }
            val status = item.service.getStatus(track.status)
            with(binding.trackStatus) {
                if (status.isEmpty()) {
                    setText(MR.strings.unknown_status)
                } else {
                    text = item.service.getStatus(track.status)
                }
                setTextColor(enabledTextColor(status.isNotEmpty()))
            }
            val supportsScoring = item.service.getScoreList().isNotEmpty()
            if (supportsScoring) {
                with(binding.trackScore) {
                    text =
                        if (track.score == 0f) {
                            binding.trackScore.context.getString(MR.strings.score)
                        } else {
                            item.service.displayScore(track)
                        }
                    setCompoundDrawablesWithIntrinsicBounds(
                        0,
                        0,
                        starIcon(track),
                        0,
                    )
                    setTextColor(enabledTextColor(track.score != 0f))
                    TextViewCompat.setCompoundDrawableTintList(this, ColorStateList.valueOf(enabledTextColor(track.score != 0f)))
                }
            }
            binding.scoreContainer.isVisible = supportsScoring
            binding.vertDivider2.isVisible = supportsScoring

            binding.dateGroup.isVisible = item.service.supportsReadingDates
            if (item.service.supportsReadingDates) {
                with(binding.trackStartDate) {
                    text =
                        if (track.started_reading_date != 0L) {
                            dateFormat.format(track.started_reading_date)
                        } else {
                            context.getString(MR.strings.started_reading_date)
                        }
                    setTextColor(enabledTextColor(track.started_reading_date != 0L))
                }
                with(binding.trackFinishDate) {
                    text =
                        if (track.finished_reading_date != 0L) {
                            dateFormat.format(track.finished_reading_date)
                        } else {
                            context.getString(MR.strings.finished_reading_date)
                        }
                    setTextColor(enabledTextColor(track.finished_reading_date != 0L))
                }
            }
        }
    }

    fun enabledTextColor(enabled: Boolean): Int {
        return binding.root.context.getResourceColor(
            if (enabled) {
                AR.attr.textColorPrimary
            } else {
                AR.attr.textColorHint
            },
        )
    }

    private fun starIcon(track: Track): Int {
        return if (track.score == 0f || binding.trackScore.text.toString().toFloatOrNull() != null) {
            R.drawable.ic_star_12dp
        } else {
            0
        }
    }

    fun setProgress(enabled: Boolean) {
        binding.progress.isVisible = enabled
        binding.trackLogo.isVisible = !enabled
    }
}
