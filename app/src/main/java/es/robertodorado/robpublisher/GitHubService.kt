package es.robertodorado.robpublisher

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.util.Base64

data class Repository(val fullName: String, val isPrivate: Boolean, val archived: Boolean, val branch: String,
    val topics: Set<String> = emptySet(), val fork: Boolean = false) {
    val name: String get() = fullName.substringAfter('/')
}
data class Release(val tag: String, val publishedAt: String)
data class ApplicationSummary(val repository: Repository, val release: Release?, val integrationReady: Boolean,
    val status: String, val releaseChecked: Boolean = true, val discovered: Boolean = false)
class GitHubException(val status: Int) : Exception(when (status) {
    401 -> "GitHub ha rechazado la sesión. Vuelve a conectar tu cuenta."
    403 -> "GitHub ha denegado el acceso. Revisa permisos o límite de consultas."
    404 -> "Repositorio no accesible o recurso no encontrado."
    422 -> "GitHub ha rechazado los datos. La aplicación necesita actualizar su integración de distribución."
    409 -> "El repositorio ha cambiado o la operación entra en conflicto. Actualiza su estado."
    429 -> "Demasiadas consultas. Espera antes de reintentar."
    else -> "GitHub no está disponible (HTTP $status)."
})

data class ApiResponse(val status: Int, val body: String, val location: String? = null)
fun interface GitHubTransport {
    suspend fun request(method: String, path: String, token: String, body: String?): ApiResponse
}

/** Solo api.github.com recibe Authorization; los errores nunca incluyen cuerpos ni secretos. */
class RestTransport : GitHubTransport {
    override suspend fun request(method: String, path: String, token: String, body: String?): ApiResponse = withContext(Dispatchers.IO) {
        require(path.startsWith("/repos/") || path.startsWith("/user"))
        val connection = URI("https://api.github.com$path").toURL().openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            connection.setRequestProperty("User-Agent", "RobPublisher/1.0")
            if (body != null) {
                val bytes = body.toByteArray(Charsets.UTF_8)
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(bytes) }
            }
            val status = connection.responseCode
            val text = if (status in 200..299) connection.inputStream.use {
                val bytes = it.readLimited(2 * 1024 * 1024)
                check(bytes.size <= 2 * 1024 * 1024)
                bytes.toString(Charsets.UTF_8)
            } else ""
            ApiResponse(status, text, connection.getHeaderField("Location"))
        } finally { connection.disconnect() }
    }
}

