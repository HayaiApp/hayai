package eu.kanade.tachiyomi.ui.setting

import android.app.Activity
import androidx.preference.PreferenceGroup
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.preference.asImmediateFlowIn
import eu.kanade.tachiyomi.data.track.EnhancedTrackService
import eu.kanade.tachiyomi.data.track.TrackManager
import eu.kanade.tachiyomi.data.track.TrackPreferences
import eu.kanade.tachiyomi.data.track.TrackService
import eu.kanade.tachiyomi.data.track.anilist.AnilistApi
import eu.kanade.tachiyomi.data.track.bangumi.BangumiApi
import eu.kanade.tachiyomi.data.track.hikka.HikkaApi
import eu.kanade.tachiyomi.data.track.mangabaka.MangaBakaApi
import eu.kanade.tachiyomi.data.track.myanimelist.MyAnimeListApi
import eu.kanade.tachiyomi.data.track.shikimori.ShikimoriApi
import eu.kanade.tachiyomi.source.SourceManager
import eu.kanade.tachiyomi.util.system.launchIO
import eu.kanade.tachiyomi.util.system.materialAlertDialog
import eu.kanade.tachiyomi.util.system.openInBrowser
import eu.kanade.tachiyomi.util.view.snack
import eu.kanade.tachiyomi.widget.preference.TrackLoginDialog
import eu.kanade.tachiyomi.widget.preference.TrackLogoutDialog
import eu.kanade.tachiyomi.widget.preference.TrackerPreference
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import eu.kanade.tachiyomi.data.preference.PreferenceKeys as Keys

