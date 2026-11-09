package es.robertodorado.robpublisher

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.awaitCancellation

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { RobStyle { PublisherApp() } }
    }
}

@Composable
fun PublisherApp(vm: PublisherViewModel = viewModel()) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val tracking = vm.publication
    LaunchedEffect(tracking?.repository, tracking?.runId, tracking?.watching, vm.hasToken) {
        if (tracking?.watching == true && vm.hasToken) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) { vm.updatePublication(); delay(10_000) }
            }
        }
    }
    LaunchedEffect(lifecycle, vm.hasToken, vm.screen) {
        if (vm.hasToken && vm.screen == "Inicio") {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (vm.busy) delay(250)
                vm.refreshApplications()
                awaitCancellation()
            }
        }
    }
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
        vm.publication?.let { session -> item {
            Panel {
                Text("Publicación en seguimiento", style = MaterialTheme.typography.titleLarge)
                Text("${session.repository} · ${session.phase}")
                Button(onClick = { vm.open(session.repository) }, enabled = !vm.busy) { Text("Continuar seguimiento") }
            }
        } }
        item {
            Panel {
                Text("Conexión con GitHub", style = MaterialTheme.typography.titleLarge)
                Text(if (vm.login != null) "● Conectado como ${vm.login}" else if (vm.hasToken) "● Conexión pendiente de comprobar" else "● Sin conexión configurada",
                    color = if (vm.login != null) RobSuccess else RobWarning)
                TextButton(onClick = { vm.screen = "Ajustes" }) { Text(if (vm.authorizing) "Continuar autorización" else "Configurar conexión") }
            }
        }
        item {
            Text("Aplicaciones (${vm.applicationNames.size})", style = MaterialTheme.typography.titleLarge)
            Button(onClick = vm::refreshApplications, enabled = vm.hasToken && !vm.busy) { Text("Actualizar") }
            Text("Descubrimiento automático en robdor80: topic robstore-app o configuración RS-05. Se muestran los repositorios autorizados en GitHub.")
            if (!vm.discoveryComplete && !vm.busy && vm.applications.isNotEmpty()) Text("Datos de la consulta anterior. Pulsa Actualizar para reintentar.", color = RobWarning)
            TextButton(onClick = { vm.screen = "Ajustes"; if (vm.hasToken && !vm.busy) vm.loadRepositories() }) {
                Text("Añadir aplicación manualmente")
            }
        }
        if (vm.applicationNames.isEmpty()) item {
            Text(if (!vm.hasToken) "Configura GitHub para descubrir tus aplicaciones automáticamente. También puedes añadir repositorios en Ajustes."
                else if (vm.busy) "Buscando aplicaciones…"
                else if (vm.discoveryComplete) "No se encontraron aplicaciones con robstore-app o configuración RS-05. Puedes añadir una manualmente."
                else "Pulsa Actualizar para descubrir tus aplicaciones.")
        }
        items(vm.applicationNames, key = { it }) { name ->
            Card(onClick = { vm.open(name) }, enabled = !vm.busy, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(name.substringAfter('/'), style = MaterialTheme.typography.titleLarge)
                    Text(name)
                    val summary = vm.applications.firstOrNull { it.repository.fullName == name }
                    Text(when {
                        summary == null || !summary.releaseChecked -> "Última versión pendiente de consultar"
                        summary.release != null -> "Última Release estable: ${summary.release.tag} · ${summary.release.publishedAt}"
                        else -> "Sin Release estable publicada"
                    })
                    Text("● ${summary?.status ?: "Estado RS-05 pendiente de comprobar"}",
                        color = if (summary?.integrationReady == true) RobSuccess else RobWarning)
                    if (summary != null) Text(if (summary.discovered) "Descubierta automáticamente" else "Alta manual",
                        style = MaterialTheme.typography.bodySmall)
                    Text("Gestionar publicación →", color = MaterialTheme.colorScheme.secondary)
                }
            }
        }
    }
}

