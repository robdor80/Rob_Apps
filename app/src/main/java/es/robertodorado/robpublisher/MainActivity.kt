package es.robertodorado.robpublisher

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Evita capturas y previsualizaciones del token en recientes.
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()
        setContent { RobStyle { PublisherApp() } }
    }
}

@Composable
fun PublisherApp(vm: PublisherViewModel = viewModel()) {
    BackHandler(vm.screen != "Inicio") { vm.screen = "Inicio" }
    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(selected = vm.screen != "Ajustes", onClick = { vm.screen = "Inicio" },
                    icon = { Text("⌂") }, label = { Text("Inicio") })
                NavigationBarItem(selected = vm.screen == "Ajustes", onClick = { vm.screen = "Ajustes" },
                    icon = { Text("⚙") }, label = { Text("Ajustes") })
            }
        }
    ) { insets ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(insets), contentAlignment = Alignment.TopCenter) {
            val wide = maxWidth >= 700.dp
            Column(Modifier.widthIn(max = 1040.dp).fillMaxWidth().padding(horizontal = if (wide) 30.dp else 20.dp)) {
                Text("RobPublisher", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.padding(top = 20.dp))
                Text(if (vm.screen == "Inicio") "Tus aplicaciones, listas para publicar" else vm.screen,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 16.dp))
                if (vm.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                vm.message?.let { message ->
                    Card(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        Column(Modifier.padding(12.dp)) {
                            Text(message)
                            TextButton(onClick = vm::dismissMessage) { Text("Cerrar aviso") }
                        }
                    }
                }
                when (vm.screen) {
                    "Ajustes" -> Settings(vm)
                    "Aplicación" -> ApplicationDetails(vm)
                    else -> Home(vm)
                }
            }
        }
    }
}

@Composable
private fun Home(vm: PublisherViewModel) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Panel {
                Text("Conexión con GitHub", style = MaterialTheme.typography.titleLarge)
                Text(if (vm.login != null) "● Conectado como ${vm.login}" else if (vm.hasToken) "● Conexión pendiente de comprobar" else "● Sin conexión configurada",
                    color = if (vm.login != null) RobSuccess else RobWarning)
                TextButton(onClick = { vm.screen = "Ajustes" }) { Text("Configurar conexión") }
            }
        }
        item {
            Text("Aplicaciones (${vm.configured.size})", style = MaterialTheme.typography.titleLarge)
            Button(onClick = { vm.screen = "Ajustes"; if (vm.hasToken && !vm.busy) vm.loadRepositories() }) {
                Text("Añadir aplicación")
            }
        }
        if (vm.configured.isEmpty()) item { Text("Todavía no has añadido aplicaciones. Configura GitHub y elige sus repositorios en Ajustes.") }
        items(vm.configured, key = { it }) { name ->
            Card(onClick = { vm.open(name) }, enabled = !vm.busy, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(name.substringAfter('/'), style = MaterialTheme.typography.titleLarge)
                    Text(name)
                    Text("● Configurada · estado pendiente de consultar", color = RobWarning)
                    Text("Gestionar publicación →", color = MaterialTheme.colorScheme.secondary)
                }
            }
        }
    }
}

@Composable
private fun ApplicationDetails(vm: PublisherViewModel) {
    val uri = LocalUriHandler.current
    LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { TextButton(onClick = { vm.screen = "Inicio" }) { Text("← Volver a Inicio") } }
        item {
            Panel {
                Text(vm.selected?.substringAfter('/') ?: "Aplicación", style = MaterialTheme.typography.titleLarge)
                Text(vm.selected.orEmpty())
                val repo = vm.repository
                if (repo != null) {
                    Text("${if (repo.private) "Privado" else "Público"} · ${if (repo.archived) "Archivado" else "Activo"}")
                    Text("Rama predeterminada: ${repo.branch}")
                } else Text("Estado del repositorio sin comprobar", color = RobWarning)
                if (vm.detailsLoaded) {
                    Text(vm.release?.let { "Última versión publicada: ${it.tag}\nFecha: ${it.publishedAt}" }
                        ?: "Sin Release estable publicada.")
                } else Text("Última versión pendiente de consultar.")
                OutlinedButton(onClick = vm::refresh, enabled = vm.hasToken && !vm.busy) { Text("Actualizar información") }
            }
        }
        item {
            Panel {
                Text("Publicación", style = MaterialTheme.typography.titleLarge)
                Text("Disponible en RP-02. Estos botones solo muestran un aviso.")
                Button(onClick = vm::publicationPending) { Text("Publicar primera versión") }
                OutlinedButton(onClick = vm::publicationPending) { Text("Publicar actualización") }
                OutlinedButton(onClick = { uri.openUri("https://github.com/${vm.selected}/actions") }) { Text("Abrir GitHub Actions") }
            }
        }
    }
}

@Composable
private fun Settings(vm: PublisherViewModel) {
    // Nunca se usa rememberSaveable para el token ni se copia al estado persistido.
    var candidate by remember { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf("") }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Panel {
                Text("Conexión GitHub", style = MaterialTheme.typography.titleLarge)
                Text(vm.login?.let { "Conectado como $it" } ?: if (vm.hasToken) "Token guardado; comprueba la conexión." else "Introduce un token personal de GitHub.")
                Text("Para RP-01: acceso de lectura a metadatos y contenido de los repositorios elegidos. No se necesitan permisos de escritura ni Actions.")
                OutlinedTextField(value = candidate, onValueChange = { candidate = it },
                    label = { Text("Token personal") }, singleLine = true, enabled = !vm.busy,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
                Text("Se guarda cifrado en este dispositivo con Android Keystore.")
                Button(onClick = { vm.connect(candidate); candidate = "" }, enabled = candidate.isNotBlank() && !vm.busy) { Text("Guardar y comprobar") }
                OutlinedButton(onClick = vm::checkConnection, enabled = vm.hasToken && !vm.busy) { Text("Comprobar conexión") }
                TextButton(onClick = { candidate = ""; vm.disconnect() }, enabled = !vm.busy) { Text("Desconectar y borrar token") }
            }
        }
        item { Text("Aplicaciones añadidas", style = MaterialTheme.typography.titleLarge) }
        if (vm.configured.isEmpty()) item { Text("No hay repositorios añadidos.") }
        items(vm.configured, key = { "saved:$it" }) { name ->
            Panel {
                Text(name)
                TextButton(onClick = { vm.remove(name) }, enabled = !vm.busy) { Text("Eliminar de RobPublisher") }
            }
        }
        item {
            Panel {
                Text("Repositorios accesibles", style = MaterialTheme.typography.titleLarge)
                Button(onClick = vm::loadRepositories, enabled = vm.hasToken && !vm.busy) { Text("Consultar repositorios") }
                OutlinedTextField(value = filter, onValueChange = { filter = it }, label = { Text("Filtrar por nombre") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("${vm.accessible.size} repositorios consultados")
            }
        }
        items(vm.accessible.filter { it.fullName.contains(filter, ignoreCase = true) }, key = { "available:${it.fullName}" }) { repo ->
            Panel {
                Text(repo.fullName)
                Text(if (repo.private) "Privado" else "Público", color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick = { vm.add(repo.fullName) }, enabled = repo.fullName !in vm.configured && !vm.busy) {
                    Text(if (repo.fullName in vm.configured) "Ya añadida" else "Añadir aplicación")
                }
            }
        }
    }
}

@Composable
private fun Panel(content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}
