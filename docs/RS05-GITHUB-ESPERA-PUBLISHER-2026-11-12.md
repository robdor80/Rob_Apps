# RobPublisher 1.0.6 — seguimiento de aprobaciones y esperas de GitHub

## Hallazgo
El código de `DistributionService.update()` marcaba la ejecución como error cuando `status` era `waiting`, mostrando siempre «GitHub requiere una aprobación». Esto detenía el seguimiento de `MainActivity` porque `PublicationSession.watching` solo incluye `pendiente` y `ejecutándose`.

En RobGuard, comprobación manual de entornos `android-release` y `android-publish` (12/11/2026): Required reviewers desactivado, wait timer desactivado, rama permitida `main`. No hay indicios de una aprobación obligatoria en esas dos configuraciones. La última distribución comprobada, RobGuard v0.2.3-5, finalizó correctamente.

## Corrección
- `waiting` se trata como estado de espera monitorizado, NO como error ni como confirmación de aprobación.
- El mensaje invita a revisar Actions únicamente si allí se exige una aprobación genuina.
- `action_required` conserva estado de error que requiere intervención. No se desbloquea automáticamente.
- El servicio no modifica entornos, aprobadores, secretos ni el motor RS-05.
- Mantiene la disciplina de un solo `workflow_dispatch`, seguimiento persistente y orden jamás reenviada en reintentos.

## Verificación
- Test de espera→error auténtico y no reenvío.
- Test de espera→en curso→publicada y no reenvío.
- Test de acción requerida real.
- CI tests Release, lint, compilación y contrato RS-05.
- Tras publicar: instalar actualización 1.0.6 sobre 1.0.5 desde RobStore, sin borrar datos, y comprobar seguimiento real de futuras distribuciones. No disparar una publicación de prueba sin cambio de versión real.

**No se afirma que todas las esperas sean automáticas:** GitHub puede exigir aprobación auténtica en otras aplicaciones/entornos. Se conserva el aviso condicionado a evidencia y seguimiento en lugar de un error falso que bloquea la app.
