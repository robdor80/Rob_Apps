package es.robertodorado.robpublisher

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** El token se cifra con AES-GCM; la clave no exportable permanece en Android Keystore. */
internal class LocalStore(context: Context) : DeviceAuthStore {
    private val preferences = context.getSharedPreferences("robpublisher", Context.MODE_PRIVATE)
    private val alias = "robpublisher.github"
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun saveToken(token: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val encrypted = cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        check(preferences.edit().putString("token", Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP)).commit())
    }
    fun token(): String? {
        val encrypted = preferences.getString("token", null) ?: return null
        val iv = preferences.getString("iv", null) ?: error("Falta el vector de cifrado")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
        }
        return String(cipher.doFinal(Base64.decode(encrypted, Base64.NO_WRAP)), Charsets.UTF_8)
    }
    /** Client ID público de RobGit RD: no es token ni secreto. */
    fun oauthClientId(): String = preferences.getString("oauth_client_id", "").orEmpty()
    fun saveOAuthClientId(value: String) {
        require(GitHubOAuth.validClientId(value))
        check(preferences.edit().putString("oauth_client_id", value).commit())
    }
    internal fun oauth(): OAuthCredentials? = readEncrypted("oauth")?.let { data ->
        OAuthCredentials(data.getString("access"), data.getString("refresh"),
            data.getLong("accessExpiry"), data.getLong("refreshExpiry"), data.getString("login"))
    }
    private fun credentialsJson(value: OAuthCredentials) = org.json.JSONObject()
        .put("access", value.accessToken).put("refresh", value.refreshToken)
        .put("accessExpiry", value.accessExpiresAt).put("refreshExpiry", value.refreshExpiresAt)
        .put("login", value.login)
    internal fun saveOAuth(value: OAuthCredentials) = writeEncrypted("oauth", credentialsJson(value))
    override fun completeAuth(value: OAuthCredentials) =
        writeEncrypted("oauth", credentialsJson(value), clearAttempt = true)
    fun discardLegacyToken() {
        check(preferences.edit().remove("token").remove("iv").commit())
    }
    override fun pendingAuth(): PendingDeviceAuth? {
        val data = readEncrypted("device_auth") ?: return null
        val device = DeviceAuthorization(data.getString("device"), data.getString("user"),
            data.getString("uri"), data.getLong("expires"), data.getLong("interval"))
        check(device.verificationUri == "https://github.com/login/device")
        val candidate = data.optJSONObject("candidate")?.let {
            OAuthCredentials(it.getString("access"), it.getString("refresh"), it.getLong("accessExpiry"),
                it.getLong("refreshExpiry"), "")
        }
        return PendingDeviceAuth(device, data.getLong("interval"), data.getLong("nextPoll"),
            candidate, data.optLong("validationDeadline"))
    }
    override fun savePendingAuth(value: PendingDeviceAuth?) {
        val data = value?.let { p ->
            org.json.JSONObject().put("device", p.authorization.deviceCode).put("user", p.authorization.userCode)
                .put("uri", p.authorization.verificationUri).put("expires", p.authorization.expiresAt)
                .put("interval", p.intervalSeconds).put("nextPoll", p.nextPollAt)
                .put("validationDeadline", p.validationDeadline).apply {
                    p.credentials?.let { put("candidate", org.json.JSONObject()
                        .put("access", it.accessToken).put("refresh", it.refreshToken)
                        .put("accessExpiry", it.accessExpiresAt).put("refreshExpiry", it.refreshExpiresAt)) }
                }
        }
        writeEncrypted("device_auth", data)
    }
    private fun readEncrypted(name: String): org.json.JSONObject? {
        val encrypted = preferences.getString(name, null) ?: return null
        val iv = preferences.getString("${name}_iv", null) ?: error("Falta el vector de cifrado")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
        }
        return org.json.JSONObject(String(cipher.doFinal(Base64.decode(encrypted, Base64.NO_WRAP)), Charsets.UTF_8))
    }
    private fun writeEncrypted(name: String, data: org.json.JSONObject?, clearAttempt: Boolean = false) {
        val editor = preferences.edit()
        if (clearAttempt) editor.remove("device_auth").remove("device_auth_iv").remove("token").remove("iv")
        if (data == null) { check(editor.remove(name).remove("${name}_iv").commit()); return }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val encrypted = cipher.doFinal(data.toString().toByteArray(Charsets.UTF_8))
        check(editor.putString(name, Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .putString("${name}_iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP)).commit())
    }
    fun clearOAuth() {
        check(preferences.edit().remove("oauth").remove("oauth_iv").commit())
    }
    fun disconnect() {
        check(preferences.edit().remove("token").remove("iv")
            .remove("oauth").remove("oauth_iv").remove("device_auth").remove("device_auth_iv").commit())
        KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
    }
    fun publication(): PublicationSession? = preferences.getString("publication", null)?.let {
        PublicationSession.from(org.json.JSONObject(it))
    }
    fun savePublication(session: PublicationSession?) {
        check(preferences.edit().putString("publication", session?.json()?.toString()).commit())
    }
    fun repositories(): List<String> = preferences.getStringSet("repositories", emptySet())!!.sorted()
    fun saveRepositories(names: List<String>) {
        check(preferences.edit().putStringSet("repositories", names.toSet()).commit())
    }
}
