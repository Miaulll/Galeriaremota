# Compilar APK sin Android Studio

GitHub Actions compila el proyecto en un servidor. No necesitas Android Studio ni Gradle instalados en tu computadora.

1. Crea un repositorio **privado** en github.com/new (por ejemplo, `galeria-remota`).
2. Sube el **contenido de esta carpeta GaleriaRemota** a la raíz del repositorio, manteniendo `.github/workflows/compilar-apk.yml` y la carpeta `android/`. La web de GitHub permite subir archivos; asegúrate de preservar las carpetas, incluido el archivo oculto `.github`.
3. Abre la pestaña **Actions** y selecciona **Compilar APK Android**.
4. Selecciona **Run workflow**. Si no aparece, comprueba que el YAML esté en la rama predeterminada.
5. Cuando la ejecución termine en verde, entra a la ejecución y descarga el artefacto **GaleriaRemota-APK-debug**.
6. Descomprime ese archivo ZIP: dentro encontrarás `app-debug.apk` para instalar en tu teléfono.

Si la compilación falla, copia el error del paso **Generar APK de prueba**. Este proyecto es un prototipo, y la compilación no ha sido verificada en un entorno Android real.

**Seguridad:** Usa repositorio privado, Tailscale y una clave por teléfono. No publiques tus credenciales ni compartas la clave mostrada en la APK. La versión de prueba requiere que el servicio esté activo y puede ser suspendida por Android; no garantiza acceso remoto continuo.
