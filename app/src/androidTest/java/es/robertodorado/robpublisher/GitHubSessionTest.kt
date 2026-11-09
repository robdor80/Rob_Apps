package es.robertodorado.robpublisher

import android.app.Application
import androidx.lifecycle.ViewModelStore
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class GitHubSessionTest {
    @get:Rule val compose = createComposeRule()
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
    private val store get() = LocalStore(app)
    @Before fun prepare() { store.disconnect() }
    private val models = ViewModelStore()
    @After fun clean() { compose.runOnUiThread { models.clear() }; store.disconnect() }
    private fun saveSession() = store.saveOAuth(OAuthCredentials("test-access", "", Long.MAX_VALUE,
        Long.MAX_VALUE, "robdor80"))

    @Test fun previousOfficialEncryptedSessionAndPreferencesRemainReadable() {
        // Reproduce el formato de LocalStore de la Release 1.0.2, sin usar saveOAuth nuevo.
        store.saveToken("legacy-token") // Inicializa el alias histórico de Android Keystore.
        val key = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            .getKey("robpublisher.github", null)
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(javax.crypto.Cipher.ENCRYPT_MODE, key)
        }
        val oldJson = org.json.JSONObject().put("access", "old-access").put("refresh", "old-refresh")
            .put("accessExpiry", Long.MAX_VALUE).put("refreshExpiry", Long.MAX_VALUE).put("login", "robdor80")
        val encrypted = cipher.doFinal(oldJson.toString().toByteArray(Charsets.UTF_8))
        val prefs = app.getSharedPreferences("robpublisher", 0)
        assertTrue(prefs.edit()
            .putString("oauth", android.util.Base64.encodeToString(encrypted, android.util.Base64.NO_WRAP))
            .putString("oauth_iv", android.util.Base64.encodeToString(cipher.iv, android.util.Base64.NO_WRAP))
            .putStringSet("repositories", setOf("robdor80/Rob_Apps"))
            .putString("oauth_client_id", ROBGIT_RD_CLIENT_ID).commit())
        val reopened = LocalStore(app)
        assertEquals("old-access", reopened.oauth()?.accessToken)
        assertEquals("old-refresh", reopened.oauth()?.refreshToken)
        assertEquals("robdor80", reopened.oauth()?.login)
        assertEquals("legacy-token", reopened.token())
        assertEquals(listOf("robdor80/Rob_Apps"), reopened.repositories())
        assertEquals(ROBGIT_RD_CLIENT_ID, reopened.oauthClientId())
        assertTrue(java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            .containsAlias("robpublisher.github"))
        // No dejar preferencias de esta prueba a las siguientes.
        assertTrue(prefs.edit().remove("repositories").remove("oauth_client_id").commit())
    }

    @Test fun savedSessionRestoresWithoutDeviceFlowAndLogoutRemovesIt() {
        saveSession()
        var deviceRequests = 0
        lateinit var vm: PublisherViewModel
        compose.runOnUiThread {
            vm = PublisherViewModel(app, GitHubService(GitHubTransport { _, path, _, _ ->
                ApiResponse(200, if (path == "/user") """{"login":"robdor80"}""" else "[]")
            }), GitHubOAuth(transport = OAuthTransport { _, _ -> deviceRequests++; error("No Device Flow expected") }))
        }
        compose.runOnUiThread { models.put("session-test", vm) }
        compose.setContent { RobStyle { PublisherApp(vm) } }
        compose.waitUntil { vm.hasToken && vm.login == "robdor80" && !vm.busy }
        assertEquals(0, deviceRequests)
        compose.runOnUiThread { vm.disconnect() }
        compose.waitUntil { !vm.hasToken && !vm.busy }
        assertNull(store.oauth()); assertNull(store.pendingAuth()); assertNull(vm.login)
    }

    @Test fun transientSessionValidationErrorPreservesCredentialsAndIdentity() {
        saveSession()
        lateinit var vm: PublisherViewModel
        compose.runOnUiThread {
            vm = PublisherViewModel(app, GitHubService(GitHubTransport { _, _, _, _ -> ApiResponse(503, "") }), GitHubOAuth())
        }
        compose.runOnUiThread { models.put("session-test", vm) }
        compose.setContent { RobStyle { PublisherApp(vm) } }
        compose.waitUntil { !vm.busy }
        assertTrue(vm.hasToken); assertEquals("robdor80", vm.login)
        assertEquals("test-access", store.oauth()?.accessToken)
    }

    @Test fun transientRefreshFailureKeepsSessionAndInvalidRefreshRemovesIt() {
        for (invalid in listOf(false, true)) {
            store.saveOAuth(OAuthCredentials("test-access", "test-refresh", System.currentTimeMillis() + 60_000,
                System.currentTimeMillis() + 86_400_000, "robdor80"))
            lateinit var vm: PublisherViewModel
            compose.runOnUiThread {
                vm = PublisherViewModel(app, GitHubService(GitHubTransport { _, _, _, _ -> error("Refresh failed before /user") }),
                    GitHubOAuth(transport = OAuthTransport { _, _ ->
                        if (invalid) org.json.JSONObject("""{"error":"bad_refresh_token"}""")
                        else throw OAuthFailure("Temporal", retryable = true)
                    }))
                models.put("refresh-test", vm)
            }
            compose.waitUntil { !vm.busy }
            assertEquals(!invalid, vm.hasToken)
            assertEquals(!invalid, store.oauth() != null)
        }
    }

    @Test fun confirmedUnauthorizedSessionIsRemoved() {
        saveSession()
        lateinit var vm: PublisherViewModel
        compose.runOnUiThread {
            vm = PublisherViewModel(app, GitHubService(GitHubTransport { _, _, _, _ -> ApiResponse(401, "") }), GitHubOAuth())
        }
        compose.runOnUiThread { models.put("session-test", vm) }
        compose.setContent { RobStyle { PublisherApp(vm) } }
        compose.waitUntil { !vm.busy }
        assertFalse(vm.hasToken); assertNull(vm.login); assertNull(store.oauth())
    }
}
