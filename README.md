# RobPublisher — RP-01 y RP-02

Aplicación Android personal en Kotlin, Jetpack Compose y Material 3. SDK 36,
minSdk 26, bytecode/JDK de CI 17. Paquete `es.robertodorado.robpublisher`;
Debug `es.robertodorado.robpublisher.debug`. El nombre visible es RobPublisher en
ambas variantes; DEV se distingue por la «D» en el icono.

## Uso y prueba desde el móvil

1. Descargar `RobPublisher-DEV` en el run correcto de **Actions → Android RobPublisher**,
   extraer el ZIP e instalar el APK Debug. Es un artefacto temporal de CI, no una Release.
2. Crear personalmente un PAT fine-grained en GitHub, con acceso a los repositorios
   deseados: **Metadata: Read**, **Contents: Read** y **Actions: Read and write**.
   No se necesitan permisos de Secrets, Administration, Deployments ni escritura
   en Contents: las aprobaciones de entornos se hacen en la web oficial de GitHub. No enviarlo por chat ni añadirlo al repositorio.
3. En Ajustes introducirlo y pulsar **Guardar y comprobar**. Debe aparecer el usuario
   real de GitHub. El token solo se guarda si `/user` acepta la autenticación.
4. Volver a Inicio: al abrir la app o volver a Inicio en primer plano se consultan
   los repositorios accesibles de `robdor80`. Se muestran automáticamente los que
   tienen `robstore-app` o `.github/android-distribution.json` en su rama predeterminada,
   incluso sin Release. **Actualizar** repite la consulta. Las tarjetas muestran última
   Release estable y estado básico RS-05. Ajustes conserva el alta manual alternativa,
   integrada en la misma lista sin duplicados. Retirar el alta manual de una app
   descubierta no la oculta del descubrimiento automático.
5. Para publicar, primero completar personalmente el alta RS-05 del repositorio.
   En su ficha pulsar **Publicar primera versión** o **Publicar actualización**,
   seleccionar Release/DEV, revisar la etiqueta y pulsar **Preparar candidato**.
   Esto sí inicia `prepare` en GitHub: no probarlo contra producción sin autorización.
   La propuesta de etiqueta usa la versión literal de main; no aumenta versiones ni
   modifica código. Si main calcula su versión dinámicamente, introducir la etiqueta.
6. El panel sigue el run y abre GitHub cuando necesita aprobar `android-release` o
   `android-dev`. Al volver continúa consultando automáticamente. Una vez preparado,
   muestra paquete, versión, hash oficial y huella pública. **Revisar y publicar candidato**
   solicita confirmación explícita `PUBLICAR <etiqueta>`; envía run/hash/canal/tag/SHA
   automáticamente. Aprobar `android-publish` en GitHub y volver para ver el resultado
   y abrir la Release. No hay que copiar IDs, hashes ni comandos.
7. Probar Volver, Inicio y Ajustes, girar a horizontal (tablet: contenido hasta 1040 dp),
   cerrar/reabrir y comprobar persistencia de la lista y reconexión. El token pendiente
   de introducir no sobrevive a la recreación de la pantalla.
8. Eliminar una aplicación (no modifica GitHub). Desconectar borra token y clave local;
   conserva la lista. Probar token inválido y falta de Internet para ver los errores.

El token se cifra AES-GCM con una clave no exportable de Android Keystore. No se guarda
como texto, no se registra en logs y no entra en savedInstanceState. Copias de seguridad
Android desactivadas y ventana protegida contra capturas. Al desinstalar se pierden los datos.

## Compilar

Con JDK 17 y Android SDK instalado:

```sh
./gradlew assembleDebug testDebugUnitTest lintDebug
```

La CI ejecuta esos mismos comandos y adjunta `app-debug.apk` durante 14 días.
La firma Debug es temporal; no sustituye las claves DEV persistentes de distribución.
No hay backend, Firebase ni base de datos remota. Servicio REST reutilizable en
`GitHubService.kt`, almacenamiento local en `LocalStore.kt` y estado sencillo en
`PublisherViewModel.kt`. `DistributionService.kt` controla RS-05. La API usa HTTPS
con Authorization exclusivamente a `api.github.com`, sin seguir redirecciones.
Para leer el candidato se sigue su URL temporal HTTPS **sin token** y se recorren
los metadatos del ZIP con límites de tamaño; no se conserva, instala ni firma el APK.
No se registran cabeceras, URLs firmadas ni cuerpos de error.
La consulta de repositorios recorre todas las páginas. La última Release usa
`/repos/{owner}/{repo}/releases/latest` (estable, sin borradores ni prereleases).

## Referencias visuales y siguiente fase

Se inspeccionaron RobGit_App (`RobGitTheme.kt`) y RobStore (`ui/robstyle`): fondo
petróleo, texto hielo, estados verde/ámbar, sans serif, tarjetas de 18 dp y márgenes
20/30 dp. Se conservan tres pantallas, navegación Inicio/Ajustes y retorno desde detalle.

