# Corrección de autenticación GitHub de RobPublisher

Base investigada: `cb1d96330660ca876619c6aa5aa58057ae729f29` (main, RobPublisher 1.0.2).
Rama: `fix/github-device-auth-lifecycle`.

## Diagnóstico con evidencia del código

En la implementación original, `PublisherViewModel.beginAuthorization()` guardaba
`pendingAuthorization` exclusivamente en `mutableStateOf` y lanzaba el polling en
`viewModelScope`. Cualquier `OAuthFailure` o excepción de `/user` terminaba el trabajo;
el `finally` eliminaba SIEMPRE el código y restablecía `authorizing=false`.
`GitHubOAuth.post()` convertía un fallo de red en ese `OAuthFailure` terminal. Es
una ruta demostrable desde un error temporal hasta la vuelta al botón de conexión,
aunque el código siguiese siendo válido.

El intento no se guardaba en `LocalStore`, `SavedStateHandle` ni el estado guardado
de la Activity. Tras muerte del proceso, el nuevo ViewModel inicializaba el código
a `null` y `screen` a `Inicio`. El servidor podía haber autorizado el dispositivo,
pero el cliente ya no tenía el `device_code` necesario para recibir el token.

`GitHubOAuth.credentials()` exigía `refresh_token`, `expires_in` y
`refresh_token_expires_in`. Una GitHub App puede emitir un token sin caducidad si
esa es su configuración; ese token válido era rechazado antes de comprobar `/user`.
Además, el token solo se guardaba después de consultar `/user`: un fallo de esa
consulta descartaba un token ya obtenido.

`MainActivity.onCreate()` usa `viewModel()` de Compose con el propietario de la
Activity. Una recreación por configuración conserva ese ViewModel. No hay un
callback de navegador, `onStop`, `onResume` ni `LaunchedEffect` que cancele OAuth.
Los efectos `repeatOnLifecycle` existentes pertenecen al seguimiento de publicación
y al descubrimiento de repositorios, no a la autenticación. Navegar a otra pantalla
puede ocultar el código, pero no lo borraba del ViewModel.

Estos defectos están identificados en el código. No se ha capturado el fallo
original en la Galaxy Tab S9+: no hay logs de lifecycle, respuesta OAuth ni prueba
de que Samsung matase el proceso en ese incidente. La configuración real de
caducidad de RobGit RD tampoco se ha cambiado ni se da por comprobada.

## Cambios y garantías

- `DeviceAuthController.begin/restore/poll/cancel`: un trabajo por ViewModel,
  independiente de la composición y del estado visible del navegador. `StateFlow`
  expone código, espera, cuenta atrás y validación; nunca expone el access token.
- `LocalStore.pendingAuth/savePendingAuth`: conserva únicamente los códigos, URI
  oficial, expiración, intervalo y próxima consulta. Si ya hay token, lo conserva
  cifrado para reintentar `/user` durante como máximo 15 minutos y nunca más allá
  de su caducidad. Todo usa AES-GCM con IV nuevo y clave Android Keystore no
  exportable. La copia de seguridad de la aplicación continúa desactivada.
- El estado cifrado de disco, en vez del Bundle de `onSaveInstanceState` o
  `SavedStateHandle`, permite recuperar el intento incluso desde un arranque nuevo.
  El ViewModel existente sigue cubriendo la recreación por configuración. Al
  recuperar un intento se abre Ajustes para que su código siga accesible.
- Polling: espera el intervalo inicial, trata `authorization_pending`, añade cinco
  segundos persistentes con `slow_down`, respeta `Retry-After` numérico y aplica
  espera creciente ante I/O, HTTP 403/429/5xx. No genera códigos nuevos al volver
  del navegador. Reserva la siguiente consulta antes del POST para respetar el
  intervalo al reconstruirse el proceso; vuelve a calcularlo después de responder.
- HTTP: solicita JSON, limita cuerpos, no sigue redirecciones, permite los estados
  OAuth oficiales también con HTTP 400 y no incluye cuerpos ni secretos en errores.
  Los errores terminales y la caducidad eliminan solo el intento.
- Un token recibido se cifra antes de consultar `/user`. Solo la cuenta `robdor80`
  validada se convierte en sesión; se conserva la restricción privada existente.
  `LocalStore.completeAuth()` guarda la sesión y elimina intento y token antiguo
  en una sola transacción. La navegación vuelve a Inicio después de ese éxito.
- Se aceptan tokens con renovación y sin caducidad. Un error temporal de comprobación
  de sesión conserva credenciales e identidad; un HTTP 401 confirmado elimina la
  sesión rechazada. Una renovación con `bad_refresh_token`, `expired_token` o
  `invalid_grant` confirma su invalidez; otros errores conservan la sesión.
  Se recupera la sesión al abrir la aplicación.
- Cancelar detiene el trabajo y elimina el intento; las cancelaciones de coroutines
  se propagan y no pueden resucitar un intento después de una respuesta tardía.
  Desconectar elimina credenciales, intento y clave; mantiene los repositorios.
