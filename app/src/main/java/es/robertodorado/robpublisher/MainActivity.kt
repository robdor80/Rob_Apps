package es.robertodorado.robpublisher

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
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
            // Inicio ya no necesita una barra de navegación que repita la pantalla actual.
            if (vm.screen != "Inicio") {
                NavigationBar {
                    NavigationBarItem(selected = vm.screen != "Ajustes", onClick = { vm.screen = "Inicio" },
                        icon = { Text("⌂") }, label = { Text("Inicio") })
                    NavigationBarItem(selected = vm.screen == "Ajustes", onClick = { vm.screen = "Ajustes" },
                        icon = { Text("⚙") }, label = { Text("Ajustes") })
                }
            }
        }
    ) { insets ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(insets), contentAlignment = Alignment.TopCenter) {
            val wide = maxWidth >= 700.dp
            Column(Modifier.widthIn(max = 1040.dp).fillMaxWidth().padding(horizontal = if (wide) 30.dp else 20.dp)) {
                if (vm.screen == "Inicio") {
                    Row(Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 22.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text("RobPublisher", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.weight(1f))
                        Box(Modifier.size(13.dp)
                            .background(if (vm.login != null) RobSuccess else MaterialTheme.colorScheme.error, CircleShape)
                            .semantics {
                                contentDescription = if (vm.login != null) "GitHub conectado" else "GitHub desconectado"
                            })
                        Spacer(Modifier.width(12.dp))
                        IconButton(onClick = { vm.screen = "Ajustes" },
                            modifier = Modifier.semantics { contentDescription = "Abrir ajustes" }) {
                            Text("⚙", fontSize = 23.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                } else {
                    Text("RobPublisher", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.padding(top = 20.dp))
                    Text(vm.screen, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 16.dp))
                }
                if (vm.busy && vm.screen != "Inicio") LinearProgressIndicator(Modifier.fillMaxWidth())
                vm.message?.takeUnless { vm.screen == "Inicio" && it == "Conexión con GitHub comprobada." }?.let { message ->
                    if (vm.screen == "Inicio") {
                        Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(message, modifier = Modifier.weight(1f),
                                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = vm::dismissMessage) { Text("×") }
                        }
                    } else {
                        Card(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                            Column(Modifier.padding(12.dp)) {
                                Text(message)
                                TextButton(onClick = vm::dismissMessage) { Text("Cerrar aviso") }
                            }
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

private fun displayRelease(tag: String): String {
    val match = Regex("^v?([0-9]+(?:\\.[0-9]+)+)[-_]([0-9]+)$").matchEntire(tag)
    return if (match != null) "${match.groupValues[1]} (${match.groupValues[2]})" else tag.removePrefix("v")
}

private fun displayDate(date: String): String {
    val parts = date.split('-')
    return if (parts.size == 3 && parts[0].length == 4) "${parts[2]}/${parts[1]}/${parts[0]}"
        else date.ifBlank { "No disponible" }
}

@Composable
private fun RepositoryIcon(name: String, vm: PublisherViewModel) {
    LaunchedEffect(name, vm.hasToken) { if (vm.hasToken) vm.loadIcon(name) }
    val bitmap = vm.icons[name]
    Box(Modifier.size(66.dp).clip(RoundedCornerShape(14.dp))
        .background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        if (bitmap != null) {
            Image(bitmap = bitmap, contentDescription = null, contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().padding(3.dp))
        } else {
            Text(name.substringAfter('/').firstOrNull()?.uppercase() ?: "•",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun Home(vm: PublisherViewModel) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(0.dp),
        contentPadding = PaddingValues(bottom = 24.dp)) {
        vm.publication?.let { session -> item {
            Row(Modifier.fillMaxWidth().padding(bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("${session.repository.substringAfter('/')} · ${session.phase}",
                    modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.secondary)
                TextButton(onClick = { vm.open(session.repository) }, enabled = !vm.busy) { Text("Ver") }
            }
        } }
        item {
            Row(Modifier.fillMaxWidth().padding(bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("${vm.applicationNames.size} aplicaciones",
                    style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                if (vm.busy) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                else IconButton(onClick = vm::refreshApplications, enabled = vm.hasToken,
                    modifier = Modifier.semantics { contentDescription = "Actualizar aplicaciones" }) {
                    Text("↻", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 24.sp)
                }
            }
        }
        if (vm.applicationNames.isEmpty()) item {
            Text(when {
                !vm.hasToken -> "Conecta GitHub desde Ajustes."
                vm.busy -> "Buscando aplicaciones…"
                vm.discoveryComplete -> "No se encontraron aplicaciones."
                else -> "No se pudo completar el descubrimiento."
            }, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        items(vm.applicationNames, key = { it }) { name ->
            val summary = vm.applications.firstOrNull { it.repository.fullName == name }
            Row(
                modifier = Modifier.fillMaxWidth()
                    .clickable(enabled = !vm.busy) { vm.open(name) }
                    .padding(vertical = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.Top
            ) {
                RepositoryIcon(name, vm)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(name.substringAfter('/'), style = MaterialTheme.typography.titleMedium)
                    when {
                        summary?.releaseChecked == true && summary.release != null -> {
                            Text("Release ${displayRelease(summary.release.tag)}",
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("Fecha ${displayDate(summary.release.publishedAt)}",
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        summary?.releaseChecked == true -> Text("Sin Release estable",
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        else -> Text("Release pendiente de consultar",
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(
                        when {
                            summary == null -> "Pendiente de comprobar"
                            summary.integrationReady -> "Publicable"
                            else -> summary.status.removePrefix("Preparación pendiente: ").removeSuffix(".")
                        },
                        color = when {
                            summary == null -> MaterialTheme.colorScheme.onSurfaceVariant
                            summary.integrationReady -> RobSuccess
                            else -> MaterialTheme.colorScheme.error
                        },
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.23f))
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
                Text(vm.versionPreflight?.message ?: "Versión pendiente de comprobar.")
                val browser = androidx.compose.ui.platform.LocalUriHandler.current
                if (vm.versionPreflight?.allowed == false) {
                    Text("Preparar nueva versión: crea una rama desde main y una PR que incremente la versión evaluada por Gradle. No se modificará main desde esta pantalla. Para versiones dinámicas, valida el resultado en CI. Si tu sesión no permite crear PR, utiliza GitHub con tus permisos actuales; no necesitas ampliar OAuth.")
                    TextButton(onClick = { browser.openUri("https://github.com/${vm.selected}/compare") }) { Text("Preparar nueva versión") }
                }
                Button(onClick = vm::distribute, enabled = !vm.busy && vm.publication?.watching != true && vm.versionPreflight?.allowed == true) { Text("Publicar actualización") }
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
        val browser = androidx.compose.ui.platform.LocalUriHandler.current
        if (session.runId > 0) {
            TextButton(onClick = { browser.openUri("https://github.com/${session.repository}/actions/runs/${session.runId}") }) { Text("Abrir ejecución y detalle técnico en GitHub") }
        }
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
