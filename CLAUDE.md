# ParlanteY — Galaxy Y como parlante Bluetooth

## Objetivo
Un Android moderno (**emisor**) envía música por Bluetooth a un Samsung Galaxy Y (**receptor**), que la reproduce por su parlante o por la salida de audífonos. Sin root en ningún teléfono.

## Decisiones de diseño (no reabrir sin avisar)
- Android 2.3 solo implementa A2DP como fuente: puede emitir audio a un parlante, no recibirlo. No existe el rol A2DP sink. → Protocolo propio sobre **RFCOMM** (socket Bluetooth clásico, API 5+).
- El Galaxy Y no tiene MediaCodec (API 16) y su CPU es ARMv6 sin NEON. → El emisor hace todo el trabajo pesado y envía **IMA ADPCM** (4 bits por muestra). El receptor solo decodifica ADPCM en Java (sumas y tablas) y reproduce con `AudioTrack`.
- Dispositivo: Samsung Galaxy Y GT-S5360L (variante latinoamericana), Android 2.3.6 (API 10, compilación GINGERBREAD.UHMC1), Bluetooth 3.0, pantalla 240×320, parlante mono, jack 3,5 mm. Confirmado en Ajustes → Acerca del teléfono (2026-10-17).
- Entorno de desarrollo: Windows. El receptor se prueba en el teléfono real, sin emulador.

## Flujo de trabajo
- Antes de cada fase: modo plan → presentar plan → esperar aprobación.
- Al cerrar cada fase: mostrar evidencia de los criterios de aceptación y detenerse.
- Mediciones y resultados de pruebas en dispositivo → `NOTAS.md`.
- `PROTOCOLO.md` es la fuente de verdad del protocolo. Todo cambio: actualizar `PROTOCOLO.md` + tests + número de versión.

## Arquitectura
```
EMISOR (Android 8+)                                          RECEPTOR (Galaxy Y)
Archivos ─► MediaExtractor + MediaCodec ─┐
                                         ├─► PCM16 ─► downmix/decimar ─► IMA ADPCM ─► RFCOMM ─► cola ─► ADPCM→PCM ─► AudioTrack ─► parlante
Captura (Android 10+) ─► AudioRecord ────┘                                   ◄── STATE (nivel de buffer, cada 250 ms) ──
```

## Repositorio
```
parlantey/
├── CLAUDE.md
├── PROTOCOLO.md
├── NOTAS.md
├── core/       Android library, Java, minSdk 9: protocolo, códec, DSP, tests JVM
├── receptor/   app para el Galaxy Y, Java, minSdk 9
├── emisor/     app moderna, Kotlin + Compose, minSdk 26
└── pc/         emisor para Windows 10/11 x64: Java 21 + JNA, compila las fuentes de core
```
Un solo build Gradle multi-módulo. Paquetes: `cl.parlantey.core`, `cl.parlantey.receptor`, `cl.parlantey.emisor`, `cl.parlantey.pc`.
- `pc` (agregado a pedido del usuario): manda al Galaxy Y lo que suena en el PC (WASAPI loopback) por Bluetooth (Winsock `AF_BTH`), con el mismo protocolo y el mismo `FlujoEmisor`/`ControlCola` en vivo. Se empaqueta como `ParlanteY.exe` (jpackage, con su propio Java) en el job de Windows del workflow de release.

## Reglas duras