- El Device Flow de una GitHub App usa los permisos de la instalación y de la cuenta.
  Se conserva `scope=offline_access`: solicita tokens con caducidad y renovación,
  sin ampliar permisos. La documentación oficial admite renovar tokens obtenidos
  por Device Flow sin `client_secret`. No se añaden scopes, client secrets,
  permisos Android ni cambios a RobGit RD.
  Referencia: [documentación oficial de renovación](https://github.com/github/docs/blob/main/content/apps/creating-github-apps/authenticating-with-a-github-app/refreshing-user-access-tokens.md).

Se mantiene la UI y la arquitectura de publicación. No se modifica el motor RS-05
ni se ejecuta `android-distribute.yml` (que publica Release).

## Pruebas

`DeviceAuthControllerTest` usa reloj virtual, transporte simulado y almacenamiento
en memoria. Comprueba autorización normal, ausencia de observadores de UI,
reconstrucción del controlador tras cancelar el scope del proceso, código estable,
`pending`, `slow_down` persistente, caducidad local/remota, errores temporales,
`Retry-After`, token pendiente de validar tras reinicio, cuenta incorrecta,
HTTP 401, cancelación durante la petición inicial y ausencia de polling duplicado.
Esto simula la reconstrucción del proceso; no es una muerte real de Android.

`GitHubOAuthTest` usa MockWebServer y el transporte HTTP real contra loopback:
formularios y endpoints, estados OAuth con HTTP 200/400, tokens renovables y sin
caducidad, límites HTTP reintentables, datos inválidos, URI inesperada y expiración.
No contacta con GitHub ni utiliza tokens reales.

`DeviceAuthLifecycleTest` es instrumentación Android para segundo plano/regreso,
`ActivityScenario.recreate()`, cierre y nueva Activity/ViewModel, navegación,
cancelación y lectura/escritura cifrada real con Keystore. No abre un navegador real;
el cambio de lifecycle se produce con ActivityScenario. `GitHubSessionTest` comprueba
restauración de sesión sin Device Flow, logout y conservación ante 503 frente a
eliminación ante 401 usando la API GitHub simulada. Sigue existiendo la prueba de
navegación general `NavigationSmokeTest`.

Resultados reales (9 de noviembre de 2026), implementación `1dd196f6292dbe2939fb6d92b3f8a0fbe805bb61`:

| Comprobación | Resultado |
| --- | --- |
| Local, JDK 17: `assembleDebug testDebugUnitTest lintDebug compileDebugAndroidTestKotlin` | Correcto; 33 pruebas JVM, 0 fallos, 0 omitidas; lint sin errores (con avisos) |
| CI: compilación, pruebas JVM y lint | Correcto |
| CI: `connectedDebugAndroidTest`, emulador Android 15/API 35 x86_64 | 11 pruebas ejecutadas, correcto |
| Navegador real, autorización GitHub real y Samsung Galaxy Tab S9+ | Pendiente de comprobación del propietario |
| Muerte real del proceso Android durante OAuth | Pendiente; reconstrucción de controlador y nueva Activity/ViewModel sí cubiertas |

Las 33 pruebas JVM son 17 de controlador, 11 de protocolo HTTP/renovación y 5 de
publicación existentes. Las 11 Android son 6 de lifecycle/cifrado, 4 de sesión y
1 de navegación existente. Localmente no se ejecutó instrumentación por falta de
`/dev/kvm`; su ejecución real tuvo lugar en el emulador de GitHub Actions.

Evidencia: [CI correcta](https://github.com/robdor80/Rob_Apps/actions/runs/42664728135),
[informe Android](https://github.com/robdor80/Rob_Apps/actions/runs/42664728135/artifacts/13696306404)
e [informe unitario](https://github.com/robdor80/Rob_Apps/actions/runs/42664728135/artifacts/13695608342).
La ejecución prueba la revisión de integración `e1f9c4ced7bd09eb1b4e2cc2cd842429d11d71bc`
de la PR, cuyo código de aplicación corresponde al commit de implementación citado.

## Entrega oficial tras integrar la corrección

La PR #7 se integró en `main` el 9 de noviembre de 2026. Las compilaciones DEV
mencionadas en la evidencia histórica fueron pruebas internas de aquella PR.
No son la entrega vigente ni una instrucción para instalar una segunda aplicación.
La entrega al propietario se realiza por Release oficial, según la
[política permanente](distribucion-oficial.md).

El propietario confirmó en su Galaxy Tab S9+ la autorización real como `robdor80`,
el acceso a Inicio y el descubrimiento de las cinco aplicaciones. La persistencia
al cerrar y reabrir continúa pendiente de comprobación física, igual que instalar
la nueva Release sobre la oficial anterior. La CI prueba restauración de sesión,
lifecycle, lectura del esquema cifrado anterior y navegación; no sustituye esa
evidencia particular de la tablet.

## Límites pendientes

- La conexión real GitHub/navegador fue confirmada posteriormente por el propietario.
  No se infiere de ello la persistencia tras cerrar y reabrir ni la actualización
  física del paquete oficial. No se cambian credenciales ni permisos de RobGit RD.
- Android puede suspender o matar el proceso en segundo plano. No se promete
  ejecución continua: al volver, se reanuda el intento válido o se recupera la
  sesión si ya se obtuvo. Si el código caduca antes de poder consultar, se necesita
  iniciar otro intento. No se introduce un servicio permanente.
- Existe una ventana inevitable si el proceso muere entre recibir un token del
  servidor y persistirlo. Se reduce guardándolo antes de cualquier otra petición;
  ninguna transacción local puede hacer atómico un canje remoto con disco Android.
- La referencia RS-05 `50a7ec0f2f0720040467d1187fb07b67ff8b27c0` no se altera ni se
  utiliza para disparar una publicación durante esta corrección de autenticación.
