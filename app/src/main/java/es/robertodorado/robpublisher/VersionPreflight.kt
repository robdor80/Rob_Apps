package es.robertodorado.robpublisher

import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64
import java.net.URLEncoder

data class VersionPreflight(val maximum: Long, val candidate: Long?, val name: String?) {
    val allowed get() = candidate != null && candidate > maximum
    val message get() = when {
        candidate == null -> "No se puede determinar la versión candidata con seguridad. Revisa el versionado evaluado por Gradle en CI antes de publicar."
        !allowed -> "La versión que intentas publicar ya existe o tiene un código interno demasiado bajo. Código actual: $maximum. Código mínimo necesario: ${maximum + 1}."
        else -> "Código oficial actual: $maximum. Versión candidata: $name (código $candidate)."
    }
}

/** Inspection only. Never edits Gradle or executes repository code on the device. */
internal class VersionPreflightService(private val github: GitHubService) {
    private suspend fun file(repo: String, path: String, ref: String, token: String): String {
        val item = JSONObject(github.get("/repos/$repo/contents/$path?ref=${URLEncoder.encode(ref, "UTF-8")}", token))
        return Base64.getMimeDecoder().decode(item.getString("content")).toString(Charsets.UTF_8)
    }
    suspend fun inspect(repo: String, token: String): VersionPreflight {
        demand(repo.matches(Regex("robdor80/[A-Za-z0-9_.-]+")), "Repositorio no autorizado.")
        val head = JSONObject(github.get("/repos/$repo/branches/main", token)).getJSONObject("commit").getString("sha")
        val config = JSONObject(file(repo, ".github/android-distribution.json", head, token))
        val pkg = config.getJSONObject("packages").getString("release")
        val registry = JSONObject(file("robdor80/RobStore", "tools/android-original-identities.json", "main", token)).getJSONArray("applications")
        val baselines = (0 until registry.length()).map { registry.getJSONObject(it) }.filter { it.getString("repository") == repo }
        val codes = baselines.map { it.getLong("versionCode") }.toMutableList()
        for (page in 1..100) {
            val releases = JSONArray(github.get("/repos/$repo/releases?per_page=100&page=$page", token))
            for (i in 0 until releases.length()) {
                val release = releases.getJSONObject(i)
                if (release.getBoolean("draft") || release.getBoolean("prerelease")) continue
                val tag = release.getString("tag_name")
                val contract = try { JSONObject(file(repo, "robstore-update.json", "refs/tags/$tag", token)) }
                catch (e: GitHubException) {
                    if (e.status != 404) throw e
                    val baseline = baselines.singleOrNull { it.getString("tag") == tag }
                    demand(baseline != null, "Falta el contrato de una Release histórica. Verifica su APK en CI antes de publicar.")
                    baseline!!
                }
                demand(contract.getString("packageName") == pkg, "La identidad histórica no coincide.")
                codes += contract.getLong("versionCode")
            }
            if (releases.length() < 100) break
            demand(page < 100, "Listado de Releases incompleto; no se publicará.")
        }
        val module = config.getString("module")
        demand(module.matches(Regex("[A-Za-z][A-Za-z0-9_-]*")), "Módulo Android no compatible.")
        val source = try { file(repo, "$module/build.gradle.kts", head, token) }
        catch (e: GitHubException) { if (e.status == 404) "" else throw e }
        val candidate = literal(source, "versionCode")?.toLongOrNull()
        val name = Regex("(?m)^\\s*versionName\\s*=\\s*\"([0-9.]+)\"\\s*$").find(source)?.groupValues?.get(1)
        return VersionPreflight(codes.maxOrNull() ?: 0, candidate, name)
    }
    companion object {
        fun literal(source: String, key: String): String? {
            // Multiple assignments, comments and calculations are deliberately not guessed.
            if (source.contains("/*") || Regex("\\b$key\\b").findAll(source).count() != 1) return null
            return Regex("(?m)^\\s*$key\\s*=\\s*([1-9][0-9]*)\\s*$").find(source)?.groupValues?.get(1)
        }
    }
}