### core y receptor (corren en Android 2.3)
- Java con nivel de lenguaje 8 (D8 desazucara las lambdas). Sin Kotlin, sin AndroidX, sin librerías externas, sin NDK.
- Con AGP 9+: desactivar el Kotlin integrado en estos dos módulos (no tienen fuentes Kotlin). La app no debe traer kotlin-stdlib ni AndroidX: `./gradlew :receptor:dependencies --configuration debugRuntimeClasspath` (y `releaseRuntimeClasspath`) debe mostrar solo `project ':core'`, y `./gradlew :receptor:verificarApk` debe pasar (comprueba que el dex no referencia `Lkotlin/` ni `Landroidx/`). El informe completo de `:receptor:dependencies` siempre nombra kotlin-stdlib en configuraciones de herramientas de AGP (`androidLintTool`, `kotlin*Classpath`, `unified-test-platform…`); eso no cuenta.
- minSdk 9. Lint `NewApi` e `InlinedApi` como error. APIs superiores a 9 solo con guarda `Build.VERSION.SDK_INT` (ej.: sockets RFCOMM insecure, API 10).
- No usar: `java.util.function`, streams, `java.util.Objects`, `StandardCharsets`, `java.time`, `Notification.Builder` (API 11), `BluetoothSocket.isConnected()` (API 14), MediaCodec (API 16).
- Notificación del servicio: `new Notification()` llenando los campos públicos `icon`, `tickerText`, `when`, `contentView` (RemoteViews con layout propio) y `contentIntent`. `setLatestEventInfo` ya no existe en el SDK actual.
- Tema `@android:style/Theme.Black.NoTitleBar`. Íconos PNG (sin adaptive icons ni vector drawables).
- UI pensada para 240×320: un `LinearLayout`, textos grandes.
- Camino de audio sin asignaciones de memoria: buffers y bloques preasignados y reutilizados (el GC de Dalvik provoca cortes).
- Hilo de reproducción con `Process.THREAD_PRIORITY_URGENT_AUDIO`.
- APK con firma v1: `apksigner verify --min-sdk-version 9 --verbose <apk>` debe pasar.

### emisor
- Kotlin + Jetpack Compose. minSdk 26; compileSdk y targetSdk = última versión estable instalada.
- Solo dispositivos ya vinculados (`bondedDevices`). Sin discovery → sin permiso de ubicación.
- Permisos: hasta API 30, `BLUETOOTH` y `BLUETOOTH_ADMIN` con `maxSdkVersion="30"`; API 31+, `BLUETOOTH_CONNECT` en runtime; API 33+, `POST_NOTIFICATIONS`; modo captura, `RECORD_AUDIO`.
- Servicio en primer plano tipo `connectedDevice` (+ `mediaProjection` al capturar) con sus permisos `FOREGROUND_SERVICE_*` de API 34+. En captura, respetar el orden de Android 14+: consentimiento de MediaProjection → `startForeground` con tipo `mediaProjection` → crear la proyección.

## Protocolo v1
- RFCOMM con UUID v4 propio (generar una vez; constante en `core`). Receptor = servidor (`listenUsingRfcommWithServiceRecord`); emisor = cliente (`createRfcommSocketToServiceRecord`). Variante insecure como fallback (API 10+).
- Big-endian con `DataInputStream`/`DataOutputStream`; strings con `writeUTF`/`readUTF`.
- Trama: `magic u16 (0x5059) | tipo u8 | flags u8 | largo u32 | payload`. Largo máximo 64 KiB. Magic inválido o largo excesivo → cerrar la conexión.
- Al conectar, el receptor envía HELLO. Si la versión no coincide: BYE y error visible en ambas apps.

| Tipo | Dirección | Payload |
|---|---|---|
| 0x01 HELLO | R→E | versión u8, apiLevel u16, nombre UTF, maxCanales u8, capacidadBufferMs u32 |
| 0x02 CONFIG | E→R | códec u8 (1 = IMA ADPCM), sampleRate u32, canales u8, muestrasPorBloque u16, bufferObjetivoMs u32 |
| 0x03 AUDIO | E→R | seq u32 + bloque ADPCM |
| 0x04 TRACK | E→R | título UTF, artista UTF, duraciónMs u32 (0 = en vivo) |
| 0x05 CONTROL | E→R | cmd u8: 1 PAUSA, 2 REANUDAR, 3 VACIAR, 4 DETENER |
| 0x06 STATE | R→E | bufferMs u32, cortes u32, ultimoSeq u32, reproduciendo u8 (cada 250 ms) |
| 0x07 PING / 0x08 PONG | E→R / R→E | timestamp u64 (eco) |
| 0x09 TEST_DATA | E→R | relleno; `flags` bit 0 = última trama de la prueba |
| 0x0A TEST_RESULT | R→E | bytesRecibidos u64, duraciónMs u32 |
| 0x0B BYE | ambos | código u8 |

