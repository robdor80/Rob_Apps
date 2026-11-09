package es.robertodorado.robpublisher

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.Base64
import java.net.URLEncoder

internal fun usesRs05Workflow(repo: String, text: String): Boolean {
    val central = Regex("(?m)^\\s*uses:\\s*['\"]?robdor80/RobStore/\\.github/workflows/android-distribution-engine\\.yml@main['\"]?\\s*(?:#.*)?$")
    val local = Regex("(?m)^\\s*uses:\\s*['\"]?\\./\\.github/workflows/android-distribution-engine\\.yml['\"]?\\s*(?:#.*)?$")
    return "workflow_dispatch:" in text && (central.containsMatchIn(text) || repo == "robdor80/RobStore" && local.containsMatchIn(text))
}
class PublicationError(message: String) : Exception(message)
internal fun demand(condition: Boolean, message: String) { if (!condition) throw PublicationError(message) }
internal fun InputStream.readLimited(limit: Int): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        demand(output.size() + count <= limit, "La respuesta de GitHub es demasiado grande.")
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}
data class DistributionPlan(val repository: String, val workflowId: Long)
/** Estado interno persistente: la interfaz no muestra ni solicita identificadores. */
data class PublicationSession(val repository: String, val workflowId: Long,
    val channel: String = "release", val baseline: Long = 0, val runId: Long = 0,
    val phase: String = "pendiente", val detail: String = "Orden enviada. Esperando a GitHub.", val tag: String = "",
    val stages: Map<String, String> = emptyMap()) {
    val watching get() = phase in setOf("pendiente", "ejecutándose")
    fun json(): JSONObject = JSONObject().put("schemaVersion", 2).put("repository", repository)
        .put("workflowId", workflowId).put("channel", channel).put("baseline", baseline).put("runId", runId)
        .put("phase", phase).put("detail", detail).put("tag", tag).put("stages", JSONObject(stages))
    companion object {
        fun from(json: JSONObject): PublicationSession {
            val legacy = json.optInt("schemaVersion") != 2
            return PublicationSession(json.getString("repository"), json.getLong("workflowId"),
                json.optString("channel", "release"), json.optLong("baseline"), json.optLong("runId"),
                if (legacy) "error" else json.getString("phase"),
                if (legacy) "Este seguimiento usa el procedimiento antiguo. Actualiza la integración de la aplicación antes de distribuir de nuevo. No se ha enviado otra orden." else json.getString("detail"),
                json.optString("tag"),
                json.optJSONObject("stages")?.let { steps ->
                    listOf("guard", "build", "sign", "publish").mapNotNull { key ->
                        steps.optString(key).takeIf { it in setOf("pendiente", "en curso", "completado", "fallido") }?.let { key to it }
                    }.toMap()
                } ?: emptyMap())
        }
    }
}
class DistributionService(private val github: GitHubService) {
    private fun stages(jobs: List<JSONObject>): Map<String, String> {
        val names = setOf("guard", "build", "sign", "publish")
        return jobs.mapNotNull { job ->
            val step = job.optString("name").substringAfterLast("/ ").trim()
            if (step !in names) null else {
                val state = when {
                    job.optString("status") == "completed" && job.optString("conclusion") == "success" -> "completado"
                    job.optString("status") == "completed" && job.optString("conclusion") in setOf("failure", "cancelled", "timed_out") -> "fallido"
                    job.optString("status") == "in_progress" -> "en curso"
                    else -> "pendiente"
                }
                step to state
            }
        }.toMap()
    }
    private fun base(repo: String): String {
        demand(repo.matches(Regex("robdor80/[A-Za-z0-9_.-]+")), "Selecciona una aplicación de robdor80.")
        return "/repos/$repo"
    }
    private suspend fun content(repo: String, path: String, token: String): String {
        val json = JSONObject(github.get("${base(repo)}/contents/$path?ref=main", token))
        return Base64.getMimeDecoder().decode(json.getString("content")).toString(Charsets.UTF_8)
    }
    suspend fun plan(repo: String, token: String): DistributionPlan {
        val workflow = JSONObject(github.get("${base(repo)}/actions/workflows/android-distribute.yml", token))
        demand(workflow.getString("state") == "active", "La distribución de esta aplicación está deshabilitada.")
        demand(usesRs05Workflow(repo, content(repo, ".github/workflows/android-distribute.yml", token)),
            "Esta aplicación necesita la migración única al motor automático.")
        return DistributionPlan(repo, workflow.getLong("id"))
    }
    suspend fun latestRunId(repo: String, token: String): Long = JSONObject(github.get(
        "${base(repo)}/actions/workflows/android-distribute.yml/runs?per_page=1", token)).getJSONArray("workflow_runs")
        .let { if (it.length() == 0) 0 else it.getJSONObject(0).getLong("id") }

