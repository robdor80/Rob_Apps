# RobPublisher — RP-01

Aplicación Android personal en Kotlin, Jetpack Compose y Material 3. SDK 36,
minSdk 26, bytecode/JDK de CI 17. Paquete `es.robertodorado.robpublisher`;
Debug `es.robertodorado.robpublisher.debug` (RobPublisher-DEV).

## Uso y prueba desde el móvil

1. Descargar `RobPublisher-DEV` en el run correcto de **Actions → Android RP-01**,
   extraer el ZIP e instalar el APK Debug. Es un artefacto temporal de CI, no una Release.
2. Crear personalmente un PAT fine-grained en GitHub, con acceso a los repositorios
   deseados, **Metadata: Read** y **Contents: Read**. En organizaciones puede requerir
   autorización adicional. No enviarlo por chat ni añadirlo al repositorio.
3. En Ajustes introducirlo y pulsar **Guardar y comprobar**. Debe aparecer el usuario
   real de GitHub. El token solo se guarda si `/user` acepta la autenticación.
4. **Consultar repositorios**, filtrar y añadir uno. Volver a Inicio, seleccionarlo y
   comprobar nombre, visibilidad, estado archivado, rama y última Release estable.
   Un repositorio sin Release muestra ese estado; los errores no se presentan como éxito.
5. Abrir GitHub Actions en el navegador. Ambos botones de publicación deben mostrar
   que la función llegará en RP-02, sin iniciar workflows.
6. Probar Volver, Inicio y Ajustes, girar a horizontal (tablet: contenido hasta 1040 dp),
   cerrar/reabrir y comprobar persistencia de la lista y reconexión. El token pendiente
   de introducir no sobrevive a la recreación de la pantalla.
7. Eliminar una aplicación (no modifica GitHub). Desconectar borra token y clave local;
   conserva la lista. Probar token inválido y falta de Internet para ver los errores.

El token se cifra AES-GCM con una clave no exportable de Android Keystore. No se guarda
como texto, no se registra en logs y no entra en savedInstanceState. Copias de seguridad
Android desactivadas y ventana protegida contra capturas. Al desinstalar se pierden los datos.

## Compilar

Con JDK 17 y Android SDK instalado:

```sh
./gradlew assembleDebug lintDebug
```

La CI ejecuta esos mismos comandos y adjunta `app-debug.apk` durante 14 días.
La firma Debug es temporal; no sustituye las claves DEV persistentes de distribución.
No hay backend, Firebase ni base de datos remota. Servicio REST reutilizable en
`GitHubService.kt`, almacenamiento local en `LocalStore.kt` y estado sencillo en
`PublisherViewModel.kt`. Todas las peticiones usan HTTPS a `api.github.com`, sin
seguimiento automático de redirecciones ni registro de cabeceras o cuerpos de error.
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

**Pendiente RP-02:** lectura y gestión real de Actions, contrato de configuración por
aplicación e integración con ese workflow y sus aprobaciones. No se ha copiado ni
modificado el motor, configurado secretos, ejecutado workflows de distribución,
creado tags ni publicado versiones. RP-03 cubrirá la prueba funcional y entrega final.
