package dev.ahmedmohamed.hayai.source.presentation

import android.widget.ImageView
import coil.dispose
import coil.load
import dev.ahmedmohamed.hayai.novel.plugin.source.NovelPluginSource
import dev.ahmedmohamed.hayai.novel.source.local.LocalNovelSource
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.image.coil.CoverViewTarget
import eu.kanade.tachiyomi.source.LocalSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.icon

/** Binds the same source artwork on Browse, migration, and source selection surfaces. */
fun ImageView.bindSourceArtwork(source: Source) {
    dispose()
    setImageDrawable(null)
    when {
        source is NovelPluginSource && source.iconUrl.isNotBlank() -> {
            load(source.iconUrl) {
                target(CoverViewTarget(this@bindSourceArtwork))
            }
        }
        source.id == LocalSource.ID -> setImageResource(R.mipmap.ic_local_source)
        source.id == LocalNovelSource.ID -> setImageResource(R.drawable.ic_local_novel_source)
        else -> source.icon()?.let(::setImageDrawable)
    }
}
