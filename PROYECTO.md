# Music Controller — Documentación del Proyecto

App Android fullscreen para controlar Spotify durante ciclismo. Samsung A54, Mi Band 8.
**Repo:** https://github.com/faravenat-prog/music-controller

---

## Stack

- Kotlin, Android nativo, minSdk 26
- MediaSessionManager + NotificationListenerService para controlar otras apps (Spotify, YouTube Music, etc.)
- **PpppClient.kt** — protocolo PPPP UDP nativo para cámara IP A9 (reemplazó ExoPlayer/RTSP y CameraX)
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
| `app/src/main/java/com/faravenat/musiccontroller/PpppClient.kt` | Cliente cámara IP A9 |
| `app/src/main/java/com/faravenat/musiccontroller/PpppCipher.kt` | Cifrado XOR PPPP |
| `app/src/main/java/com/faravenat/musiccontroller/CloudRelay.kt` | Cloud relay PPPP/iLnkP2P |
| `.github/workflows/build.yml` | CI/CD GitHub Actions |
| `app/src/main/res/drawable/ic_launcher_foreground.xml` | Ícono foreground |

---

## Próximas features pendientes

1. **Mejorar ícono** — retomar diseño, el usuario no quedó conforme (prioridad inmediata)
2. **Pulso Mi Band 8** — leer desde Health Connect
   - Confirmar: ¿tiene Health Connect? ¿Mi Fitness sincroniza con Health Connect?
3. **Cámara externa WiFi A9** — COMPRADA (2026-06-24) — IMPLEMENTADA CON REINTENTOS, PENDIENTE PRUEBA REAL 📱

---

## Cámara IP A9 — Estado de implementación

**Modelo:** cámara IP A9 (app oficial: YsxLite, package: com.ysxlite.cam)  
**Protocolo:** PPPP (iLnkP2P) — NO es RTSP, es UDP propietario

### Implementación — commits relevantes (al 2026-06-26)

| Commit | Descripción |
|---|---|
| `6e59b22` | ✅ Fix peerPort como var para permitir reasignación en PUNCH handler |
| `6cab74c` | ✅ Fix PROBE unicast a cámara tras relay + PUNCH usa puerto real |
| `fc18391` | ✅ Fix relay LOOKUP payload: include LAN IP, 52 bytes matching YsxLite |
| `286ffe9` | ✅ Fix: handle 0x21 relay handshake from camera — echo back + retry VIDEO_CMD |
| `e924b17` | ✅ Skip PUNCH handshake on relay path, retry VIDEO_CMD both encrypted/plain |
| `ca19eba` | ✅ Debug: log first received packet type and bytes as Toast |
| `cac3d16` | ✅ Add raw byte dump for camera debug |
| `158ac2e` | ✅ Fix JPEG frame detection: emit on new SOI, remove XOR decrypt from video (CRÍTICO) |
| `e91e694` | ✅ Replace PPPP frame magic detection with JPEG SOI/EOI state machine |
| `8c3389d` | ✅ Fix relay: use same socket for lookup and video |
| `dfe4479` | ✅ Implement cloud relay (PPPP/iLnkP2P) para camera discovery via UID |

### Pendiente confirmar en prueba real (últimos commits 6cab74c + 6e59b22)

- **PRIORIDAD:** Si el PROBE unicast a `cámara:32108` tras relay lookup hace que la cámara responda con PUNCH (0x41)
  - Toast esperado: `Pkt#1 tipo=0x41 src=10.x.x.x` → eco → VIDEO_CMD → `Frame recibido: NNNN bytes`
- Si el video muestra imagen clara (SOI/EOI state machine, sin XOR decrypt)
- Calidad y fluidez durante ciclismo
- Si el fallback local funciona cuando relay no está disponible

### Mensajes de debug al conectar

```
"Relay OK: 10.x.x.x:YYYY"    → relay encontró la cámara ✓
"Conectado — 10.x.x.x"       → handshake OK, stream pedido ✓
"Datos de cámara recibidos"   → primer paquete DRW llegó ✓
"Frame recibido: NNNN bytes"  → primer JPEG completo emitido ✓
"Cámara no encontrada"        → relay Y local fallaron ✗
"Error: ..."                  → excepción inesperada ✗
```

