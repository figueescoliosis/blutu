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

## Fase 2 — receptor completo + tono de prueba

### Verificación sin dispositivo (contenedor Linux, 2026-10-17)

**Tests JVM:** `./gradlew :core:testDebugUnitTest` → **157 tests, 0 fallas** (~10 s; los de tiempo real, repetidos 3 veces seguidas sin fallas).

| Test | Qué prueba |
|---|---|
| `SimulacionTest` | Emisor y receptor completos en un hilo con tiempo virtual (pasos de 1 ms) y un enlace simulado como el medido en la Fase 0: 165 KB/s y 1,5 MB de buffer en el socket del emisor. La salida imita al AudioTrack de 2.3 (no suena hasta llenarse; posición de a 1024 frames). Incluye reconexiones con la misma cola y el mismo reproductor, como en el servicio. |
| `ReproductorTest` | Estados del reproductor contra una `SalidaFalsa` con la semántica de 2.3: no escribe sin CONFIG, llenando ni en pausa; umbral de arranque con el buffer de la salida; un corte largo es un solo corte; VACIAR no deja sonar nada viejo; CONFIG mono tras estéreo recrea la salida; escrituras parciales exactas; salida rota, trabada o recreada por dentro → se reabre (una pausa larga no); `abrir` fallido → reintento cada 1 s y `bufferMs` igual cuenta la cola; `bufferMs` con la posición dando la vuelta en 2³¹; corte → pausa → reanudar sin contar el silencio; corte sin faltas con 15 ms de demora del hilo. |
| `PipelineEmisorTest` | Formato de cada preset (también 96 y 192 kHz, 5.1 y mono); CONFIG + TRACK + seq 0; control de flujo sin STATE (se detiene en 94 bloques = 2005 ms), con STATE, con un STATE de otra conexión y tras VACIAR; DETENER; último bloque rellenado; trozos de largo irregular → bloques idénticos; SNR del tono decodificado ≥ 20 dB en los tres presets. |
| `PuntaAPuntaTest` | Hilos y sockets TCP reales, como en las apps (red, STATE y audio en el receptor; lector y pipeline en el emisor): 3 s de tono → 440 Hz limpio (SNR ≥ 20 dB), STATE llegando, 0 cortes; AUDIO de largo incorrecto → BYE(2). |
| `SesionTest` | `SesionEmisor` asíncrona: STATE intercalado con PONG y TEST_RESULT, watchdog de 4 s con reloj virtual, BYE durante la espera, cerrar desbloquea al lector y despierta a quien espera aunque la lectura siga bloqueada. |
| `SesionReceptorTest` | Cada trama sobre la cola y el reproductor: AUDIO sin CONFIG (cuenta para `ultimoSeq`), pool agotado, CONFIG del mismo formato no vacía, VACIAR conserva el CONFIG, PAUSA/REANUDAR, DETENER quita la pausa, CONFIG que no cabe al decodificar → BYE(2), una conexión nueva no hereda pausa ni seq, el primer STATE espera a que las métricas estén en cero. |
| `ColaBloquesTest` | Orden, pool agotado, generaciones (un VACIAR durante un paso del hilo de audio no pierde ni deja sonar bloques), 20 000 operaciones al azar sin perder ni duplicar bloques. |
| `SinAsignacionesTest` | 0 bytes asignados en el ciclo completo del receptor (red → cola → decodificar → escribir → STATE, con PING y VACIAR intercalados) y en el pipeline del emisor (96 kHz estéreo → Segura). |

**Simulación de 10 minutos** (criterio ① en la JVM; la salida tiene 8192 frames):

| Preset | Cortes | Subcortes | Faltas en la salida | Desbordes | Nivel estimado máx. | `bufferMs` sonando | Cola máx. | En tránsito máx. |
|---|---|---|---|---|---|---|---|---|
| Alta (48 kHz estéreo) | 0 | 0 | 0 | 0 | 2005 ms | 1408–1941 ms | 82 bloques | 98 KB (la ráfaga inicial) |
| Segura (24 kHz mono) | 0 | 0 | 0 | 0 | 2005 ms | 1408–2005 ms | 38 bloques | 25 KB |

Además:
- Enlace detenido 1 s → 0 cortes. Detenido 3 s → exactamente 1 corte, se recupera solo y el nivel no pasa de 2022 ms.
- VACIAR → no suena nada de antes y no cuenta corte. DETENER → la salida se libera y la conexión sigue viva con los PING; un tono nuevo en la misma conexión sigue la numeración de seq.
- Emisor mudo → el receptor cierra entre 5000 y 5001 ms después de la última trama; 4,5 s de silencio no lo cierran.
- Reconexión tras una PAUSA y un corte: el primer STATE trae `ultimoSeq` = `0xFFFFFFFF` y 0 cortes, vuelve a sonar y el buffer no pasa de 2100 ms, también con dos reconexiones seguidas.

