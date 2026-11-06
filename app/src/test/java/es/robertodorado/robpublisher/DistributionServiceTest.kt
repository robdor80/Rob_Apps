package es.robertodorado.robpublisher

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Respuestas simuladas: ninguna petición de red, firma o publicación real. */
class DistributionServiceTest {
    private val repo = "robdor80/Rob_Apps"
    private val source = "a".repeat(40)
    private val hash = "b".repeat(64)
    private val cert = "c".repeat(64)
    private val pkg = "es.robertodorado.robpublisher"
    private fun session() = PublicationSession(repo, source, 55, "release", "v1.0.0", pkg, baseline = 9, runId = 10)
    private fun metadata(session: PublicationSession): MutableMap<String, String> = mutableMapOf(
        "robstore-update.json" to JSONObject().put("schemaVersion", 1).put("channel", session.channel)
            .put("packageName", session.expectedPackage).put("versionName", if (session.channel == "dev") "1.0.0-DEV" else "1.0.0")
            .put("versionCode", 1).put("apkAssetName", "Android-${session.channel}.apk").put("sha256", hash).toString(),
        "robstore-distribution.json" to JSONObject().put("schemaVersion", 1).put("sourceCommit", source)
            .put("channel", session.channel).put("certificateSha256", cert).put("apkSha256", hash)
            .put("repository", repo).put("preparationRun", session.runId.toString()).put("tag", session.tag).toString(),
        "SHA256SUMS" to "$hash  Android-${session.channel}.apk\n"
    )
    private fun run(id: Long, status: String, conclusion: String = ""): JSONObject = JSONObject()
        .put("id", id).put("workflow_id", 55).put("head_sha", source).put("head_branch", "main")
        .put("event", "workflow_dispatch").put("actor", JSONObject().put("login", "robdor80"))
        .put("path", ".github/workflows/android-distribute.yml").put("status", status).put("conclusion", conclusion)
    private fun content(text: String) = JSONObject().put("content", Base64.getEncoder().encodeToString(text.toByteArray())).toString()

