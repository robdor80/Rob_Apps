# RobPublisher

Aplicación Android personal para distribuir las aplicaciones de `robdor80` con
GitHub Actions. Kotlin, Compose y Material 3; Android 8 (API 26) o posterior.
Paquete oficial: `es.robertodorado.robpublisher`.
La entrega al propietario se realiza exclusivamente mediante versiones oficiales
Release, actualizables sobre la instalación anterior con el mismo certificado.
Véase la [política permanente de distribución](docs/distribucion-oficial.md).
RobPublisher está integrada en el catálogo de RobStore mediante el topic
`robstore-app` y el manifiesto de su etiqueta oficial. La
[comprobación de integración](docs/integracion-robstore.md) cubre descubrimiento,
APK firmado y actualización Android sin desinstalar. `robstorePolicy: false`
es la configuración RS-05 correcta para esta aplicación externa a RobStore.

## Uso

1. Conectar con GitHub en Ajustes mediante RobGit RD y su Device Flow.
   Las credenciales se guardan cifradas con Android Keystore, sin PAT ni secretos
   de cliente integrados. Las capturas de pantalla normales están permitidas.
2. Seleccionar Cuadrante u otra aplicación descubierta automáticamente por su topic
   `robstore-app` o configuración RS-05. Las altas manuales siguen siendo opcionales.
3. Pulsar **Publicar actualización** para iniciar la distribución oficial Release.
4. La pantalla muestra el progreso: espera, compilación, firma automática,
   publicación y resultado. Puede cerrarse la app y retomarse el seguimiento.
5. Al publicarse una versión normal, RobStore puede encontrarla al refrescar su catálogo.

No hay formulario de etiquetas, revisión de candidatos ni segunda confirmación.
No se copian hashes o identificadores y no se abre GitHub para aprobar entornos.
La app nunca recibe keystores o contraseñas de firma. Las claves se configuran una
vez por aplicación y se reutilizan en GitHub, manteniendo la Release original.

## Integración coordinada con RobStore #12

La aplicación está integrada en `main` y utiliza el motor central de RobStore.
Los repositorios necesitan su configuración `.github/android-distribution.json`
y el wrapper RS-05 para distribuirse automáticamente. Que otra aplicación carezca
de esa configuración no bloquea la publicación de RobPublisher ni autoriza a
modificar ese repositorio.

La plantilla incluida llama al motor central en `@main` con `secrets: inherit`.
Las futuras mejoras del motor se reciben sin cambiar referencias SHA por aplicación.
No se utiliza `ANDROID_DISTRIBUTION_ENABLED` ni `ANDROID_DISTRIBUTION_TOOLING_SHA`.
El alta inicial y la migración automática, que conservan secretos y certificados,
están descritas en [RS-05](https://github.com/robdor80/RobStore/blob/rs05/one-button-distribution/docs/RS-05-AUTOMATICO.md).
No forman parte de cada actualización.

El fallo de `apksigner` de Cuadrante no se declara resuelto por cambiar la interfaz
ni por retirar revisores: el diagnóstico del motor y sus límites se documentan en
RobStore #12. Una compilación, firma o publicación fallida se muestra en castellano
y no se anuncia como actualización publicada.

## Funcionamiento y validación

Un único POST `workflow_dispatch` en `main`, con `channel`, inicia toda la distribución.
El seguimiento se persiste antes de enviarlo. Si la respuesta se pierde, se busca
la ejecución sin repetir el POST. Los IDs sólo se gestionan internamente.
Se consultan los trabajos para mostrar el progreso y se confirma la Release mediante
el recibo existente generado por esa ejecución. No se descarga el APK para revisar
un candidato. Los pushes y PR ejecutan únicamente las comprobaciones de desarrollo.

```sh
./gradlew --no-daemon assembleDebug testDebugUnitTest lintDebug
./gradlew --no-daemon assembleRelease testReleaseUnitTest lintRelease
```

Las pruebas HTTP simulan distribución, progreso, envío incierto, errores,
descubrimiento y reanudación. No publican ni acceden a claves de producción.
La prueba breve de navegación recorre Inicio, detalle y Ajustes sin credenciales.
La CI valida también la variante Release y su identidad frente al APK oficial
publicado; adjunta informes de pruebas. Los APK internos no son una entrega al
propietario. La firma oficial se ejecuta exclusivamente en el trabajo aislado RS-05.

El icono está en `assets/robpublisher/icon.png`; los recursos de launcher se regeneran
con `python3 tools/generate_launcher_icons.py`. Los recursos Debug se limitan a
las comprobaciones internas y no forman parte del APK Release.


## Inicio de sesión con GitHub (RobGit RD)

RobPublisher comparte la **GitHub App RobGit RD**, ya instalada con acceso a los repositorios autorizados y permisos Actions: Read and write. Cada aplicación Android tiene una sesión independiente: no lee los datos privados de RobGit.

La aplicación usa el **device authorization flow** de GitHub, sin PAT ni `client_secret` incrustado. El **Client ID público** de RobGit RD ya viene configurado, así que basta con pulsar «Conectar con GitHub» y se introduce el código de un solo uso en https://github.com/login/device. La GitHub App debe tener **Device Flow habilitado** en Developer settings → GitHub Apps → RobGit RD → General. Esto no cambia el OAuth web de RobGit.

GitHub puede expedir tokens de usuario con renovación o sin caducidad, según la configuración de RobGit RD. Ambos formatos se aceptan y se guardan cifrados con Android Keystore; cuando hay renovación, se solicita antes de caducar. Si se revoca la autorización o caduca el token de renovación tras un periodo prolongado sin usarla, se autoriza nuevamente desde la misma pantalla, sin generar PAT. Los permisos corresponden a la intersección de RobGit RD instalada y la cuenta usuaria.

El código permanece disponible al consultar el navegador y tras recrear la Activity. El intento pendiente se cifra también y se recupera al reabrir la aplicación mientras sea válido. El polling respeta los intervalos de GitHub, aumenta la espera con `slow_down` y reintenta errores temporales sin generar otro código. Abrir o cerrar el navegador no implica estar conectado: primero se obtiene el token y se valida la cuenta en `/user`.

La pantalla permite copiar el código, abrir GitHub, consultar el tiempo restante y cancelar. Si Android suspende o mata el proceso, las consultas se retoman al ejecutarse de nuevo; no se requiere servicio permanente en segundo plano.

Diagnóstico, cobertura de pruebas y comprobación en tablet: [docs/github-auth-fix.md](docs/github-auth-fix.md).

Se mantiene lectura del token antiguo exclusivamente para migrar la instalación ya existente. Al completar OAuth, RobPublisher borra ese token antiguo. No se guardan secretos ni tokens en el repositorio.


### Recordatorio para futuras aplicaciones

RobPublisher tiene preconfigurado el Client ID **público** de RobGit RD, y en cada ficha de publicación incluye un recordatorio opcional con un botón para copiarlo. **No es necesario añadirlo a una aplicación para publicarla:** la autenticación de publicación pertenece a RobPublisher. Solo debe integrarse OAuth y el Client ID en una aplicación que necesite acceder a GitHub por sí misma; el identificador sin la implementación del flujo OAuth no concede acceso. Este recordatorio no bloquea publicaciones.