**Cambio en los tests:** ya no corren con `-Xint` sino con `-XX:-DoEscapeAnalysis -XX:-EliminateAllocations` (core/build.gradle.kts). Con el intérprete, la simulación de 10 minutos tardaba demasiado. Sin escape analysis el JIT ya no puede eliminar asignaciones que Dalvik sí haría. Un control nuevo lo comprueba: mide 10 000 objetos locales que no salen del método. Con estas opciones los ve; con el escape analysis activo mide 0 bytes y el control falla (se probó).

**Pruebas de mutación** (en una copia aparte del repo; cada error se introduce solo y se corre toda la suite):
- Primera ronda, sobre el código inicial: 12 errores (corte por cada bloque de silencio, VACIAR que no suelta el bloque en curso, escribir más de lo que cabe, STATE previo al corte, seq que vuelve a 0 en la conexión, AUDIO sin CONFIG que no actualiza `ultimoSeq`, watchdog de 50 s, silencio contado en `bufferMs`, una asignación en el decodificador, pausa que no detiene las escrituras, `sacar` que ignora la generación, STATE que no se cuentan). Se detectaron 11; el que faltaba (`ultimoSeq` sin CONFIG) llevó a `SesionReceptorTest`. La ronda también destapó un test inestable que era un defecto real: `esperarEstado` perdía un STATE llegado justo antes de la llamada (ahora `esperarEstadoTras`).
- Segunda ronda, sobre los arreglos de la revisión: 12 errores (cada arreglo revertido). Se detectaron 9; para dos se afinaron los tests (el de reanudar tras un corte medía antes de que entrara audio real; el del margen del corte necesitaba consumo cada 5,3 ms y 15 ms de demora del hilo) y ahora se detectan: **11 de 12**. El que queda es el orden entre poner las métricas en cero y avisarlo: una carrera entre hilos que un test determinista no puede provocar; se revisó a mano.

**Revisión adversarial** (tres revisores independientes: core, receptor en 2.3, emisor en Android 14–16). Ninguno encontró algo que haga caer las apps; el criterio ② se siguió paso a paso y funciona. Se corrigieron, con tests:
- Receptor y core:
  - la pausa, `ultimoSeq` y los cortes pasaban de una conexión a la siguiente (el primer STATE de una reconexión podía traer el `ultimoSeq` anterior y provocar una segunda ráfaga: 3242 ms en vez de 2000);
  - salida que queda muda si AudioTrack se traba o se recrea por dentro (mediaserver reiniciado): ahora se detecta en 1,5 s y se reabre;
  - un CONFIG mono de 2050 muestras rompía el hilo de audio y perdía bloques del pool;
  - silencio contado como audio tras corte → pausa → reanudar;
  - un aviso perdido entre el paso del reproductor y su espera (contador de eventos en la cola);
  - `bufferMs` en 0 mientras la salida no se puede abrir;
  - el BYE(0) de «Detener receptor» podía perderse (ahora se envía antes de cerrar y se espera hasta 1 s);
  - el mensaje de la pantalla del receptor tapaba los errores del servicio, incluido el de versión.
- Emisor:
  - un segundo tono en la misma conexión heredaba los cortes del primero;
  - un BYE del receptor podía clasificarse como «se perdió la conexión» (5 min de reintentos);
  - Detener podía esperar una prueba de enlace entera;
  - wake lock tomado con el Bluetooth apagado;
  - sin aviso al rendirse con la pantalla apagada (ahora deja una notificación);
  - el vigía disparaba durante la espera entre reintentos.

**Revisión final del código de los arreglos** (un cuarto revisor): nada crítico. Con 200 simulaciones al azar de 60 s con la semántica de 2.3 (pausas, VACIAR, huecos de datos, demoras del hilo de hasta 300 ms, períodos del mezclador de 256 a 4096 frames), la detección de salida trabada no se disparó nunca en falso. Se corrigieron, con tests y mutaciones que los detectan:
- REANUDAR con la salida a medio llenar y sin bloques para completarla quedaba «sonando» mudo (en 2.3 AudioFlinger vuelve a exigir el buffer lleno al reanudar); ahora vuelve a llenar. La `SalidaFalsa` imita ese comportamiento.
- Tras 3 reaperturas seguidas por salida trabada, espera 5 s antes de la siguiente (antes se reabría sin parar si no se arreglaba).
- El primer STATE espera el reinicio de métricas como mucho 500 ms, reintentando cada 10 ms.
- Una pausa ya no se quita sobre la pista vieja mientras se aplica un DETENER.
- El BYE de «Detener receptor» también llega a una conexión recién aceptada; el wake lock del receptor no se toma después de destruir el servicio; el plazo de Detener del emisor no se puede pisar; el emisor suelta el wake lock si el Bluetooth se vuelve a apagar enseguida.

