# NOTAS — mediciones y resultados

Aquí van las mediciones y pruebas en dispositivo de cada fase. Lo que dice `[PENDIENTE]` se llena con los teléfonos reales.

## Entorno de build

| Herramienta | Versión |
|---|---|
| JDK | 21 (javac 21 compila `--release 8` para core y receptor) |
| Gradle (wrapper) | 9.8.1 |
| Android Gradle Plugin | 9.4.1 |
| Kotlin (solo emisor) | 2.4.21 + plugin Compose |
| compileSdk | 37 (los tres módulos) |
| targetSdk | receptor 10, emisor 37 |
| Compose BOM | 2026.09.00 |
| Build tools (apksigner, aapt2) | 36.0.0, el valor por defecto de AGP 9.4 |

## Fase 0 — Riesgos primero

### Verificación sin dispositivo (contenedor Linux, 2026-10-17)

- **Tests JVM de core**:
  - `./gradlew :core:testDebugUnitTest` → 30 tests, 0 fallas (`TramasTest` 21, `SesionTest` 9).
  - `TramasTest` incluye HELLO byte a byte y `read()` que devuelve 0 (tratado como EOF).
  - `SesionTest` hace la sesión completa emisor ↔ receptor sobre TCP de loopback: HELLO, pings, prueba de enlace con bytes exactos, segunda prueba que cuenta desde cero, PONG viejos y tramas desconocidas descartados, versión distinta → BYE(1), trama corrupta, payload corto y corte sin BYE.
- **Lint**: `:core:lintDebug :receptor:lintDebug :emisor:lintDebug` → *No issues found* en los tres.
  - Para comprobar que NewApi/InlinedApi funcionan como error, se agregaron violaciones a propósito y lint las marcó como error:
    - `StandardCharsets` (API 19), `java.time` (26) y streams (24) en core.
    - `BluetoothSocket.isConnected()` (14), `Notification.Builder` (11) y `AudioFormat.CHANNEL_OUT_7POINT1_SURROUND` (23) en receptor.
  - `String.join` (26) no lo marca, porque D8 lo reescribe para cualquier API.
- **APK del receptor**: `./gradlew :receptor:verificarApk`
  ```
  Verifies
  Verified using v1 scheme (JAR signing): true
  Verified using v2 scheme (APK Signature Scheme v2): true
  Number of signers: 1
  minSdkVersion:'9' · dex 035 (classes.dex) · sin Kotlin ni AndroidX · 50554 bytes
  OK: receptor-debug.apk apto para Android 2.3 (API 9)
  ```
  Los digests v1 de `META-INF/MANIFEST.MF` son SHA-1, como exige Android < 4.3.
- **Referencias del dex contra el SDK real** (`herramientas/verificar_api.py`): se resolvieron las 90 referencias externas y los 44 tipos del framework que usa el dex final del receptor.
  - Contra el `android.jar` de **API 10** (2.3.3): **0 faltantes**.
  - Contra **API 9**: falta solo `BluetoothAdapter.listenUsingInsecureRfcommWithServiceRecord`, que está detrás de `SDK_INT >= 10`.
- **Dependencias del receptor**:
  - `./gradlew :receptor:dependencies --configuration debugRuntimeClasspath` (y `releaseRuntimeClasspath`) → solo `project ':core'`.
  - El informe completo de `:receptor:dependencies` sí nombra kotlin-stdlib, pero solo en configuraciones de herramientas de AGP 9: `androidLintTool`, `kotlinAbiValidationCompatClasspath`, `kotlinBuildToolsApiClasspath`, `kotlinCompilerClasspath` y `unified-test-platform-gradle-work-action`. Pasa igual en un módulo Java puro con `enableKotlin = false`, porque lint está escrito en Kotlin y AGP 9 siempre aplica la base de su Kotlin integrado.
  - Ninguna configuración de la app trae Kotlin ni AndroidX, y `verificarApk` confirma que el dex no referencia `Lkotlin/` ni `Landroidx/`.
- **Avisos conocidos del build**, que no vienen del proyecto:
  - AGP 9.4.1 usa `Configuration.setVisible` (deprecado en Gradle 9, se elimina en 11).
  - javac 21 avisa que el nivel 8 es obsoleto; está silenciado con `-Xlint:-options`.

### Dispositivos

