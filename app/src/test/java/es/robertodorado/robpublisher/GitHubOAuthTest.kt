package es.robertodorado.robpublisher

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.net.HttpURLConnection

class GitHubOAuthTest {
    private val server = MockWebServer().apply { start() }
    private val oauth = GitHubOAuth({ 1000L }, HttpOAuthTransport {
        server.url(it).toUrl().openConnection() as HttpURLConnection
    })
    @After fun close() = server.shutdown()
    private val pending = DeviceAuthorization("secret-device", "ABCD", "https://github.com/login/device", 900_000, 5)
    private fun enqueue(body: String, status: Int = 200) = server.enqueue(MockResponse().setResponseCode(status)
        .setHeader("Content-Type", "application/json").setBody(body))

    @Test fun requestDeviceCodeRequestsExpiryWithoutSecretsAndUsesOfficialUri() = runBlocking {
        enqueue("""{"device_code":"secret-device","user_code":"ABCD","verification_uri":"https://github.com/login/device","expires_in":900,"interval":5}""")
        val result = oauth.begin(ROBGIT_RD_CLIENT_ID)
        assertEquals("ABCD", result.userCode); assertEquals(901_000L, result.expiresAt)
        val request = server.takeRequest()
        assertEquals("/login/device/code", request.path)
        assertEquals("application/json", request.getHeader("Accept"))
        assertEquals("client_id=$ROBGIT_RD_CLIENT_ID&scope=offline_access", request.body.readUtf8())
    }

    @Test fun pendingAndSlowDownAreParsedWithoutTreatingBrowserAsSuccess() = runBlocking {
        enqueue("""{"error":"authorization_pending"}""")
        enqueue("""{"error":"slow_down"}""")
        assertEquals(DevicePoll.Waiting, oauth.poll(ROBGIT_RD_CLIENT_ID, pending))
        assertEquals(DevicePoll.SlowDown, oauth.poll(ROBGIT_RD_CLIENT_ID, pending))
        assertTrue(server.takeRequest().body.readUtf8().contains("grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Adevice_code"))
    }

    @Test fun oauthErrorsInHttp400AreHandledAsFlowStates() = runBlocking {
        enqueue("""{"error":"authorization_pending"}""", 400)
        enqueue("""{"error":"slow_down"}""", 400)
        enqueue("""{"error":"expired_token"}""", 400)
        assertEquals(DevicePoll.Waiting, oauth.poll(ROBGIT_RD_CLIENT_ID, pending))
        assertEquals(DevicePoll.SlowDown, oauth.poll(ROBGIT_RD_CLIENT_ID, pending))
        assertTrue(oauth.poll(ROBGIT_RD_CLIENT_ID, pending) is DevicePoll.Failed)
    }

    @Test fun acceptsValidNonExpiringGitHubAppToken() = runBlocking {
        enqueue("""{"access_token":"valid-access","token_type":"bearer"}""")
        val result = oauth.poll(ROBGIT_RD_CLIENT_ID, pending) as DevicePoll.Authorized
        assertEquals("valid-access", result.token.accessToken)
        assertEquals("", result.token.refreshToken)
        assertEquals(Long.MAX_VALUE, result.token.accessExpiresAt)
    }

    @Test fun acceptsExpiringAndRenewableToken() = runBlocking {
        enqueue("""{"access_token":"access","token_type":"bearer","expires_in":28800,"refresh_token":"refresh","refresh_token_expires_in":15811200}""")
        val token = (oauth.poll(ROBGIT_RD_CLIENT_ID, pending) as DevicePoll.Authorized).token
        assertEquals(28_801_000L, token.accessExpiresAt); assertEquals("refresh", token.refreshToken)
    }

    @Test fun networkHttpErrorsAreRetryableAndNeverExposeBody() = runBlocking {
        for (code in listOf(403, 429, 500, 502, 503)) {
            server.enqueue(MockResponse().setResponseCode(code).setHeader("Retry-After", "25").setBody("secret-device secret-token"))
            try { oauth.poll(ROBGIT_RD_CLIENT_ID, pending); fail("Expected failure") }
            catch (e: OAuthFailure) {
                assertTrue(e.retryable); assertEquals(25L, e.retryAfterSeconds)
                assertFalse(e.message!!.contains("secret"))
            }
        }
    }

    @Test fun refreshOfDeviceFlowTokenNeedsNoSecretAndKeepsIdentity() = runBlocking {
        enqueue("""{"access_token":"new-access","token_type":"bearer","expires_in":28800,"refresh_token":"new-refresh","refresh_token_expires_in":15811200}""")
        val fresh = oauth.refresh(ROBGIT_RD_CLIENT_ID, OAuthCredentials("old", "old-refresh", 0, 900_000, "robdor80"))
        assertEquals("robdor80", fresh.login); assertEquals("new-access", fresh.accessToken)
        assertEquals("client_id=$ROBGIT_RD_CLIENT_ID&grant_type=refresh_token&refresh_token=old-refresh",
            server.takeRequest().body.readUtf8())
    }

    @Test fun onlyConfirmedInvalidRefreshErrorsInvalidateSession() = runBlocking {
        for (error in listOf("bad_refresh_token", "invalid_grant", "expired_token", "incorrect_client_credentials")) {
            enqueue("""{"error":"$error"}""")
            try { oauth.refresh(ROBGIT_RD_CLIENT_ID, OAuthCredentials("old", "old-refresh", 0, 900_000, "robdor80")); fail("Expected failure") }
            catch (e: OAuthFailure) { assertEquals(error != "incorrect_client_credentials", e.invalidSession) }
        }
    }

    @Test fun malformedTokenAndWrongTokenTypeNeverAuthorize() = runBlocking {
        for (body in listOf("""{"token_type":"bearer","access_token":""}""", """{"token_type":"mac","access_token":"secret"}""", "bad-json")) {
            enqueue(body)
            try { oauth.poll(ROBGIT_RD_CLIENT_ID, pending); fail("Expected failure") }
            catch (_: Exception) { /* no token */ }
        }
    }

    @Test fun refusesUnexpectedVerificationUri() = runBlocking {
        enqueue("""{"device_code":"s","user_code":"C","verification_uri":"https://attacker.invalid","expires_in":900}""")
        try { oauth.begin(ROBGIT_RD_CLIENT_ID); fail("Expected failure") }
        catch (e: OAuthFailure) { assertFalse(e.retryable) }
    }

    @Test fun expiredDeviceIsNotSentToServer() = runBlocking {
        assertTrue(oauth.poll(ROBGIT_RD_CLIENT_ID,
            DeviceAuthorization("s", "C", "https://github.com/login/device", 1, 5)) is DevicePoll.Failed)
        assertEquals(0, server.requestCount)
    }
}
