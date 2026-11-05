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

    @Test fun homeDetailsSettingsAndBack() {
        compose.onNodeWithText("Aplicaciones (1)").assertIsDisplayed()
        compose.onNodeWithText("Rob_Apps").performClick()
        compose.onNodeWithText("Publicar primera versión").performScrollTo().performClick()
        compose.onNodeWithText("La publicación estará disponible en RP-02. No se ha ejecutado ninguna acción.").assertIsDisplayed()
        compose.onNodeWithText("Cerrar aviso").performClick()
        compose.onNodeWithText("Volver a Inicio", substring = true).performScrollTo().performClick()
        compose.onNodeWithText("Aplicaciones (1)").assertIsDisplayed()
        compose.onNodeWithText("Ajustes").performClick()
        compose.onNodeWithText("Token personal").assertIsDisplayed()
        compose.onNodeWithText("Eliminar de RobPublisher").performScrollTo().performClick()
        compose.onNodeWithText("Inicio").performClick()
        compose.onNodeWithText("Aplicaciones (0)").assertIsDisplayed()
    }
}