**Bloque IMA ADPCM** (decodificable de forma aislada):
- Por canal: `predictor i16, índicePaso u8, reservado u8` (estado del códec al inicio del bloque).
- Luego, por canal (planar): `muestrasPorBloque / 2` bytes; el nibble bajo es la muestra anterior.
- `muestrasPorBloque` = 1024.
- Tablas IMA estándar (89 pasos + tabla de ajuste de índice). Predictor saturado a int16, índice a 0..88. El codificador replica el estado del decodificador.

## Presets de calidad
| Preset | Formato | Tasa | Uso |
|---|---|---|---|
| Alta | estéreo, tasa original | 353–384 kbps (≈ 44–48 KB/s) | cable a un equipo de música o audífonos |
| Normal (defecto) | mono, tasa original | 176–192 kbps (≈ 22–24 KB/s) | parlante interno (es mono) |
| Segura | mono, tasa ÷ 2 | 88–96 kbps (≈ 11–12 KB/s) | enlace inestable |

- Auto: el preset más alto cuya tasa sea ≤ 70 % del throughput medido en la prueba de enlace. El usuario puede forzar otro.
- Tasa original > 48 kHz → decimar ×2 hasta quedar ≤ 48 kHz (`AudioTrack` de 2.3 acepta 4–48 kHz). Decimación: FIR paso bajo de media banda + descartar 1 de cada 2 muestras.
- Downmix a mono: promedio de canales con saturación.
- Si cambian tasa o canales entre pistas: nuevo CONFIG; el receptor vacía y recrea `AudioTrack`.

## Receptor
- Si el Bluetooth está apagado, pedir encenderlo (`ACTION_REQUEST_ENABLE`).
- Servicio en primer plano + `PARTIAL_WAKE_LOCK` mientras haya conexión (sigue sonando con la pantalla apagada).
- Hilo de red: `accept()` → leer tramas → cola acotada de bloques (pool preasignado para `capacidadBufferMs` = 4000).
- Hilo de audio: decodifica → `AudioTrack` (`MODE_STREAM`, `STREAM_MUSIC`, buffer = 4 × `getMinBufferSize`). Arranca con ≥ 70 % del buffer objetivo; si se vacía, escribe silencio, cuenta un corte y vuelve a llenar.
- `bufferMs` = bloques en cola × ms por bloque + (frames escritos − `getPlaybackHeadPosition()`) en ms.
- Volumen con las teclas físicas: `setVolumeControlStream(AudioManager.STREAM_MUSIC)`.
- UI: estado (esperando / conectado a X / reproduciendo), título y artista, barra de buffer, cortes, preset y botón **Hacer visible** (`ACTION_REQUEST_DISCOVERABLE`, 300 s) para vincular.
- Conexión perdida → volver a `accept()` sin intervención.

## Emisor
- Inicio: elegir el Galaxy Y entre los vinculados → conectar → prueba de enlace (KB/s y RTT) → elegir modo.
- Si la conexión se corta: reintentar con backoff (1, 2, 4… máx. 10 s).
- **Modo Archivos** (Android 8+): selector múltiple (`ACTION_OPEN_DOCUMENT`, `audio/*`), cola, play/pausa/siguiente/anterior. Decodificar con MediaExtractor + MediaCodec a PCM16 (MP3, AAC, FLAC, Opus y lo que soporte el teléfono).
  - Control de flujo: nivel estimado = `STATE.bufferMs` + (seqEnviado − `STATE.ultimoSeq`) × ms por bloque. Enviar mientras el nivel sea < 2000 ms.
  - Cambio de pista: CONTROL VACIAR → TRACK → CONFIG (si cambia) → audio.
