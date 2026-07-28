package com.playtranslate.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.playtranslate.Prefs
import com.playtranslate.R
import com.playtranslate.bunpro.BunproClient
import com.playtranslate.bunpro.BunproLookup
import com.playtranslate.translation.KeyStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Settings sub-screen for the Bunpro SRS integration: session token + an
 * on/off switch.
 *
 * UX contract (mirrors [LlmBackendSettingsActivity], the only other screen
 * with a real validation ping):
 *  - The toolbar X discards in-progress edits — nothing is written.
 *  - Save validates the typed token against Bunpro BEFORE persisting. On
 *    `Invalid` an [OverlayAlert] explains and nothing is written; on
 *    `Ok`/`Unreachable` everything commits and the screen finishes.
 *    `Unreachable` persists because it means we couldn't *prove* the token
 *    wrong (offline, 5xx) — the next lookup surfaces any real problem.
 *  - A blank token clears the saved token and disables the feature.
 *
 * Differs from the LLM screen in one way: the enable switch is independent of
 * token presence, so the user can silence Bunpro without discarding a working
 * token (the LLM/DeepL screens derive `enabled` from key presence instead).
 *
 * A successful save also clears [Prefs.bunproTokenRejected] — the flag exists
 * because Bunpro session tokens expire with no refresh path, and a freshly
 * validated token is by definition not stale. See
 * `docs/features/bunpro-integration.md`.
 */
class BunproSettingsActivity : SettingsSubPageActivity() {

    override val layoutResId = R.layout.activity_bunpro_settings

    private lateinit var prefs: Prefs
    private lateinit var etToken: EditText
    private lateinit var btnSave: MaterialButton
    private lateinit var progressSave: ProgressBar
    private lateinit var switchEnabled: MaterialSwitch

    override fun onContentCreated(savedInstanceState: Bundle?) {
        prefs = Prefs(this)

        etToken = findViewById(R.id.etBunproToken)
        etToken.setText(prefs.bunproToken)
        etToken.setSelection(etToken.text.length)

        wireEnabledRow(findViewById(R.id.rowBunproEnabled))

        btnSave = findViewById(R.id.btnSave)
        progressSave = findViewById(R.id.progressSave)
        btnSave.setOnClickListener { onSave() }
    }

    /** The enable switch. Reflects the pref on entry but does NOT write on
     *  toggle — like every other field here it commits on Save, so the
     *  toolbar X discards it. */
    private fun wireEnabledRow(row: View) {
        row.findViewById<TextView>(R.id.tvRowTitle).text =
            getString(R.string.bunpro_enabled_row_title)
        row.findViewById<TextView>(R.id.tvRowSubtitle).apply {
            text = getString(R.string.bunpro_enabled_row_subtitle)
            isVisible = true
        }
        switchEnabled = row.findViewById(R.id.switchRowToggle)
        switchEnabled.isChecked = prefs.bunproEnabled
        row.setOnClickListener { switchEnabled.isChecked = !switchEnabled.isChecked }
    }

    /** Save-button loading state: text blanked + click suppressed with the
     *  spinner overlaid, and the token field disabled so an edit can't race
     *  the in-flight validation. */
    private fun setLoading(loading: Boolean) {
        if (loading) {
            btnSave.text = ""
            btnSave.isEnabled = false
            progressSave.isVisible = true
            etToken.isEnabled = false
        } else {
            btnSave.text = getString(R.string.deepl_settings_save)
            btnSave.isEnabled = true
            progressSave.isGone = true
            etToken.isEnabled = true
        }
    }

    private fun onSave() {
        val token = etToken.text.toString().trim()

        // Blank token: clear the credential and disable. Nothing to validate.
        if (token.isBlank()) {
            prefs.bunproToken = ""
            prefs.bunproEnabled = false
            prefs.bunproTokenRejected = false
            // Cached answers belong to the credential that fetched them.
            BunproLookup.clear()
            finish()
            return
        }

        setLoading(true)
        // lifecycleScope so the toolbar X (finish()) cancels the in-flight
        // validation cleanly — nothing persists on cancel.
        lifecycleScope.launch {
            val status = try {
                BunproClient.validateToken(token)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Defensive: validateToken shouldn't throw (Unreachable covers
                // network errors), but don't block the user if it does.
                KeyStatus.Unreachable
            }
            when (status) {
                is KeyStatus.Invalid -> {
                    setLoading(false)
                    showInvalidTokenAlert()
                }
                else -> {
                    prefs.bunproToken = token
                    prefs.bunproEnabled = switchEnabled.isChecked
                    BunproLookup.clear()
                    // A freshly accepted token isn't stale by definition. On
                    // Unreachable we clear too: the old rejection referred to
                    // the token being replaced, so carrying it over would
                    // pin a stale-token warning onto a brand-new credential.
                    prefs.bunproTokenRejected = false
                    finish()
                }
            }
        }
    }

    private fun showInvalidTokenAlert() {
        OverlayAlert.Builder(this)
            .setTitle(getString(R.string.bunpro_invalid_token_alert_title))
            .setMessage(getString(R.string.bunpro_invalid_token_alert_message))
            .addCancelButton(getString(R.string.bunpro_invalid_token_alert_button))
            .show()
    }

    companion object {
        fun newIntent(context: Context): Intent =
            Intent(context, BunproSettingsActivity::class.java)
    }
}
