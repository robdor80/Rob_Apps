# UX — Inicio compacto de RobPublisher (12/11/2026)

## Decisión visual

La pantalla de Inicio conserva únicamente el nombre de la app, un indicador circular de GitHub (verde si la cuenta se ha validado, rojo en otro caso), acceso compacto a Ajustes, el contador `N aplicaciones`, una acción discreta de actualización y una lista vertical de apps sin tarjetas.

Cada fila presenta el icono oficial de la aplicación a la izquierda; a la derecha, nombre, Release, fecha y estado. Las etiquetas `vX.Y.Z-N` se muestran como `Release X.Y.Z (N)`, sin inventar un número cuando la etiqueta antigua no lo contiene. Las fechas se muestran como `dd/mm/aaaa`. Si el contrato RS-05 está preparado se presenta `Publicable`; si falta algo, el motivo concreto aparece en rojo. **Publicable aquí significa preparación estructural RS-05**, no autorización definitiva de una versión candidata: el comprobador de versiones del detalle sigue bloqueando la publicación si corresponde.

Los iconos se leen exclusivamente de los repositorios autorizados de GitHub: primero el `icon` de `robstore.json`, después rutas conocidas de recursos PNG/WebP. Sin URL externas, sin credenciales expuestas, con límites de tamaño y dimensión; si falta el archivo, un marcador neutro permite seguir usando la lista. El icono no afecta al estado de publicación.

Se eliminan de Inicio la tarjeta de conexión GitHub, textos explicativos sobre descubrimiento, URL completas de repositorio, leyendas de 'descubierta automáticamente', tarjetas individuales y navegación inferior redundante. El alta manual y OAuth continúan en Ajustes. El seguimiento de una publicación activa continúa disponible con una línea resumida, sin eliminar su pantalla de detalle.

## Restricciones y aceptación

- No modificar la integración de firma, OAuth, seguridad, Workflows RS-05, certificados ni la lógica de `distribute()`.
- Conexión: punto verde/rojo accesible a lectores de pantalla; el icono Ajustes permite configurar la conexión.
- La lista sigue consultando GitHub y se puede refrescar manualmente, sin borrar datos válidos si falla la consulta.
- Cada fila completa abre la gestión de publicación correspondiente.
- La ausencia de icono no retrasa ni bloquea la publicación; nunca se confunde un error de conexión con una lista vacía certificada.
- Mantener los test de navegación; añadir tests de formato de versión y fecha.
- CI: `assembleDebug testDebugUnitTest lintDebug`, pruebas de navegación con emulador, y variante Release. **No fusionar ni distribuir sin resultados verdes y revisión del propietario.**
