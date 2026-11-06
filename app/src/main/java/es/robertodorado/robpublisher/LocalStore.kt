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
class LocalStore(context: Context) {
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
    internal fun oauth(): OAuthCredentials? {
        val encrypted = preferences.getString("oauth", null) ?: return null
        val iv = preferences.getString("oauth_iv", null) ?: error("Falta el vector de cifrado de OAuth")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
        }
        val data = org.json.JSONObject(String(cipher.doFinal(Base64.decode(encrypted, Base64.NO_WRAP)), Charsets.UTF_8))
        return OAuthCredentials(
            data.getString("access"), data.getString("refresh"),
            data.getLong("accessExpiry"), data.getLong("refreshExpiry"), data.getString("login"),
        )
    }
    internal fun saveOAuth(value: OAuthCredentials) {
        val json = org.json.JSONObject()
            .put("access", value.accessToken).put("refresh", value.refreshToken)
            .put("accessExpiry", value.accessExpiresAt).put("refreshExpiry", value.refreshExpiresAt)
            .put("login", value.login).toString()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val encrypted = cipher.doFinal(json.toByteArray(Charsets.UTF_8))
        check(preferences.edit().putString("oauth", Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .putString("oauth_iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP)).commit())
    }
    fun discardLegacyToken() {
        check(preferences.edit().remove("token").remove("iv").commit())
    }
    fun clearOAuth() {
        check(preferences.edit().remove("oauth").remove("oauth_iv").commit())
    }
    fun disconnect() {
        check(preferences.edit().remove("token").remove("iv")
            .remove("oauth").remove("oauth_iv").commit())
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
