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
    var screen by mutableStateOf("Inicio")
    init {
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
        accessible = emptyList()
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
    fun publicationPending() { message = "La publicación estará disponible en RP-02. No se ha ejecutado ninguna acción." }
    fun dismissMessage() { message = null }
    private fun requireToken() = token ?: throw IllegalStateException("Sin token")
}
