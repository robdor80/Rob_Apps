package es.robertodorado.robpublisher

import android.os.Bundle
import android.view.WindowManager
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
import androidx.compose.ui.platform.LocalUriHandler
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
        // Evita capturas y previsualizaciones del token en recientes.
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()
        setContent { RobStyle { PublisherApp() } }
    }
}

@Composable
fun PublisherApp(vm: PublisherViewModel = viewModel()) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val tracking = vm.publication
    LaunchedEffect(tracking?.repository, tracking?.operation, tracking?.runId, tracking?.watching, vm.hasToken) {
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
                TextButton(onClick = { vm.screen = "Ajustes" }) { Text("Configurar conexión") }
            }
        }
        item {
            Text("Aplicaciones (${vm.applicationNames.size})", style = MaterialTheme.typography.titleLarge)
            Button(onClick = vm::refreshApplications, enabled = vm.hasToken && !vm.busy) { Text("Actualizar") }
            Text("Descubrimiento automático en robdor80: topic robstore-app o configuración RS-05. Solo se muestran repositorios accesibles al token.")
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
    val uri = LocalUriHandler.current
    val list = rememberLazyListState()
    LaunchedEffect(vm.publicationForm, vm.publication?.repository, vm.publication?.operation) {
        if (vm.publicationForm || vm.publication?.repository == vm.selected) list.scrollToItem(3)
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
                Text("RS-05 prepara el APK en GitHub. Después podrás revisar el candidato y confirmar su publicación.")
                Button(onClick = vm::showPublicationForm, enabled = !vm.busy && vm.publication == null) { Text("Publicar primera versión") }
                OutlinedButton(onClick = vm::showPublicationForm, enabled = !vm.busy && vm.publication == null) { Text("Publicar actualización") }
                OutlinedButton(onClick = { uri.openUri("https://github.com/${vm.selected}/actions") }) { Text("Abrir GitHub Actions") }
            }
        }
        if (vm.publicationForm) item { PublicationForm(vm) }
        vm.publication?.let { session -> item { PublicationProgress(vm, session) } }
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
                Text("Token fine-grained de robdor80, limitado a los repositorios elegidos: Metadata y Contents de lectura; Actions de lectura y escritura. Las aprobaciones de despliegues se hacen en GitHub; la app consulta su espera mediante Actions. No se necesitan permisos de Deployments, escritura en Contents, Secrets ni Administration.")
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
private fun PublicationForm(vm: PublisherViewModel) {
    Panel {
        Text("Preparar candidato RS-05", style = MaterialTheme.typography.titleLarge)
        Text("Workflow disponible y habilitado. Fuente: main · ${vm.plan?.source?.take(12)}")
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = vm.channel == "release", onClick = { vm.chooseChannel("release") }, enabled = !vm.busy)
            Text("Release")
            RadioButton(selected = vm.channel == "dev", onClick = { vm.chooseChannel("dev") }, enabled = !vm.busy)
            Text("DEV")
        }
        OutlinedTextField(value = vm.tag, onValueChange = { vm.tag = it }, singleLine = true,
            label = { Text("Etiqueta de versión") }, enabled = !vm.busy, modifier = Modifier.fillMaxWidth())
        Text("La etiqueta debe corresponder a la versión del APK en main, con versionCode creciente. La propuesta usa las versiones literales del código; no modifica el repositorio.")
        Text("El repositorio necesita el alta personal RS-05, notas aprobadas y CI de main correcta. GitHub conservará sus aprobaciones de firma y publicación.")
        Button(onClick = vm::prepare, enabled = !vm.busy && vm.tag.isNotBlank()) { Text("Preparar candidato") }
        TextButton(onClick = vm::closePublicationForm, enabled = !vm.busy) { Text("Cancelar preparación") }
    }

}

@Composable
private fun PublicationProgress(vm: PublisherViewModel, session: PublicationSession) {
    val uri = LocalUriHandler.current
    var confirmPublish by remember { mutableStateOf(false) }
    var confirmClose by remember { mutableStateOf(false) }
    val success = session.phase in setOf("preparado", "publicado")
    Panel {
        Text("Seguimiento RS-05", style = MaterialTheme.typography.titleLarge)
        Text("${session.repository} · ${if (session.channel == "release") "Release" else "DEV"} · ${session.tag}")
        Text("${if (success) "● Correcto" else "● ${session.phase.replaceFirstChar { it.uppercase() }}"} · ${session.operation}",
            color = if (success) RobSuccess else if (session.phase == "error") MaterialTheme.colorScheme.error else RobWarning)
        Text(session.detail)
        Text("Commit: ${session.source.take(12)} · Run: ${if (session.runId > 0) session.runId else "buscando"}")
        session.candidate?.let { candidate ->
            Text("APK: ${candidate.asset}\nPaquete: ${candidate.packageName}\nVersión: ${candidate.version} (${candidate.code})")
            Text("SHA-256 oficial: ${candidate.sha256}", style = MaterialTheme.typography.bodySmall)
            Text("Huella pública de firma: ${candidate.certificate}", style = MaterialTheme.typography.bodySmall)
        }
        if (session.operation == "prepare" && session.phase == "preparado") {
            Button(onClick = { confirmPublish = true }, enabled = !vm.busy && vm.hasToken) { Text("Revisar y publicar candidato") }
        }
        OutlinedButton(onClick = vm::updatePublication, enabled = !vm.busy && vm.hasToken) { Text("Actualizar estado") }
        OutlinedButton(onClick = { uri.openUri(session.runUrl) }) {
            Text(if (session.phase == "requiere aprobación") "Aprobar entorno en GitHub" else "Ver ejecución en GitHub")
        }
        if (session.phase == "publicado") {
            Button(onClick = { uri.openUri(session.releaseUrl) }) { Text("Abrir GitHub Releases") }
        }
        TextButton(onClick = { confirmClose = true }, enabled = !vm.busy) { Text("Cerrar seguimiento") }
        Text("El seguimiento se guarda en este dispositivo. Actualiza automáticamente mientras la app está visible; al volver de GitHub se retoma.", style = MaterialTheme.typography.bodySmall)
    }
    if (confirmPublish) AlertDialog(onDismissRequest = { confirmPublish = false },
        title = { Text("Confirmar publicación del candidato") },
        text = { Text("${session.repository}\n${session.channel} · ${session.tag}\n${session.candidate?.version} (${session.candidate?.code})\nSHA-256: ${session.candidate?.sha256}\n\nAl confirmar autorizas PUBLICAR ${session.tag}. Se enviarán automáticamente el run y el hash de este candidato. GitHub aún exigirá aprobar android-publish.") },
        confirmButton = { TextButton(onClick = { confirmPublish = false; vm.publishConfirmed() }, enabled = !vm.busy) { Text("PUBLICAR ${session.tag}") } },
        dismissButton = { TextButton(onClick = { confirmPublish = false }) { Text("Cancelar") } })
    if (confirmClose) AlertDialog(onDismissRequest = { confirmClose = false },
        title = { Text("Cerrar seguimiento local") },
        text = { Text("La ejecución puede continuar en GitHub. Cerrar el seguimiento no cancela ni revoca nada. Revisa su resultado allí antes de iniciar otra publicación.") },
        confirmButton = { TextButton(onClick = { confirmClose = false; vm.closePublication() }) { Text("Cerrar seguimiento") } },
        dismissButton = { TextButton(onClick = { confirmClose = false }) { Text("Mantener seguimiento") } })
}
