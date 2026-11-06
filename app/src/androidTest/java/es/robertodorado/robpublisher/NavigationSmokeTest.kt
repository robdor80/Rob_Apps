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
        compose.onNodeWithText("Aplicaciones (1)").assertIsDisplayed()
        clickInList("Rob_Apps")
        clickInList("Publicar actualización")
        compose.onNodeWithText("Configura la conexión con GitHub una sola vez en Ajustes.").assertIsDisplayed()
        compose.onNodeWithText("Cerrar aviso").performClick()
        clickInList("Volver a Inicio", substring = true)
        compose.onNodeWithText("Aplicaciones (1)").assertIsDisplayed()
        compose.onNodeWithText("Ajustes").performClick()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Client ID público de RobGit RD"))
        compose.onNodeWithText("Client ID público de RobGit RD").assertIsDisplayed()
        clickInList("Eliminar de RobPublisher")
        compose.onNodeWithText("Inicio").performClick()
        compose.onNodeWithText("Aplicaciones (0)").assertIsDisplayed()
    }
}
