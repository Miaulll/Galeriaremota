# Galería Remota — prototipo 0.1

## Contenido
- `android/`: código fuente de aplicación Android en Kotlin para Android Studio.
- `panel/index.html`: panel de múltiples teléfonos desde un navegador de laptop.

## IMPORTANTE: ESTADO ACTUAL
Es un **prototipo de código fuente, no una APK compilada ni una versión probada físicamente**. Se requiere compilar y probar en celulares reales. La aplicación usa un servicio en primer plano que debe iniciarse manualmente; Android puede detenerlo, y Android 15+ puede imponer límites de tiempo. No promete acceso 24/7. No se almacenan copias de las fotos en un servidor.

## Compilar APK
1. Instalar Android Studio actualizado con Android SDK 35 y JDK 17 o 21.
2. Abrir la carpeta `android/` en Android Studio y permitir sincronización de Gradle.
3. Menú `Build` > `Build Bundle(s) / APK(s)` > `Build APK(s)`.
4. Buscar APK en `android/app/build/outputs/apk/debug/app-debug.apk`.
5. Instalarla solamente en teléfonos que poseas y controles.

## Configurar la red privada
1. Instalar Tailscale en laptop y teléfonos; iniciar sesión en tu misma red privada (tailnet).
2. En cada teléfono, abrir Tailscale y mantenerlo conectado. Comprobar IP 100.64.0.0/10.
3. Abrir Galería Remota, conceder acceso completo a fotos (o solo las seleccionadas en Android 14+), y pulsar `Iniciar servidor privado`.
4. Tomar nota de la IP Tailscale y la clave de 48 caracteres, que aparecen en pantalla.
5. En la laptop abrir `panel/index.html`, agregar el teléfono y completar su IP y clave.
6. Mantener el teléfono encendido, conectado a Internet, Tailscale funcionando y el servicio activo.

## Seguridad y limitaciones
- El servicio solo escucha la dirección IP Tailscale detectada; no escucha en Wi-Fi abierto.
- Clave aleatoria por instalación. Mantén esa clave privada; el panel la conserva en localStorage de ese navegador.
- La comunicación HTTP se transporta dentro del túnel cifrado de Tailscale. No utilices ni expongas el puerto 8765 en Internet pública ni abras puertos del router.
- Tailscale puede requerir configuración para aceptar tráfico entrante en Android; probar comunicación primero.
- API limitada a lectura de fotos del MediaStore; no permite subir, borrar ni modificar imágenes.
- Compatible desde Android 8.0 en código; en Android 13+ requiere permiso de fotos, Android 14+ puede permitir acceso parcial y Android 15+ limita foreground services dataSync. La permanencia en segundo plano depende del sistema y fabricante.
- Google Chrome puede bloquear llamadas de una página local a una IP privada dependiendo de sus políticas de acceso de red; si ocurre, servir `panel` desde `localhost` con `python -m http.server 8080` y abrir http://localhost:8080; algunas versiones del navegador aun pueden requerir permisos de acceso a red local.
- Prototipo sin auditoría de seguridad; no utilizar para datos especialmente sensibles.
- No implementa video, ZIP múltiple, sincronización, arranque automático, ni acceso mientras el teléfono está apagado.
