package com.playtranslate.bunpro

import com.playtranslate.translation.KeyStatus
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Locks [BunproClient] to API-token auth: every route carries the token as a
 * Bearer header AND the [BunproClient.API_TOKEN_PARAM] flag, keeps its own
 * query, and maps 401/403 to the rejected-token outcome. Requests never leave
 * the process — an interceptor records each one and answers it.
 */
class BunproClientAuthTest {

    private val token = "test-api-token"
    private val seen = mutableListOf<Request>()
    private var replyCode = 200
    private var replyBody = "{}"

    @Before
    fun setUp() {
        BunproClient.clientOverride = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val req = chain.request()
                seen += req
                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(replyCode)
                    .message("test")
                    .body(replyBody.toResponseBody("application/json".toMediaType()))
                    .build()
            })
            .build()
    }

    @After
    fun tearDown() {
        BunproClient.clientOverride = null
    }

    private fun assertAuthed(req: Request, method: String, path: String) {
        assertEquals(method, req.method)
        assertEquals("https", req.url.scheme)
        assertEquals("api.bunpro.jp", req.url.host)
        assertEquals("/api/frontend/$path", req.url.encodedPath)
        assertEquals("true", req.url.queryParameter(BunproClient.API_TOKEN_PARAM))
        assertEquals("Bearer $token", req.header("Authorization"))
        assertEquals("application/json", req.header("Accept"))
    }

    // ── Request shape, per route ─────────────────────────────────────────

    @Test
    fun `validateToken pings user with the API token`() = runBlocking {
        assertEquals(KeyStatus.Ok, BunproClient.validateToken(token))
        assertAuthed(seen.single(), "GET", "user")
    }

    @Test
    fun `searchVocab posts the search with the API token`() = runBlocking {
        replyBody = """{"vocabs":{"data":[],"included":[]}}"""
        val result = BunproClient.searchVocab(token, "肺")
        assertTrue(result is BunproResult.Ok)
        assertAuthed(seen.single(), "POST", "search/reviewables_v1_1")
    }

    @Test
    fun `addToReviews patches with the API token`() = runBlocking {
        replyBody = """{"data":[{"id":"1","type":"review","attributes":{"id":1,"reviewable_id":42,"reviewable_type":"Vocab"}}]}"""
        val result = BunproClient.addToReviews(token, 42L)
        assertEquals(42L, (result as BunproResult.Ok).value.reviewableId)
        assertAuthed(seen.single(), "PATCH", "reviews/update_via_action_type")
    }

    @Test
    fun `studiedGrammar keeps its own query beside the flag`() = runBlocking {
        replyBody = """{"data":[]}"""
        val result = BunproClient.studiedGrammar(token)
        assertTrue(result is BunproResult.Ok)
        val req = seen.single()
        assertAuthed(req, "GET", "reviews/hydrate_reviewable_index")
        assertEquals("GrammarPoint", req.url.queryParameter("reviewable_type"))
    }

    @Test
    fun `the flag appears exactly once`() {
        val url = BunproClient.authedRequest(token, "user").build().url
        assertEquals(listOf("true"), url.queryParameterValues(BunproClient.API_TOKEN_PARAM))
    }

    // ── Rejection handling ───────────────────────────────────────────────

    @Test
    fun `a rejected token reads as rejected on every route`() = runBlocking {
        for (code in listOf(401, 403)) {
            replyCode = code
            assertTrue(BunproClient.validateToken(token) is KeyStatus.Invalid)
            assertEquals(BunproResult.Unauthorized, BunproClient.searchVocab(token, "肺"))
            assertEquals(BunproResult.Unauthorized, BunproClient.addToReviews(token, 42L))
            assertEquals(BunproResult.Unauthorized, BunproClient.studiedGrammar(token))
        }
    }

    @Test
    fun `a server error is not blamed on the token`() = runBlocking {
        replyCode = 500
        assertEquals(KeyStatus.Unreachable, BunproClient.validateToken(token))
        assertEquals(BunproResult.Failed, BunproClient.searchVocab(token, "肺"))
    }

    @Test
    fun `a blank token never reaches the network`() = runBlocking {
        assertTrue(BunproClient.validateToken("") is KeyStatus.Invalid)
        assertEquals(BunproResult.Failed, BunproClient.searchVocab("", "肺"))
        assertEquals(BunproResult.Failed, BunproClient.studiedGrammar(""))
        assertTrue(seen.isEmpty())
    }
}
