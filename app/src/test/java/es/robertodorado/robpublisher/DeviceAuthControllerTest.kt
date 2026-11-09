package es.robertodorado.robpublisher

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DeviceAuthControllerTest {
    private class MemoryStore : DeviceAuthStore {
        var pending: PendingDeviceAuth? = null
        var session: OAuthCredentials? = null
        var saves = 0
        override fun pendingAuth() = pending
        override fun savePendingAuth(value: PendingDeviceAuth?) { pending = value }
        override fun completeAuth(value: OAuthCredentials) { session = value; pending = null; saves++ }
    }
    private class Server : OAuthTransport {
        val replies = ArrayDeque<Any>()
        val requests = mutableListOf<String>()
        var begins = 0
        var polls = 0
        override suspend fun post(path: String, fields: Map<String, String>): JSONObject {
            requests += path
            if (path == "/login/device/code") {
                begins++
                assertEquals(setOf("client_id", "scope"), fields.keys)
                assertEquals("offline_access", fields["scope"]) // Solicita caducidad, no amplía permisos.
                return JSONObject("""{"device_code":"secret-device","user_code":"ABCD-EFGH","verification_uri":"https://github.com/login/device","expires_in":900,"interval":5}""")
            }
            polls++
            assertEquals("secret-device", fields["device_code"])
            val response = if (replies.isEmpty()) "authorization_pending" else replies.removeFirst()
            if (response is Exception) throw response
            return if (response == "token") JSONObject("""{"access_token":"secret-token","token_type":"bearer"}""")
                else JSONObject().put("error", response)
        }
    }
    private fun TestScope.controller(store: MemoryStore, server: Server,
        scope: CoroutineScope = backgroundScope, user: suspend (String) -> String = { "robdor80" }) =
        DeviceAuthController(scope, store, GitHubOAuth({ testScheduler.currentTime }, server), user,
            { testScheduler.currentTime })

    @Test fun normalAuthorizationValidatesThenPersistsAndConnects() = runTest {
        val store = MemoryStore(); val server = Server().apply { replies += "token" }
        var validated = false
        val controller = controller(store, server, user = {
            assertEquals("secret-token", it)
            assertNotNull(store.pending?.credentials)
            assertNull(store.session)
            validated = true; "robdor80"
        })
        controller.begin(); runCurrent()
        assertTrue(controller.state.value.running)
        assertEquals(0, server.polls)
        advanceTimeBy(5000); runCurrent()
        assertTrue(validated)
        assertEquals("robdor80", controller.state.value.connectedLogin)
        assertEquals("robdor80", store.session?.login)
        assertNull(store.pending)
        assertEquals(1, store.saves)
    }

    @Test fun repeatedBeginRestoreAndUiCollectionNeverDuplicatePoller() = runTest {
        val store = MemoryStore(); val server = Server(); val controller = controller(store, server)
        repeat(10) { controller.begin(); controller.restore() }; runCurrent()
        repeat(10) { controller.begin(); controller.restore(); controller.state.value }
        advanceTimeBy(10_000); runCurrent()
        assertEquals(1, server.begins); assertEquals(2, server.polls)
        assertEquals("ABCD-EFGH", controller.state.value.pending?.userCode)
    }

    @Test fun authorizationPendingKeepsSameCodeAcrossTimeWithoutUi() = runTest {
        val store = MemoryStore(); val server = Server(); val controller = controller(store, server)
        controller.begin(); runCurrent()
        val code = controller.state.value.pending
        advanceTimeBy(15_000); runCurrent()
        assertSame(code, controller.state.value.pending)
        assertTrue(controller.state.value.running)
        assertNull(store.session)
    }

    @Test fun authorizationGrantedWithoutUiCollectorsCompletesInBackground() = runTest {
        val store = MemoryStore(); val server = Server().apply { replies += "authorization_pending"; replies += "token" }
        val controller = controller(store, server)
        controller.begin(); runCurrent()
        advanceTimeBy(10_000); runCurrent()
        assertEquals("robdor80", controller.state.value.connectedLogin)
        assertEquals(2, server.polls)
    }

    @Test fun processScopeDestroyedAndNewControllerResumesStoredCodeWithoutEarlyPolling() = runTest {
        val store = MemoryStore(); val server = Server()
        val oldScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        controller(store, server, oldScope).begin(); runCurrent()
        advanceTimeBy(2000); oldScope.cancel(); runCurrent()
        val restored = controller(store, server); restored.restore(); runCurrent()
        assertEquals("ABCD-EFGH", restored.state.value.pending?.userCode)
        advanceTimeBy(2999); runCurrent(); assertEquals(0, server.polls)
        server.replies += "token"; advanceTimeBy(1); runCurrent()
        assertEquals(1, server.begins); assertEquals(1, server.polls)
        assertNotNull(store.session)
    }

    @Test fun slowDownAddsFiveSecondsAndSurvivesProcessRestart() = runTest {
        val store = MemoryStore(); val server = Server().apply { replies += "slow_down"; replies += "token" }
        val oldScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        controller(store, server, oldScope).begin(); runCurrent()
        advanceTimeBy(5000); runCurrent(); oldScope.cancel()
        assertEquals(10L, store.pending?.intervalSeconds)
        controller(store, server).restore(); runCurrent()
        advanceTimeBy(9999); runCurrent(); assertEquals(1, server.polls)
        advanceTimeBy(1); runCurrent(); assertEquals(2, server.polls)
        assertNotNull(store.session)
    }

    @Test fun localExpiryStopsAndRemovesPendingWithoutAnotherPoll() = runTest {
        val store = MemoryStore(); val server = Server(); val controller = controller(store, server)
        controller.begin(); runCurrent(); advanceTimeBy(900_000); runCurrent()
        assertFalse(controller.state.value.running); assertNull(store.pending)
        assertTrue(controller.state.value.message!!.contains("caducado"))
        val polls = server.polls; advanceTimeBy(100_000); runCurrent(); assertEquals(polls, server.polls)
    }

    @Test fun restoreExpiredCodeDoesNotContactServer() = runTest {
        val store = MemoryStore().apply { pending = PendingDeviceAuth(DeviceAuthorization("secret-device", "CODE", "https://github.com/login/device", 0, 5)) }
        val server = Server(); val controller = controller(store, server)
        controller.restore(); runCurrent()
        assertNull(store.pending); assertEquals(0, server.polls); assertEquals(0, server.begins)
    }

    @Test fun networkFailureKeepsCodeAndRetriesWithBackoff() = runTest {
        val store = MemoryStore(); val server = Server().apply { replies += IOException("sensitive"); replies += "token" }
        val controller = controller(store, server)
        controller.begin(); runCurrent(); advanceTimeBy(5000); runCurrent()
        assertNotNull(controller.state.value.pending); assertNotNull(store.pending)
        assertFalse(controller.state.value.message!!.contains("sensitive"))
        advanceTimeBy(9999); runCurrent(); assertEquals(1, server.polls)
        advanceTimeBy(1); runCurrent(); assertNotNull(store.session)
    }

    @Test fun rateLimitHonorsRetryAfter() = runTest {
        val store = MemoryStore(); val server = Server().apply {
            replies += OAuthFailure("rate", true, 40); replies += "token"
        }
        controller(store, server).begin(); runCurrent(); advanceTimeBy(5000); runCurrent()
        advanceTimeBy(39_999); runCurrent(); assertEquals(1, server.polls)
        advanceTimeBy(1); runCurrent(); assertNotNull(store.session)
    }

    @Test fun transientIdentityFailureRetainsReceivedTokenAcrossRestart() = runTest {
        val store = MemoryStore(); val server = Server().apply { replies += "token" }
        val oldScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        controller(store, server, oldScope, user = { throw GitHubException(503) }).begin()
        runCurrent(); advanceTimeBy(5000); runCurrent()
        assertNotNull(store.pending?.credentials); assertNull(store.session); oldScope.cancel()
        controller(store, server).restore(); runCurrent(); advanceTimeBy(10_000); runCurrent()
        assertNotNull(store.session); assertEquals(1, server.polls)
    }

    @Test fun voluntaryCancelClearsPendingAndStopsPolling() = runTest {
        val store = MemoryStore(); val server = Server(); val controller = controller(store, server)
        controller.begin(); runCurrent(); controller.cancel(); advanceTimeBy(50_000); runCurrent()
        assertNull(store.pending); assertFalse(controller.state.value.running); assertEquals(0, server.polls)
        controller.begin(); runCurrent(); assertEquals(2, server.begins)
    }

    @Test fun cancelWhileBeginIsInFlightCannotResurrectCode() = runTest {
        val store = MemoryStore()
        val oauth = GitHubOAuth({ testScheduler.currentTime }, OAuthTransport { _, _ ->
            kotlinx.coroutines.delay(5000)
            JSONObject("""{"device_code":"s","user_code":"C","verification_uri":"https://github.com/login/device","expires_in":900}""")
        })
        val controller = DeviceAuthController(backgroundScope, store, oauth, { "robdor80" }, { testScheduler.currentTime })
        controller.begin(); runCurrent(); controller.cancel(); advanceTimeBy(10_000); runCurrent()
        assertNull(store.pending); assertFalse(controller.state.value.running)
    }

    @Test fun cancelDuringIdentityValidationCannotSaveSession() = runTest {
        val store = MemoryStore(); val server = Server().apply { replies += "token" }
        val controller = controller(store, server, user = { kotlinx.coroutines.delay(5000); "robdor80" })
        controller.begin(); runCurrent(); advanceTimeBy(5000); runCurrent()
        assertNotNull(store.pending?.credentials)
        controller.cancel(); advanceTimeBy(10_000); runCurrent()
        assertNull(store.pending); assertNull(store.session)
        assertFalse(controller.state.value.running)
    }

    @Test fun wrongAccountCannotBecomeSession() = runTest {
        val store = MemoryStore(); val server = Server().apply { replies += "token" }
        val controller = controller(store, server, user = { "other-account" })
        controller.begin(); runCurrent(); advanceTimeBy(5000); runCurrent()
        assertNull(store.session); assertNull(store.pending); assertFalse(controller.state.value.running)
    }

    @Test fun invalidTokenCannotBecomeSession() = runTest {
        val store = MemoryStore(); val server = Server().apply { replies += "token" }
        val controller = controller(store, server, user = { throw GitHubException(401) })
        controller.begin(); runCurrent(); advanceTimeBy(5000); runCurrent()
        assertNull(store.session); assertNull(store.pending)
    }

    @Test fun terminalOAuthErrorsStopWithoutDeletingExistingSession() = runTest {
        for (error in listOf("expired_token", "access_denied", "device_flow_disabled", "bad_verification_code", "unknown")) {
            val store = MemoryStore().apply { session = OAuthCredentials("existing", "", Long.MAX_VALUE, Long.MAX_VALUE, "robdor80") }
            val server = Server().apply { replies += error }; val controller = controller(store, server)
            controller.begin(); runCurrent(); advanceTimeBy(5000); runCurrent()
            assertFalse(controller.state.value.running); assertNull(store.pending)
            assertEquals("existing", store.session?.accessToken)
        }
    }
}
