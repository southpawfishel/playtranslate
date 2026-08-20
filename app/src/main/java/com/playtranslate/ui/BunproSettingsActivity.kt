package com.playtranslate.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.Toast
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.playtranslate.Prefs
import com.playtranslate.R
import com.playtranslate.themeColor
import com.playtranslate.bunpro.BunproClient
import com.playtranslate.bunpro.BunproGrammarLookup
import com.playtranslate.bunpro.BunproGrammarScraper
import com.playtranslate.bunpro.BunproGrammarStore
import com.playtranslate.bunpro.BunproLookup
import com.playtranslate.bunpro.BunproSelfCheck
import com.playtranslate.translation.KeyStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job

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
 *
 * That same expiry is why the field has a [BunproLoginActivity] button beside
 * it: re-entry is a recurring chore, and reading the cookie by hand needs
 * desktop DevTools. The button only *fills* the field — Save still validates and
 * commits — so a capture behaves exactly like a paste, and the toolbar X
 * discards it like any other edit.
 */
class BunproSettingsActivity : SettingsSubPageActivity() {

    override val layoutResId = R.layout.activity_bunpro_settings

    private lateinit var prefs: Prefs
    private lateinit var etToken: EditText
    private lateinit var btnSave: MaterialButton
    private lateinit var progressSave: ProgressBar
    private lateinit var switchEnabled: MaterialSwitch

