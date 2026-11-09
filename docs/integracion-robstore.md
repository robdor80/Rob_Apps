# RobPublisher en el catálogo de RobStore

RobPublisher mantiene una sola identidad oficial: `es.robertodorado.robpublisher`,
firmada con el certificado histórico SHA-256
`4be334716492b70d36fe8bbf87571a6519bad564ce42cb3e19b03475a35356d2`.
Su entrada de catálogo apunta al mismo APK Release que se instala desde GitHub.
No hay una aplicación específica para RobStore ni otra clave de firma.

## Qué significa robstorePolicy

`robstorePolicy: false` en `.github/android-distribution.json` es correcto para
RobPublisher. No controla la pertenencia al catálogo ni desactiva las actualizaciones.
Selecciona las comprobaciones de APK de una aplicación externa en RS-05.

En `RobStore/tools/mobile_distribution.py`, `inspect()` reserva el valor `true`
para el repositorio y los paquetes de **RobStore** y rechaza reutilizar esa política
en otra aplicación con `RobStore verification policy cannot be repurposed`.
Activarla en RobPublisher bloquearía su distribución. Se conserva `false`.

## Contrato de catálogo y actualización

La versión RobStore 0.1.3, código fuente
`95a3b43f5bb8ae6a5a969e33f82671ef717ad3d0`, descubre repositorios públicos activos
de `robdor80` con el topic **robstore-app**. Selecciona la Release estable publicada
más reciente y lee **robstore.json en su etiqueta**, no en `main`. Por ello,
la ausencia de ese archivo en `main` no excluye la aplicación.

RS-05 publica en cada etiqueta oficial:

- `robstore.json`: nombre, paquete, ruta del icono, nombre de APK y versiones.
- `robstore/icon.png`: icono del catálogo.
- `robstore-update.json`: contrato de paquete, canal, versión y SHA-256 del APK.
- `robstore-distribution.json`: certificado, SHA-256, commit de origen y ejecución.
- Assets de la Release: `Android-release.apk` y `SHA256SUMS`.

La entrada actual es `v1.0.3-4`, versión `1.0.3`, `versionCode 4`. Conserva paquete
y certificado de `v1.0.2-3` (código 3). RobStore compara el código instalado con el
publicado: 3 → 4 ofrece actualización; 4 → 4 indica que está al día; un código
instalado superior no permite rebajar la versión. El verificador comprueba tamaño,
SHA-256 y metadatos del APK antes de entregarlo al instalador interactivo de Android
mediante una URI de lectura. Android exige la firma compatible para actualizar.
El flujo no requiere desinstalar ni borrar datos. El permiso de Android para instalar
desde RobStore y la confirmación del instalador siguen siendo necesarios.

## Validación permanente

El workflow **RobPublisher catalog and official upgrade** comprueba la Release
real más reciente en las PR relevantes, en `main`, por ejecución manual y tras
una distribución oficial correcta. No publica ni firma APK nuevos y no necesita
secretos de firma.

`tools/verify_robstore_catalog.py` valida topic, manifiesto, icono, Release, recibo,
versión, URL, tamaño, certificado y SHA-256. Descarga los APK oficiales publicados.
Los metadatos públicos reales se usan con el código de descubrimiento de RobStore,
comparación de versiones, verificación del APK y entrega al instalador. El checkout
de RobStore solo se utiliza como dependencia de pruebas, sin modificar su repositorio.

`tools/verify_official_upgrade.py` utiliza un emulador Android 15/API 35 aislado:
instala el APK oficial anterior, añade datos y preferencias sintéticos sin tokens,
actualiza con el APK oficial actual mediante `adb install -r`, comprueba conservación
del UID, archivo y preferencias, arranque de la aplicación y rechazo de un downgrade.
Se adjuntan informes, nunca APK experimentales para el propietario.

Estas pruebas distinguen catálogo, verificación de descarga e instalación Android.
No acreditan pulsaciones reales de la interfaz de RobStore en la tablet ni la
persistencia de su sesión GitHub particular. Esa sesión y el estado de su Keystore
solo pueden confirmarse en el dispositivo. La prueba de actualización del emulador
no utiliza credenciales de producción y no sustituye esa evidencia física.

Para usarlo, instalar o actualizar la Release oficial de RobPublisher y refrescar
el catálogo en RobStore. RobPublisher aparecerá con su identidad oficial; las
versiones futuras con código creciente podrán actualizarla desde esa ficha.
