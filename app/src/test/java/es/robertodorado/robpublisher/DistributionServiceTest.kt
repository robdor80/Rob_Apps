package es.robertodorado.robpublisher

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

/** HTTP fixtures only: no real publication or signing. */
class DistributionServiceTest {
    private val repo = "robdor80/Rob_Apps"
    private val source = "a".repeat(40)
    private val pkg = "es.robertodorado.robpublisher"
    private fun session() = PublicationSession(repo, 55, baseline = 9, runId = 10)
    private fun content(text: String) = JSONObject().put("content", Base64.getEncoder().encodeToString(text.toByteArray())).toString()
    private fun run(status: String, conclusion: String = "") = JSONObject().put("id", 10).put("workflow_id", 55)
        .put("head_sha", source).put("head_branch", "main").put("event", "workflow_dispatch")
        .put("actor", JSONObject().put("login", "robdor80")).put("status", status).put("conclusion", conclusion)

    @Test fun oneOrderBuildsSignsAndPublishesWithoutAnotherPost() = runBlocking {
        val posts = mutableListOf<JSONObject>()
        var status = "in_progress"
        var job = "build"
        val api = DistributionService(GitHubService(GitHubTransport { method, path, _, body ->
            val value = when {
                method == "POST" -> { posts += JSONObject(body!!); """{"workflow_run_id":10}""" }
                path.endsWith("/jobs?per_page=100") -> JSONObject().put("jobs", JSONArray().apply {
                    listOf("guard", "build", "sign", "publish").forEach { step ->
                        val state = if (status == "completed" || step == "guard") "completed"
                            else if (step == job) "in_progress" else "queued"
                        put(JSONObject().put("name", "distribute / $step")
                            .put("status", state).put("conclusion", if (state == "completed") "success" else ""))
                    }
                }).toString()
                path.contains("/actions/runs/") -> run(status, if (status == "completed") "success" else "").toString()
                path.contains("/releases?") -> """[{"tag_name":"v1.0.0","draft":false,"prerelease":false,"assets":[{"name":"Android-release.apk","state":"uploaded"}]}]"""
                path.contains("/contents/robstore-distribution.json") -> content("""{"preparationRun":"10","channel":"release","repository":"$repo"}""")
                else -> error("Unexpected request: $path")
            }
            ApiResponse(200, value)
        }))
        assertEquals(10L, api.dispatch(session(), "test-token"))
        assertEquals(1, posts.single().getJSONObject("inputs").length())
        assertEquals("release", posts.single().getJSONObject("inputs").getString("channel"))
        val building = api.update(session(), "test-token")
        assertTrue(building.detail.contains("Compilando"))
        assertEquals("completado", building.stages["guard"])
        assertEquals("en curso", building.stages["build"])
        assertEquals("pendiente", building.stages["sign"])
        assertEquals("pendiente", building.stages["publish"])
        job = "sign"; assertEquals("en curso", api.update(session(), "test-token").stages["sign"])
        job = "publish"; assertEquals("en curso", api.update(session(), "test-token").stages["publish"])
        status = "completed"
        val complete = api.update(session(), "test-token")
        assertEquals("publicado", complete.phase)
        assertTrue(complete.stages.values.all { it == "completado" })
        assertEquals("v1.0.0", complete.tag)
        assertEquals(complete, PublicationSession.from(complete.json()))
        assertEquals(1, posts.size)
    }

    @Test fun lostResponseIsRecoveredWithoutRepeatingOrder() = runBlocking {
        var posts = 0
        val api = DistributionService(GitHubService(GitHubTransport { method, _, _, _ ->
            if (method == "POST") { posts++; ApiResponse(204, "") }
            else ApiResponse(200, JSONObject().put("workflow_runs", JSONArray().put(run("queued"))).toString())
        }))
        val pending = session().copy(runId = 0)
        assertEquals(0L, api.dispatch(pending, "test-token"))
        assertEquals(10L, api.recover(pending, "test-token"))
        assertEquals(1, posts)
    }