| | Receptor | Emisor |
|---|---|---|
| Modelo | Samsung Galaxy Y **GT-S5360L** (variante latinoamericana; confirmado en Acerca del teléfono) | Samsung **SM-A356E** (Galaxy A35 5G) |
| Android / API | **2.3.6 (API 10)** · banda base S5360LUHMC2 · compilación GINGERBREAD.UHMC1 · kernel 2.6.35.7 (mar 2013) | Android 16 (API 36) |
| Versión de la app | 0.0.1 (protocolo v1), release v0.0.1 | 0.0.1 (protocolo v1), release v0.0.1 |

### Instalación y conexión (2026-10-17)

- [x] Receptor instalado y abierto en el Galaxy Y: aceptó la conexión y respondió las pruebas.
- [x] Emisor instalado con el permiso «Dispositivos cercanos».
- [x] Teléfonos vinculados.
- [x] El emisor conecta y recibe el HELLO del receptor (protocolo v1).
- [x] Conexión estable: 5 min conectados sin cortes.
- [x] Desconectar y reconectar sin reabrir la app receptor.

### Mediciones de enlace (2026-10-17)

Condiciones: no registradas (misma sesión, teléfonos cerca).

Tabla copiada desde el emisor:

Emisor: samsung SM-A356E (Android 16, API 36) · ParlanteY Emisor 0.0.1 · protocolo v1

| Fecha y hora | Receptor | Wi-Fi Galaxy Y | KB/s (receptor) | KB/s (emisor) | RTT mín/prom/máx (ms) | KB en s | Preset Auto |
|---|---|---|---|---|---|---|---|
| 2026-10-17 02:37:29 | GT-S5360L | ? | 165.6 | 165.2 | 7/13/58 | 2396.7 en 14.5 | Alta |
| 2026-10-17 02:38:01 | GT-S5360L | OFF | 165.7 | 165.5 | 7/9/12 | 2396.7 en 14.5 | Alta |

La primera prueba (marcada «?») se hizo con el **Wi-Fi del Galaxy Y encendido**: lo confirmó el usuario; el chip quedó sin marcar en la app.

**Observación: el emisor no se bloquea al escribir.** Cada prueba envía TEST_DATA durante 5 s, pero el receptor tardó 14,5 s en recibirlo todo.
- En esos 5 s, el SM-A356E (Android 16) aceptó ~2,4 MB en el socket. El enlace solo había entregado ~0,8 MB, así que quedaron **~1,5 MB en buffers del lado emisor** (~9,5 s de enlace).
- La cifra de throughput sigue siendo válida: el receptor mide la llegada real de los 2,4 MB en 14,5 s.
- Consecuencia para las fases siguientes: bloquear `write()` no sirve como control de flujo en este emisor. Hay que regular el envío con `STATE.bufferMs` y `ultimoSeq`, como ya prevé CLAUDE.md.
- En el modo Todo el audio (Fase 4), esos buffers podrían guardar ~30 s de audio Alta. El descarte de lo más antiguo debe hacerse antes de escribir en el socket, y la cola debe acotarse por bytes en vuelo según STATE, no por lo que acepte `write()`.

### Conclusión de la Fase 0 — ✅ cerrada el 2026-10-17

| Criterio | Resultado |
|---|---|
| APK instalado y abierto | ✓ receptor en el Galaxy Y (2.3.6) y emisor en el SM-A356E |
| Conexión estable | ✓ 5 min sin cortes; reconecta sin tocar el receptor |
| KB/s anotados (Wi-Fi ON y OFF) | ✓ 165,6 / 165,7 KB/s |
| ¿Alcanza para Segura (≥ 17,7 KB/s)? | ✓ por casi 10×: no hace falta adelantar TCP |


- **Throughput según el receptor: ≈ 165,6 KB/s** (unos 1,3 Mbit/s, típico de RFCOMM sobre Bluetooth EDR).
  - Wi-Fi del Galaxy Y encendido: 165,6 KB/s.
  - Wi-Fi apagado: 165,7 KB/s.
  - No hubo diferencia apreciable.
- **RTT promedio: 9–13 ms** (máx. 58 ms).
- Umbrales con la regla del 70 %, incluyendo el overhead de las tramas AUDIO (ver PROTOCOLO.md §6):

  | Preset | KB/s medidos | Medido ≈ 165,6 |
  |---|---|---|
  | Segura | ≥ 17,7 | ✓ |
  | Normal | ≥ 35,4 | ✓ |
  | Alta | ≥ 69,9 | ✓ (2,4× el umbral) |

