package es.robertodorado.robpublisher

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class PublisherViewModel internal constructor(
    application: Application,
    private val github: GitHubService,
    private val oauth: GitHubOAuth,
) : AndroidViewModel(application) {
    constructor(application: Application) : this(application, GitHubService(), GitHubOAuth())
    private val store = LocalStore(application)
    private val distribution = DistributionService(github)
    private val auth = DeviceAuthController(viewModelScope, store, oauth, github::user)
    private var token: String? = null
    val oauthClientId: String = ROBGIT_RD_CLIENT_ID
    internal var pendingAuthorization by mutableStateOf<DeviceAuthorization?>(null); private set
    var authorizing by mutableStateOf(false); private set
    var validatingAuthorization by mutableStateOf(false); private set
    var authorizationRemainingSeconds by mutableStateOf(0L); private set
    var configured by mutableStateOf(store.repositories()); private set
    var accessible by mutableStateOf<List<Repository>>(emptyList()); private set
    var applications by mutableStateOf<List<ApplicationSummary>>(emptyList()); private set
    var discoveryComplete by mutableStateOf(false); private set
    val applicationNames: List<String> get() = (configured + applications.map { it.repository.fullName }).distinct().sorted()
    var login by mutableStateOf<String?>(null); private set
    var hasToken by mutableStateOf(false); private set
    var busy by mutableStateOf(false); private set
    var message by mutableStateOf<String?>(null); private set
    var repository by mutableStateOf<Repository?>(null); private set
    var release by mutableStateOf<Release?>(null); private set
    var detailsLoaded by mutableStateOf(false); private set
    var selected by mutableStateOf<String?>(null); private set
    var publication by mutableStateOf<PublicationSession?>(null); private set
    var screen by mutableStateOf("Inicio")
    init {
        try { publication = store.publication() }
        catch (_: Exception) { message = "No se pudo recuperar el seguimiento guardado. No se enviará ninguna orden automáticamente." }

        try {
            val session = store.oauth()
            token = if (session == null) store.token() else null
            hasToken = session != null || token != null
            login = session?.login
        } catch (_: Exception) {
            message = "No se pudo recuperar la conexión cifrada. Vuelve a conectar con GitHub."
        }
        viewModelScope.launch {
            var previousAuthMessage: String? = null
            auth.state.collect { state ->
                pendingAuthorization = state.pending
                authorizing = state.running
                validatingAuthorization = state.validating
                authorizationRemainingSeconds = state.remainingSeconds
                if (state.message != previousAuthMessage) state.message?.let { message = it }
                previousAuthMessage = state.message
                state.connectedLogin?.let {
                    token = null; login = it; hasToken = true
                    applications = emptyList(); accessible = emptyList(); discoveryComplete = false
                    screen = "Inicio"
                }
            }
        }
        if (hasToken) {
            // Una sesión ya guardada tiene prioridad sobre un intento interrumpido al finalizar.
            try { store.savePendingAuth(null) }
            catch (_: Exception) { message = "No se pudo eliminar el intento anterior. La sesión guardada sigue disponible." }
            checkConnection()
        } else {
            auth.restore()
            if (auth.state.value.running) screen = "Ajustes"
        }
    }
    private fun operation(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        message = null
        viewModelScope.launch {
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: PublicationError) { message = e.message }
            catch (e: OAuthFailure) { message = e.message }
            catch (e: GitHubException) {
                if (e.status == 401) {
                    login = null; accessible = emptyList()
                    store.clearOAuth(); store.discardLegacyToken()
                    token = null; hasToken = false
                    message = "GitHub ha revocado la conexión. Vuelve a autorizar RobGit RD."
                } else message = e.message
            }
            catch (_: java.io.IOException) { message = "No se pudo conectar con GitHub. Comprueba Internet y reintenta." }
            catch (_: Exception) { message = "No se pudo completar la operación. Revisa la configuración y reintenta." }
            finally { busy = false }
        }
    }

    /** El navegador nunca determina el éxito: lo hace el token validado por /user. */
    fun beginAuthorization() {
        if (busy) return
        screen = "Ajustes"
        auth.begin()
    }
    fun cancelAuthorization() = auth.cancel()
    fun checkConnection() = operation {
        login = github.user(requireToken())
        message = "Conexión con GitHub comprobada."
    }
    fun loadRepositories() = operation { accessible = github.repositories(requireToken()) }
    fun refreshApplications() {
        if (!hasToken) return
        operation {
            discoveryComplete = false
            accessible = github.repositories(requireToken())
            // Sustituir la instantánea solo cuando termina: un fallo no equivale a lista vacía.
            applications = github.discoverApplications(accessible, configured, requireToken())
            discoveryComplete = true
        }
    }
    fun disconnect() = operation {
        cancelAuthorization()
        store.disconnect(); token = null; hasToken = false; login = null
        applications = emptyList(); discoveryComplete = false
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
        try {
            store.saveRepositories(updated); configured = updated
            applications = applications.filter { it.repository.fullName != name || it.discovered }
        }
        catch (_: Exception) { message = "No se pudo guardar la lista de aplicaciones." }
    }
    fun open(name: String) {
        if (busy) return
        selected = name; screen = "Aplicación"
        refresh()
    }
    fun refresh() = operation {
        repository = null; release = null; detailsLoaded = false
        val name = selected ?: return@operation
        repository = github.repository(name, requireToken())
        release = github.latestRelease(name, requireToken())
        detailsLoaded = true
    }
    private fun save(session: PublicationSession?) {
        store.savePublication(session)
        publication = session
    }
    fun distribute() {
        if (!hasToken) { message = "Configura la conexión con GitHub una sola vez en Ajustes."; return }
        if (publication?.watching == true) { message = "La distribución ya está en curso."; return }
        operation {
            val name = selected ?: return@operation
            val current = distribution.plan(name, requireToken())
            val session = PublicationSession(current.repository, current.workflowId,
                baseline = distribution.latestRunId(current.repository, requireToken()))
            // Save before the only POST so reopening the app cannot repeat the order.
            save(session)
            send(session)
        }
    }
    private suspend fun send(session: PublicationSession) {
        try { save(session.copy(runId = distribution.dispatch(session, requireToken()))) }
        catch (e: GitHubException) {
            if (e.status in 400..499) save(session.copy(phase = "error", detail = e.message ?: "GitHub rechazó el envío."))
            throw e
        }
        catch (e: java.io.IOException) {
            throw PublicationError("No se pudo confirmar la respuesta. Buscaremos la ejecución automáticamente sin repetir la orden.")
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
        if (publication?.watching == true) return
        try { save(null); message = null }
        catch (_: Exception) { message = "No se pudo cerrar el seguimiento guardado." }
    }
    fun dismissMessage() { message = null }
    private suspend fun requireToken(): String {
        val saved = store.oauth()
        if (saved != null) {
            val now = System.currentTimeMillis()
            if (saved.accessExpiresAt <= now && (saved.refreshToken.isBlank() || saved.refreshExpiresAt <= now)) {
                store.clearOAuth()
                hasToken = false; login = null
                throw OAuthFailure("La sesión de GitHub ha caducado. Vuelve a conectarte con RobGit RD.")
            }
            if (saved.refreshToken.isNotBlank() && saved.refreshExpiresAt > now && saved.accessExpiresAt - now <= 300_000L) {
                val fresh = try { oauth.refresh(oauthClientId, saved) }
                catch (e: OAuthFailure) {
                    if (e.invalidSession) { store.clearOAuth(); hasToken = false; login = null }
                    throw e
                }
                store.saveOAuth(fresh)
                return fresh.accessToken
            }
            return saved.accessToken
        }
        return token ?: throw OAuthFailure("Conecta con GitHub desde Ajustes.")
    }
}
