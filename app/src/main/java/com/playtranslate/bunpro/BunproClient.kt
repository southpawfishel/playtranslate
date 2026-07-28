package com.playtranslate.bunpro

import android.util.Log
import com.playtranslate.PtJson
import com.playtranslate.net.PtHttp
import com.playtranslate.translation.KeyStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Read-only client for the Bunpro frontend API. Stateless — the caller passes
 * the user's frontend session token (see [com.playtranslate.Prefs]) per call.
 *
 * Auth: the token is the `frontend_api_token` cookie from a logged-in
 * bunpro.jp session, sent as a plain `Authorization: Bearer`. It EXPIRES and
 * has no refresh path, so callers must treat a 401 as "token needs
 * re-entry", not a hard failure. The account API key from settings does NOT
 * work against this API. See `docs/features/bunpro-integration.md`.
 *
 * Best-effort throughout: every failure mode collapses to `null`
 * ([searchVocab]) or [KeyStatus.Unreachable] ([validateToken]) so the UI can
 * degrade cleanly.
 */
object BunproClient {

    private const val TAG = "BunproClient"
    private const val BASE_URL = "https://api.bunpro.jp/api/frontend"
    private val JSON = "application/json".toMediaType()

    /**
     * Encoder for request bodies. Unlike [PtJson.lenient] (tuned for reading),
     * this sets `encodeDefaults = true` so the `options` block and its flags
     * are always written — the API expects the full body, and kotlinx omits
     * default-valued fields otherwise.
     */
    private val requestJson = Json { encodeDefaults = true }

    private val client: OkHttpClient by lazy {
        PtHttp.clientBuilder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Checks a token against `GET /user`. [KeyStatus.Ok] on 200,
     * [KeyStatus.Invalid] on 401/403 (bad/expired token), else
     * [KeyStatus.Unreachable] (offline, 5xx, parse issue). Used by the
     * settings screen's "validate" action.
     */
    suspend fun validateToken(token: String): KeyStatus = withContext(Dispatchers.IO) {
        if (token.isBlank()) return@withContext KeyStatus.Invalid("Token blank")
        val req = Request.Builder()
            .url("$BASE_URL/user")
            .addHeader("Authorization", "Bearer $token")
            .addHeader("Accept", "application/json")
            .get()
            .build()
        try {
            client.newCall(req).execute().use { resp ->
                when (resp.code) {
                    200 -> KeyStatus.Ok
                    401, 403 -> KeyStatus.Invalid("HTTP ${resp.code}")
                    else -> KeyStatus.Unreachable
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "validateToken failed: ${e.message}")
            KeyStatus.Unreachable
        }
    }

    /**
     * Searches vocab for [query] (Japanese, romaji, or English) and returns the
     * `vocabs` section — items plus this user's side-loaded SRS reviews (query
     * [BunproSection.srsFor] per item). Grammar is intentionally not requested
     * here (`is_searching_grammar = false`).
     *
     * A 401/403 returns [BunproResult.Unauthorized] rather than a generic
     * failure so the caller can flag the stored token as expired — see
     * `Prefs.bunproTokenRejected`.
     */
    suspend fun searchVocab(token: String, query: String): BunproResult<BunproSection> =
        withContext(Dispatchers.IO) {
            if (token.isBlank() || query.isBlank()) return@withContext BunproResult.Failed
            val req = Request.Builder()
                .url("$BASE_URL/search/reviewables_v1_1")
                .addHeader("Authorization", "Bearer $token")
                .addHeader("Accept", "application/json")
                .post(searchRequestBody(query).toRequestBody(JSON))
                .build()
            try {
                client.newCall(req).execute().use { resp ->
                    when {
                        resp.code == 401 || resp.code == 403 -> {
                            Log.d(TAG, "searchVocab($query): token rejected (${resp.code})")
                            BunproResult.Unauthorized
                        }
                        !resp.isSuccessful -> {
                            Log.d(TAG, "searchVocab($query): HTTP ${resp.code}")
                            BunproResult.Failed
                        }
                        else -> {
                            val section = PtJson.lenient
                                .decodeFromString<BunproSearchResponse>(resp.body.string())
                                .vocabs
                            if (section == null) BunproResult.Failed else BunproResult.Ok(section)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "searchVocab($query) failed: ${e.message}")
                BunproResult.Failed
            }
        }

    /** Encoded `search/reviewables_v1_1` request body. Extracted for testability
     *  so the exact wire shape (full `options`, both flags) can be asserted. */
    internal fun searchRequestBody(
        query: String,
        searchGrammar: Boolean = false,
        searchVocab: Boolean = true,
    ): String = requestJson.encodeToString(
        BunproSearchRequest(query = query, isSearchingGrammar = searchGrammar, isSearchingVocab = searchVocab)
    )
}
