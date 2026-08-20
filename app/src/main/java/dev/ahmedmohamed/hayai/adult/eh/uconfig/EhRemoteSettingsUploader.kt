package dev.ahmedmohamed.hayai.adult.eh.uconfig

import dev.ahmedmohamed.hayai.adult.eh.domain.EhSite
import dev.ahmedmohamed.hayai.adult.eh.session.EhSessionState
import dev.ahmedmohamed.hayai.adult.eh.session.EhSessionStore
import dev.ahmedmohamed.hayai.adult.eh.settings.EhPreferences
import dev.ahmedmohamed.hayai.adult.eh.settings.EhRemoteSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class EhRemoteSettingsUploader(
    private val remote: EhRemoteSettingsRemote,
    private val sessions: EhSessionStore,
    private val preferences: EhPreferences,
) {
    private val uploadMutex = Mutex()

    suspend fun upload(
        desired: EhRemoteSettings,
        targets: Set<EhSite> = EhSite.entries.toSet(),
        onProgress: (EhUploadProgress) -> Unit = {},
    ): EhUploadReport = uploadMutex.withLock {
        if (targets.isEmpty()) return@withLock EhUploadReport(emptyMap())
        if (sessions.state.value !is EhSessionState.Verified) {
            val results = targets.associateWith { site ->
                EhSiteUploadResult.Failed(site, EhRemoteSettingsFailure.AuthenticationRequired())
            }
            return@withLock EhUploadReport(results)
        }

        val perks = try {
            remote.fetchHathPerks(sessions.cookieHeader(EhSite.EHentai))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            val typed = failure.asRemoteFailure()
            return@withLock EhUploadReport(targets.associateWith { EhSiteUploadResult.Failed(it, typed) })
        }

        val results = linkedMapOf<EhSite, EhSiteUploadResult>()
        targets.sortedBy { it.ordinal }.forEach { site ->
            onProgress(EhUploadProgress.Started(site))
            val result = try {
                uploadSite(site, desired, perks)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                EhSiteUploadResult.Failed(site, failure.asRemoteFailure())
            }
            results[site] = result
            onProgress(EhUploadProgress.Finished(result))
        }
        EhUploadReport(results)
    }

    private suspend fun uploadSite(
        site: EhSite,
        desired: EhRemoteSettings,
        perks: EhHathPerks,
    ): EhSiteUploadResult.Applied {
        val initialCookie = sessions.cookieHeader(site)
        val profiles = remote.profiles(site, initialCookie)
        val owned = profiles.filter { it.name in OWNED_PROFILE_NAMES }.minByOrNull { it.slot.value }
        val slot = owned?.slot ?: firstFreeSlot(profiles, site)
        val createdCookies =
            if (owned == null) remote.createProfile(site, slot, PROFILE_NAME, initialCookie) else EhRemoteCookies()
        val configuredCookie = createdCookies.overlay(initialCookie)
        val appliedCookies = remote.applyProfile(site, slot, desired, perks, configuredCookie)
        val mergedCookies = createdCookies.merge(appliedCookies)
        val verificationCookie = mergedCookies.overlay(configuredCookie)
        val verified = remote.profiles(site, verificationCookie).any { it.slot == slot && it.name in OWNED_PROFILE_NAMES }
        if (!verified) throw EhRemoteSettingsFailure.MalformedResponse("${site.displayName} did not retain the configured profile.")

        sessions.commitRemoteProfile(site, slot, mergedCookies)
        preferences.markRemoteSettingsApplied(site, desired.fingerprint())
        return EhSiteUploadResult.Applied(site, slot)
    }

    private fun firstFreeSlot(profiles: List<EhRemoteProfile>, site: EhSite): EhProfileSlot {
        val occupied = profiles.mapTo(hashSetOf()) { it.slot.value }
        return (1..3).firstOrNull { it !in occupied }?.let(::EhProfileSlot)
            ?: throw EhRemoteSettingsFailure.OutOfProfileSlots(site)
    }

    private fun Throwable.asRemoteFailure(): EhRemoteSettingsFailure =
        this as? EhRemoteSettingsFailure
            ?: EhRemoteSettingsFailure.Network(message ?: "The E-Hentai settings upload failed.", this)

    companion object {
        const val PROFILE_NAME = "Hayai App"
        val OWNED_PROFILE_NAMES = setOf(PROFILE_NAME, "TachiyomiEH App")
    }
}