RS-05 se ha consultado en
[ANDROID-MOBILE-FIRST.md](https://github.com/robdor80/RobStore/blob/main/docs/ANDROID-MOBILE-FIRST.md).
La entrada es `.github/workflows/android-distribute.yml`, que llama al motor central
`android-distribution-engine.yml` fijado a un SHA aprobado. `prepare` genera el candidato;
`publish` exige el run anterior, SHA-256, mismo SHA/canal/tag y confirmación
`PUBLICAR <etiqueta>`, además de aprobaciones en GitHub. El alta de claves es personal.

**RP-02:** workflow activo comprobado en main y motor central fijado a SHA completo;
seguimiento persistente de una publicación, con actualización cada 10 segundos en
primer plano. La API devuelve el run exacto (`return_run_details`). Si la respuesta
se pierde, se busca una ejecución nueva del mismo workflow/commit/propietario sin
repetir el POST; ante varias posibles no se selecciona una automáticamente. Cerrar
seguimiento solo elimina el estado local, sin cancelar GitHub. El recibo del candidato
se contrasta con repositorio, run, SHA, canal, etiqueta, paquete y SHA256SUMS antes de
habilitar publish. Si main cambia, se necesita un candidato nuevo. No se aprueba ningún
entorno por API ni se sustituyen las comprobaciones del motor.

Validación mínima: cinco pruebas JVM con respuestas simuladas cubren prepare,
aprobación, candidato ZIP/recibo/hash, inputs de publish, Release visible y errores
(401/403/422, workflow deshabilitado, cancelación, caducidad y envío incierto).
También se comprueban topic, configuración sin Release, última versión y alta manual
sin duplicados. No ejecutan ninguna operación real. La base RS-05 se considera lista
cuando existen configuración coherente, workflow oficial activo en main y notas
aprobadas. Este indicador no certifica claves, alta personal ni CI: el motor las
comprueba al preparar. Un error de consulta conserva la instantánea anterior y muestra
el error, sin convertirlo en ausencia de aplicaciones. **RP-03 pendiente:** prueba autorizada en dispositivo
con credenciales y repositorios ya preparados, experiencia final y entrega. No se ha copiado ni
modificado el motor, configurado secretos, ejecutado workflows de distribución,
creado tags ni publicado versiones desde Codex. Las operaciones oficiales solo se
solicitan al usar explícitamente los controles de la app contra un repositorio habilitado.

## Icono oficial

Fuente: `assets/robpublisher/icon.png`, conservada sin recortar ni recolorear.
Los recursos de launcher normales, redondos y adaptativos cubren mdpi, hdpi,
xhdpi, xxhdpi y xxxhdpi. DEV usa la misma imagen con la insignia «D» de RobStore.
Para regenerarlos: `python tools/generate_launcher_icons.py` (Pillow 12.3.0);
`--check` comprueba que los PNG versionados coinciden con la fuente.

## Preparación nativa RS-05

`.github/android-distribution.json` declara las identidades Release y DEV, módulo
`app`, SDK 26/36, nombre, descripción, icono oficial y notas versionadas.
`robstorePolicy: false` selecciona la verificación genérica para otras aplicaciones;
la política especial `true` está reservada a RobStore y no controla la entrada al catálogo.

`.github/workflows/android-distribute.yml` conserva la entrada manual oficial
`prepare`/`publish` y llama al motor de RobStore fijado al commit
`50a7ec0f2f0720040467d1187fb07b67ff8b27c0`, ya incorporado en su `main`.
No contiene el motor ni recibe secretos con `inherit`. El job queda desactivado
mientras `ANDROID_DISTRIBUTION_ENABLED` no sea `true`; no se configura esa variable
en RP-01. Las notas mantienen `NOT_FOR_PUBLICATION`, que el motor rechaza.

Más adelante, desde Android/Termux y siguiendo el
[procedimiento oficial RS-05](https://github.com/robdor80/RobStore/blob/50a7ec0f2f0720040467d1187fb07b67ff8b27c0/docs/ANDROID-MOBILE-FIRST.md),
el propietario ejecutará el asistente central `tools/android_owner_setup.py` desde
ese mismo commit aprobado, con `--repository robdor80/Rob_Apps`, un directorio
privado propio, `--tooling-sha` con ese SHA completo y `--catalog`. El asistente
realiza el alta de dos claves independientes, su recuperación comprobada, los
entornos y sus secretos/huellas, el topic `robstore-app` y las variables del motor.
No ejecutar ese asistente desde Codex ni desde CI. Si se actualiza el motor, cambiar
juntos el pin del workflow y `ANDROID_DISTRIBUTION_TOOLING_SHA`.

Versión inicial preparada: `versionCode = 1`, `versionName = 1.0.0`; DEV añade
`-DEV`. Antes de distribuir se aprobarán versión y notas. Las versiones posteriores
aumentarán `versionCode` por paquete y conservarán la clave permanente de cada canal.
Las identidades no cambian. El motor compila sin claves, desactiva la firma Gradle
y el modo depurable del candidato DEV, y firma después usando los entornos oficiales.
Los APK Debug temporales de CI no sustituyen esa firma DEV permanente ni garantizan
actualización compatible sobre una instalación oficial.

No se añaden contratos ficticios `robstore.json`, `robstore-update.json` ni recibos: el
motor los genera en el commit de cierre del tag con datos del APK real y copia el
PNG oficial a `robstore/icon.png`. Una Release estable que cumpla ese contrato podrá
aparecer en el catálogo; DEV conserva su identidad independiente y no se lista como
estable. RP-01 no crea claves, entornos, topics, variables, tags ni Releases, ni ejecuta
`prepare`, `publish` u operaciones de firma oficiales.