**Pendiente para las fases siguientes** (no ocurre con el tono de la Fase 2):
- Fase 3: un pipeline nuevo sin VACIAR ni DETENER (pistas sin pausa entre ellas) ignora lo que el receptor ya tiene, y el fin de una fuente finita no se avisa (el receptor cuenta un corte). El supervisor actual siempre envía DETENER antes de un tono nuevo.
- Fase 4: si 4 × `getMinBufferSize` del Galaxy Y se acerca al objetivo de 500 ms del modo captura, el receptor no puede arrancar (AudioFlinger no suena hasta llenar su buffer). Hay que decidirlo con el `getMinBufferSize` medido en esta fase.

**Lint, APK y SDK real:**
- Lint sin problemas en core, receptor y emisor.
- `./gradlew :receptor:verificarApk`: firma v1 válida para minSdk 9, dex 035, sin Kotlin ni AndroidX.
- `herramientas/verificar_api.py` contra API 10: 204 referencias externas y 71 tipos del framework, **0 faltantes**. Contra API 9 falta solo el socket insecure, protegido con `SDK_INT >= 10`.
- APK del receptor: 131 969 bytes.
- Dependencias del receptor (`debugRuntimeClasspath` y `releaseRuntimeClasspath`): solo `project ':core'`.

### Pruebas en los teléfonos — [PENDIENTE]

Versiones: receptor 0.2.0 y emisor 0.2.0 (protocolo v1), release v0.2.0.

| Prueba | Resultado |
|---|---|
| ① 10 min de tono con las dos pantallas apagadas (emisor en segundo plano, batería «Optimizada») | [PENDIENTE] cortes, subcortes |
| ② Apagar y encender el Bluetooth del emisor, app en segundo plano: reconecta solo y el tono vuelve (5 ciclos) | [PENDIENTE] |
| Cambios de tono sin reiniciar el receptor | ✓ 4 cambios seguidos (Auto→Alta, 48↔44,1 kHz), 0 cortes |
| Detener desde la notificación mientras reconecta → no reintenta | [PENDIENTE] |
| «Detener receptor» con el tono sonando → el emisor no reintenta | [PENDIENTE] |
| Reconexión con la pantalla del Galaxy Y apagada desde hace ≥ 2 min | [PENDIENTE] |

**Primera tanda (2026-10-17 23:43).** Tabla copiada desde el emisor; el usuario informa «todo ok»:

Emisor: samsung SM-A356E (Android 16, API 36) · ParlanteY Emisor 0.2.0 · protocolo v1

| Inicio | Duración | Preset | Formato | Cortes | Buffer mín/prom/máx (ms) | Reconexiones | Encender BT → suena (s) |
|---|---|---|---|---|---|---|---|
| 2026-10-17 23:43:03 | 0:28 | Alta (Auto) | 48 kHz estéreo | 0 | 1722/1759/1789 | 0 | — |
| 2026-10-17 23:43:32 | 0:02 | Alta (Auto) | 44.1 kHz estéreo | 0 | 1740/1780/1833 | 0 | — |
| 2026-10-17 23:43:37 | 0:02 | Alta | 44.1 kHz estéreo | 0 | 1740/1776/1833 | 0 | — |
| 2026-10-17 23:43:44 | 0:03 | Alta | 48 kHz estéreo | 0 | 1708/1760/1786 | 0 | — |
| 2026-10-17 23:43:52 | 0:01 | Alta | 48 kHz estéreo | 0 | 1584/1722/1780 | 0 | — |

- El tono suena en Alta a 48 y a 44,1 kHz, sin cortes, y los cambios de tono funcionan sin reiniciar el receptor.
- El buffer medido en el receptor (prom. ≈ 1760 ms) coincide con la simulación: objetivo de 2000 ms menos la antigüedad del STATE (≤ 250 ms).
- En esta tabla no aparecen ① ni ②: la prueba más larga dura 28 s y no hay reconexiones. La tabla vive en la memoria de la app emisora, así que se pierde si Android la cierra.

**Mediciones del Galaxy Y** (logcat `adb logcat -s ParlanteY`, línea «minuto: …», y la pantalla del receptor):

| Dato | 44,1 kHz | 48 kHz |
|---|---|---|
| `getMinBufferSize` mono / estéreo (B) | [PENDIENTE] | [PENDIENTE] |
| Tasa nativa de salida | [PENDIENTE] | |
| Prioridad efectiva del hilo de audio | [PENDIENTE] | |
| Decodificación por bloque prom./máx. (µs), estéreo | [PENDIENTE] | [PENDIENTE] |
| CPU del proceso (%), `adb shell top -m 5` | [PENDIENTE] | [PENDIENTE] |
| GC por minuto | [PENDIENTE] | [PENDIENTE] |
| ¿Se oye distinto el tono de 48 kHz (remuestreo a 44,1 en AudioFlinger)? | | [PENDIENTE] |