- **Preset Auto: Alta.** El flujo Alta ocupa ≈ 48,9 KB/s, el 30 % de lo medido.
- **No hace falta adelantar el transporte TCP (Fase 5):** Segura se supera por casi 10×.

## Fase 1 — core completo

Verificación sin dispositivo (contenedor Linux, 2026-10-17). Esta fase no cambia las apps, así que no hay nada que probar en los teléfonos.

### Criterios ✅

| Criterio | Test | Resultado |
|---|---|---|
| ① Ida y vuelta de cada tipo de trama | `TramasTest` (6 tipos de la Fase 0) y `MensajesTest` (CONFIG, AUDIO, TRACK, CONTROL, STATE byte a byte y con valores extremos; los 11 tipos en un mismo flujo) | ✓ |
| ② Trama corrupta rechazada | magic, largo, truncada, `read()=0`, payload corto de cada tipo y valores inválidos de CONFIG → `ProtocoloException` | ✓ |
| ③ SNR ≥ 20 dB (seno 1 kHz, −6 dBFS, 44,1 kHz) | `ImaAdpcmTest.snrSeno*` | ✓ **36,2 dB** en mono; **36,2 y 34,3 dB** por canal en estéreo |
| ④ Bloque aislado = en secuencia | `ImaAdpcmTest.bloqueAisladoIgualQueEnSecuencia`: 12 bloques en mono y en estéreo, más onda cuadrada y ruido a escala completa (índice 88 y predictor saturado); cada cabecera coincide con el estado arrastrado y el byte reservado es 0 | ✓ |
| ⑤ Salida determinista | Codificar dos veces da bytes idénticos. Los CRC32 coinciden con una **implementación independiente en Python** (`herramientas/referencia_core.py`) en el codificador y el decodificador (también a escala completa), en el decimador y en el downmix | ✓ |

Total: **84 tests JVM, 0 fallas** (`./gradlew :core:testDebugUnitTest`). Corren con `-Xint` (ver abajo).

**Pruebas de mutación** (en una copia aparte del repo): se introdujeron 6 errores a propósito y los tests detectaron los 6.
- cabecera escrita con el estado final;
- nibbles invertidos en el codificador y el decodificador;
- fase de salida del decimador cambiada;
- predictor en little-endian;
- downmix que trunca en vez de redondear;
- STATE sin su último byte.

### Otras mediciones
- **Decimador ×2** (FIR de media banda de 47 coeficientes, Kaiser β = 7, Q15):
  - diseño: ≥ 68,9 dB de atenuación entre 0,3 y 0,5 fs (mínimo 68,97 dB cerca de 0,303 fs) y rizado ≤ 0,003 dB entre 0 y 0,2 fs;
  - medido: un tono a 0,1 fs pasa con −0,000 dB y uno a 0,35 fs queda en **−83,4 dB**;
  - ganancia DC exacta; procesar por trozos de cualquier largo da lo mismo que procesar todo junto.
- **Sin asignaciones de memoria** (`SinAsignacionesTest`, ThreadMXBean): **0 bytes** en
  - decodificar y codificar bloques (2000 repeticiones cada uno);
  - decimador + downmix (200 bloques de 1024 frames);
  - escribir AUDIO + STATE (2000 de cada uno);
  - leer AUDIO (1000 tramas).

  Los tests de core corren con `-Xint` (intérprete). El escape analysis del JIT de HotSpot puede eliminar asignaciones que Dalvik sí haría: con JIT, un `new` por canal en el decodificador pasaba el test sin que se notara. Un control positivo comprueba que la medición sí detecta asignaciones.
- **Rendimiento del decodificador en la JVM** (solo referencia):
  - ~98× tiempo real a 48 kHz estéreo en el intérprete;
  - ~560× con JIT.

  La medición en el Galaxy Y (Dalvik con JIT, ARMv6) va en la Fase 2.
- **Lint:** sin problemas en core, receptor y emisor.
- **Contra el SDK real:** el APK del receptor (71 888 bytes) ya incluye el códec, el decimador y el downmix.
  - `herramientas/verificar_api.py` contra API 10: 91 referencias, **0 faltantes**.
  - Contra API 9 falta solo el socket insecure, que está protegido con `SDK_INT >= 10`.
