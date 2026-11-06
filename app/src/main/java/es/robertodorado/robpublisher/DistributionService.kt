package es.robertodorado.robpublisher

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.util.Base64
import java.util.zip.ZipInputStream

class PublicationError(message: String) : Exception(message)
internal fun demand(condition: Boolean, message: String) { if (!condition) throw PublicationError(message) }
internal fun InputStream.readLimited(limit: Int): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        demand(output.size() + count <= limit, "La respuesta de GitHub supera el tamaño admitido.")
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}
private fun encoded(value: String) = URLEncoder.encode(value, "UTF-8")

data class DistributionPlan(val repository: String, val source: String, val workflowId: Long,
    val releasePackage: String, val devPackage: String, val version: String?, val code: Int?) {
    fun suggestion(channel: String): String = if (version == null) "" else if (channel == "release") "v$version"
        else if (code == null) "" else "dev-$version-DEV-$code"
}
data class Candidate(val packageName: String, val version: String, val code: Int, val sha256: String,
    val certificate: String, val asset: String) {
    fun json() = JSONObject().put("package", packageName).put("version", version).put("code", code)
        .put("sha256", sha256).put("certificate", certificate).put("asset", asset)
    companion object {
        fun from(json: JSONObject) = Candidate(json.getString("package"), json.getString("version"), json.getInt("code"),
            json.getString("sha256"), json.getString("certificate"), json.getString("asset"))
    }
}
/** Un único seguimiento personal; solo metadatos públicos, nunca token ni claves de firma. */
data class PublicationSession(val repository: String, val source: String, val workflowId: Long,
    val channel: String, val tag: String, val expectedPackage: String, val operation: String = "prepare",
    val baseline: Long = 0, val runId: Long = 0, val prepareRun: Long = 0,
    val phase: String = "pendiente", val detail: String = "Esperando ejecución de GitHub.",
    val candidate: Candidate? = null) {
    val watching get() = phase in setOf("pendiente", "ejecutándose", "requiere aprobación")
    val runUrl get() = if (runId > 0) "https://github.com/$repository/actions/runs/$runId" else "https://github.com/$repository/actions/workflows/android-distribute.yml"
    val releaseUrl get() = "https://github.com/$repository/releases/tag/${encoded(tag)}"
    fun json(): JSONObject = JSONObject().put("repository", repository).put("source", source).put("workflowId", workflowId)
        .put("channel", channel).put("tag", tag).put("expectedPackage", expectedPackage).put("operation", operation)
        .put("baseline", baseline).put("runId", runId).put("prepareRun", prepareRun).put("phase", phase)
        .put("detail", detail).put("candidate", candidate?.json())
    companion object {
        fun from(json: JSONObject) = PublicationSession(json.getString("repository"), json.getString("source"),
            json.getLong("workflowId"), json.getString("channel"), json.getString("tag"), json.getString("expectedPackage"),
            json.getString("operation"), json.getLong("baseline"), json.getLong("runId"), json.getLong("prepareRun"),
            json.getString("phase"), json.getString("detail"), json.optJSONObject("candidate")?.let(Candidate::from))
    }
}

fun interface CandidateArchive { suspend fun read(url: String): Map<String, String> }
/** Sigue la URL temporal sin Authorization. No conserva ni instala el APK del ZIP. */
class ZipCandidateArchive : CandidateArchive {
    override suspend fun read(url: String): Map<String, String> = withContext(Dispatchers.IO) {
        var current = url
        repeat(4) {
            val uri = URI(current)
            demand(uri.scheme == "https" && uri.host != null && uri.userInfo == null, "GitHub devolvió una descarga no válida.")
            val connection = uri.toURL().openConnection() as HttpURLConnection
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 15_000; connection.readTimeout = 30_000
                val status = connection.responseCode
                if (status in setOf(301, 302, 303, 307, 308)) {
                    current = uri.resolve(connection.getHeaderField("Location") ?: throw PublicationError("Descarga sin ubicación.")).toString()
                } else {
                    demand(status == 200, "No se pudo descargar el candidato. Comprueba si ha caducado y actualiza su estado.")
                    return@withContext readMetadata(connection.inputStream)
                }
            } finally { connection.disconnect() }
        }
        throw PublicationError("Demasiadas redirecciones al descargar el candidato.")
    }
    companion object {
        internal fun readMetadata(input: InputStream): Map<String, String> {
            val expected = setOf("robstore-update.json", "robstore-distribution.json", "SHA256SUMS")
            val result = mutableMapOf<String, String>()
            var bytes = 0L; var entries = 0
            ZipInputStream(input.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    demand(++entries <= 30, "El artefacto no tiene el formato de candidato RS-05.")
                    val metadata = entry.name in expected
                    val output = if (metadata) ByteArrayOutputStream() else null
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = zip.read(buffer)
                        if (count < 0) break
                        bytes += count
                        demand(bytes <= 600L * 1024 * 1024, "El artefacto es demasiado grande.")
                        if (output != null) {
                            demand(output.size() + count <= 64 * 1024, "Metadatos del candidato demasiado grandes.")
                            output.write(buffer, 0, count)
                        }
                    }
                    if (metadata) {
                        demand(entry.name !in result, "Metadatos duplicados en el candidato.")
                        result[entry.name] = output!!.toString("UTF-8")
                    }
                }
            }
            demand(result.keys == expected, "Faltan el contrato, el recibo o SHA256SUMS oficiales del candidato.")
            return result
        }
    }
}

