package com.playtranslate.bunpro

import com.playtranslate.translation.KeyStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Test

/**
 * Talks to the REAL Bunpro API with a real account API token — the check that
 * the server actually honours [BunproClient.API_TOKEN_PARAM], which
 * [BunproClientAuthTest] can only assume.
 *
 * Skipped unless `BUNPRO_API_TOKEN` is set (from https://bunpro.jp/settings/api),
 * so CI and normal test runs stay offline. Read-only on purpose: it never
 * calls [BunproClient.addToReviews], which would change the account's review
 * queue.
 *
 *     BUNPRO_API_TOKEN=… ./gradlew :app:testDebugUnitTest --tests '*BunproClientLiveTest*'
 */
class BunproClientLiveTest {

    private val token = System.getenv("BUNPRO_API_TOKEN").orEmpty().trim()

    @Before
    fun requireToken() {
        assumeFalse("BUNPRO_API_TOKEN not set; skipping live Bunpro test", token.isEmpty())
    }

    @Test
    fun `the API token authenticates`() = runBlocking {
        assertEquals(KeyStatus.Ok, BunproClient.validateToken(token))
    }

    @Test
    fun `a bogus token is rejected, not mistaken for an outage`() = runBlocking {
        assertTrue(BunproClient.validateToken("not-a-real-token") is KeyStatus.Invalid)
    }

    /** Not an auth check: search answers 200 even for a bogus token (it just
     *  carries no SRS reviews then). It only proves the flagged request is
     *  still accepted. [the API token authenticates] and
     *  [studied grammar loads with the API token] are the auth proof. */
    @Test
    fun `vocab search works with the API token`() = runBlocking {
        val result = BunproClient.searchVocab(token, "肺")
        assertTrue("search returned $result", result is BunproResult.Ok)
    }

    @Test
    fun `studied grammar loads with the API token`() = runBlocking {
        val result = BunproClient.studiedGrammar(token)
        assertTrue("studiedGrammar returned $result", result is BunproResult.Ok)
    }
}