@Composable
private fun ApplicationDetails(vm: PublisherViewModel) {
    val list = rememberLazyListState()
    var showGitHubHelp by rememberSaveable(vm.selected) { mutableStateOf(false) }
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    LaunchedEffect(vm.publication?.repository) {
        if (vm.publication?.repository == vm.selected) list.scrollToItem(4)
    }
    LazyColumn(state = list, verticalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { TextButton(onClick = { vm.screen = "Inicio" }) { Text("← Volver a Inicio") } }
        item {
            Panel {
                Text(vm.selected?.substringAfter('/') ?: "Aplicación", style = MaterialTheme.typography.titleLarge)
                Text(vm.selected.orEmpty())
                val repo = vm.repository
                if (repo != null) {
                    Text("${if (repo.isPrivate) "Privado" else "Público"} · ${if (repo.archived) "Archivado" else "Activo"}")
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
                Text("GitHub compila, firma y publica automáticamente. Puedes seguir el progreso aquí.")
                Button(onClick = vm::distribute, enabled = !vm.busy && vm.publication?.watching != true) { Text("Publicar actualización") }
            }
        }
        item {
            Panel {
                Text("Acceso propio a GitHub (opcional)", style = MaterialTheme.typography.titleMedium)
                Text("Para publicar esta aplicación NO necesitas añadir ningún Client ID: RobPublisher ya se encarga.")
                Text("Si la propia aplicación va a consultar o modificar GitHub, recuerda integrar el inicio de sesión OAuth de RobGit RD con sus permisos autorizados.")
                OutlinedButton(onClick = { showGitHubHelp = !showGitHubHelp }) {
                    Text(if (showGitHubHelp) "Ocultar datos de conexión" else "Ver conexión de RobGit RD")
                }
                if (showGitHubHelp) {
                    Text("Client ID público: $ROBGIT_RD_CLIENT_ID")
                    OutlinedButton(onClick = {
                        clipboard.setText(androidx.compose.ui.text.AnnotatedString(ROBGIT_RD_CLIENT_ID))
                    }) { Text("Copiar Client ID") }
                    Text("El Client ID por sí solo no conecta una app: también debe implementar OAuth y solicitar autorización. No copies tokens ni secretos.")
                }
            }
        }
        vm.publication?.let { session -> item { PublicationProgress(vm, session) } }
    }
}

@Composable
private fun Settings(vm: PublisherViewModel) {
    // El Client ID no es un secreto. Los tokens OAuth nunca entran en el estado Compose.
    val browser = androidx.compose.ui.platform.LocalUriHandler.current
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    var filter by rememberSaveable { mutableStateOf("") }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Panel {
                Text("Conexión GitHub", style = MaterialTheme.typography.titleLarge)
                Text(vm.login?.let { "Conectado como $it" } ?: if (vm.hasToken) "Conexión anterior disponible." else "Conecta tu cuenta GitHub una sola vez.")
                Text("Usamos la integración RobGit RD: acceso a tus repositorios, renovación automática y ninguna necesidad de generar tokens personales.")
                Text("RobGit RD está configurado. No necesitas copiar identificadores ni tokens.")
                Button(onClick = vm::beginAuthorization, enabled = !vm.busy && !vm.authorizing &&
                    GitHubOAuth.validClientId(vm.oauthClientId)) { Text("Conectar con GitHub") }
                vm.pendingAuthorization?.let { pending ->
                    Text("Autoriza RobPublisher con GitHub. Código de un solo uso:")
                    Text(pending.userCode, style = MaterialTheme.typography.headlineMedium)
                    OutlinedButton(onClick = { clipboard.setText(androidx.compose.ui.text.AnnotatedString(pending.userCode)) }) {
                        Text("Copiar código")
                    }
                    Button(onClick = { browser.openUri(pending.verificationUri) }) {
                        Text("Abrir GitHub para autorizar")
                    }
                    Text(if (vm.validatingAuthorization) "Validando la cuenta de GitHub…" else "Esperando autorización")
                    Text("Tiempo restante: ${vm.authorizationRemainingSeconds / 60}:${(vm.authorizationRemainingSeconds % 60).toString().padStart(2, '0')}")
                }
                if (vm.authorizing) TextButton(onClick = vm::cancelAuthorization) { Text("Cancelar conexión") }
                Text("Las credenciales se guardan cifradas con Android Keystore y se renuevan automáticamente.")
                OutlinedButton(onClick = vm::checkConnection, enabled = vm.hasToken && !vm.busy) { Text("Comprobar conexión") }
                TextButton(onClick = vm::disconnect, enabled = !vm.busy) { Text("Desconectar GitHub") }
            }
        }
        item { Text("Aplicaciones añadidas manualmente", style = MaterialTheme.typography.titleLarge) }
        item { Text("El alta manual es una alternativa. Las aplicaciones descubiertas automáticamente siguen apareciendo en Inicio aunque elimines su alta manual.") }
        if (vm.configured.isEmpty()) item { Text("No hay repositorios añadidos manualmente.") }
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
                Text(if (repo.isPrivate) "Privado" else "Público", color = MaterialTheme.colorScheme.onSurfaceVariant)
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

@Composable
private fun PublicationProgress(vm: PublisherViewModel, session: PublicationSession) {
    Panel {
        Text("Distribución", style = MaterialTheme.typography.titleLarge)
        Text(session.repository.substringAfter('/'))
        listOf(
            "guard" to "Preparación",
            "build" to "Compilación",
            "sign" to "Firma",
            "publish" to "Publicación",
        ).forEach { (step, label) ->
            val status = session.stages[step] ?: "pendiente"
            val symbol = when (status) {
                "completado" -> "✓"
                "en curso" -> "◉"
                "fallido" -> "✕"
                else -> "○"
            }
            val color = when (status) {
                "completado" -> RobSuccess
                "fallido" -> MaterialTheme.colorScheme.error
                "en curso" -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
            Text("$symbol $label · $status", color = color)
        }
        Text(session.detail, color = when (session.phase) {
            "publicado" -> RobSuccess
            "error" -> MaterialTheme.colorScheme.error
            else -> MaterialTheme.colorScheme.onSurface
        })
        if (session.watching) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text("Estados reales de GitHub, consultados cada 10 segundos.")
            Text("Puedes cerrar la aplicación. El seguimiento se retomará al volver.")
        } else {
            TextButton(onClick = vm::closePublication, enabled = !vm.busy) { Text("Cerrar") }
        }
    }
}
