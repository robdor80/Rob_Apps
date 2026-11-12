package es.robertodorado.robpublisher

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class NavigationSmokeTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before fun prepare() {
        LocalStore(context).saveRepositories(listOf("robdor80/Rob_Apps"))
        // Sin credenciales ni llamadas de red.
        compose.setContent { RobStyle { PublisherApp() } }
    }
    @After fun clean() { LocalStore(context).saveRepositories(emptyList()) }

    private fun clickInList(text: String, substring: Boolean = false) {
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(text, substring = substring))
        compose.onNodeWithText(text, substring = substring).performClick()
    }

    @Test fun homeDetailsSettingsAndBack() {
        compose.onNodeWithText("1 aplicaciones").assertIsDisplayed()
        clickInList("Rob_Apps")
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Publicar actualización"))
        compose.onNodeWithText("Publicar actualización").assertIsNotEnabled()
        compose.onNodeWithText("Versión pendiente de comprobar.").assertExists()
        clickInList("Volver a Inicio", substring = true)
        compose.onNodeWithText("1 aplicaciones").assertIsDisplayed()
        compose.onNodeWithContentDescription("Abrir ajustes").performClick()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Conectar con GitHub"))
        compose.onNodeWithText("Conectar con GitHub").assertIsDisplayed()
        clickInList("Eliminar de RobPublisher")
        compose.onNodeWithText("Inicio").performClick()
        compose.onNodeWithText("0 aplicaciones").assertIsDisplayed()
    }
}
