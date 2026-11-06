package es.robertodorado.robpublisher

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class PublisherViewModel(application: Application) : AndroidViewModel(application) {
    private val store = LocalStore(application)
    private val github = GitHubService()
    private val distribution = DistributionService(github)
    private val oauth = GitHubOAuth()
    private var oauthJob: Job? = null
    private var token: String? = null
    var oauthClientId by mutableStateOf(store.oauthClientId()); private set
    var pendingAuthorization by mutableStateOf<DeviceAuthorization?>(null); private set
    var authorizing by mutableStateOf(false); private set
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
        } catch (_: Exception) {
            message = "No se pudo recuperar la conexión cifrada. Vuelve a conectar con GitHub."
        }
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
            catch (e: OAuthFailure) { message = e.message }
            catch (e: GitHubException) {
                if (e.status == 401) {
                    login = null; accessible = emptyList()
                    if (store.oauth() != null) {
                        store.clearOAuth(); hasToken = false
                        message = "GitHub ha revocado la conexión. Vuelve a autorizar RobGit RD."
                    }
                }
                message = e.message
            }
            catch (_: java.io.IOException) { message = "No se pudo conectar con GitHub. Comprueba Internet y reintenta." }
            catch (_: Exception) { message = "No se pudo completar la operación. Revisa la configuración y reintenta." }
            finally { busy = false }
        }
    }
    fun setOAuthClientId(value: String) { if (!authorizing) oauthClientId = value.trim() }

    /** Conexión mediante RobGit RD; ni PAT ni secreto de cliente. */
    fun beginAuthorization() {
        if (authorizing) return
        operation {
            val id = oauthClientId.trim()
            if (!GitHubOAuth.validClientId(id)) throw OAuthFailure("Introduce el Client ID público de RobGit RD (una sola vez).")
            store.saveOAuthClientId(id)
            val pending = oauth.begin(id)
            pendingAuthorization = pending
            authorizing = true
            oauthJob?.cancel()
            oauthJob = viewModelScope.launch {
                var seconds = pending.intervalSeconds
                try {
                    while (System.currentTimeMillis() < pending.expiresAt) {
                        delay(seconds * 1000L)
                        when (val result = oauth.poll(id, pending)) {
                            DevicePoll.Waiting -> Unit
                            DevicePoll.SlowDown -> seconds += 5L
                            is DevicePoll.Failed -> {
                                message = result.explanation
                                return@launch
                            }
                            is DevicePoll.Authorized -> {
                                val account = github.user(result.token.accessToken)
                                if (account != "robdor80") {
                                    message = "Conecta con tu cuenta robdor80."
                                    return@launch
                                }
                                store.saveOAuth(OAuthCredentials(
                                    result.token.accessToken, result.token.refreshToken,
                                    result.token.accessExpiresAt, result.token.refreshExpiresAt, account,
                                ))
                                store.discardLegacyToken()
                                token = null
                                login = account
                                hasToken = true
                                applications = emptyList()
                                accessible = emptyList()
                                discoveryComplete = false
                                message = "GitHub conectado. Las credenciales se renovarán automáticamente."
                                return@launch
                            }
                        }
                    }
                    message = "Ha caducado el código de GitHub. Pulsa Conectar de nuevo."
                } catch (e: CancellationException) {
                    throw e
                } catch (e: OAuthFailure) {
                    message = e.message
                } catch (e: Exception) {
                    message = "No se pudo completar la conexión. Vuelve a intentarlo."
                } finally {
                    pendingAuthorization = null
                    authorizing = false
                }
            }
        }
    }
    fun cancelAuthorization() {
        oauthJob?.cancel()
        pendingAuthorization = null
        authorizing = false
    }
    fun checkConnection() = operation {
        login = null
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
            if (saved.refreshExpiresAt <= now) {
                store.clearOAuth()
                hasToken = false
                throw OAuthFailure("La sesión de GitHub ha caducado. Vuelve a conectarte con RobGit RD.")
            }
            if (saved.accessExpiresAt - now <= 300_000L) {
                val fresh = oauth.refresh(oauthClientId, saved)
                store.saveOAuth(fresh)
                return fresh.accessToken
            }
            return saved.accessToken
        }
        return token ?: throw OAuthFailure("Conecta con GitHub desde Ajustes.")
    }
}
