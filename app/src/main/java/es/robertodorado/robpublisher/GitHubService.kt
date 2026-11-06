package es.robertodorado.robpublisher

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI

data class Repository(val fullName: String, val isPrivate: Boolean, val archived: Boolean, val branch: String) {
    val name: String get() = fullName.substringAfter('/')
}
data class Release(val tag: String, val publishedAt: String)
class GitHubException(val status: Int) : Exception(when (status) {
    401 -> "Token no válido o caducado. Revisa la conexión."
    403 -> "GitHub ha denegado el acceso. Revisa permisos o límite de consultas."
    404 -> "Repositorio no accesible o recurso no encontrado."
    422 -> "GitHub ha rechazado los datos. Revisa la etiqueta, la rama y los inputs del workflow."
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
    private fun parseRepository(json: JSONObject) = Repository(
        json.getString("full_name"), json.getBoolean("private"),
        json.optBoolean("archived"), json.optString("default_branch", "—")
    )
}
