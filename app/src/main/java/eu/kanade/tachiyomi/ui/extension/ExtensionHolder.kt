package eu.kanade.tachiyomi.ui.extension

import android.content.res.ColorStateList
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import coil.dispose
import coil.load
import dev.ahmedmohamed.hayai.extension.managed.ManagedExtensionBackend
import dev.ahmedmohamed.hayai.extension.managed.ManagedHealth
import dev.ahmedmohamed.hayai.extension.managed.ManagedInstallation
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.image.coil.CoverViewTarget
import eu.kanade.tachiyomi.databinding.ExtensionCardItemBinding
import eu.kanade.tachiyomi.extension.model.InstallStep
import eu.kanade.tachiyomi.ui.base.holder.BaseFlexibleViewHolder
import eu.kanade.tachiyomi.util.system.LocaleHelper
import eu.kanade.tachiyomi.util.system.getResourceColor
import eu.kanade.tachiyomi.util.view.applyStyle
import eu.kanade.tachiyomi.util.view.applyStyleFromAttr
import eu.kanade.tachiyomi.util.view.makeContainerShape
import java.util.Locale

class ExtensionHolder(
    view: View,
    val adapter: ExtensionAdapter,
) : BaseFlexibleViewHolder(view, adapter) {
    private val binding = ExtensionCardItemBinding.bind(view)

    init {
        binding.extButton.setOnClickListener { adapter.buttonClickListener.onButtonClick(flexibleAdapterPosition) }
        binding.cancelButton.setOnClickListener { adapter.buttonClickListener.onCancelClick(flexibleAdapterPosition) }
        binding.webviewButton.setOnClickListener { adapter.buttonClickListener.onWebViewClick(flexibleAdapterPosition) }
    }

    fun bind(item: ExtensionItem) {
        val extension = item.extension
        binding.date.isVisible = false
        binding.extDivider.isVisible = extension.hasUpdate
        binding.extTitle.text = extension.name
        binding.version.text = buildList {
            add(extension.installedVersion ?: extension.availableVersion.orEmpty())
            add(
                itemView.context.getString(
                    if (extension.backend == ManagedExtensionBackend.JavaScript) {
                        R.string.hayai_extension_javascript
                    } else {
                        R.string.hayai_extension_apk
                    },
                ),
            )
        }.filter(String::isNotBlank).joinToString(" • ")
        binding.lang.isVisible = extension.language != null && extension.installation != ManagedInstallation.Untrusted
        binding.lang.text = extension.language?.let(LocaleHelper::getDisplayName).orEmpty()
        binding.warning.text =
            when {
                extension.health is ManagedHealth.LoadFailed -> itemView.context.getString(R.string.hayai_extension_load_failed)
                extension.installation == ManagedInstallation.Untrusted -> itemView.context.getString(R.string.untrusted)
                extension.isNsfw -> itemView.context.getString(R.string.nsfw_short)
                extension.backend == ManagedExtensionBackend.JavaScript -> itemView.context.getString(R.string.hayai_extension_javascript)
                else -> ""
            }.uppercase(Locale.ROOT)
        binding.installProgress.progress = item.sessionProgress ?: 0
        binding.installProgress.isVisible = item.sessionProgress != null
        binding.cancelButton.isVisible = item.sessionProgress != null
        binding.webviewButton.isVisible = extension.websiteUrl != null && item.sessionProgress == null

        binding.sourceImage.dispose()
        binding.sourceImage.imageTintList = null
        when {
            extension.installation == ManagedInstallation.Untrusted || extension.health is ManagedHealth.LoadFailed -> {
                binding.sourceImage.imageTintList = ColorStateList.valueOf(itemView.context.getResourceColor(R.attr.colorError))
                binding.sourceImage.setImageResource(R.drawable.ic_app_untrusted_24dp)
            }
            extension.installedIcon != null -> binding.sourceImage.load(extension.installedIcon)
            extension.iconUrl != null -> binding.sourceImage.load(extension.iconUrl) { target(CoverViewTarget(binding.sourceImage)) }
            extension.backend == ManagedExtensionBackend.JavaScript -> binding.sourceImage.setImageResource(R.drawable.ic_code_24dp)
            else -> binding.sourceImage.setImageResource(R.mipmap.ic_launcher)
        }
        bindButton(item)
    }

    @Suppress("ResourceType")
    fun bindButton(item: ExtensionItem) = with(binding.extButton) {
        if (item.installStep == InstallStep.Done) return@with
        val installStep = item.installStep
        val extension = item.extension
        isEnabled = true
        isClickable = true
        binding.installProgress.progress = item.sessionProgress ?: 0
        binding.cancelButton.isVisible = item.sessionProgress != null
        binding.installProgress.isVisible = item.sessionProgress != null
        binding.webviewButton.isVisible = extension.websiteUrl != null && item.sessionProgress == null
        if (installStep != null) {
            applyStyle(R.style.Widget_Tachiyomi_Button_TextButton)
            setText(
                when (installStep) {
                    InstallStep.Pending -> R.string.pending
                    InstallStep.Downloading -> R.string.downloading
                    InstallStep.Loading -> R.string.loading
                    InstallStep.Installing -> R.string.installing
                    InstallStep.Installed -> R.string.installed
                    InstallStep.Error -> R.string.retry
                    InstallStep.Done -> return@with
                },
            )
            if (installStep != InstallStep.Error) {
                isEnabled = false
                isClickable = false
            }
        } else {
            when {
                extension.installation == ManagedInstallation.Untrusted -> {
                    applyStyleFromAttr(R.attr.materialButtonOutlinedStyle)
                    setText(R.string.trust)
                }
                extension.health is ManagedHealth.LoadFailed && extension.availableVersion != null -> {
                    applyStyleFromAttr(R.attr.materialButtonOutlinedStyle)
                    setText(R.string.retry)
                }
                extension.hasUpdate -> {
                    applyStyleFromAttr(R.attr.materialButtonStyle)
                    setText(R.string.update)
                }
                extension.installation == ManagedInstallation.Installed -> {
                    applyStyle(R.style.Widget_Tachiyomi_Button_TextButton)
                    setText(R.string.settings)
                }
                else -> {
                    applyStyleFromAttr(R.attr.materialButtonOutlinedStyle)
                    setText(if (extension.backend == ManagedExtensionBackend.Apk && adapter.installPrivately) R.string.add else R.string.install)
                }
            }
        }
        updateLayoutParams { width = ViewGroup.LayoutParams.WRAP_CONTENT }
    }

    fun setCorners(top: Boolean, bottom: Boolean) {
        binding.extCard.shapeAppearanceModel = binding.extCard.makeContainerShape(top, bottom)
    }
}
