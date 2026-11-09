package es.robertodorado.robstore.catalog

import android.content.Intent
import es.robertodorado.robstore.download.*
import es.robertodorado.robstore.install.AndroidInstaller
import es.robertodorado.robstore.inventory.*
import java.io.File
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Uses public metadata/APK downloaded from the real official Release, not invented versions. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RobPublisherCatalogIntegrationTest {
    private val directory get() = File(System.getenv("ROBPUBLISHER_FIXTURE_DIRECTORY")!!)
    private fun json(name: String) = catalogJson.parseToJsonElement(File(directory, name).readText())
    private fun transport() = FixtureTransport().apply {
        repos = listOf(json("repository.json").jsonObject)
        releases = json("releases.json").jsonArray.map { it.jsonObject }
        assets = json("assets.json").jsonArray.map { it.jsonObject }
        manifest = File(directory, "manifest.json").readBytes()
    }
    private fun discovery(transport: FixtureTransport = transport()) =
        CatalogDiscovery(GitHubClient(transport, MemoryCache())) { System.currentTimeMillis() }
    private fun app() = discovery().fetch().apps.single()
    private fun installed(app: CatalogApp, version: Long) = InstallationQuery.Installed(
        InstalledApplication(app.packageName, version, "observed", LauncherQuery.Unavailable))

    @Test fun actualPublishedEntryIsDiscoveredAndRevalidatedUsingItsTag() {
        val transport = transport()
        val discovery = discovery(transport)
        val snapshot = discovery.fetch()
        assertTrue(snapshot.diagnostics.isEmpty())
        val app = snapshot.apps.single()
        assertEquals("Rob_Apps", app.repositoryName)
        assertEquals("RobPublisher", app.name)
        assertEquals("es.robertodorado.robpublisher", app.packageName)
        assertNotNull(app.apk.sha256)
        assertTrue(CatalogValidation.app(app))
        discovery.revalidate(app)
        val requests = transport.requests.filter { it.url.encodedPath.endsWith("/robstore.json") }
        assertTrue(requests.isNotEmpty())
        assertTrue(requests.all { it.url.queryParameter("ref") == app.release.tag })
    }

    @Test fun catalogTopicIsRequiredIndependentlyOfDistributionPolicy() {
        val transport = transport()
        transport.repos = transport.repos.map { JsonObject(it + ("topics" to JsonArray(emptyList()))) }
        assertTrue(discovery(transport).fetch().apps.isEmpty())
    }

    @Test fun officialPreviousVersionOffersUpdateAndCurrentVersionDoesNot() {
        val app = app()
        val published = app.release.versionCode!!
        assertEquals(VersionComparison.PUBLISHED_NEWER, compareVersions(app.release, installed(app, 3)))
        assertEquals(VersionComparison.SAME, compareVersions(app.release, installed(app, published)))
        assertEquals(VersionComparison.PUBLISHED_OLDER, compareVersions(app.release, installed(app, published + 1)))
    }

    private fun prepare(app: CatalogApp): Pair<PreparedApk, DownloadFiles> {
        val context = RuntimeEnvironment.getApplication()
        val files = DownloadFiles(File(context.filesDir, "apk-downloads"))
        files.cleanup()
        val partial = files.partial()
        File(directory, "Android-release.apk").copyTo(partial)
        return PreparedApk.verify(partial, app, files, AndroidArchiveInspector(context.packageManager),
            DownloadCancellation(), System.currentTimeMillis()) to files
    }

    @Test fun realOfficialApkPassesRobStoreDigestAndAndroidArchiveChecks() {
        val app = app()
        val (apk, files) = prepare(app)
        assertTrue(apk.officialDigestMatched)
        assertEquals(app.apk.sha256, apk.localSha256)
        assertEquals(app.packageName, apk.metadata.packageName)
        assertEquals(app.release.versionCode, apk.metadata.versionCode)
        assertEquals(app.release.versionName, apk.metadata.versionName)
        apk.recheck(files, AndroidArchiveInspector(RuntimeEnvironment.getApplication().packageManager),
            DownloadCancellation(), System.currentTimeMillis())
    }

    @Test fun officialApkIsHandedToAndroidInstallerWithReadOnlyContentUri() {
        val context = RuntimeEnvironment.getApplication()
        val (apk, _) = prepare(app())
        val intent = AndroidInstaller(context).intent(apk)
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("content", intent.data!!.scheme)
        assertEquals("application/vnd.android.package-archive", intent.type)
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, intent.flags)
        assertEquals(0, intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        assertNotEquals(Intent.ACTION_DELETE, intent.action)
        assertEquals(intent.data, intent.clipData!!.getItemAt(0).uri)
    }

    @Test fun changedOfficialDigestIsRejectedBeforeInstallation() {
        val app = app().let { it.copy(apk = it.apk.copy(sha256 = "0".repeat(64))) }
        assertThrows(DownloadIntegrity::class.java) { prepare(app) }
    }
}
