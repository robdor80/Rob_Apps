package es.robertodorado.robpublisher

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Solo LocalStore implementa este contrato en producción: todo el intento se cifra. */
internal interface DeviceAuthStore {
    fun pendingAuth(): PendingDeviceAuth?
    fun savePendingAuth(value: PendingDeviceAuth?)
    fun completeAuth(value: OAuthCredentials)
}

// No data classes: toString() nunca debe revelar códigos ni credenciales.
internal class PendingDeviceAuth(
    val authorization: DeviceAuthorization,
    val intervalSeconds: Long = authorization.intervalSeconds,
    val nextPollAt: Long = 0,
    val credentials: OAuthCredentials? = null,
    val validationDeadline: Long = 0,
) {
    val expiresAt get() = if (credentials == null) authorization.expiresAt else validationDeadline
}

internal data class DeviceAuthState(
    val pending: DeviceAuthorization? = null,
    val running: Boolean = false,
    val validating: Boolean = false,
    val remainingSeconds: Long = 0,
    val message: String? = null,
    val connectedLogin: String? = null,
)

/** Un único trabajo del ViewModel, independiente del lifecycle de la pantalla/navegador.
 * Al morir el proceso, el siguiente ViewModel retoma el intento cifrado y su intervalo. */
internal class DeviceAuthController(
    private val scope: CoroutineScope,
    private val store: DeviceAuthStore,
    private val oauth: GitHubOAuth,
    private val user: suspend (String) -> String,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val mutableState = MutableStateFlow(DeviceAuthState())
    val state = mutableState.asStateFlow()
    private var job: Job? = null

    fun restore() {
        if (job?.isActive == true) return
        try {
            val saved = store.pendingAuth() ?: return
            run(saved)
        } catch (_: Exception) {
            mutableState.value = DeviceAuthState(message = "No se pudo recuperar la autorización cifrada. Reintenta la conexión.")
        }
    }

    fun begin() {
        if (job?.isActive == true) return
        // No sustituir un código todavía válido por otro.
        val saved = try { store.pendingAuth() } catch (_: Exception) { null }
        if (saved != null && saved.expiresAt > now()) { run(saved); return }
        mutableState.value = DeviceAuthState(running = true)
        job = scope.launch {
            try {
                val device = oauth.begin(ROBGIT_RD_CLIENT_ID)
                val pending = PendingDeviceAuth(device, nextPollAt = now() + device.intervalSeconds * 1000)
                currentCoroutineContext().ensureActive()
                store.savePendingAuth(pending)
                poll(pending)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { fail(e) }
        }
    }

    private fun run(saved: PendingDeviceAuth) {
        mutableState.value = display(saved)
        job = scope.launch {
            try { poll(saved) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { fail(e) }
        }
    }

    private fun display(p: PendingDeviceAuth, message: String? = null) = DeviceAuthState(
        pending = p.authorization, running = true, validating = p.credentials != null,
        remainingSeconds = ((p.expiresAt - now()).coerceAtLeast(0) + 999) / 1000, message = message,
    )

    private suspend fun poll(initial: PendingDeviceAuth) {
        var pending = initial
        var backoff = pending.intervalSeconds
        while (now() < pending.expiresAt) {
            val due = pending.nextPollAt.coerceAtLeast(now())
            while (now() < due && now() < pending.expiresAt) {
                mutableState.value = display(pending, mutableState.value.message)
                delay(minOf(1000L, due - now(), pending.expiresAt - now()))
            }
            if (now() >= pending.expiresAt) break
            try {
                if (pending.credentials != null) {
                    val credentials = pending.credentials
                    val account = user(credentials.accessToken)
                    currentCoroutineContext().ensureActive()
                    if (account != "robdor80") {
                        finish("Conecta con tu cuenta robdor80."); return
                    }
                    store.completeAuth(OAuthCredentials(credentials.accessToken, credentials.refreshToken,
                        credentials.accessExpiresAt, credentials.refreshExpiresAt, account))
                    mutableState.value = DeviceAuthState(connectedLogin = account,
                        message = "GitHub conectado.")
                    return
                }
                // Reservar antes del POST: una muerte del proceso no provoca consultas inmediatas duplicadas.
                pending = PendingDeviceAuth(pending.authorization, pending.intervalSeconds,
                    now() + pending.intervalSeconds * 1000)
                store.savePendingAuth(pending)
                when (val result = oauth.poll(ROBGIT_RD_CLIENT_ID, pending.authorization)) {
                    DevicePoll.Waiting -> pending = PendingDeviceAuth(pending.authorization,
                        pending.intervalSeconds, now() + pending.intervalSeconds * 1000)
                    DevicePoll.SlowDown -> pending = PendingDeviceAuth(pending.authorization,
                        pending.intervalSeconds + 5, now() + (pending.intervalSeconds + 5) * 1000)
                    is DevicePoll.Failed -> { finish(result.explanation); return }
                    is DevicePoll.Authorized -> pending = PendingDeviceAuth(pending.authorization,
                        pending.intervalSeconds, credentials = result.token,
                        validationDeadline = minOf(now() + 900_000, result.token.accessExpiresAt))
                }
                currentCoroutineContext().ensureActive()
                // El token recibido se guarda cifrado ANTES de /user: un fallo no vuelve a canjear el código.
                store.savePendingAuth(pending)
                backoff = pending.intervalSeconds
                mutableState.value = display(pending)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                val transient = (e is OAuthFailure && e.retryable) || e is java.io.IOException ||
                    (e is GitHubException && (e.status == 403 || e.status == 429 || e.status >= 500))
                if (!transient) throw e
                backoff = maxOf(pending.intervalSeconds, (backoff * 2).coerceAtMost(60),
                    (e as? OAuthFailure)?.retryAfterSeconds ?: 0)
                pending = PendingDeviceAuth(pending.authorization, pending.intervalSeconds,
                    now() + backoff * 1000, pending.credentials, pending.validationDeadline)
                store.savePendingAuth(pending)
                mutableState.value = display(pending, "Error temporal de conexión. Reintentando automáticamente.")
            }
        }
        finish("La autorización ha caducado. Vuelve a conectar con GitHub.")
    }

    private fun finish(message: String) {
        store.savePendingAuth(null)
        mutableState.value = DeviceAuthState(message = message)
    }

    private fun fail(error: Exception) {
        // No mostrar cuerpos HTTP, códigos ni mensajes arbitrarios de excepciones.
        val message = if (error is OAuthFailure) error.message else "No se pudo completar la conexión. Reintenta."
        try { finish(message ?: "No se pudo completar la conexión.") }
        catch (_: Exception) { mutableState.value = DeviceAuthState(message = "No se pudo guardar la conexión cifrada. Reintenta.") }
    }

    fun cancel() {
        job?.cancel()
        job = null
        try { finish("Conexión cancelada.") }
        catch (_: Exception) { mutableState.value = DeviceAuthState(message = "No se pudo eliminar la autorización guardada. Reintenta.") }
    }
}