    @Test fun waitingKeepsTrackingUntilRealFailureWithoutResendingOrder() = runBlocking {
        var waiting = true
        var posts = 0
        val api = DistributionService(GitHubService(GitHubTransport { method, path, _, _ ->
            if (method == "POST") posts++
            ApiResponse(200, if (path.contains("/jobs?")) """{"jobs":[{"name":"distribute / sign","conclusion":"failure"}]}"""
                else run(if (waiting) "waiting" else "completed", "failure").toString())
        }))
        val pending = api.update(session(), "test-token")
        assertEquals("pendiente", pending.phase)
        assertTrue(pending.watching)
        assertTrue(pending.detail.contains("espera"))
        assertFalse(pending.detail.contains("requiere una aprobación"))
        waiting = false
        val error = api.update(pending, "test-token")
        assertEquals("error", error.phase)
        assertTrue(error.detail.contains("clave existente"))
        assertEquals(0, posts)
    }

    @Test fun waitingCanResumeAndPublishAutomaticallyWithoutResendingOrder() = runBlocking {
        var status = "waiting"
        var posts = 0
        val api = DistributionService(GitHubService(GitHubTransport { method, path, _, _ ->
            if (method == "POST") posts++
            val body = when {
                path.endsWith("/jobs?per_page=100") -> JSONObject().put("jobs", JSONArray().put(
                    JSONObject().put("name", "distribute / sign").put("status",
                        if (status == "completed") "completed" else "queued").put("conclusion",
                        if (status == "completed") "success" else ""))).toString()
                path.contains("/actions/runs/") -> run(status, if (status == "completed") "success" else "").toString()
                path.contains("/releases?") -> """[{"tag_name":"v1.0.6-7","draft":false,"prerelease":false,"assets":[{"name":"Android-release.apk","state":"uploaded"}]}]"""
                path.contains("/contents/robstore-distribution.json") -> content(
                    """{"preparationRun":"10","channel":"release","repository":"$repo"}""")
                else -> error("Unexpected request: $path")
            }
            ApiResponse(200, body)
        }))
        val waiting = api.update(session(), "test-token")
        assertTrue(waiting.watching)
        assertEquals("pendiente", waiting.phase)
        status = "in_progress"
        assertEquals("ejecutándose", api.update(waiting, "test-token").phase)
        status = "completed"
        val complete = api.update(waiting, "test-token")
        assertEquals("publicado", complete.phase)
        assertEquals("v1.0.6-7", complete.tag)
        assertEquals(0, posts)
    }

    @Test fun genuineActionRequiredDoesNotBypassProtectionsOrSendNewOrder() = runBlocking {
        var posts = 0
        val api = DistributionService(GitHubService(GitHubTransport { method, _, _, _ ->
            if (method == "POST") posts++
            ApiResponse(200, run("action_required").toString())
        }))
        val result = api.update(session(), "test-token")
        assertEquals("error", result.phase)
        assertTrue(result.detail.contains("intervención"))
        assertFalse(result.watching)
        assertEquals(0, posts)
    }

    @Test fun wrapperUsesCentralMainAndLegacySessionCannotResumePreparation() {
        assertTrue(usesRs05Workflow(repo, "workflow_dispatch:\n    uses: robdor80/RobStore/.github/workflows/android-distribution-engine.yml@main"))
        assertFalse(usesRs05Workflow(repo, "workflow_dispatch:\n    uses: robdor80/RobStore/.github/workflows/android-distribution-engine.yml@${"d".repeat(40)}"))
        val metadata = session().json(); metadata.remove("schemaVersion")
        val migrated = PublicationSession.from(metadata)
        assertEquals("error", migrated.phase)
        assertFalse(migrated.watching)
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
                    content("on:\n  workflow_dispatch:\njobs:\n  distribute:\n    uses: robdor80/RobStore/.github/workflows/android-distribution-engine.yml@main\n"))
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

}