class SettingsTrackingController :
    SettingsController(),
    TrackLoginDialog.Listener,
    TrackLogoutDialog.Listener {
    private val trackManager: TrackManager by injectLazy()
    val trackPreferences: TrackPreferences by injectLazy()

    override fun setupPreferenceScreen(screen: PreferenceScreen) =
        screen.apply {
            titleRes = R.string.tracking

            switchPreference {
                key = Keys.autoUpdateTrack
                titleRes = R.string.update_tracking_after_reading
                defaultValue = true
            }
            switchPreference {
                key = Keys.trackMarkedAsRead
                titleRes = R.string.update_tracking_marked_read
                defaultValue = false
            }
            preferenceCategory {
                titleRes = R.string.services

                trackPreference(trackManager.myAnimeList) {
                    activity?.openInBrowser(MyAnimeListApi.authUrl(), trackManager.myAnimeList.getLogoColor(), true)
                }
                trackPreference(trackManager.aniList) {
                    activity?.openInBrowser(AnilistApi.authUrl(), trackManager.aniList.getLogoColor(), true)
                }
                preference {
                    key = "update_anilist_scoring"
                    isPersistent = false
                    isIconSpaceReserved = true
                    title = context.getString(R.string.update_tracking_scoring_type, context.getString(R.string.anilist))

                    preferences
                        .getStringPref(trackManager.aniList.getUsername())
                        .asImmediateFlowIn(viewScope) {
                            isVisible = it.isNotEmpty()
                        }

                    onClick {
                        viewScope.launchIO {
                            val (result, error) = trackManager.aniList.updatingScoring()
                            if (result) {
                                view?.snack(R.string.scoring_type_updated)
                            } else {
                                view?.snack(
                                    context.getString(
                                        R.string.could_not_update_scoring_,
                                        error?.localizedMessage.orEmpty(),
                                    ),
                                )
                            }
                        }
                    }
                }
                trackPreference(trackManager.kitsu) {
                    val dialog = TrackLoginDialog(trackManager.kitsu, R.string.email)
                    dialog.targetController = this@SettingsTrackingController
                    dialog.showDialog(router)
                }
                trackPreference(trackManager.mangaUpdates) {
                    val dialog = TrackLoginDialog(trackManager.mangaUpdates, R.string.username)
                    dialog.targetController = this@SettingsTrackingController
                    dialog.showDialog(router)
                }
                trackPreference(trackManager.shikimori) {
                    activity?.openInBrowser(ShikimoriApi.authUrl(), trackManager.shikimori.getLogoColor(), true)
                }
                trackPreference(trackManager.bangumi) {
                    activity?.openInBrowser(BangumiApi.authUrl(), trackManager.bangumi.getLogoColor(), true)
                }
                trackPreference(trackManager.hikka) {
                    activity?.openInBrowser(HikkaApi.authUrl(), trackManager.hikka.getLogoColor(), true)
                }
                trackPreference(trackManager.mangaBaka) {
                    activity?.openInBrowser(MangaBakaApi.authUrl(), trackManager.mangaBaka.getLogoColor(), true)
                }
                infoPreference(R.string.tracking_info)
            }
            preferenceCategory {
                titleRes = R.string.hayai_novel_tracker_services
                trackPreference(trackManager.novelUpdates) {
                    showCredentialLogin(trackManager.novelUpdates, "https://www.novelupdates.com/login/")
                }
                trackPreference(trackManager.novelList) {
                    showCredentialLogin(trackManager.novelList, "https://www.novellist.co/login")
                }
                trackPreference(trackManager.ranobeDb) {
                    showCredentialLogin(trackManager.ranobeDb, "https://ranobedb.org/login")
                }
                infoPreference(R.string.hayai_novel_tracker_login_help)
            }
            preferenceCategory {
                titleRes = R.string.enhanced_services
                val sourceManager = Injekt.get<SourceManager>()
                val enhancedTrackers =
                    trackManager.services
                        .filter { service ->
                            if (service !is EnhancedTrackService) return@filter false
                            sourceManager.getCatalogueSources().any { service.accept(it) }
                        }
                enhancedTrackers.forEach { trackPreference(it) }
                infoPreference(R.string.enhanced_tracking_info)
            }
        }

    private inline fun PreferenceGroup.trackPreference(
        service: TrackService,
        crossinline login: () -> Unit = { },
    ): TrackerPreference =
        add(
            TrackerPreference(context).apply {
                key = trackPreferences.trackUsername(service).key()
                title = context.getString(service.nameRes())
                iconRes = service.getLogo()
                iconColor = service.getLogoColor()
                onClick {
                    if (service.isLogged) {
                        if (service is EnhancedTrackService) {
                            service.logout()
                            updatePreference(service)
                        } else {
                            val dialog = TrackLogoutDialog(service)
                            dialog.targetController = this@SettingsTrackingController
                            dialog.showDialog(router)
                        }
                    } else {
                        if (service is EnhancedTrackService) {
                            service.loginNoop()
                            updatePreference(service)
                        } else {
                            login()
                        }
                    }
                }
            },
        )

    override fun onActivityResumed(activity: Activity) {
        super.onActivityResumed(activity)
        updatePreference(trackManager.myAnimeList)
        updatePreference(trackManager.aniList)
        updatePreference(trackManager.shikimori)
        updatePreference(trackManager.bangumi)
        updatePreference(trackManager.hikka)
        updatePreference(trackManager.mangaBaka)
    }

    private fun updatePreference(service: TrackService) {
        val pref = findPreference(trackPreferences.trackUsername(service).key()) as? TrackerPreference
        pref?.notifyChanged()
    }

    private fun showCredentialLogin(service: TrackService, loginUrl: String) {
        val host = activity ?: return
        host.materialAlertDialog()
            .setTitle(service.nameRes())
            .setItems(arrayOf("Open sign-in page", "Paste authenticated session")) { _, action ->
                if (action == 0) {
                    host.openInBrowser(loginUrl, service.getTrackerColor(), true)
                } else {
                    val dialog = TrackLoginDialog(service, R.string.username)
                    dialog.targetController = this@SettingsTrackingController
                    dialog.showDialog(router)
                }
            }.show()
    }

    override fun trackLoginDialogClosed(service: TrackService) {
        updatePreference(service)
    }

    override fun trackLogoutDialogClosed(service: TrackService) {
        updatePreference(service)
    }
}
