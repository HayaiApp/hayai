package dev.ahmedmohamed.hayai.source.presentation

import android.content.Context
import android.graphics.drawable.Drawable
import androidx.annotation.DrawableRes
import androidx.appcompat.content.res.AppCompatResources
import dev.ahmedmohamed.hayai.adult.eh.domain.EhSite
import dev.ahmedmohamed.hayai.novel.source.local.LocalNovelSource
import eu.kanade.tachiyomi.R

object BundledSourceIconResolver {
    @DrawableRes
    fun resource(sourceId: Long): Int? = when (sourceId) {
        EhSite.EHentai.sourceId -> R.mipmap.ic_ehentai_source
        EhSite.ExHentai.sourceId -> R.mipmap.ic_exhentai_source
        LocalNovelSource.ID -> R.drawable.ic_local_novel_source
        else -> null
    }

    fun drawable(context: Context, sourceId: Long): Drawable? =
        resource(sourceId)?.let { AppCompatResources.getDrawable(context, it) }
}