    /** Receives a freshly captured token and drops it in the field. Treated as
     *  a paste, not a save: the user still commits it, so a bad capture can't
     *  silently replace a working token. */
    private val bunproLogin = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        val token = result.data?.getStringExtra(BunproLoginActivity.EXTRA_TOKEN).orEmpty()
        if (token.isBlank()) return@registerForActivityResult
        etToken.setText(token)
        etToken.setSelection(etToken.text.length)
    }

    override fun onContentCreated(savedInstanceState: Bundle?) {
        prefs = Prefs(this)

        etToken = findViewById(R.id.etBunproToken)
        etToken.setText(prefs.bunproToken)
        etToken.setSelection(etToken.text.length)

        findViewById<MaterialButton>(R.id.btnBunproLogin).setOnClickListener {
            bunproLogin.launch(BunproLoginActivity.newIntent(this))
        }

        wireEnabledRow(findViewById(R.id.rowBunproEnabled))
        wireSyncRow(findViewById(R.id.rowBunproSync))
        wireDetailsRow(findViewById(R.id.rowBunproDetails))
        wireSelfCheckRow(findViewById(R.id.rowBunproSelfCheck))

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

    /**
     * Mirrors the grammar catalogue to this device. Only the INDEX — one
     * request for all ~979 points, which is enough for matching. The
     * per-point detail sweep is ~979 more requests and stays opt-in.
     */
    private fun wireSyncRow(row: View) {
        row.findViewById<TextView>(R.id.tvRowTitle).text =
            getString(R.string.bunpro_sync_row_title)
        val subtitle = row.findViewById<TextView>(R.id.tvRowSubtitle).apply { isVisible = true }
        val button = row.findViewById<MaterialButton>(R.id.btnRowAction)
        button.text = getString(R.string.bunpro_sync_button)

        fun refreshSubtitle() {
            lifecycleScope.launch {
                val n = BunproGrammarStore.indexSize(this@BunproSettingsActivity)
                if (!isFinishing) {
                    subtitle.text =
                        if (n == 0) getString(R.string.bunpro_sync_row_never)
                        else getString(R.string.bunpro_sync_row_count, n)
                }
            }
        }
        refreshSubtitle()

        button.setOnClickListener {
            button.isEnabled = false
            button.text = getString(R.string.bunpro_sync_working)
            lifecycleScope.launch {
                val result = BunproGrammarScraper.syncIndex(this@BunproSettingsActivity)
                // The studied set is the relevance lever: without it the
                // matcher surfaces N5 conjugations the user learned years ago
                // (measured: it removes ~64% of matches while keeping N3+).
                // One request, so it rides along with the catalogue sync.
                val studiedOk =
                    if (result is BunproGrammarScraper.SyncResult.Ok) {
                        BunproGrammarLookup.syncStudied(this@BunproSettingsActivity)
                    } else false
                // Drop the in-memory index so the next lookup sees both.
                BunproGrammarLookup.invalidate()
                if (isFinishing) return@launch
                button.isEnabled = true
                button.text = getString(R.string.bunpro_sync_button)
                refreshSubtitle()
                val message = when (result) {
                    is BunproGrammarScraper.SyncResult.Ok ->
                        getString(R.string.bunpro_sync_ok, result.count) + "\n\n" +
                            getString(
                                if (studiedOk) R.string.bunpro_sync_studied_ok
                                else R.string.bunpro_sync_studied_failed
                            )
                    BunproGrammarScraper.SyncResult.NotConfigured ->
                        getString(R.string.bunpro_sync_not_configured)
                    is BunproGrammarScraper.SyncResult.Failed ->
                        getString(R.string.bunpro_sync_failed, result.reason)
                }
                OverlayAlert.Builder(this@BunproSettingsActivity)
                    .setTitle(getString(R.string.bunpro_sync_result_title))
                    .setMessage(message)
                    .addCancelButton(getString(R.string.bunpro_selfcheck_close))
                    .show()
            }
        }
    }

    /** Non-null while a detail sweep is running, so the button can stop it. */
    private var detailJob: Job? = null

    /**
     * The optional per-point sweep: one request per grammar point, for the
     * conjugation tables and short nuance text.
     *
     * Kept on its own row, behind a confirmation, because it is minutes long
     * and touches someone else's server ~979 times. It is resumable, so
     * stopping it (or leaving the screen, which cancels the job) costs only
     * the in-flight request.
     *
     * Explicitly optional: matching works from the index alone, and the lemma
     * arm already covers much of what the conjugation tables would add.
     */
    private fun wireDetailsRow(row: View) {
        row.findViewById<TextView>(R.id.tvRowTitle).text =
            getString(R.string.bunpro_details_row_title)
        val subtitle = row.findViewById<TextView>(R.id.tvRowSubtitle).apply { isVisible = true }
        val button = row.findViewById<MaterialButton>(R.id.btnRowAction)

        fun refresh() {
            lifecycleScope.launch {
                val total = BunproGrammarStore.indexSize(this@BunproSettingsActivity)
                val done = BunproGrammarStore.detailCount(this@BunproSettingsActivity)
                if (isFinishing) return@launch
                subtitle.text =
                    if (total == 0) getString(R.string.bunpro_details_row_none)
                    else getString(R.string.bunpro_details_row_progress, done, total)
                button.isEnabled = total > 0
            }
        }
        refresh()
        button.text = getString(R.string.bunpro_details_button)

        button.setOnClickListener {
            // Already running → this is the stop control.
            detailJob?.let {
                it.cancel()
                detailJob = null
                button.text = getString(R.string.bunpro_details_button)
                refresh()
                Toast.makeText(this, R.string.bunpro_details_stopped, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            OverlayAlert.Builder(this)
                .setTitle(getString(R.string.bunpro_details_confirm_title))
                .setMessage(getString(R.string.bunpro_details_confirm_body))
                .addButton(
                    label = getString(R.string.bunpro_details_confirm_start),
                    color = themeColor(R.attr.ptAccent),
                ) { startDetailSweep(button, ::refresh) }
                .addCancelButton(getString(R.string.word_bunpro_add_cancel))
                .show()
        }
    }

    private fun startDetailSweep(button: MaterialButton, refresh: () -> Unit) {
        detailJob = lifecycleScope.launch {
            val result = BunproGrammarScraper.syncDetails(
                this@BunproSettingsActivity,
            ) { done, total ->
                // Progress arrives off the main thread; hop back to touch views.
                lifecycleScope.launch {
                    if (!isFinishing) {
                        button.text = getString(R.string.bunpro_details_row_progress, done, total)
                    }
                }
            }
            detailJob = null
            if (isFinishing) return@launch
            button.text = getString(R.string.bunpro_details_button)
            refresh()
            // Fresh conjugation forms mean the matcher's index is stale.
            BunproGrammarLookup.invalidate()
            if (result is BunproGrammarScraper.SyncResult.Ok) {
                Toast.makeText(this@BunproSettingsActivity, R.string.bunpro_details_done, Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * Runs [BunproSelfCheck] and shows its report.
     *
     * This exists because the matcher's central assumption — that Sudachi
     * lemmatizes よかった to いい — cannot be tested off-device: Sudachi needs a
     * packaged dictionary from a downloaded language pack. Reporting into an
     * alert means verification needs nothing but the app itself.
     */
    private fun wireSelfCheckRow(row: View) {
        row.findViewById<TextView>(R.id.tvRowTitle).text =
            getString(R.string.bunpro_selfcheck_row_title)
        row.findViewById<TextView>(R.id.tvRowSubtitle).apply {
            text = getString(R.string.bunpro_selfcheck_row_subtitle)
            isVisible = true
        }
        val button = row.findViewById<MaterialButton>(R.id.btnRowAction)
        button.text = getString(R.string.bunpro_selfcheck_button)
        button.setOnClickListener {
            button.isEnabled = false
            button.text = getString(R.string.bunpro_selfcheck_working)
            lifecycleScope.launch {
                val report = BunproSelfCheck.run(this@BunproSettingsActivity)
                if (isFinishing) return@launch
                button.isEnabled = true
                button.text = getString(R.string.bunpro_selfcheck_button)
                OverlayAlert.Builder(this@BunproSettingsActivity)
                    .setTitle(getString(R.string.bunpro_selfcheck_title))
                    .setMessage(report.summary + "\n\n" + report.asText())
                    .addCancelButton(getString(R.string.bunpro_selfcheck_close))
                    .show()
            }
        }
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
