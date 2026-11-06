package es.robertodorado.robpublisher

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder

/** Identificador público de nuestra GitHub App, sin credenciales ni secretos. */
internal const val ROBGIT_RD_CLIENT_ID = "Iv23liwEJPI2xtkpLqfF"

/** Sesión de usuario de la GitHub App RobGit RD. Nunca se guarda en texto claro. */
internal class OAuthCredentials(
    val accessToken: String,
    val refreshToken: String,
    val accessExpiresAt: Long,
    val refreshExpiresAt: Long,
    val login: String,
)

internal class DeviceAuthorization(
    val deviceCode: String,
    val userCode: String,
    val verificationUri: String,
    val expiresAt: Long,
    val intervalSeconds: Long,
)

internal sealed interface DevicePoll {
    data object Waiting : DevicePoll
    data object SlowDown : DevicePoll
    data class Authorized(val token: OAuthCredentials) : DevicePoll
    data class Failed(val explanation: String) : DevicePoll
}

internal class OAuthFailure(message: String) : Exception(message)

/** La autorización por dispositivo no requiere client_secret, PAT ni callback de RobGit. */
internal class GitHubOAuth(
    private val now: () -> Long = System::currentTimeMillis,
) {
    suspend fun begin(clientId: String): DeviceAuthorization {
        require(validClientId(clientId)) { "ID de cliente de RobGit RD incorrecto." }
        val result = post("/login/device/code", mapOf("client_id" to clientId, "scope" to "offline_access"))
        if (result.has("error")) throw OAuthFailure("GitHub no permite iniciar la conexión. Comprueba que Device Flow está habilitado en RobGit RD.")
        val url = result.getString("verification_uri")
        if (url != "https://github.com/login/device") throw OAuthFailure("GitHub devolvió una dirección de autorización inesperada.")
        val lifetime = result.getLong("expires_in").coerceIn(1, 900)
        return DeviceAuthorization(
            result.getString("device_code"),
            result.getString("user_code"),
            url,
            now() + lifetime * 1000,
            result.optLong("interval", 5).coerceIn(5, 60),
        )
    }

    suspend fun poll(clientId: String, pending: DeviceAuthorization): DevicePoll {
        if (now() >= pending.expiresAt) return DevicePoll.Failed("La autorización ha caducado. Vuelve a conectar con GitHub.")
        val result = post("/login/oauth/access_token", mapOf(
            "client_id" to clientId,
            "device_code" to pending.deviceCode,
            "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
        ))
        return when (result.optString("error")) {
            "authorization_pending" -> DevicePoll.Waiting
            "slow_down" -> DevicePoll.SlowDown
            "access_denied" -> DevicePoll.Failed("No se autorizó la conexión con GitHub.")
            "expired_token", "bad_verification_code" -> DevicePoll.Failed("El código de autorización ha caducado.")
            "device_flow_disabled" -> DevicePoll.Failed("Activa Device Flow en la configuración de RobGit RD.")
            "" -> DevicePoll.Authorized(credentials(result))
            else -> DevicePoll.Failed("GitHub ha rechazado la autorización. Vuelve a intentarlo.")
        }
    }

    suspend fun refresh(clientId: String, old: OAuthCredentials): OAuthCredentials {
        val result = post("/login/oauth/access_token", mapOf(
            "client_id" to clientId,
            "grant_type" to "refresh_token",
            "refresh_token" to old.refreshToken,
        ))
        if (result.has("error")) throw OAuthFailure("La autorización de GitHub ha caducado o se ha revocado. Vuelve a conectarte.")
        return credentials(result, old.login)
    }

    private fun credentials(result: JSONObject, login: String = ""): OAuthCredentials {
        if (!result.optString("token_type").equals("bearer", true)) {
            throw OAuthFailure("Respuesta de autenticación inesperada.")
        }
        val access = result.getString("access_token")
        val refresh = result.optString("refresh_token")
        val accessSeconds = result.optLong("expires_in")
        val refreshSeconds = result.optLong("refresh_token_expires_in")
        if (access.isBlank() || refresh.isBlank() || accessSeconds <= 0 || refreshSeconds <= 0) {
            throw OAuthFailure("GitHub no ha proporcionado credenciales renovables. Revisa la configuración de RobGit RD.")
        }
        val instant = now()
        return OAuthCredentials(
            access, refresh,
            instant + accessSeconds * 1000,
            instant + refreshSeconds * 1000,
            login,
        )
    }

    private suspend fun post(path: String, fields: Map<String, String>): JSONObject = withContext(Dispatchers.IO) {
        require(path in setOf("/login/device/code", "/login/oauth/access_token"))
        val connection = URI("https://github.com$path").toURL().openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            connection.setRequestProperty("User-Agent", "RobPublisher/1.1")
            val body = fields.entries.joinToString("&") { (key, value) ->
                "${URLEncoder.encode(key, "UTF-8")}=${URLEncoder.encode(value, "UTF-8")}"
            }.toByteArray(Charsets.UTF_8)
            connection.doOutput = true
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { it.write(body) }
            val status = connection.responseCode
            if (status !in 200..299) {
                throw OAuthFailure(if (status == 403 || status == 429)
                    "GitHub está limitando la conexión. Reinténtalo más tarde."
                else "GitHub ha rechazado la conexión (HTTP $status).")
            }
            val response = connection.inputStream.use { it.readLimited(32 * 1024).toString(Charsets.UTF_8) }
            JSONObject(response)
        } catch (e: OAuthFailure) {
            throw e
        } catch (_: java.io.IOException) {
            throw OAuthFailure("No se ha podido conectar con GitHub. Comprueba Internet.")
        } catch (_: org.json.JSONException) {
            throw OAuthFailure("GitHub ha devuelto una respuesta de autorización incorrecta.")
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        fun validClientId(id: String): Boolean =
            id.length in 8..128 && id.all { it.isLetterOrDigit() || it == '_' || it == '-' || it == '.' }
    }
}
