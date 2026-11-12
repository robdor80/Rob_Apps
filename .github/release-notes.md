RobPublisher 1.0.6 — seguimiento correcto de esperas GitHub

- El estado de ejecución `waiting` ya no se considera erróneamente una firma fallida ni una aprobación confirmada.
- Cuando GitHub queda en espera, RobPublisher conserva el seguimiento automático de la orden existente; al reanudarse se actualizan compilación, firma y publicación sin pulsar «Publicar» otra vez.
- Si GitHub requiere realmente intervención (`action_required`), la aplicación sigue mostrando un bloqueo y remite a Actions. Nunca omite las protecciones de los entornos.
- Mensajes más precisos, sin falsas afirmaciones de que GitHub exige aprobación.
- Pruebas nuevas: espera → fallo real, espera → publicación correcta y estado con intervención real; ninguna ruta reenvía el POST de publicación.
- Sin cambios en OAuth, certificados, secretos, permisos, firma o motor central RS-05.
- Se conserva la interfaz minimalista de la versión 1.0.5.

Package: es.robertodorado.robpublisher
Version: 1.0.6 · versionCode 7
Publicación exclusiva mediante RobPublisher / RS-05 usando la firma Release original.
