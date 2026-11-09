package es.robertodorado.robpublisher

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Android real: lifecycle, navegación Compose y cifrado con Keystore; sin GitHub de producción. */
class DeviceAuthLifecycleTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private var scenario: ActivityScenario<MainActivity>? = null
    private val store get() = LocalStore(context)

    @Before fun prepare() { store.disconnect() }
    @After fun clean() { scenario?.close(); store.disconnect() }

    private fun seedAndLaunch() {
        val now = System.currentTimeMillis()
        // No endpoint real: ninguna consulta es debida durante estas pruebas de lifecycle.
        store.savePendingAuth(PendingDeviceAuth(DeviceAuthorization("private-device", "TEST-CODE",
            "https://github.com/login/device", now + 600_000, 300), nextPollAt = now + 300_000))
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }
    private fun assertCode() {
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("TEST-CODE"))
        compose.onNodeWithText("TEST-CODE").assertIsDisplayed()
        assertEquals("private-device", store.pendingAuth()?.authorization?.deviceCode)
    }

    @Test fun backgroundAndReturnRetainSameCode() {
        seedAndLaunch(); assertCode()
        scenario!!.moveToState(Lifecycle.State.CREATED)
        scenario!!.moveToState(Lifecycle.State.RESUMED)
        assertCode()
    }

    @Test fun activityRecreationRetainsCode() {
        seedAndLaunch(); assertCode(); scenario!!.recreate(); assertCode()
    }

    @Test fun newActivityAndViewModelRestoreEncryptedAttempt() {
        seedAndLaunch(); assertCode(); scenario!!.close()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        assertCode()
    }

    @Test fun navigatingAwayAndBackDoesNotStartNewAttempt() {
        seedAndLaunch(); assertCode()
        compose.onNodeWithText("Inicio").performClick()
        compose.onNodeWithText("Ajustes").performClick(); assertCode()
        compose.onNodeWithText("Conectar con GitHub").assertIsNotEnabled()
    }

    @Test fun voluntaryCancelRemovesEncryptedAttempt() {
        seedAndLaunch()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Cancelar conexión"))
        compose.onNodeWithText("Cancelar conexión").performClick()
        compose.waitForIdle()
        assertNull(store.pendingAuth())
        compose.onNodeWithText("TEST-CODE").assertDoesNotExist()
    }

    @Test fun credentialsAndPendingAreEncryptedRecoverableAndRemovedOnLogout() {
        val now = System.currentTimeMillis()
        val credentials = OAuthCredentials("test-secret-access", "test-secret-refresh", now + 3_600_000,
            now + 86_400_000, "robdor80")
        store.saveOAuth(credentials)
        store.savePendingAuth(PendingDeviceAuth(DeviceAuthorization("private-device", "TEST-CODE",
            "https://github.com/login/device", now + 600_000, 5), credentials = credentials,
            validationDeadline = now + 300_000))
        val raw = context.getSharedPreferences("robpublisher", 0).all.toString()
        for (secret in listOf("test-secret-access", "test-secret-refresh", "private-device", "TEST-CODE")) {
            assertFalse(raw.contains(secret))
        }
        val reopened = LocalStore(context)
        assertEquals("test-secret-access", reopened.oauth()?.accessToken)
        assertEquals("robdor80", reopened.oauth()?.login)
        assertEquals("test-secret-refresh", reopened.pendingAuth()?.credentials?.refreshToken)
        reopened.saveToken("test-legacy-access")
        reopened.completeAuth(credentials)
        assertNull(LocalStore(context).pendingAuth()); assertNull(LocalStore(context).token())
        assertEquals("test-secret-access", LocalStore(context).oauth()?.accessToken)
        reopened.disconnect()
        assertNull(LocalStore(context).oauth()); assertNull(LocalStore(context).pendingAuth())
    }
}