### Discovery / Conexión

- **Discovery:** reintenta probe cada 2s, timeout 10s, 5 subredes comunes del hotspot Android
- **Cloud relay:** contacta servidores en puerto 32100 usando STUN+LOOKUP con UID de la cámara (`batg529474bormc`)
- **Fallback:** si relay falla, intenta discovery local con ARP+broadcast
- **UID de la cámara:** `batg529474bormc`

### Protocolo PPPP — detalles técnicos

**Descubrimiento:**
- UDP broadcast → puerto 32108, paquete: `[0x2c, 0xba, 0x5f, 0x5d]`
- Cámara responde MSG_PUNCH (0x41)

**Handshake:**
1. Cliente → broadcast UDP 32108 (probe)
2. Cámara → MSG_PUNCH (0x41)
3. Cliente → echo del mismo paquete (hasta 5 veces)
4. Cámara → MSG_P2P_RDY (0x42) ← conexión lista

**Estructura paquete (header 8 bytes):**
```
[0xf1][TIPO][LEN_HI][LEN_LO][0xd1][CANAL][IDX_HI][IDX_LO][DATOS XOR]
```
- Canal: 0=CMD, 1=Video, 2=Audio

**Tipos de mensaje:**
```
MSG_PUNCH=0x41, MSG_P2P_RDY=0x42, MSG_DRW=0xd0, MSG_DRW_ACK=0xd1
MSG_ALIVE=0xe0, MSG_ALIVE_ACK=0xe1, MSG_CLOSE=0xf0
```

**Comando para pedir video (JSON cifrado en MSG_DRW canal 0):**
```json
{"pro":"stream","cmd":111,"video":1,"user":"admin","pwd":"6666","devmac":"0000"}
```
- `video:1` = alta resolución, `video:2` = baja resolución

**Frames MJPEG:**
- Detección stateful: busca `FF D8` (JPEG SOI) → acumula bytes → busca `FF D9` (JPEG EOI) → frame completo
- Canal: 0 o 1 (flexible)
- Acumula bytes del stream continuo — sin depender de posición de paquetes UDP

**Cifrado XOR:**
- Clave primaria: `{0x69, 0x97, 0xcc, 0x19}`
- Tabla de 256 bytes; índice = `key[prevByte & 0x03] + prevByte`
- Simétrico: encode y decode usan la misma lógica
- **IMPORTANTE:** Video NO está cifrado — solo los comandos JSON enviados a la cámara sí lo están

### Lecciones aprendidas (confirmadas en pruebas y PCAP)

- Video NO está cifrado XOR — solo los comandos JSON enviados a la cámara sí lo están
- Frames MJPEG: delimitados por `FF D8` (SOI) al inicio; sin `FF D9` (EOI) entre frames consecutivos
- Relay retorna IP local de la cámara en el hotspot (ej. `10.245.249.30:51998`)
- Relay y video deben usar el MISMO socket UDP (mismo puerto local al relay)
- **PCAP YsxLite (PCAPdroid 24-jun):** relay en puerto 32100. IP cámara: `10.165.35.30:51997` y `10.245.249.30:51998`. Video: ~673KB en 10s ≈ 67KB/s MJPEG. YsxLite envía 52 bytes al relay, recibe 100 bytes con IP:puerto de la cámara.
- **0x21 = mensaje del servidor relay** (NO de la cámara) — src confirmado: `119.45.114.92` (relay Tencent). Se debe ignorar (filtrado por peer IP).
- **Relay hace discovery pero NO hace NAT punch efectivo** — relay devuelve IP:puerto correcto pero la cámara nunca inicia conexión directa. Estrategia: usar relay solo para descubrir IP, luego hacer handshake directo con PROBE unicast.

### Repos de referencia (protocolo A9)

- https://github.com/datenstau/A9_PPPP — JS, protocolo exacto A9, XOR + JSON
- https://github.com/hyc/a9serv — C puro, sin deps, pppp.c + cipher.c
- https://github.com/DavidVentura/cam-reverse — reverse engineering completo

---

## Info del usuario

- Celular: Samsung A54
- Reloj: Xiaomi Mi Band 8 (usa app Mi Fitness)
- Uso principal: ciclismo
- País: Chile
