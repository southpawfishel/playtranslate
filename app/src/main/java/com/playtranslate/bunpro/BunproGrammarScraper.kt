package com.playtranslate.bunpro

import android.content.Context
import android.util.Log
import com.playtranslate.Prefs
import com.playtranslate.PtJson
import com.playtranslate.net.PtHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Mirrors the Bunpro grammar catalogue to [BunproGrammarStore].
 *
 * Two phases, deliberately separable:
 *  - [syncIndex] — ONE request for all ~979 points. Cheap, and enough on its
 *    own for matching to work.
 *  - [syncDetails] — one request PER point for the conjugation tables. ~979
 *    requests against someone else's server, so it is throttled, resumable,
 *    and never implicit: a caller must ask for it.
 *
 * The URLs embed a deploy-scoped `buildId` that changes whenever Bunpro ships,
 * so it is read at runtime from `__NEXT_DATA__` ([fetchBuildId]) rather than
 * hardcoded — and re-read once mid-sweep if a request starts 404ing, which is
 * what a deploy looks like from here.
 *
 * Gated on a saved Bunpro token. The grammar pages are public, so this is a
 * policy check rather than a technical one: the mirror is for people who hold
 * a Bunpro licence, and [BunproGrammarStore.clear] removes it when they don't.
 */
object BunproGrammarScraper {

    private const val TAG = "BunproScraper"
    private const val SITE = "https://bunpro.jp"
    private const val INDEX_PAGE = "$SITE/grammar_points"

    /** Politeness gap between detail requests. ~979 points at this rate is a
     *  little over eight minutes — slow on purpose. This is a bulk read of
     *  another service's content; it should look like a person, not a flood. */
    private const val DETAIL_DELAY_MS = 500L

    private val BUILD_ID = Regex("\"buildId\"\\s*:\\s*\"([^\"]+)\"")

    private val client: OkHttpClient by lazy {
        PtHttp.clientBuilder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    sealed interface SyncResult {
        data class Ok(val count: Int) : SyncResult
        /** No token saved — the feature is gated, not broken. */
        data object NotConfigured : SyncResult
        data class Failed(val reason: String) : SyncResult
    }

    /**
     * Reads the current deploy's build id out of any bunpro.jp page.
     *
     * Next.js embeds it in a `__NEXT_DATA__` script tag. Everything downstream
     * 404s without a current one, so this is the first call in every sweep and
     * the recovery step when a sweep starts failing.
     */
    suspend fun fetchBuildId(): String? = withContext(Dispatchers.IO) {
        try {
            client.newCall(Request.Builder().url(INDEX_PAGE).get().build()).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                BUILD_ID.find(resp.body.string())?.groupValues?.get(1)
            }
        } catch (e: Exception) {
            Log.d(TAG, "fetchBuildId failed: ${e.message}")
            null
        }
    }

    /** The whole catalogue in one request. */
    suspend fun syncIndex(ctx: Context): SyncResult {
        if (Prefs(ctx.applicationContext).bunproToken.isBlank()) return SyncResult.NotConfigured
        val buildId = fetchBuildId() ?: return SyncResult.Failed("could not read buildId")
        val body = get("$SITE/_next/data/$buildId/en/grammar_points.json")
            ?: return SyncResult.Failed("index request failed")
        val points = try {
            PtJson.lenient.decodeFromString<BunproGrammarIndexResponse>(body)
                .pageProps.grammarPoints
        } catch (e: Exception) {
            return SyncResult.Failed("index parse failed: ${e.message}")
        }
        if (points.isEmpty()) return SyncResult.Failed("index was empty")
        BunproGrammarStore.saveIndex(ctx, points, buildId)
        return SyncResult.Ok(points.size)
    }

