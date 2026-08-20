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
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
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

    /** `reviewable_type` values, matching what the API returns. */
    const val TYPE_VOCAB = "Vocab"
    const val TYPE_GRAMMAR = "GrammarPoint"

    private const val TAG = "BunproClient"
    private const val BASE_URL = "https://api.bunpro.jp/api/frontend"

    /**
     * Path for the add/remove-reviews call, relative to [BASE_URL] — verified
     * against real traffic as
     * `PATCH https://api.bunpro.jp/api/frontend/reviews/update_via_action_type`.
     *
     * Note the method is PATCH, not POST: the call *updates* the user's review
     * set via [addToReviewsBody]'s `action_type`, rather than creating a
     * resource at this path.
     */
    private const val ADD_TO_REVIEWS_PATH = "reviews/update_via_action_type"
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

    /**
     * Adds vocab to the user's review queue — the app's only WRITE.
     *
     * Body shape captured from real traffic; note it is a BULK, multi-action
     * endpoint (`action_type` also takes "remove"), and `reviewables` is an
     * array of `[type, id]` tuples rather than objects. We only ever send
     * `"add"`, and only ever one item, but the wire format is the server's.
     *
     * Returns the created [BunproReview] so the caller can render the new SRS
     * standing without a re-search. A 401 yields [BunproResult.Unauthorized] so
     * the expired-token path stays uniform with the reads.
     */
    suspend fun addToReviews(
        token: String,
        id: Long,
        type: String = TYPE_VOCAB,
    ): BunproResult<BunproReview> =
        withContext(Dispatchers.IO) {
            if (token.isBlank()) return@withContext BunproResult.Failed
            val req = Request.Builder()
                .url("$BASE_URL/$ADD_TO_REVIEWS_PATH")
                .addHeader("Authorization", "Bearer $token")
                .addHeader("Accept", "application/json")
                .patch(addToReviewsBody(listOf(id), type).toRequestBody(JSON))
                .build()
            try {
                client.newCall(req).execute().use { resp ->
                    when {
                        resp.code == 401 || resp.code == 403 -> {
                            Log.d(TAG, "addToReviews($type $id): token rejected (${resp.code})")
                            BunproResult.Unauthorized
                        }
                        !resp.isSuccessful -> {
                            Log.d(TAG, "addToReviews($type $id): HTTP ${resp.code}")
                            BunproResult.Failed
                        }
                        else -> {
                            val review = PtJson.lenient
                                .decodeFromString<BunproAddResponse>(resp.body.string())
                                .data.firstOrNull()?.attributes
                            if (review == null) BunproResult.Failed else BunproResult.Ok(review)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "addToReviews($type $id) failed: ${e.message}")
                BunproResult.Failed
            }
        }

    /**
     * Every grammar point the user has studied, with its SRS state — one
     * unpaginated request.
     *
     * `hydrate_reviewable_index` is misleadingly named: it returns the user's
     * REVIEW RECORDS, not a catalogue. There is no grammar content here at all
     * (no title, meaning or structure), just SRS state keyed by
     * `reviewable_id`. That makes it the relevance signal rather than a source
     * of grammar: on the benchmark corpus, suppressing points the user already
     * knows cut shown matches by 64% while keeping the N3+ material.
     */
    suspend fun studiedGrammar(token: String): BunproResult<List<BunproReview>> =
        withContext(Dispatchers.IO) {
            if (token.isBlank()) return@withContext BunproResult.Failed
            val req = Request.Builder()
                .url("$BASE_URL/reviews/hydrate_reviewable_index?reviewable_type=GrammarPoint")
                .addHeader("Authorization", "Bearer $token")
                .addHeader("Accept", "application/json")
                .get()
                .build()
            try {
                client.newCall(req).execute().use { resp ->
                    when {
                        resp.code == 401 || resp.code == 403 -> BunproResult.Unauthorized
                        !resp.isSuccessful -> BunproResult.Failed
                        else -> BunproResult.Ok(
                            // Same envelope as the add call — no new DTOs.
                            PtJson.lenient
                                .decodeFromString<BunproAddResponse>(resp.body.string())
                                .data.map { it.attributes },
                        )
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "studiedGrammar failed: ${e.message}")
                BunproResult.Failed
            }
        }

    /**
     * Encoded add-to-reviews body. Built as raw JSON rather than a
     * `@Serializable` DTO because `reviewables` holds heterogeneous
     * `[String, Long]` tuples, which kotlinx can't express as a data class
     * without a custom serializer. Extracted for testability.
     */
    internal fun addToReviewsBody(
        ids: List<Long>,
        type: String = TYPE_VOCAB,
    ): String = buildJsonObject {
        put("action_type", JsonPrimitive("add"))
        // Explicit null, not omitted — matches the captured request.
        put("deck_id", JsonNull)
        putJsonArray("reviewables") {
            ids.forEach { id ->
                addJsonArray {
                    add(JsonPrimitive(type))
                    add(JsonPrimitive(id))
                }
            }
        }
    }.toString()

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