    @Test fun prepareApprovalCandidateAndConfirmedPublishAreConnected() = runBlocking {
        var status = "queued"
        var conclusion = ""
        var pending = false
        val posts = mutableListOf<JSONObject>()
        val transport = GitHubTransport { method, path, _, body ->
            val value = when {
                method == "POST" -> {
                    val request = JSONObject(body!!); posts += request
                    return@GitHubTransport ApiResponse(200, JSONObject().put("workflow_run_id", if (posts.size == 1) 10 else 11).toString())
                }
                path == "/user" -> """{"login":"robdor80"}"""
                path == "/repos/$repo" -> """{"fork":false,"archived":false,"default_branch":"main"}"""
                path.endsWith("/actions/workflows/android-distribute.yml") -> """{"id":55,"state":"active"}"""
                path.endsWith("/branches/main") -> JSONObject().put("commit", JSONObject().put("sha", source)).toString()
                path.contains("/contents/.github/workflows/") -> content("on:\n  workflow_dispatch:\njobs:\n  distribute:\n    uses: robdor80/RobStore/.github/workflows/android-distribution-engine.yml@${"d".repeat(40)}\n")
                path.contains("/contents/.github/android-distribution.json") -> content("""{"schemaVersion":1,"repository":"$repo","module":"app","packages":{"release":"$pkg","dev":"$pkg.debug"}}""")
                path.contains("/contents/app/build.gradle.kts") -> content("versionName = \"1.0.0\"\nversionCode = 1")
                path.contains("/pending_deployments") -> if (pending) """[{"environment":{"name":"android-release"}}]""" else "[]"
                path.contains("/artifacts?") -> """{"artifacts":[{"id":99,"name":"android-candidate-release-$source","expired":false,"size_in_bytes":2000}]}"""
                path.endsWith("/artifacts/99/zip") -> return@GitHubTransport ApiResponse(302, "", "https://example.test/temporary-signed-archive")
                path.contains("/releases/tags/") -> """{"draft":false,"prerelease":false,"assets":[{"name":"Android-release.apk","digest":"sha256:$hash"}]}"""
                path.contains("/actions/runs/") -> run(if (posts.size == 1) 10 else 11, status, conclusion).toString()
                else -> error("Petición inesperada: $path")
            }
            ApiResponse(200, value)
        }
        val zip = ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { stream ->
                for ((name, value) in metadata(session()) + ("Android-release.apk" to "APK simulado")) {
                    stream.putNextEntry(ZipEntry(name)); stream.write(value.toByteArray()); stream.closeEntry()
                }
            }
        }.toByteArray()
        val api = DistributionService(GitHubService(transport), CandidateArchive { url ->
            assertEquals("https://example.test/temporary-signed-archive", url)
            ZipCandidateArchive.readMetadata(ByteArrayInputStream(zip))
        })
        val plan = api.plan(repo, "token-simulado")
        assertEquals("v1.0.0", plan.suggestion("release"))
        assertEquals("dev-1.0.0-DEV-1", plan.suggestion("dev"))
        assertEquals(10L, api.dispatch(session().copy(runId = 0), "token-simulado"))
        assertEquals("main", posts.single().getString("ref"))
        assertTrue(posts.single().getBoolean("return_run_details"))
        var state = api.update(session(), "token-simulado")
        assertEquals("pendiente", state.phase)
        pending = true; status = "waiting"
        state = api.update(state, "token-simulado")
        assertEquals("requiere aprobación", state.phase)
        assertTrue(state.detail.contains("android-release"))
        pending = false; status = "in_progress"
        state = api.update(state, "token-simulado")
        assertEquals("ejecutándose", state.phase)
        status = "completed"; conclusion = "success"
        state = api.update(state, "token-simulado")
        assertEquals("preparado", state.phase)
        assertEquals(hash, state.candidate!!.sha256)
        assertEquals(10L, state.prepareRun)
        assertEquals(1, posts.size) // Consultar candidato no publica nada.
        val publish = state.copy(operation = "publish", runId = 0, phase = "pendiente")
        assertEquals(11L, api.dispatch(publish, "token-simulado"))
        val inputs = posts.last().getJSONObject("inputs")
        assertEquals("10", inputs.getString("candidate_run"))
        assertEquals(hash, inputs.getString("expected_sha256"))
        assertEquals("PUBLICAR v1.0.0", inputs.getString("confirmation"))
        assertEquals(source, inputs.getString("source_sha"))
        assertEquals("publicado", api.update(publish.copy(runId = 11), "token-simulado").phase)
        assertEquals(2, posts.size)
        assertEquals(state, PublicationSession.from(state.json()))
    }

    @Test fun mismatchedReceiptHashAndExpiredArtifactCannotBecomeCandidate() = runBlocking {
        val files = metadata(session())
        files["SHA256SUMS"] = "${"f".repeat(64)}  Android-release.apk"
        expectPublicationError { DistributionService.parseCandidate(files, session()) }
        val other = session().copy(source = "e".repeat(40))
        expectPublicationError { DistributionService.parseCandidate(metadata(session()), other) }
        val api = DistributionService(GitHubService(GitHubTransport { _, path, _, _ ->
            ApiResponse(200, if (path.contains("artifacts?")) """{"artifacts":[{"id":99,"name":"android-candidate-release-$source","expired":true,"size_in_bytes":2000}]}"""
                else run(10, "completed", "success").toString())
        }))
        try { api.update(session(), "token-simulado"); fail("Se aceptó un artefacto caducado") }
        catch (e: PublicationError) { assertTrue(e.message!!.contains("vigente")) }
        val dev = session().copy(channel = "dev", tag = "dev-1.0.0-DEV-1", expectedPackage = "$pkg.debug")
        assertEquals("1.0.0-DEV", DistributionService.parseCandidate(metadata(dev), dev).version)
    }

    @Test fun uncertainDispatchIsNotResentAndAmbiguousRunsAreRejected() = runBlocking {
        var posts = 0
        var runs = JSONArray().put(run(10, "queued"))
        val api = DistributionService(GitHubService(GitHubTransport { method, _, _, _ ->
            if (method == "POST") { posts++; ApiResponse(204, "") }
            else ApiResponse(200, JSONObject().put("workflow_runs", runs).toString())
        }))
        val pending = session().copy(runId = 0)
        try { api.dispatch(pending, "token-simulado"); fail("Un 204 no contiene ID") } catch (_: PublicationError) { }
        assertEquals(10L, api.recover(pending, "token-simulado"))
        runs = runs.put(run(11, "queued"))
        try { api.recover(pending, "token-simulado"); fail("Se eligió una ejecución ambigua") } catch (_: PublicationError) { }
        assertEquals(1, posts)
    }

    @Test fun disabledWorkflowApiFailuresAndCancelledRunAreExplained() = runBlocking {
        val disabled = DistributionService(GitHubService(GitHubTransport { _, path, _, _ -> ApiResponse(200, when {
            path == "/user" -> """{"login":"robdor80"}"""
            path == "/repos/$repo" -> """{"fork":false,"default_branch":"main"}"""
            else -> """{"id":55,"state":"disabled_manually"}"""
        }) }))
        try { disabled.plan(repo, "token-simulado"); fail("Workflow deshabilitado aceptado") }
        catch (e: PublicationError) { assertTrue(e.message!!.contains("deshabilitado")) }
        for (code in listOf(401, 403, 422)) {
            val github = GitHubService(GitHubTransport { _, _, _, _ -> ApiResponse(code, "cuerpo privado omitido") })
            try { github.user("token-simulado"); fail("HTTP $code aceptado") }
            catch (e: GitHubException) { assertEquals(code, e.status); assertFalse(e.message!!.contains("privado")) }
        }
        val cancelled = DistributionService(GitHubService(GitHubTransport { _, _, _, _ -> ApiResponse(200, run(10, "completed", "cancelled").toString()) }))
        assertEquals("error", cancelled.update(session(), "token-simulado").phase)
    }
    @Test fun discoveryFindsTopicAndRs05WithoutReleaseAndKeepsManualAlternative() = runBlocking {
        val calls = mutableListOf<String>()
        fun repository(name: String, topic: Boolean) = JSONObject().put("full_name", name)
            .put("private", false).put("archived", false).put("fork", false).put("default_branch", "main")
            .put("topics", if (topic) JSONArray().put("robstore-app") else JSONArray())
        val repositories = JSONArray().put(repository("robdor80/TopicOnly", true))
            .put(repository("robdor80/ConfigOnly", false)).put(repository("robdor80/Manual", false))
            .put(repository("robdor80/Unrelated", false)).put(repository("other/Android", true))
        val github = GitHubService(GitHubTransport { method, path, _, _ ->
            assertEquals("GET", method)
            calls += path
            when {
                path.startsWith("/user/repos?") -> ApiResponse(200, repositories.toString())
                path.contains("/ConfigOnly/contents/.github/android-distribution.json") -> ApiResponse(200,
                    content("""{"schemaVersion":1,"repository":"robdor80/ConfigOnly","packages":{"release":"$pkg","dev":"$pkg.debug"},"notesPath":".github/release-notes.md"}"""))
                path.contains("/ConfigOnly/actions/workflows/") -> ApiResponse(200, """{"state":"active"}""")
                path.contains("/ConfigOnly/contents/.github/workflows/") -> ApiResponse(200,
                    content("on:\n  workflow_dispatch:\njobs:\n  distribute:\n    uses: robdor80/RobStore/.github/workflows/android-distribution-engine.yml@${"d".repeat(40)}\n"))
                path.contains("/ConfigOnly/contents/.github/release-notes.md") -> ApiResponse(200, content("Notas aprobadas de la aplicación."))
                path.contains("/TopicOnly/releases/latest") -> ApiResponse(200, """{"tag_name":"v2.0.0","published_at":"2026-11-06T00:00:00Z"}""")
                else -> ApiResponse(404, "")
            }
        })
        val available = github.repositories("token-simulado")
        val apps = github.discoverApplications(available, listOf("robdor80/ConfigOnly", "robdor80/Manual"), "token-simulado")
        assertEquals(listOf("robdor80/ConfigOnly", "robdor80/Manual", "robdor80/TopicOnly"), apps.map { it.repository.fullName })
        val configured = apps.first()
        assertTrue(configured.discovered)
        assertTrue(configured.integrationReady)
        assertTrue(configured.releaseChecked)
        assertNull(configured.release) // Se descubre antes de su primera Release.
        assertEquals("v2.0.0", apps.last().release!!.tag)
        assertFalse(apps.last().integrationReady)
        assertTrue(apps.last().status.contains("falta .github/android-distribution.json"))
        assertFalse(apps[1].discovered)
        assertTrue(calls.none { it.contains("/other/") })
        assertTrue(calls.none { it.contains("/Unrelated/releases/") })
        val automatic = github.discoverApplications(available, emptyList(), "token-simulado")
        assertEquals(2, automatic.size)
        assertTrue(automatic.all { it.discovered })
    }

    private fun expectPublicationError(block: () -> Unit) {
        try { block(); fail("Candidato incoherente aceptado") } catch (_: PublicationError) { }
    }
}