    /**
     * Fetches per-point detail for everything still missing it.
     *
     * Resumes from [BunproGrammarStore.idsMissingDetail], so an interruption
     * costs only the in-flight request. [onProgress] reports `(done, total)`
     * for a UI that must show this taking minutes. [limit] caps one run, for
     * callers that would rather do it in slices.
     *
     * Detail is an OPTIMISATION, not a prerequisite: matching works from the
     * index alone, and the lemma arm already covers many conjugated forms. The
     * measured payoff is the recall delta from feeding
     * [BunproGrammarStore.loadStructureForms] into the matcher's `extraForms`.
     */
    suspend fun syncDetails(
        ctx: Context,
        limit: Int = Int.MAX_VALUE,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): SyncResult {
        if (Prefs(ctx.applicationContext).bunproToken.isBlank()) return SyncResult.NotConfigured
        val index = BunproGrammarStore.loadIndex(ctx).associateBy { it.id }
        val pending = BunproGrammarStore.idsMissingDetail(ctx).take(limit)
        if (pending.isEmpty()) return SyncResult.Ok(0)

        var buildId = BunproGrammarStore.buildId(ctx)
            ?: fetchBuildId()
            ?: return SyncResult.Failed("could not read buildId")
        var done = 0
        var rebuilt = false

        for (id in pending) {
            val slug = index[id]?.slug ?: index[id]?.title ?: continue
            var body = get(detailUrl(buildId, slug))
            if (body == null && !rebuilt) {
                // A mid-sweep deploy invalidates the build id. Re-read it once
                // and carry on rather than failing the whole run.
                rebuilt = true
                fetchBuildId()?.let { fresh ->
                    if (fresh != buildId) {
                        Log.d(TAG, "buildId changed mid-sweep; resuming on $fresh")
                        buildId = fresh
                        body = get(detailUrl(buildId, slug))
                    }
                }
            }
            if (body != null) {
                try {
                    PtJson.lenient.decodeFromString<BunproGrammarDetailResponse>(body)
                        .pageProps.reviewable
                        ?.let { BunproGrammarStore.saveDetail(ctx, it) }
                } catch (e: Exception) {
                    Log.d(TAG, "detail parse failed for $slug: ${e.message}")
                }
            }
            done++
            onProgress(done, pending.size)
            delay(DETAIL_DELAY_MS)
        }
        return SyncResult.Ok(done)
    }

    /**
     * Fetches the lesson write-up for ONE point, on demand.
     *
     * Separate from [syncDetails] on purpose. The sweep stores only the short
     * fields (nuance, caution, conjugation forms); the write-up is the actual
     * teaching content and is fetched only for a point the user has opened —
     * one request, for one thing they asked to read.
     *
     * The result is cached in [BunproGrammarStore.saveWriteup] so the same
     * point encountered in a later sentence costs nothing. That caches the
     * pages the user opened, the way a browser would; it is still not a sweep
     * of all ~979.
     *
     * Returns null on any failure; the caller shows a fallback rather than an
     * error, since this decorates a row that already renders.
     */
    suspend fun fetchWriteup(ctx: Context, slug: String): String? {
        if (Prefs(ctx.applicationContext).bunproToken.isBlank()) return null
        var buildId = BunproGrammarStore.buildId(ctx) ?: fetchBuildId() ?: return null
        var body = get(detailUrl(buildId, slug))
        if (body == null) {
            // Stale build id (Bunpro deployed since the last sync) — re-read
            // once and retry before giving up.
            fetchBuildId()?.takeIf { it != buildId }?.let {
                buildId = it
                body = get(detailUrl(buildId, slug))
            }
        }
        val json = body ?: return null
        return try {
            val props = PtJson.lenient
                .decodeFromString<BunproGrammarDetailResponse>(json).pageProps
            // Store the short fields while we have them — free, and it means a
            // later sweep has less to do.
            props.reviewable?.let { BunproGrammarStore.saveDetail(ctx, it) }
            val text = props.included.writeups.firstOrNull()?.plainText()
                ?.takeIf { it.isNotBlank() }
            val id = props.reviewable?.id
            if (text != null && id != null) BunproGrammarStore.saveWriteup(ctx, id, text)
            text
        } catch (e: Exception) {
            Log.d(TAG, "writeup parse failed for $slug: ${e.message}")
            null
        }
    }

    private fun detailUrl(buildId: String, slug: String): String {
        val enc = java.net.URLEncoder.encode(slug, "UTF-8")
        return "$SITE/_next/data/$buildId/en/grammar_points/$enc.json?slug=$enc"
    }

    private suspend fun get(url: String): String? = withContext(Dispatchers.IO) {
        try {
            client.newCall(Request.Builder().url(url).get().build()).execute().use { resp ->
                if (resp.isSuccessful) resp.body.string() else null
            }
        } catch (e: Exception) {
            Log.d(TAG, "GET $url failed: ${e.message}")
            null
        }
    }
}