    suspend fun dispatch(session: PublicationSession, token: String): Long {
        val response = github.request("POST", "${base(session.repository)}/actions/workflows/android-distribute.yml/dispatches", token,
            JSONObject().put("ref", "main").put("return_run_details", true).put("inputs", inputs(session)))
        // Older GitHub responses return 204: recover by polling, without sending the order again.
        return if (response.status == 200) JSONObject(response.body).getLong("workflow_run_id") else 0
    }
    suspend fun recover(session: PublicationSession, token: String): Long {
        val runs = JSONObject(github.get("${base(session.repository)}/actions/workflows/android-distribute.yml/runs?event=workflow_dispatch&branch=main&per_page=100", token)).getJSONArray("workflow_runs")
        val matches = (0 until runs.length()).map { runs.getJSONObject(it) }.filter {
            it.getLong("id") > session.baseline &&
                it.getJSONObject("actor").getString("login") == "robdor80"
        }
        demand(matches.size <= 1, "Se encontraron varias distribuciones. No se repetirá la orden; su resultado aún no se puede asociar automáticamente.")
        return matches.firstOrNull()?.getLong("id") ?: 0
    }
    suspend fun update(session: PublicationSession, token: String): PublicationSession {
        val id = if (session.runId > 0) session.runId else recover(session, token)
        if (id == 0L) return session.copy(detail = "Buscando la orden en GitHub. No tienes que enviarla de nuevo.")
        val current = session.copy(runId = id)
        val run = JSONObject(github.get("${base(session.repository)}/actions/runs/$id", token))
        demand(run.getLong("workflow_id") == session.workflowId && run.getString("event") == "workflow_dispatch" &&
            run.getString("head_branch") == "main", "La ejecución recibida no corresponde a esta distribución.")
        if (run.getString("status") in setOf("waiting", "action_required")) {
            return current.copy(phase = "error", detail = "GitHub requiere una aprobación. Consulta la ejecución y respeta las protecciones configuradas; no vuelvas a enviar la orden.")
        }
        val jobs = JSONObject(github.get("${base(session.repository)}/actions/runs/$id/jobs?per_page=100", token)).getJSONArray("jobs")
        val list = (0 until jobs.length()).map { jobs.getJSONObject(it) }
        val currentStages = stages(list)
        if (run.getString("status") != "completed") {
            val active = list.firstOrNull { it.optString("status") == "in_progress" }?.optString("name").orEmpty()
            val detail = when {
                active.endsWith("/ build") -> "Compilando la aplicación y comprobando el APK…"
                active.endsWith("/ sign") -> "Firmando automáticamente la aplicación…"
                active.endsWith("/ publish") -> "Publicando la actualización…"
                active.endsWith("/ guard") -> "Preparando la distribución…"
                else -> "Esperando un equipo disponible de GitHub…"
            }
            return current.copy(phase = if (run.getString("status") == "in_progress") "ejecutándose" else "pendiente", detail = detail, stages = currentStages)
        }
        if (run.optString("conclusion") != "success") {
            val job = list.firstOrNull { it.optString("conclusion") in setOf("failure", "timed_out", "cancelled") }
            val failed = job?.optString("name").orEmpty()
            val safePhase = failed.substringAfterLast("/ ").takeIf { it in setOf("guard", "build", "sign", "publish") } ?: "preparación"
            // Only classify known annotations. Never render arbitrary remote logs or exception bodies.
            val annotations = try {
                val url = job?.optString("check_run_url").orEmpty()
                val prefix = "https://api.github.com${base(session.repository)}/check-runs/"
                if (url.startsWith(prefix) && url.removePrefix(prefix).all { it.isDigit() } && url.removePrefix(prefix).isNotEmpty())
                    github.get(url.removePrefix("https://api.github.com") + "/annotations?per_page=100", token) else ""
            } catch (_: GitHubException) { "" }
            val detail = when {
                annotations.contains("versionCode must strictly increase") -> "La versión publicada ya existe o su código es demasiado bajo. Actualiza la información y prepara una nueva versión mediante PR."
                annotations.contains("Resource not accessible") || annotations.contains("HTTP 403") -> "GitHub denegó permisos o limitó las consultas. Revisa el acceso existente; no generes ni compartas tokens."
                run.optString("conclusion") == "cancelled" -> "La distribución se ha cancelado."
                failed.endsWith("/ build") -> "No se pudo compilar la aplicación. Hay que corregir el proyecto antes de distribuir."
                failed.endsWith("/ sign") -> "No se pudo firmar con la clave existente. Hay que corregir una vez su configuración; no debes firmar manualmente ni crear otra clave."
                failed.endsWith("/ publish") -> "No se pudo completar la publicación. La actualización no está confirmada; puede haber un borrador incompleto."
                else -> "No se pudo iniciar la distribución. Comprueba que el alta inicial de la aplicación esté completa."
            }
            return current.copy(phase = "error", detail = "Fase: $safePhase. $detail", stages = currentStages)
        }
        val releases = org.json.JSONArray(github.get("${base(session.repository)}/releases?per_page=100", token))
        var published: JSONObject? = null
        for (index in 0 until releases.length()) {
            val release = releases.getJSONObject(index)
            if (release.getBoolean("draft") || release.getBoolean("prerelease") != (session.channel == "dev")) continue
            val tag = release.getString("tag_name")
            val receipt = try {
                val file = JSONObject(github.get("${base(session.repository)}/contents/robstore-distribution.json?ref=${URLEncoder.encode("refs/tags/$tag", "UTF-8")}", token))
                JSONObject(Base64.getMimeDecoder().decode(file.getString("content")).toString(Charsets.UTF_8))
            } catch (e: GitHubException) { if (e.status == 404) continue else throw e }
            if (receipt.optString("preparationRun") == id.toString() && receipt.optString("channel") == session.channel &&
                receipt.optString("repository") == session.repository) { published = release; break }
        }
        val release = published ?: throw PublicationError("GitHub terminó, pero todavía no se ha confirmado la actualización publicada.")
        val tag = release.getString("tag_name")
        val assets = release.getJSONArray("assets")
        demand((0 until assets.length()).any { assets.getJSONObject(it).optString("name") == "Android-${session.channel}.apk" &&
            assets.getJSONObject(it).optString("state") == "uploaded" }, "No se encuentra el APK publicado.")
        return current.copy(phase = "publicado", tag = tag, stages = currentStages, detail = if (session.channel == "release")
            "Actualización publicada. Ya puede aparecer en RobStore al actualizar el catálogo." else "Versión de pruebas publicada.")
    }
    companion object {
        fun inputs(session: PublicationSession): JSONObject {
            demand(session.channel == "release", "Solo se permiten versiones definitivas.")
            return JSONObject().put("channel", session.channel)
        }
    }
}
