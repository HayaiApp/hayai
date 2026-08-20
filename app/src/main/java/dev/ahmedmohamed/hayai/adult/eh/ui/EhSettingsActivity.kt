package dev.ahmedmohamed.hayai.adult.eh.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.setPadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.viewbinding.ViewBinding
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import dev.ahmedmohamed.hayai.adult.eh.domain.EhSite
import dev.ahmedmohamed.hayai.adult.eh.session.EhSessionMutationResult
import dev.ahmedmohamed.hayai.adult.eh.session.EhSessionState
import dev.ahmedmohamed.hayai.adult.eh.session.EhSessionStore
import dev.ahmedmohamed.hayai.adult.eh.session.EhSessionVerifier
import dev.ahmedmohamed.hayai.adult.eh.session.EhVerificationResult
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.ui.base.activity.BaseActivity
import eu.kanade.tachiyomi.util.system.dpToPx
import eu.kanade.tachiyomi.util.system.materialAlertDialog
import kotlinx.coroutines.launch
import uy.kohesive.injekt.injectLazy

class EhSettingsActivity : BaseActivity<ViewBinding>() {
    private val sessionStore by injectLazy<EhSessionStore>()
    private val network by injectLazy<NetworkHelper>()
    private val verifier by lazy { EhSessionVerifier(network.client) }

    private lateinit var status: TextView
    private lateinit var recheck: MaterialButton
    private lateinit var ehProfile: EditText
    private lateinit var exhProfile: EditText

    private val loginLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val outcome = result.data?.getStringExtra(EhLoginActivity.EXTRA_OUTCOME)
            if (outcome != null && outcome != EhLoginOutcome.Success.name) {
                status.text =
                    when (outcome) {
                        EhLoginOutcome.Cloudflare.name -> "Cloudflare interrupted login. Reopen login and complete the challenge."
                        EhLoginOutcome.InvalidCredentials.name -> "The credentials were invalid or do not have ExHentai access."
                        else -> "Login was cancelled."
                    }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = buildContent()
        binding = ViewBinding { root }
        setContentView(root)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                sessionStore.state.collect(::renderState)
            }
        }
    }

    private fun buildContent(): LinearLayout {
        val root =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                fitsSystemWindows = true
            }
        root.addView(
            MaterialToolbar(this).apply {
                title = "E-Hentai and ExHentai"
                setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
                setNavigationOnClickListener { finish() }
            },
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        val content =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(20.dpToPx)
            }
        status = TextView(this).apply { textSize = 16f }
        content.addView(status, matchWidth())
        content.addView(
            TextView(this).apply {
                text = "Hayai stores the SY-compatible private cookie keys. Credentials are not considered active until ExHentai verifies them."
                setPadding(0, 8.dpToPx, 0, 16.dpToPx)
            },
            matchWidth(),
        )
        content.addView(button("Login or replace credentials") { loginLauncher.launch(EhLoginActivity.newIntent(this)) })
        recheck = button("Recheck current credentials", ::recheckSession)
        content.addView(recheck)
        content.addView(button("Log out") { confirmLogout() })

        content.addView(sectionLabel("Server settings profiles"))
        ehProfile = profileInput("E-Hentai profile", sessionStore.settingsProfile(EhSite.EHentai))
        exhProfile = profileInput("ExHentai profile", sessionStore.settingsProfile(EhSite.ExHentai))
        content.addView(ehProfile, matchWidth())
        content.addView(exhProfile, matchWidth())
        content.addView(button("Save profiles") { saveProfiles() })

        root.addView(
            ScrollView(this).apply { addView(content) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
        )
        return root
    }

    private fun renderState(state: EhSessionState) {
        status.text =
            when (state) {
                EhSessionState.LoggedOut -> "Logged out. E-Hentai remains public, and ExHentai is unavailable."
                is EhSessionState.CredentialsAvailable -> "Credentials saved but not verified. Tap Recheck current credentials."
                is EhSessionState.Verified -> "Verified ExHentai session."
                is EhSessionState.InvalidCredentials -> "Invalid credentials. ${state.reason}"
            }
        recheck.isEnabled = state !is EhSessionState.LoggedOut
    }

    private fun recheckSession() {
        recheck.isEnabled = false
        status.text = "Verifying credentials with ExHentai…"
        lifecycleScope.launch {
            when (val result = verifier.verify(sessionStore)) {
                EhVerificationResult.Verified -> {
                    if (sessionStore.markVerified() is EhSessionMutationResult.Failure) {
                        status.text = "No complete credentials are available."
                    }
                }
                is EhVerificationResult.Cloudflare -> status.text = result.message
                is EhVerificationResult.InvalidCredentials -> sessionStore.markInvalid(result.message)
                is EhVerificationResult.NetworkFailure -> status.text = result.message
            }
            recheck.isEnabled = sessionStore.state.value !is EhSessionState.LoggedOut
        }
    }

    private fun confirmLogout() {
        materialAlertDialog()
            .setTitle("Log out of E-Hentai?")
            .setMessage("Hayai will remove only E-Hentai and ExHentai credentials and cookies. Other website sessions are untouched.")
            .setPositiveButton("Log out") { _, _ -> sessionStore.logout() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun saveProfiles() {
        val eh = parseProfile(ehProfile) ?: return
        val exh = parseProfile(exhProfile) ?: return
        sessionStore.setSettingsProfile(EhSite.EHentai, eh)
        sessionStore.setSettingsProfile(EhSite.ExHentai, exh)
        status.text = "Server settings profiles saved."
    }

    private fun parseProfile(input: EditText): Int? {
        val text = input.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return null
        val profile = text.toIntOrNull()
        if (profile == null || profile !in 0..3) {
            input.error = "Use 0 to 3, or leave blank for automatic."
            throw InvalidProfileException()
        }
        return profile
    }

    private fun profileInput(
        label: String,
        value: Int?,
    ) = EditText(this).apply {
        hint = "$label, blank for automatic"
        inputType = InputType.TYPE_CLASS_NUMBER
        setText(value?.toString().orEmpty())
    }

    private fun sectionLabel(text: String) =
        TextView(this).apply {
            this.text = text
            textSize = 18f
            setPadding(0, 24.dpToPx, 0, 8.dpToPx)
        }

    private fun button(
        text: String,
        action: () -> Unit,
    ) = MaterialButton(this).apply {
        this.text = text
        setOnClickListener {
            try {
                action()
            } catch (_: InvalidProfileException) {
            }
        }
    }

    private fun matchWidth() =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    companion object {
        fun newIntent(context: Context): Intent = Intent(context, EhSettingsActivity::class.java)
    }
}

private class InvalidProfileException : IllegalArgumentException()