- **Modo Todo el audio** (Android 10+): MediaProjection + `AudioPlaybackCaptureConfiguration` (USAGE_MEDIA, USAGE_GAME, USAGE_UNKNOWN) → `AudioRecord` 48 kHz estéreo → mismo codificador. Buffer objetivo 500 ms. Cola de envío acotada: si el enlace se atrasa, descartar lo más antiguo (la latencia no debe crecer). Avisar en la UI que algunas apps bloquean la captura (llega silencio) y que la latencia (~1 s) no sirve para video.
- Notificación persistente con pausa / siguiente / detener.

## Fases

### Fase 0 — Riesgos primero
1. Proyecto multi-módulo. Receptor "hola mundo" con minSdk 9 que se instala y abre en el Galaxy Y, con firma v1 verificada. Si el AGP actual rechaza minSdk 9, proponer solución (ej.: fijar una versión anterior de AGP) antes de seguir.
2. En `core`, solo las tramas HELLO, PING/PONG, TEST_DATA/TEST_RESULT y BYE.
3. Emisor mínimo: elegir dispositivo vinculado, conectar, medir RTT y throughput (5 s de TEST_DATA).
4. Anotar en `NOTAS.md` el throughput con el Wi-Fi del Galaxy Y encendido y apagado (Bluetooth y Wi-Fi comparten la banda de 2,4 GHz).

✅ APK instalado y abierto; conexión estable; KB/s anotados. Si no alcanza para el preset Segura (≈ 18 KB/s), detenerse y proponer adelantar el transporte TCP (Fase 5).

### Fase 1 — core completo
Resto del protocolo, códec IMA ADPCM, decimador ×2 y downmix.

✅ Tests JVM: ida y vuelta de cada tipo de trama; trama corrupta rechazada; ADPCM con SNR ≥ 20 dB sobre un seno de 1 kHz a −6 dBFS y 44,1 kHz; un bloque decodificado aislado es idéntico al mismo bloque decodificado en secuencia; salida determinista.

### Fase 2 — receptor completo + tono de prueba
Receptor según la sección Receptor. En el emisor, botón **Tono de prueba** (440 Hz generado → pipeline completo).

✅ 10 min de tono sin cortes con la pantalla apagada; reconecta solo tras apagar y encender el Bluetooth del emisor.

### Fase 3 — emisor modo Archivos
✅ Una cola de 3 pistas (MP3, M4A y FLAC) suena completa; pausa y siguiente responden en < 1 s; el preset Auto es coherente con lo medido en la Fase 0.

### Fase 4 — emisor modo Todo el audio
Agregar en el receptor compensación de deriva de reloj: si `bufferMs` se aleja más de 150 ms del objetivo, descartar o duplicar 1 frame cada ~1000 hasta volver al rango.

✅ 30 min de captura con latencia estable; cortes anotados en `NOTAS.md`. Anotar también si bajar el volumen del emisor afecta la captura (varía según el fabricante).

### Fase 5 — opcional
- Transporte TCP alternativo (Galaxy Y conectado al hotspot del emisor, IP ingresada a mano) detrás de una interfaz `Transporte`; mismo protocolo.
- Inicio automático del receptor al encender el teléfono (`BOOT_COMPLETED`).

## Instalación en el Galaxy Y
- Ajustes → Aplicaciones → activar **Orígenes desconocidos**.
- `adb install -r receptor/build/outputs/apk/debug/receptor-debug.apk` (en Windows requiere el driver USB de Samsung). Alternativa: copiar el APK a la microSD y abrirlo desde un gestor de archivos.
- Vincular: en el receptor tocar **Hacer visible** → en el emisor, Ajustes → Bluetooth → vincular.

## Fuera de alcance
A2DP sink nativo, root o ROMs, iOS, varios receptores sincronizados y códecs nativos (Opus vía NDK exigiría un NDK antiguo con ABI `armeabi`).
