package es.robertodorado.robpublisher

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class PublisherViewModel(application: Application) : AndroidViewModel(application) {
    private val store = LocalStore(application)
    private val github = GitHubService()
    private val distribution = DistributionService(github)
    private var token: String? = null
    var configured by mutableStateOf(store.repositories()); private set
    var accessible by mutableStateOf<List<Repository>>(emptyList()); private set
    var login by mutableStateOf<String?>(null); private set
    var hasToken by mutableStateOf(false); private set
    var busy by mutableStateOf(false); private set
    var message by mutableStateOf<String?>(null); private set
    var repository by mutableStateOf<Repository?>(null); private set
    var release by mutableStateOf<Release?>(null); private set
    var detailsLoaded by mutableStateOf(false); private set
    var selected by mutableStateOf<String?>(null); private set
    var plan by mutableStateOf<DistributionPlan?>(null); private set
    var publication by mutableStateOf<PublicationSession?>(null); private set
    var publicationForm by mutableStateOf(false); private set
    var channel by mutableStateOf("release"); private set
    var tag by mutableStateOf("")
    var screen by mutableStateOf("Inicio")
    init {
        try { publication = store.publication() }
        catch (_: Exception) { message = "No se pudo recuperar el seguimiento. Revisa Actions antes de iniciar otra operación." }

        try { token = store.token(); hasToken = token != null }
        catch (_: Exception) { message = "No se pudo leer el token protegido. Desconecta y vuelve a configurarlo." }
        if (hasToken) checkConnection()
    }
    private fun operation(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        message = null
        viewModelScope.launch {
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: PublicationError) { message = e.message }
            catch (e: GitHubException) {
                if (e.status == 401) { login = null; accessible = emptyList() }
                message = e.message
            }
            catch (_: java.io.IOException) { message = "No se pudo conectar con GitHub. Comprueba Internet y reintenta." }
            catch (_: Exception) { message = "No se pudo completar la operación. Revisa la configuración y reintenta." }
            finally { busy = false }
        }
    }
    fun connect(value: String) = operation {
        val candidate = value.trim()
        require(candidate.isNotEmpty())
        val user = github.user(candidate)
        store.saveToken(candidate)
        token = candidate; hasToken = true; login = user
        accessible = emptyList(); publicationForm = false; plan = null
        message = "Conexión comprobada y token guardado."
    }
    fun checkConnection() = operation {
        login = null
        login = github.user(requireToken())
        message = "Conexión con GitHub comprobada."
    }
    fun loadRepositories() = operation { accessible = github.repositories(requireToken()) }
    fun disconnect() = operation {
        store.disconnect(); token = null; hasToken = false; login = null
        publicationForm = false; plan = null
        accessible = emptyList(); repository = null; release = null; detailsLoaded = false
        message = "Desconectado. Se conserva la lista de aplicaciones."
    }
    fun add(name: String) {
        val updated = (configured + name).distinct().sorted()
        try { store.saveRepositories(updated); configured = updated; message = "Aplicación añadida." }
        catch (_: Exception) { message = "No se pudo guardar la lista de aplicaciones." }
    }
    fun remove(name: String) {
        val updated = configured - name
        try { store.saveRepositories(updated); configured = updated }
        catch (_: Exception) { message = "No se pudo guardar la lista de aplicaciones." }
    }
    fun open(name: String) {
        if (busy) return
        selected = name; screen = "Aplicación"; publicationForm = false; plan = null
        refresh()
    }
    fun refresh() = operation {
        repository = null; release = null; detailsLoaded = false
        val name = selected ?: return@operation
        repository = github.repository(name, requireToken())
        release = github.latestRelease(name, requireToken())
        detailsLoaded = true
    }
    fun showPublicationForm() {
        if (!hasToken) { message = "Configura y comprueba el token de GitHub en Ajustes antes de preparar una publicación."; return }
        if (publication != null) { message = "Hay un seguimiento guardado. Termínalo o ciérralo antes de preparar otro candidato."; return }
        operation {
            val name = selected ?: return@operation
            plan = distribution.plan(name, requireToken())
            channel = "release"; tag = plan!!.suggestion(channel)
            publicationForm = true
        }
    }
    fun chooseChannel(value: String) {
        if (busy) return
        channel = value; tag = plan?.suggestion(value).orEmpty()
    }
    fun closePublicationForm() { publicationForm = false }
    private fun save(session: PublicationSession?) {
        store.savePublication(session)
        publication = session
    }
    fun prepare() = operation {
        val current = plan ?: throw PublicationError("Comprueba primero el workflow.")
        demand(publication == null && current.repository == selected, "Ya existe otro seguimiento.")
        demand(distribution.mainSha(current.repository, requireToken()) == current.source, "main ha cambiado. Vuelve a comprobar el workflow y la versión.")
        val session = PublicationSession(current.repository, current.source, current.workflowId, channel, tag.trim(),
            if (channel == "release") current.releasePackage else current.devPackage,
            baseline = distribution.latestRunId(current.repository, requireToken()))
        DistributionService.inputs(session)
        // Persistir ANTES del POST: si la respuesta se pierde no se reenviará al reiniciar.
        save(session)
        publicationForm = false
        send(session)
    }
    fun publishConfirmed() = operation {
        val session = publication ?: throw PublicationError("No existe un candidato preparado.")
        demand(session.phase == "preparado" && session.operation == "prepare" && session.candidate != null,
            "El candidato todavía no está preparado.")
        demand(github.user(requireToken()) == "robdor80", "Solo el propietario puede confirmar la publicación.")
        demand(distribution.mainSha(session.repository, requireToken()) == session.source,
            "main ha cambiado desde prepare. No se puede publicar este candidato: prepara uno del nuevo commit aprobado.")
        val publishing = session.copy(operation = "publish", runId = 0,
            baseline = distribution.latestRunId(session.repository, requireToken()), phase = "pendiente",
            detail = "Publicación solicitada. Esperando ejecución de GitHub.")
        save(publishing)
        send(publishing)
    }
    private suspend fun send(session: PublicationSession) {
        try { save(session.copy(runId = distribution.dispatch(session, requireToken()))) }
        catch (e: GitHubException) {
            if (e.status in 400..499) save(session.copy(phase = "error", detail = e.message ?: "GitHub rechazó el envío."))
            throw e
        }
        catch (e: java.io.IOException) {
            throw PublicationError("No se pudo confirmar el envío. Se buscará la ejecución sin repetir la orden. Puedes abrir GitHub para revisar su estado.")
        }
    }
    fun updatePublication() {
        if (!hasToken || publication == null) return
        operation {
            val session = publication ?: return@operation
            try { save(distribution.update(session, requireToken())) }
            catch (e: PublicationError) { save(session.copy(phase = "error", detail = e.message ?: "No se pudo confirmar el resultado.")) }
        }
    }
    fun closePublication() {
        if (busy) return
        try { save(null); message = "Seguimiento cerrado. No se ha cancelado ninguna ejecución en GitHub." }
        catch (_: Exception) { message = "No se pudo cerrar el seguimiento guardado." }
    }
    fun dismissMessage() { message = null }
    private fun requireToken() = token ?: throw IllegalStateException("Sin token")
}
