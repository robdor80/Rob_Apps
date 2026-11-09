# Política permanente de distribución de RobPublisher

Decisión del propietario, 9 de noviembre de 2026. Esta política es específica de
`robdor80/Rob_Apps` (RobPublisher); no modifica las políticas de otras aplicaciones.

## Una sola aplicación oficial

El propietario recibirá exclusivamente APK oficiales Release desde GitHub Releases.
No se entregarán versiones DEV, DEBUG, SNAPSHOT, prototipos ni instalaciones
paralelas para uso habitual, salvo solicitud expresa del propietario. Las pruebas
internas son responsabilidad del desarrollo y de CI; pueden usar compilaciones
técnicas sin convertirlas en entregas. La copia DEV previamente instalada puede
eliminarse después de comprobar la actualización oficial, sin desinstalar esta.

Cada actualización mantiene:

- Paquete `es.robertodorado.robpublisher`, nombre RobPublisher e identidad Release.
- Certificado SHA-256 `4be334716492b70d36fe8bbf87571a6519bad564ce42cb3e19b03475a35356d2`.
- `versionCode` estrictamente superior a todas las Releases oficiales anteriores;
  `versionName` estable, sin sufijos experimentales. RS-05 genera `v<versionName>-<versionCode>`.
- Datos, preferencias `robpublisher`, alias Keystore `robpublisher.github` y formato
  cifrado AES-GCM de las sesiones existentes. No se borran al arrancar ni actualizar.
  Una sesión válida compatible se conserva; una autorización revocada/caducada
  puede requerir de nuevo el Device Flow. Las sesiones de DEV pertenecen a otro
  paquete y no se importan a la aplicación oficial.

Nunca se genera ni sustituye la clave de firma para resolver un bloqueo. Si no
está disponible la original, se detiene la publicación y se restaura el keystore
original en los secretos del mismo entorno seguro de GitHub, con sus contraseñas
y alias. No se pide al propietario firmar mediante Termux ni otro ordenador.

## Compilación y publicación

GitHub Actions es el mecanismo preferente. El workflow `Android RobPublisher`
ejecuta pruebas JVM en Debug y Release, lint, compilación y pruebas Android de
autenticación, sesión y navegación. No entrega APK experimentales al propietario.

El workflow `Android distribution (RobPublisher)` solo admite `release`. Antes de
invocar RS-05 compara los certificados públicos configurados de firma y publicación
con el APK histórico verificado de `v1.0.2-3`; no lee claves privadas. La referencia
de identidad está en `.github/robpublisher-official.json`. Es una referencia
histórica permanente, no la versión que deba seguir siendo la última.

El motor RS-05 en RobStore compila Release sin claves, firma con los secretos
oficiales existentes en un trabajo aislado sin Gradle, verifica firma v2, paquete,
SDK, ausencia de `debuggable`, alineación y SHA-256, y exige un `versionCode` creciente
frente a todas las Releases oficiales. Solo distribuye desde `main`, por orden
explícita del propietario, con comprobación del origen y de los bytes subidos.
No se introducen secretos en código o logs ni se desactivan controles para publicar.

Se respetarán las aprobaciones de entornos o confirmaciones `PUBLICAR <etiqueta>`
si el procedimiento vigente las exige. En la infraestructura inspeccionada para
1.0.3 los entornos `android-release` y `android-publish` restringen a `main` y no
requieren revisores, temporizadores ni una confirmación adicional. Este documento
no autoriza a retirar aprobaciones que se configuren en el futuro.

El resultado se publica como Release estable con `Android-release.apk` descargable
y `SHA256SUMS`. El cierre de publicación RS-05 incorpora al tag `robstore.json`,
icono, `robstore-update.json` y recibo `robstore-distribution.json`, compatibles
con el catálogo de RobStore. No se altera su motor ni otros repositorios.

## Instalación y límites de las comprobaciones

Descargar únicamente el APK oficial de la Release y abrirlo en Android para
actualizar la aplicación instalada, sin desinstalarla. La igualdad de paquete y
certificado y el aumento del código de versión permiten la actualización Android.
Las pruebas de almacenamiento cubren el esquema anterior y su lectura cifrada.
Estas comprobaciones no acreditan por sí solas el resultado físico en la tablet,
el estado particular de su Keystore ni la validez remota de un token.

El propietario confirmó en Galaxy Tab S9+ que la corrección de la PR #7 conecta
con GitHub como `robdor80`, abre Inicio y descubre Cuadrante_App,
Limpiador_Android, RobGit_App, RobStore y Rob_Apps. Tres aparecen listas para
distribución; las configuraciones ausentes de Limpiador_Android y RobGit_App no
bloquean esta Release y no se modifican. La persistencia tras cerrar y reabrir
la aplicación y la instalación de la nueva Release sobre la oficial siguen
pendientes de evidencia física. No se declararán superadas sin esa evidencia.
