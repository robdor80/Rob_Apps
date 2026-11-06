# RobPublisher

Aplicación Android personal para distribuir las aplicaciones de `robdor80` con
GitHub Actions. Kotlin, Compose y Material 3; Android 8 (API 26) o posterior.
Paquetes `es.robertodorado.robpublisher` y `es.robertodorado.robpublisher.debug`.

## Uso

1. Configurar una sola vez la conexión GitHub en Ajustes. El token personal necesita
   Metadata/Contents de lectura y Actions de lectura/escritura en las aplicaciones.
   Se guarda cifrado con Android Keystore. No se muestra en la interfaz ni en logs;
   al introducirlo está enmascarado. Las capturas de pantalla normales están permitidas.
2. Seleccionar Cuadrante u otra aplicación descubierta automáticamente por su topic
   `robstore-app` o configuración RS-05. Las altas manuales siguen siendo opcionales.
3. Pulsar **Publicar actualización**. El canal normal está seleccionado por defecto;
   se puede elegir «Pruebas» para DEV antes de pulsar.
4. La pantalla muestra el progreso: espera, compilación, firma automática,
   publicación y resultado. Puede cerrarse la app y retomarse el seguimiento.
5. Al publicarse una versión normal, RobStore puede encontrarla al refrescar su catálogo.

No hay formulario de etiquetas, revisión de candidatos ni segunda confirmación.
No se copian hashes o identificadores y no se abre GitHub para aprobar entornos.
La app nunca recibe keystores o contraseñas de firma. Las claves se configuran una
vez por aplicación y se reutilizan en GitHub, manteniendo la Release original.

## Integración coordinada con RobStore #12

Esta adaptación se basa en la aplicación de la PR #1 de este repositorio;
`main` todavía sólo contiene el icono. La PR de esta adaptación apunta a esa rama,
para evitar duplicar la aplicación. Necesita el motor de
[RobStore #12](https://github.com/robdor80/RobStore/pull/12) y la migración única de
los wrappers de las aplicaciones antes de usar la publicación real.

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
```

Las pruebas HTTP simulan distribución, progreso, envío incierto, errores,
descubrimiento y reanudación. No publican ni acceden a claves de producción.
La prueba breve de navegación recorre Inicio, detalle y Ajustes sin credenciales.
La CI adjunta un APK Debug temporal para pruebas; esa firma efímera no sustituye
las identidades oficiales DEV/Release.

El icono está en `assets/robpublisher/icon.png`; los recursos de launcher se regeneran
con `python3 tools/generate_launcher_icons.py`. La insignia «D» identifica DEV.


## Inicio de sesión con GitHub (RobGit RD)

RobPublisher comparte la **GitHub App RobGit RD**, ya instalada con acceso a los repositorios autorizados y permisos Actions: Read and write. Cada aplicación Android tiene una sesión independiente: no lee los datos privados de RobGit.

La aplicación usa el **device authorization flow** de GitHub, sin PAT ni `client_secret` incrustado. El **Client ID público** de RobGit RD ya viene configurado, así que basta con pulsar «Conectar con GitHub» y se introduce el código de un solo uso en https://github.com/login/device. La GitHub App debe tener **Device Flow habilitado** en Developer settings → GitHub Apps → RobGit RD → General. Esto no cambia el OAuth web de RobGit.

GitHub expide tokens de usuario y renovación. Se guardan cifrados con Android Keystore y el acceso se renueva automáticamente antes de caducar. Si se revoca la autorización o caduca el token de renovación tras un periodo prolongado sin usarla, se autoriza nuevamente desde la misma pantalla, sin generar PAT. Los permisos corresponden a la intersección de RobGit RD instalada y la cuenta usuaria.

Se mantiene lectura del token antiguo exclusivamente para migrar la instalación ya existente. Al completar OAuth, RobPublisher borra ese token antiguo. No se guardan secretos ni tokens en el repositorio.
