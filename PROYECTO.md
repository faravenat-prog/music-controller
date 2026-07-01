# Music Controller — Documentación del Proyecto

App Android fullscreen para controlar Spotify durante ciclismo. Samsung A54, Mi Band 8.
**Repo:** https://github.com/faravenat-prog/music-controller

---

## Stack

- Kotlin, Android nativo, minSdk 26
- MediaSessionManager + NotificationListenerService para controlar otras apps (Spotify, YouTube Music, etc.)
- **IpWebcamClient.kt** — lee stream MJPEG por HTTP desde app IP Webcam en celular viejo
- LocationManager (GPS) para velocidad en tiempo real
- Health Connect para pulso Mi Band 8
- ViewBinding, Material Design 3
- Build via GitHub Actions (gradle 8.4) → descarga APK desde pestaña Actions del repo

---

## Estado actual — APP FUNCIONANDO EN SAMSUNG A54

### Features implementadas ✅

- Control de Spotify: play/pause/next/prev/volumen
- Pantalla siempre encendida (FLAG_KEEP_SCREEN_ON)
- Fullscreen sin barras del sistema
- Velocidad GPS en tiempo real
- Hora actual actualizada cada segundo
- Cámara del celular con botón on/off para ahorrar batería
- Fila de stats: ❤️ -- bpm | 🚴 xx km/h | 🕐 HH:mm (20sp negrita)
- Permisos: CAMERA, ACCESS_FINE_LOCATION, MODIFY_AUDIO_SETTINGS

### Layout (de arriba a abajo)

1. Botones anterior / play-pause / siguiente
2. Slider de volumen con botones - y +
3. Título canción + artista
4. Barra de progreso + tiempos
5. Fila stats: pulso | velocidad | hora
6. Preview de cámara (espacio restante) + botón 📷 esquina

### Tamaños y colores de botones

| Botón | Tamaño | Color |
|---|---|---|
| Play/Pausa | 112dp | #1DB954 verde oscuro |
| Anterior / Siguiente | 104dp | #5DE08A verde claro |
| Vol - / Vol + | 104dp | #64B5F6 azul claro |
| Botón cámara | 48dp | negro semitransparente |

### Ícono de la app

- Fondo: círculo verde #1DB954
- Foreground: nota musical grande + bicicleta pequeña esquina inferior derecha
- **PENDIENTE MEJORAR** — diseño quedó incompleto, retomar

---

## Archivos clave

| Archivo | Propósito |
|---|---|
| `app/src/main/res/layout/activity_main.xml` | Layout principal |
| `app/src/main/java/com/faravenat/musiccontroller/MainActivity.kt` | Lógica principal |
| `app/src/main/java/com/faravenat/musiccontroller/MediaService.kt` | Servicio media |
| `app/src/main/java/com/faravenat/musiccontroller/IpWebcamClient.kt` | Cliente cámara IP Webcam (MJPEG HTTP) |
| `app/src/main/res/xml/network_security_config.xml` | Permite HTTP plano en red local |
| `.github/workflows/build.yml` | CI/CD GitHub Actions |
| `app/src/main/res/drawable/ic_launcher_foreground.xml` | Ícono foreground |

---

## Próximas features pendientes

1. **Mejorar ícono** — retomar diseño, el usuario no quedó conforme (prioridad inmediata)
2. **Pulso Mi Band 8** — leer desde Health Connect
   - Confirmar: ¿tiene Health Connect? ¿Mi Fitness sincroniza con Health Connect?

---

## Cámara — Estado de implementación

**Solución actual:** Celular viejo con app [IP Webcam](https://play.google.com/store/apps/details?id=com.pas.webcam) conectado al hotspot del A54.

### Cómo funciona

- Celular viejo conectado al hotspot del A54 → abre IP Webcam → toca "Start server"
- IP Webcam transmite MJPEG por HTTP en `http://10.x.x.x:8080/video`
- La app lee el stream y muestra los frames en pantalla

### Uso en la app

- **Tap corto** en botón cámara (cuando está apagada) → conecta con la IP guardada; si es la primera vez pide la IP
- **Tap largo** en botón cámara → abre diálogo para cambiar la IP
- **Tap corto** cuando cámara activa → detiene el stream
- La IP se guarda en SharedPreferences — se recuerda entre sesiones

### Implementación

- `IpWebcamClient.kt` — lee stream MJPEG por HTTP usando `HttpURLConnection`
- Detecta frames por marcadores JPEG: `FF D8` (inicio) → `FF D9` (fin)
- Sin dependencias extra — solo Java estándar + coroutines
- `network_security_config.xml` — permite HTTP plano en red local (requerido desde Android 9)

### Commits clave

| Commit | Descripción |
|---|---|
| `010f57d` | ✅ Reemplaza cámara A9/PPPP por IP Webcam (MJPEG HTTP) |
| `9c7372e` | ✅ IP configurable con diálogo, guardada en SharedPreferences |
| `da502a6` | ✅ Fix cleartext HTTP — network_security_config para red local |

---

## Info del usuario

- Celular: Samsung A54
- Reloj: Xiaomi Mi Band 8 (usa app Mi Fitness)
- Uso principal: ciclismo
- País: Chile