class GitHubService(private val transport: GitHubTransport = RestTransport()) {
    internal suspend fun request(method: String, path: String, token: String, body: JSONObject? = null): ApiResponse {
        val response = transport.request(method, path, token, body?.toString())
        if (response.status !in 200..299 && response.status != 302) throw GitHubException(response.status)
        return response
    }
    internal suspend fun get(path: String, token: String): String {
        val response = request("GET", path, token)
        if (response.status !in 200..299) throw GitHubException(response.status)
        return response.body
    }
    suspend fun user(token: String): String = JSONObject(get("/user", token)).getString("login")
    suspend fun repositories(token: String): List<Repository> {
        val result = mutableListOf<Repository>()
        var page = 1
        do {
            val batch = JSONArray(get("/user/repos?per_page=100&sort=full_name&page=$page", token))
            for (i in 0 until batch.length()) result += parseRepository(batch.getJSONObject(i))
            page++
        } while (batch.length() == 100)
        return result.distinctBy { it.fullName }.sortedBy { it.fullName.lowercase() }
    }
    suspend fun repository(fullName: String, token: String): Repository {
        require(fullName.matches(Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")))
        return parseRepository(JSONObject(get("/repos/$fullName", token)))
    }
    suspend fun latestRelease(fullName: String, token: String): Release? = try {
        require(fullName.matches(Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")))
        val json = JSONObject(get("/repos/$fullName/releases/latest", token))
        Release(json.getString("tag_name"), json.optString("published_at").take(10))
    } catch (e: GitHubException) {
        if (e.status == 404) null else throw e
    }
    /** Solo lectura: reúne las apps descubiertas y las altas manuales sin duplicarlas. */
    suspend fun discoverApplications(repositories: List<Repository>, manual: List<String>, token: String): List<ApplicationSummary> {
        val candidates = repositories.filter { it.fullName.substringBefore('/') == "robdor80" || it.fullName in manual }.toMutableList()
        val result = mutableListOf<ApplicationSummary>()
        for (name in manual.distinct().filter { saved -> candidates.none { it.fullName == saved } }) {
            try { candidates += repository(name, token) }
            catch (e: GitHubException) {
                if (e.status != 404) throw e
                result += ApplicationSummary(Repository(name, false, false, "—"), null, false,
                    "Repositorio no accesible. Revisa el acceso del token o elimina su alta manual.", releaseChecked = false)
            }
        }
        for (repo in candidates.distinctBy { it.fullName }) {
            val configText = fileOrNull(repo, ".github/android-distribution.json", token)
            if (configText == null && "robstore-app" !in repo.topics && repo.fullName !in manual) continue
            val release = latestRelease(repo.fullName, token)
            val status = when {
                repo.archived -> "Preparación pendiente: repositorio archivado."
                repo.fork -> "Preparación pendiente: RS-05 necesita el repositorio original."
                repo.branch != "main" -> "Preparación pendiente: RS-05 necesita main como rama predeterminada."
                configText == null -> "Preparación pendiente: falta .github/android-distribution.json."
                else -> integrationStatus(repo, configText, token)
            }
            result += ApplicationSummary(repo, release, status == null,
                status ?: "Lista para distribuir automáticamente.",
                discovered = repo.fullName.startsWith("robdor80/") && (configText != null || "robstore-app" in repo.topics))
        }
        return result.sortedBy { it.repository.fullName.lowercase() }
    }
    private suspend fun fileOrNull(repo: Repository, path: String, token: String): String? = try {
        val ref = URLEncoder.encode(repo.branch, "UTF-8")
        val json = JSONObject(get("/repos/${repo.fullName}/contents/$path?ref=$ref", token))
        try { Base64.getMimeDecoder().decode(json.getString("content")).toString(Charsets.UTF_8) }
        catch (_: IllegalArgumentException) { "" }
    } catch (e: GitHubException) {
        if (e.status == 404) null else throw e
    }
    /** null significa integración básica lista; no certifica el alta ni las claves. */
    private suspend fun integrationStatus(repo: Repository, text: String, token: String): String? {
        val notesPath: String
        try {
            val config = JSONObject(text)
            val packages = config.getJSONObject("packages")
            val releasePackage = packages.getString("release")
            notesPath = config.getString("notesPath")
            if (config.getInt("schemaVersion") != 1 || config.getString("repository") != repo.fullName ||
                !releasePackage.matches(Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+")) ||
                packages.getString("dev") != "$releasePackage.debug" ||
                !notesPath.matches(Regex("[A-Za-z0-9_.\\-/]+")) || notesPath.split('/').any { it in setOf("", ".", "..") }) {
                return "Preparación pendiente: configuración RS-05 no válida."
            }
        } catch (_: org.json.JSONException) { return "Preparación pendiente: configuración RS-05 incompleta o no válida." }
        val workflow = try { JSONObject(get("/repos/${repo.fullName}/actions/workflows/android-distribute.yml", token)) }
        catch (e: GitHubException) {
            if (e.status == 404) return "Preparación pendiente: falta android-distribute.yml o no es accesible en Actions."
            if (e.status == 403) return "Estado RS-05 sin comprobar: revisa el permiso de lectura de Actions o el límite de consultas."
            throw e
        }
        if (workflow.optString("state") != "active") return "Preparación pendiente: workflow RS-05 deshabilitado."
        val workflowText = fileOrNull(repo, ".github/workflows/android-distribute.yml", token)
        if (workflowText == null || !usesRs05Workflow(repo.fullName, workflowText)) {
            return "Preparación pendiente: el workflow de main no llama al motor automático central."
        }
        return null
    }
    /**
     * Solo lectura. Se prioriza el icono declarado en robstore.json y los recursos
     * conocidos de las aplicaciones. No se sigue ninguna URL externa ni redirects.
     * Un icono ausente nunca bloquea descubrimiento ni publicación.
     */
    suspend fun applicationIcon(repo: Repository, token: String): ByteArray? {
        require(repo.fullName.matches(Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")))
        val declared = try {
            val text = fileOrNull(repo, "robstore.json", token)
            text?.let { JSONObject(it).optString("icon") }
        } catch (_: org.json.JSONException) { null }
        val candidates = listOfNotNull(
            declared,
            "robstore/icon.png",
            "assets/${repo.name.lowercase()}/icon.png",
            "assets/robpublisher/icon.png",
            "app/src/main/res/mipmap-xxxhdpi/ic_launcher.png",
            "app/src/main/res/drawable-nodpi/ic_launcher_art.png",
            "app/src/main/res/mipmap-xxxhdpi/ic_launcher.webp"
        ).distinct()
        val ref = URLEncoder.encode(repo.branch, "UTF-8")
        for (path in candidates) {
            // Repositorios y rutas siempre del propio propietario; sin saltos de directorio.
            if (path.length > 180 || !path.matches(Regex("[A-Za-z0-9_./-]+")) ||
                path.split('/').any { it in setOf("", ".", "..") } ||
                !(path.endsWith(".png") || path.endsWith(".webp"))) continue
            val response = try {
                JSONObject(get("/repos/${repo.fullName}/contents/$path?ref=$ref", token))
            } catch (e: GitHubException) {
                if (e.status == 404) continue else throw e
            } catch (_: org.json.JSONException) { continue }
            if (response.optString("encoding") != "base64") continue
            val bytes = try { Base64.getMimeDecoder().decode(response.getString("content")) }
                catch (_: Exception) { continue }
            if (bytes.size !in 64..512_000) continue
            val png = bytes.size >= 8 && bytes[0] == 0x89.toByte() &&
                bytes[1] == 'P'.code.toByte() && bytes[2] == 'N'.code.toByte() && bytes[3] == 'G'.code.toByte()
            val webp = bytes.size >= 12 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" &&
                String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP"
            if (png || webp) return bytes
        }
        return null
    }

    private fun parseRepository(json: JSONObject) = Repository(
        json.getString("full_name"), json.getBoolean("private"),
        json.optBoolean("archived"), json.optString("default_branch", "—"),
        json.optJSONArray("topics")?.let { topics -> (0 until topics.length()).map { topics.getString(it) }.toSet() } ?: emptySet(),
        json.optBoolean("fork")
    )
}
