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
    429 -> "Demasiadas consultas. Espera antes de reintentar."
    else -> "GitHub no está disponible (HTTP $status)."
})

/** Cliente REST reutilizable. RP-01 solo realiza lecturas; sin ejecución de workflows. */
class GitHubService {
    private suspend fun get(path: String, token: String): String = withContext(Dispatchers.IO) {
        val connection = URI("https://api.github.com$path").toURL().openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15_000
            connection.readTimeout = 20_000
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            connection.setRequestProperty("User-Agent", "RobPublisher/1.0")
            val status = connection.responseCode
            if (status !in 200..299) throw GitHubException(status)
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally { connection.disconnect() }
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