class DistributionService(private val github: GitHubService, private val archive: CandidateArchive = ZipCandidateArchive()) {
    private fun base(repo: String): String {
        demand(repo.matches(Regex("robdor80/[A-Za-z0-9_.-]+")), "RS-05 solo admite repositorios personales de robdor80.")
        return "/repos/$repo"
    }
    private suspend fun content(repo: String, path: String, source: String, token: String): String {
        val json = JSONObject(github.get("${base(repo)}/contents/$path?ref=$source", token))
        return Base64.getMimeDecoder().decode(json.getString("content")).toString(Charsets.UTF_8)
    }
    suspend fun mainSha(repo: String, token: String): String = JSONObject(github.get("${base(repo)}/branches/main", token))
        .getJSONObject("commit").getString("sha").also { demand(it.matches(Regex("[a-f0-9]{40}")), "SHA de main no válido.") }
    suspend fun plan(repo: String, token: String): DistributionPlan {
        demand(github.user(token) == "robdor80", "RS-05 requiere la cuenta propietaria robdor80.")
        val repository = JSONObject(github.get(base(repo), token))
        demand(!repository.getBoolean("fork") && !repository.optBoolean("archived") && repository.getString("default_branch") == "main",
            "RS-05 necesita un repositorio original activo con rama predeterminada main.")
        val workflow = try { JSONObject(github.get("${base(repo)}/actions/workflows/android-distribute.yml", token)) }
        catch (e: GitHubException) {
            if (e.status == 404) throw PublicationError("No se encuentra android-distribute.yml. Revisa su incorporación a main y el acceso del token a Actions.")
            throw e
        }
        demand(workflow.getString("state") == "active", "El workflow android-distribute.yml está deshabilitado. Actívalo en GitHub.")
        val source = mainSha(repo, token)
        val text = content(repo, ".github/workflows/android-distribute.yml", source, token)
        val central = Regex("(?m)^\\s*uses:\\s*['\"]?robdor80/RobStore/\\.github/workflows/android-distribution-engine\\.yml@[a-f0-9]{40}['\"]?\\s*(?:#.*)?$")
        val local = Regex("(?m)^\\s*uses:\\s*['\"]?\\./\\.github/workflows/android-distribution-engine\\.yml['\"]?\\s*(?:#.*)?$")
        demand("workflow_dispatch:" in text && (central.containsMatchIn(text) || repo == "robdor80/RobStore" && local.containsMatchIn(text)),
            "El workflow de main no usa el motor oficial RS-05 fijado a un SHA completo.")
        val config = JSONObject(content(repo, ".github/android-distribution.json", source, token))
        demand(config.getInt("schemaVersion") == 1 && config.getString("repository") == repo, "Configuración RS-05 no válida para este repositorio.")
        val packages = config.getJSONObject("packages")
        val release = packages.getString("release"); val dev = packages.getString("dev")
        demand(release.matches(Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+")) && dev == "$release.debug", "Identidades RS-05 no válidas.")
        val module = config.getString("module")
        demand(module.matches(Regex("[A-Za-z][A-Za-z0-9_-]*")), "Módulo Android no válido.")
        // Solo proponer versiones literales del código fuente; nunca incrementar una etiqueta ajena al APK.
        var gradle: String? = null
        for (file in listOf("build.gradle.kts", "build.gradle")) {
            try { gradle = content(repo, "$module/$file", source, token); break }
            catch (e: GitHubException) { if (e.status != 404) throw e }
        }
        val version = gradle?.let { Regex("versionName\\s*(?:=\\s*)?[\"']([0-9]+\\.[0-9]+\\.[0-9]+)[\"']").find(it)?.groupValues?.get(1) }
        val code = gradle?.let { Regex("versionCode\\s*(?:=\\s*)?([0-9]+)").find(it)?.groupValues?.get(1)?.toIntOrNull() }
        return DistributionPlan(repo, source, workflow.getLong("id"), release, dev, version, code)
    }
    suspend fun latestRunId(repo: String, token: String): Long = JSONObject(github.get(
        "${base(repo)}/actions/workflows/android-distribute.yml/runs?per_page=1", token)).getJSONArray("workflow_runs")
        .let { if (it.length() == 0) 0 else it.getJSONObject(0).getLong("id") }

    suspend fun dispatch(session: PublicationSession, token: String): Long {
        val response = github.request("POST", "${base(session.repository)}/actions/workflows/android-distribute.yml/dispatches", token,
            JSONObject().put("ref", "main").put("return_run_details", true).put("inputs", inputs(session)))
        demand(response.status == 200, "GitHub aceptó el envío sin devolver el ID. Se buscará la ejecución sin repetir el envío.")
        return JSONObject(response.body).getLong("workflow_run_id").also { demand(it > 0, "GitHub no devolvió un ID válido.") }
    }
    /** Recuperación tras un envío de resultado incierto: nunca repetir automáticamente un POST. */
    suspend fun recover(session: PublicationSession, token: String): Long {
        val runs = JSONObject(github.get("${base(session.repository)}/actions/workflows/android-distribute.yml/runs?event=workflow_dispatch&branch=main&head_sha=${session.source}&per_page=100", token)).getJSONArray("workflow_runs")
        val matches = (0 until runs.length()).map { runs.getJSONObject(it) }.filter {
            it.getLong("id") > session.baseline && it.getString("head_sha") == session.source && it.getJSONObject("actor").getString("login") == "robdor80"
        }
        demand(matches.size <= 1, "Hay varias ejecuciones posibles. Revisa GitHub; no se elegirá una automáticamente ni se repetirá el envío.")
        return matches.firstOrNull()?.getLong("id") ?: 0
    }
    suspend fun update(session: PublicationSession, token: String): PublicationSession {
        val id = if (session.runId > 0) session.runId else recover(session, token)
        if (id == 0L) return session.copy(detail = "Envío sin confirmar. Buscando la ejecución; no se volverá a enviar. Revisa GitHub si no aparece.")
        val current = session.copy(runId = id)
        val run = JSONObject(github.get("${base(session.repository)}/actions/runs/$id", token))
        demand(run.getLong("workflow_id") == session.workflowId && run.getString("head_sha") == session.source &&
            run.getString("head_branch") == "main" && run.getString("event") == "workflow_dispatch" &&
            run.getJSONObject("actor").getString("login") == "robdor80" &&
            run.getString("path") == ".github/workflows/android-distribute.yml", "La ejecución no corresponde al workflow y commit esperados.")
        val status = run.getString("status")
        if (status != "completed") {
            val pending = JSONArray(github.get("${base(session.repository)}/actions/runs/$id/pending_deployments", token))
            if (pending.length() > 0 || status == "waiting" || status == "action_required") {
                val names = (0 until pending.length()).joinToString { pending.getJSONObject(it).getJSONObject("environment").getString("name") }
                return current.copy(phase = "requiere aprobación", detail = "Aprueba en GitHub ${names.ifBlank { "el entorno pendiente" }}. Después vuelve a RobPublisher.")
            }
            return current.copy(phase = if (status == "in_progress") "ejecutándose" else "pendiente", detail = "GitHub está ${if (status == "in_progress") "ejecutando" else "preparando"} ${session.operation}.")
        }
        if (run.optString("conclusion") != "success") {
            return current.copy(phase = "error", detail = "GitHub terminó sin éxito (${conclusion(run.optString("conclusion"))}). Revisa en GitHub el paso que falló: alta RS-05, CI de main, versión/notas, permisos o aprobación. No reintentes publish sin revisar posibles tags o borradores parciales.")
        }
        if (session.operation == "prepare") {
            val candidate = session.candidate ?: candidate(current, token)
            return current.copy(prepareRun = id, candidate = candidate, phase = "preparado", detail = "Correcto: candidato preparado. Todavía no se ha publicado.")
        }
        val release = try { JSONObject(github.get("${base(session.repository)}/releases/tags/${encoded(session.tag)}", token)) }
        catch (e: GitHubException) {
            if (e.status == 404) throw PublicationError("El workflow terminó pero no existe una Release visible confirmada. Revisa si el job fue omitido o quedó un borrador. No repitas la publicación sin revisarlo en GitHub.")
            throw e
        }
        val assets = release.getJSONArray("assets")
        val candidate = session.candidate ?: throw PublicationError("Falta el candidato preparado.")
        demand(!release.getBoolean("draft") && release.getBoolean("prerelease") == (session.channel == "dev") &&
            (0 until assets.length()).map { assets.getJSONObject(it) }.any { it.getString("name") == candidate.asset && it.optString("digest") == "sha256:${candidate.sha256}" },
            "El workflow terminó pero no se ha podido confirmar la Release visible y su digest. Revisa GitHub; no vuelvas a publicar automáticamente.")
        return current.copy(phase = "publicado", detail = "Correcto: GitHub ha publicado la Release y confirmado el digest del APK.")
    }
    private suspend fun candidate(session: PublicationSession, token: String): Candidate {
        val matches = mutableListOf<JSONObject>()
        var page = 1
        do {
            val batch = JSONObject(github.get("${base(session.repository)}/actions/runs/${session.runId}/artifacts?per_page=100&page=$page", token)).getJSONArray("artifacts")
            for (i in 0 until batch.length()) {
                val item = batch.getJSONObject(i)
                if (item.getString("name") == "android-candidate-${session.channel}-${session.source}") matches += item
            }
            page++
        } while (batch.length() == 100)
        demand(matches.size == 1 && !matches.single().getBoolean("expired"), "No existe un candidato único vigente. Revisa si el job fue omitido o el artefacto caducó.")
        val artifact = matches.single()
        demand(artifact.getLong("size_in_bytes") <= 600L * 1024 * 1024, "El candidato supera el tamaño admitido.")
        val response = github.request("GET", "${base(session.repository)}/actions/artifacts/${artifact.getLong("id")}/zip", token)
        demand(response.status == 302 && response.location != null, "GitHub no devolvió la descarga temporal del candidato.")
        return parseCandidate(archive.read(response.location!!), session)
    }
    companion object {
        fun inputs(session: PublicationSession): JSONObject {
            demand(session.channel in setOf("release", "dev") && session.source.matches(Regex("[a-f0-9]{40}")), "Canal o commit no válido.")
            demand(session.tag.matches(if (session.channel == "release") Regex("v[0-9]+\\.[0-9]+\\.[0-9]+") else Regex("dev-[A-Za-z0-9][A-Za-z0-9._-]*")), "Etiqueta no válida. Usa vX.Y.Z o dev-X.Y.Z-DEV-N.")
            val inputs = JSONObject().put("operation", session.operation).put("source_sha", session.source).put("channel", session.channel).put("tag", session.tag)
            demand(session.operation in setOf("prepare", "publish"), "Operación no válida.")
            if (session.operation == "publish") {
                val candidate = session.candidate ?: throw PublicationError("Falta el candidato oficial.")
                demand(session.prepareRun > 0 && candidate.sha256.matches(Regex("[a-f0-9]{64}")), "Falta el recibo de preparación.")
                inputs.put("candidate_run", session.prepareRun.toString()).put("expected_sha256", candidate.sha256).put("confirmation", "PUBLICAR ${session.tag}")
            }
            return inputs
        }
        fun parseCandidate(files: Map<String, String>, session: PublicationSession): Candidate = try {
            checkedCandidate(files, session)
        } catch (_: org.json.JSONException) {
            throw PublicationError("Los metadatos del candidato RS-05 no tienen el formato esperado.")
        } catch (_: NoSuchElementException) {
            throw PublicationError("Faltan metadatos oficiales en el candidato.")
        }
        private fun checkedCandidate(files: Map<String, String>, session: PublicationSession): Candidate {
            val contract = JSONObject(files.getValue("robstore-update.json"))
            val receipt = JSONObject(files.getValue("robstore-distribution.json"))
            val sha = contract.getString("sha256")
            val certificate = receipt.getString("certificateSha256")
            val version = contract.getString("versionName"); val code = contract.getInt("versionCode")
            val asset = "Android-${session.channel}.apk"
            demand(contract.getInt("schemaVersion") == 1 && receipt.getInt("schemaVersion") == 1 &&
                contract.getString("channel") == session.channel && contract.getString("packageName") == session.expectedPackage &&
                contract.getString("apkAssetName") == asset && sha.matches(Regex("[a-f0-9]{64}")) && certificate.matches(Regex("[a-f0-9]{64}")) && code > 0 &&
                receipt.getString("sourceCommit") == session.source && receipt.getString("repository") == session.repository &&
                receipt.getString("preparationRun") == session.runId.toString() && receipt.getString("channel") == session.channel &&
                receipt.getString("tag") == session.tag && receipt.getString("apkSha256") == sha && receipt.getString("certificateSha256") == certificate &&
                session.tag == if (session.channel == "release") "v$version" else "dev-$version-$code",
                "El contrato y el recibo no corresponden a este candidato, etiqueta, canal o commit.")
            demand(files.getValue("SHA256SUMS").trim() == "$sha  $asset", "SHA256SUMS no coincide con el candidato oficial.")
            return Candidate(session.expectedPackage, version, code, sha, certificate, asset)
        }
        private fun conclusion(value: String) = when (value) {
            "failure" -> "error"; "cancelled" -> "cancelado"; "timed_out" -> "tiempo agotado"
            "skipped" -> "omitido"; "action_required" -> "requiere intervención"; "stale" -> "obsoleto"
            else -> "resultado no confirmado"
        }
    }
}
