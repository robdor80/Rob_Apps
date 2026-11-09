package es.robertodorado.robpublisher

import org.junit.Assert.*
import org.junit.Test

class VersionPreflightTest {
    @Test fun repeatedAndLowerCodesAreBlocked() {
        assertFalse(VersionPreflight(2, 2, "1.0.1").allowed)
        assertFalse(VersionPreflight(2, 1, "1.0.0").allowed)
        assertTrue(VersionPreflight(2, 2, "1.0.1").message.contains("Código mínimo necesario: 3"))
    }
    @Test fun greaterCodeAllowed() { assertTrue(VersionPreflight(2, 3, "1.0.2").allowed) }
    @Test fun unknownDynamicVersionsAreBlocked() {
        assertFalse(VersionPreflight(2, null, null).allowed)
        for (source in listOf("versionCode = calculate()", "versionCode = 2 + 1", "versionCode = 2\nversionCode = 3", "/* versionCode = 3 */"))
            assertNull(VersionPreflightService.literal(source, "versionCode"))
        assertEquals("3", VersionPreflightService.literal("    versionCode = 3\n", "versionCode"))
    }

    @Test fun allPublishedContractsCountButDraftsDoNot() = kotlinx.coroutines.runBlocking {
        fun encoded(s: String) = org.json.JSONObject().put("content", java.util.Base64.getEncoder().encodeToString(s.toByteArray())).toString()
        val github = GitHubService(GitHubTransport { method, path, _, _ ->
            assertEquals("GET", method)
            val body = when {
                path.endsWith("/branches/main") -> """{"commit":{"sha":"abc"}}"""
                path.contains("android-distribution.json") -> encoded("""{"module":"app","packages":{"release":"es.test.app"}}""")
                path.contains("android-original-identities.json") -> encoded("""{"applications":[]}""")
                path.contains("/releases?") -> """[{"tag_name":"old","draft":false,"prerelease":false},{"tag_name":"draft","draft":true,"prerelease":false}]"""
                path.contains("robstore-update.json") -> encoded("""{"packageName":"es.test.app","versionCode":4}""")
                path.contains("build.gradle.kts") -> encoded("versionCode = 3")
                else -> error(path)
            }
            ApiResponse(200, body)
        })
        val result = VersionPreflightService(github).inspect("robdor80/Test", "fixture")
        assertEquals(4L, result.maximum)
        assertFalse(result.allowed)
    }
    @Test fun permissionFailureDoesNotDispatch() = kotlinx.coroutines.runBlocking {
        val github = GitHubService(GitHubTransport { method, _, _, _ ->
            assertEquals("GET", method); ApiResponse(403, "")
        })
        try {
            VersionPreflightService(github).inspect("robdor80/Test", "fixture")
            fail("Expected permission rejection")
        } catch (e: GitHubException) { assertEquals(403, e.status) }
    }
}
