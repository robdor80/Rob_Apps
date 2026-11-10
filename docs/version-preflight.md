# Versionado y diagnóstico preventivos

La PR de Cuadrante prepara 1.0.2/código 3: el máximo oficial confirmado es 2
(v1.0.1-2); el registro histórico RS-05 fija 1 para v1.0.0. La ejecución
42702987071 conserva guard/build/sign correctos y falla al publicar una versión
repetida. No se borra ni se vuelve a publicar nada desde esta intervención.

RobPublisher consulta todos los contratos oficiales estables paginados y el
registro histórico central. No toma los borradores como versiones publicadas.
Si falta un contrato y no existe identidad histórica registrada, bloquea y exige
verificación del APK en CI; no inventa un código. Lee una instantánea de main
por SHA y muestra el código máximo y la candidata antes de ordenar distribución.
Revalida al pulsar publicar. El motor mantiene la autoridad definitiva sobre el
APK evaluado y protege frente a cambios concurrentes de main.

Esta primera implementación inspecciona asignaciones literales únicas en el
módulo Kotlin DSL declarado. Groovy, cálculos, múltiples asignaciones, comentarios
ambiguos y otros esquemas se bloquean con instrucciones para evaluar Gradle en CI.
Una lectura literal es preventiva, no prueba del resultado evaluado de Gradle:
plugins o scripts pueden modificarlo. No se ejecuta código Gradle en la tablet.

Preparar nueva versión abre el comparador de GitHub y explica rama/PR y código
mínimo. No crea PR automáticamente: no hay un editor estructural de Gradle
compatible con cualquier repositorio que permita hacerlo con seguridad. No se
escribe main ni se solicitan nuevos scopes OAuth. El propietario autoriza y revisa
la edición en GitHub; esta limitación es explícita y no se presenta como edición
automática de fuentes dinámicas.

Los errores muestran fase y mensajes fijos. Se clasifican anotaciones conocidas
de GitHub sin renderizar sus cuerpos ni logs. Si no se pueden consultar, queda
el diagnóstico por fase y enlace a Actions. El detalle técnico se consulta en
GitHub con su control de acceso. Los borradores no equivalen a publicación:
se conserva la confirmación por recibo de ejecución y APK de Release estable.
No se cambia RS-05, permisos, firma, SHA-256, inmutabilidad o recuperación de borradores.

## Prueba en Galaxy Tab S9+

Tras revisar, integrar y ordenar una futura Release oficial de RobPublisher:
1. Actualizar la aplicación oficial sin desinstalarla, nunca instalar un Debug.
2. Abrir Cuadrante con main aún en código 2: debe mostrar actual 2, mínimo 3 y
   bloquear Publicar. Preparar nueva versión abre GitHub; no crea ramas sin orden.
3. Revisar la PR independiente de Cuadrante. Una vez autorizada su integración,
   actualizar información: candidata 1.0.2/código 3 y publicación habilitada.
4. Publicar solo tras orden explícita. Seguir guard/build/sign/publish y abrir el
   detalle de Actions. Un borrador o ejecución incompleta no debe marcar éxito.
5. Confirmar la Release final en GitHub y actualizar desde RobStore manteniendo datos.

La PR prepara RobPublisher 1.0.4/código 5, superior a la oficial 1.0.3/código 4.
No se publica: integración y distribución requieren autorización del propietario.
