package eu.kanade.tachiyomi.ui.more.stats

import androidx.compose.runtime.Composable
import cafe.adriel.voyager.navigator.CurrentScreen
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.transitions.CrossfadeTransition
import eu.kanade.tachiyomi.ui.base.controller.BaseComposeController
import yokai.presentation.stats.novel.NovelStatsScreen
import yokai.presentation.theme.LocalReducedMotion

/** Conductor controller hosting the Voyager [NovelStatsScreen]. Pushed from [StatsController]. */
class NovelStatsController : BaseComposeController() {

    @Composable
    override fun ScreenContent() {
        Navigator(
            screen = NovelStatsScreen(),
            content = { navigator ->
                this.navigator = navigator
                if (LocalReducedMotion.current) CurrentScreen()
                else CrossfadeTransition(navigator = navigator)
            },
        )
    }
}
