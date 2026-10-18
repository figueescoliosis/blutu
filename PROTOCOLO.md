# Protocolo ParlanteY — versión 1

Fuente de verdad del protocolo entre el **emisor** (Android 8+) y el **receptor** (Galaxy Y, Android 2.3).
Todo cambio exige: actualizar este archivo + los tests de `core` + `Protocolo.VERSION` (y el historial al final).

Implementación: `core/src/main/java/cl/parlantey/core/`.
- Tramas: `Protocolo`, `LectorTramas`, `EscritorTramas`.
- Mensajes: `Hello`, `Config`, `Track`, `Estado`, `Seq`.
- Códec: `ImaAdpcm`, `CodificadorIma`.
- Sesiones: `SesionEmisor`, `SesionReceptor`.
- Reproducción y envío (Fase 2): `ColaBloques`, `Reproductor`, `Salida` (receptor); `PipelineEmisor`, `FuenteTono`, `Preset` (emisor).

## Estado de implementación

| Tipo | Lectura y escritura en `core` | Uso en las apps |
|---|---|---|
| 0x01 HELLO | ✅ Fase 0 | ✅ Fase 0 |
| 0x02 CONFIG | ✅ Fase 1 | ✅ Fase 2 (tono de prueba) |
| 0x03 AUDIO | ✅ Fase 1 | ✅ Fase 2 |
| 0x04 TRACK | ✅ Fase 1 | ✅ Fase 2 |
| 0x05 CONTROL | ✅ Fase 1 | ✅ Fase 2: VACIAR y DETENER en ambos; PAUSA y REANUDAR solo en el receptor (el emisor los usa en la Fase 3) |
| 0x06 STATE | ✅ Fase 1 | ✅ Fase 2 |
| 0x07 PING / 0x08 PONG | ✅ Fase 0 | ✅ Fase 0 |
| 0x09 TEST_DATA / 0x0A TEST_RESULT | ✅ Fase 0 | ✅ Fase 0 |
| 0x0B BYE | ✅ Fase 0 | ✅ Fase 0 |

## 1. Transporte

- Bluetooth clásico, **RFCOMM**, UUID de servicio **`c9cc3858-c5e1-46a3-9f9c-f1e94f108feb`** (v4, generado una sola vez; constante `Protocolo.UUID_SERVICIO`). Nombre del registro SDP: `ParlanteY`.
- **Receptor = servidor**: `listenUsingRfcommWithServiceRecord`. Si falla y el API es ≥ 10, `listenUsingInsecureRfcommWithServiceRecord`.
- **Emisor = cliente**: `createRfcommSocketToServiceRecord`. Si `connect()` falla, reintenta con `createInsecureRfcommSocketToServiceRecord`.
- Una conexión a la vez: el receptor cierra el socket servidor mientras atiende y lo vuelve a abrir al terminar la conexión.
- Solo dispositivos ya vinculados (el emisor no hace discovery).

## 2. Codificación

- Todos los enteros son **big-endian** (`DataOutputStream` / `DataInputStream`).
- `u8`, `u16`, `u32`, `u64` = enteros sin signo de 1, 2, 4 y 8 bytes; `i16` = con signo. Java no tiene `u32`: se lee con `readInt() & 0xFFFFFFFFL`.
- `UTF` = `writeUTF` / `readUTF`: largo `u16` + UTF-8 modificado (máximo 65 535 bytes codificados).
- Los bits de `flags` que no define un tipo se envían en 0 y se ignoran al recibir.

## 3. Trama

```
 0      2      3       4            8
 +------+------+-------+------------+-----------------+
 |magic | tipo | flags |   largo    |     payload     |
 | u16  |  u8  |  u8   |    u32     |   largo bytes   |
 +------+------+-------+------------+-----------------+
```

- `magic` = `0x5059` ("PY").
- `largo` = bytes del payload, **0 a 65 536** (64 KiB).
- **Magic inválido o `largo` > 65 536 → cerrar la conexión** (sin BYE: el flujo ya no está alineado).
- **Tipo desconocido → leer el payload y descartarlo** (compatibilidad hacia adelante).
- Payload más corto de lo que el tipo exige → error de protocolo → cerrar la conexión.
- El formato de la cabecera es fijo en todas las versiones.

## 4. Sesión

1. El emisor conecta.
2. El receptor envía **HELLO** de inmediato. Es siempre la primera trama.
3. El emisor lee la versión, el primer byte del payload de HELLO en cualquier versión.
   - Si no es igual a la suya, envía **BYE(1)**, cierra la conexión y muestra el error. El receptor, al recibir BYE(1), también muestra el error.
   - Si la primera trama no es HELLO, el emisor cierra la conexión (error de protocolo).
4. Sigue el intercambio de tramas:
   - **El emisor envía un PING por segundo, siempre** (también mientras envía audio): es el keepalive y da el RTT en vivo. Cualquier PONG sirve para el RTT, porque el timestamp es el instante de envío.
   - **El receptor envía STATE cada 250 ms desde el HELLO** hasta el fin de la conexión, haya audio o no.
5. Para reproducir, el emisor envía **CONFIG**, luego **TRACK** (opcional) y después **AUDIO** regulado por el control de flujo (§5, STATE).
   - Cambio de pista: CONTROL VACIAR → TRACK → CONFIG (solo si cambian tasa o canales) → AUDIO.
6. Cualquiera de los dos puede cerrar con **BYE(código)**. Si la conexión se pierde, el receptor vuelve a `accept()` solo.
7. **Watchdogs** (no hace falta ninguna trama nueva: STATE y PING llegan siempre):
   - El receptor cierra la conexión si pasan **5 s sin ninguna trama** del emisor.
   - El emisor cierra la conexión si pasan **4 s sin ninguna trama** del receptor, y reintenta (Fase 2: backoff 1, 2, 4, 8, 10… s, hasta 5 min).
8. **Fin de la conexión** (BYE, EOF, error o watchdog): el receptor actúa como si hubiera recibido DETENER (detiene y libera `AudioTrack`) y vuelve a `accept()`. Al reconectar, el emisor vuelve a enviar CONFIG y TRACK; el audio sigue con seq 0.
9. **Nada pasa de una conexión a la siguiente:** en la conexión nueva el receptor empieza sin pausa, con `ultimoSeq` = `0xFFFFFFFF` y con `cortes` = 0, ya desde el primer STATE.

## 5. Tipos

| Tipo | Dirección | Payload |
|---|---|---|
| 0x01 HELLO | R→E | versión u8, apiLevel u16, nombre UTF, maxCanales u8, capacidadBufferMs u32 |
| 0x02 CONFIG | E→R | códec u8 (1 = IMA ADPCM), sampleRate u32, canales u8, muestrasPorBloque u16, bufferObjetivoMs u32 |
| 0x03 AUDIO | E→R | seq u32 + bloque ADPCM |
| 0x04 TRACK | E→R | título UTF, artista UTF, duraciónMs u32 (0 = en vivo) |
| 0x05 CONTROL | E→R | cmd u8: 1 PAUSA, 2 REANUDAR, 3 VACIAR, 4 DETENER |
| 0x06 STATE | R→E | bufferMs u32, cortes u32, ultimoSeq u32, reproduciendo u8 (cada 250 ms) |
| 0x07 PING | E→R | timestamp u64 |
| 0x08 PONG | R→E | timestamp u64 (eco del PING) |
| 0x09 TEST_DATA | E→R | relleno; `flags` bit 0 = última trama de la prueba |
| 0x0A TEST_RESULT | R→E | bytesRecibidos u64, duraciónMs u32 |
| 0x0B BYE | ambos | código u8 |

### 0x01 HELLO (R→E)

| Campo | Tipo | Valor en el receptor v1 |
|---|---|---|
| versión | u8 | `1` (`Protocolo.VERSION`) |
| apiLevel | u16 | `Build.VERSION.SDK_INT` (10 en el Galaxy Y) |
| nombre | UTF | nombre Bluetooth del receptor (o `Build.MODEL` si no hay) |
| maxCanales | u8 | `2` |
| capacidadBufferMs | u32 | `4000` |

### 0x02 CONFIG (E→R)

Payload de 12 bytes:

| Campo | Tipo | Válido |
|---|---|---|
| códec | u8 | `1` (IMA ADPCM, §6) |
| sampleRate | u32 | 4000 a 48 000 Hz (lo que acepta `AudioTrack` en Android 2.3) |
| canales | u8 | 1 o 2 |
| muestrasPorBloque | u16 | par, ≥ 2, y el AUDIO resultante debe caber en 64 KiB. El emisor usa **1024**. |
| bufferObjetivoMs | u32 | nivel de buffer que debe mantener el receptor (Archivos 2000 ms, Todo el audio 500 ms) |

- Un valor fuera de rango es error de protocolo: BYE(2) y cierre.
- Cada CONFIG nuevo reemplaza al anterior. Si cambian la tasa o los canales, el receptor vacía la cola y recrea `AudioTrack`.

### 0x03 AUDIO (E→R)

| Campo | Tipo |
|---|---|
| seq | u32 |
| bloque | `canales × (4 + muestrasPorBloque / 2)` bytes (§6) |

- **El largo del payload debe ser exactamente `4 + bytesBloque`** del CONFIG vigente: 520 bytes en mono y 1036 en estéreo con bloques de 1024 muestras. Si no coincide, es error de protocolo: BYE(2) y cierre.
- **AUDIO sin CONFIG vigente** (antes del primero o tras DETENER) se descarta en silencio.
- **seq:**
  - Empieza en 0 en cada conexión nueva y sube 1 por AUDIO enviado.
  - Da la vuelta en 2³² (`Seq.siguiente`).
  - No se reinicia con VACIAR, DETENER ni un CONFIG nuevo: dentro de una conexión, un audio nuevo (otra pista, otro tono) sigue la numeración.
- Un AUDIO que el receptor descarta (sin CONFIG vigente, o con el pool de bloques lleno) **igual actualiza `ultimoSeq`** en STATE.

### 0x04 TRACK (E→R)

Título UTF, artista UTF y duraciónMs u32 (0 = en vivo). Solo informa a la UI del receptor y no afecta el audio.

### 0x05 CONTROL (E→R)

| cmd | Efecto en el receptor |
|---|---|
| 1 PAUSA | Pausa `AudioTrack` y conserva la cola. |
| 2 REANUDAR | Reanuda. |
| 3 VACIAR | Descarta la cola y lo pendiente en `AudioTrack` (`pause()` + `flush()`: en 2.3, `flush()` con la pista sonando no hace nada), conserva el CONFIG y vuelve a llenar hasta el umbral de arranque antes de sonar. **Conserva la pausa**: si estaba en PAUSA, sigue en pausa. |
| 4 DETENER | Lo mismo que VACIAR, más `stop()` y liberar `AudioTrack`. Olvida el CONFIG: el siguiente audio necesita un CONFIG nuevo. **Quita la pausa**: el audio siguiente empieza sonando. |

- Un comando desconocido se ignora, para que versiones futuras puedan agregar comandos.
- CONFIG, VACIAR y DETENER se aplican **en el orden en que llegan** respecto de los AUDIO: nunca suena audio recibido antes de un VACIAR, aunque el hilo de audio se atrase.
- **Umbral de arranque** (también tras VACIAR, un CONFIG con otro formato o un corte): el 70 % de `bufferObjetivoMs`, pero al menos el buffer de `AudioTrack` + 2 bloques (AudioFlinger no empieza a sonar hasta llenarlo) y como mucho la capacidad de la cola − 2 bloques. Se cuentan solo bloques reales.

### 0x06 STATE (R→E, cada 250 ms)

Payload de 13 bytes:

| Campo | Tipo | Significado |
|---|---|---|
| bufferMs | u32 | bloques en cola × ms por bloque + (frames escritos − `getPlaybackHeadPosition()`) en ms. Cuenta solo audio real: el silencio de relleno no. Acotado a 0…capacidadBufferMs. |
| cortes | u32 | veces que la cola se vació mientras sonaba, desde que se conectó. Un corte largo cuenta una vez. Arrancar, VACIAR, DETENER, un CONFIG nuevo y REANUDAR no cuentan. |
| ultimoSeq | u32 | seq del último AUDIO **recibido** en esta conexión. Antes del primero vale `0xFFFFFFFF` (`Protocolo.SEQ_NINGUNO`, equivale a −1). |
| reproduciendo | u8 | 1 si `AudioTrack` está sonando audio real; 0 si no (sin CONFIG, llenando, en pausa o rellenando tras un corte). Al recibir, cualquier valor ≠ 0 cuenta como 1. |

**Corte en el receptor:** si sonando la cola se vacía y en `AudioTrack` queda ≤ 1 bloque de audio real, el receptor cuenta un corte y escribe silencio de a un bloque (la pista sigue sonando, sin underrun de AudioFlinger) hasta volver al umbral de arranque; entonces retoma el audio sin esperar a que suene el silencio pendiente.

**Control de flujo en el emisor:**
- `nivel = STATE.bufferMs + Seq.distancia(seqEnviado, STATE.ultimoSeq) × ms por bloque`, donde `seqEnviado` es el seq del último AUDIO enviado. `Seq.distancia` hace la resta en u32, así que vale aunque el contador haya dado la vuelta.
- Se envía mientras el nivel sea menor que el objetivo (`bufferObjetivoMs` del CONFIG). El nivel nunca pasa del objetivo + 1 bloque.
- **Corte del flujo:** al empezar un audio en la conexión (seq siguiente = `seqCorte`) y tras cada VACIAR, los STATE con `ultimoSeq` anterior a `seqCorte` describen un buffer que ya no existe. Se ignoran hasta que llegue uno con `Seq.distancia(ultimoSeq, seqCorte) ≥ 0`; mientras tanto, nivel = todo lo enviado desde el corte.
- Un STATE con `Seq.distancia(seqEnviado, ultimoSeq) < 0` (un seq que no se envió) también se ignora.
- **No sirve basarse en que `write()` bloquee.** En la Fase 0 el emisor (Android 16) aceptó ~1,5 MB en el socket sin bloquear (NOTAS.md).

### 0x07 PING / 0x08 PONG

- El emisor pone en `timestamp` un valor opaco (`System.nanoTime()`). El receptor lo devuelve sin cambios en un PONG.
- RTT = ahora − timestamp.
- Si un PONG trae un timestamp que no es el del último PING, es de un PING anterior y se descarta.

### 0x09 TEST_DATA / 0x0A TEST_RESULT (prueba de enlace)

1. El emisor envía TEST_DATA sin pausa durante **5 s**, con payloads de **4096 bytes** de ceros. La última trama lleva `flags` bit 0 = 1 (`FLAG_ULTIMA`).
2. El receptor acumula, por prueba:
   - **bytesRecibidos**: bytes de trama completos (cabecera de 8 + payload) de todas las tramas TEST_DATA de la prueba, incluidas la primera y la última.
   - **duraciónMs**: desde que termina de llegar la *cabecera* de la primera TEST_DATA hasta que termina de llegar la última trama. Así casi todos los bytes contados llegan dentro del intervalo.
3. Al recibir la última trama, el receptor contesta TEST_RESULT y queda listo para otra prueba en la misma conexión.
4. **Throughput = bytesRecibidos / duraciónMs**, en bytes por ms, que equivale a **KB/s con KB = 1000 bytes**, la misma unidad de la tabla de presets.
   - El emisor también informa su propia cifra, medida desde el primer TEST_DATA hasta recibir TEST_RESULT. Incluye un RTT, así que sale algo menor.
5. Antes de los datos, el emisor hace **20 PING/PONG** y reporta el RTT mín/prom/máx.

### 0x0B BYE

| Código | Significado |
|---|---|
| 0 | cierre normal |
| 1 | versión de protocolo incompatible |
| 2 | error de protocolo |

Tras enviar o recibir BYE, la conexión se cierra.

## 6. Bloque IMA ADPCM (payload de AUDIO tras `seq`)

Implementación: `ImaAdpcm` (tablas y decodificador) y `CodificadorIma`.

```
 por canal c = 0..canales-1:   predictor i16 (big-endian) | índice u8 | reservado u8 (= 0)
 por canal c = 0..canales-1:   muestrasPorBloque / 2 bytes; byte j = muestra 2j (nibble bajo) | muestra 2j+1 (nibble alto)
```

- **La cabecera es el estado del códec *antes* de la primera muestra** del bloque, y las `muestrasPorBloque` muestras van todas como nibbles.
  - Ojo: en el IMA ADPCM de WAV de Microsoft, la cabecera **es** la primera muestra. Aquí no.
- Cada bloque se puede decodificar de forma aislada desde su cabecera, y el resultado es idéntico al de decodificarlo en secuencia. El codificador escribe en la cabecera el estado con que empieza y lo actualiza con la misma reconstrucción que el decodificador.
- Si al recibir el índice de la cabecera vale más de 88, se limita a 88. El decodificador nunca falla por datos corruptos.
- El último bloque de una pista se completa con silencio: muestras 0 codificadas normalmente.
- Al empezar una pista, el codificador parte de predictor 0 e índice 0.
- **Tablas IMA estándar:**
  - `PASOS` = 7, 8, 9, 10, 11, 12, 13, 14, 16, 17, 19, 21, 23, 25, 28, 31, 34, 37, 41, 45, 50, 55, 60, 66, 73, 80, 88, 97, 107, 118, 130, 143, 157, 173, 190, 209, 230, 253, 279, 307, 337, 371, 408, 449, 494, 544, 598, 658, 724, 796, 876, 963, 1060, 1166, 1282, 1411, 1552, 1707, 1878, 2066, 2272, 2499, 2749, 3024, 3327, 3660, 4026, 4428, 4871, 5358, 5894, 6484, 7132, 7845, 8630, 9493, 10442, 11487, 12635, 13899, 15289, 16818, 18500, 20350, 22385, 24623, 27086, 29794, 32767.
  - `AJUSTE` = −1, −1, −1, −1, 2, 4, 6, 8 (se repite para los nibbles 8–15).

**Decodificación de un nibble `n`** (el codificador usa la misma reconstrucción):

```
paso = PASOS[indice]
dif  = paso >> 3
si n & 4: dif += paso
si n & 2: dif += paso >> 1
si n & 1: dif += paso >> 2
predictor = saturar_int16(n & 8 ? predictor − dif : predictor + dif)
indice    = limitar(indice + AJUSTE[n], 0, 88)
muestra   = predictor
```

**Cuantización del codificador** (IMA de referencia; las comparaciones son `≥`):

```
paso = PASOS[indice]
diff = x − predictor
n = 0
si diff < 0:          n = 8;  diff = −diff
si diff ≥ paso:       n |= 4; diff −= paso
si diff ≥ paso >> 1:  n |= 2; diff −= paso >> 1
si diff ≥ paso >> 2:  n |= 1
(luego predictor e índice se actualizan con la decodificación de n, como arriba)
```

**Tamaño de una trama AUDIO:** 8 (cabecera) + 4 (seq) + canales × (4 + 512) bytes.
- Mono: 528 bytes por 1024 muestras.
- Estéreo: 1044 bytes.

A 48 kHz el flujo ocupa ≈ 24,8 KB/s en mono y ≈ 48,9 KB/s en estéreo; Segura (mono, 24 kHz) ≈ 12,4 KB/s.

Con la regla del 70 %, la prueba de enlace debe medir al menos:

| Preset | KB/s medidos |
|---|---|
| Segura | ≥ 17,7 |
| Normal | ≥ 35,4 |
| Alta | ≥ 69,9 |

## Historial

| Versión | Fecha | Cambios |
|---|---|---|
| 1 | 2026-10-17 | Versión inicial. En la Fase 0 se implementan HELLO, PING/PONG, TEST_DATA/TEST_RESULT y BYE. |
| 1 | 2026-10-17 | Precisiones de la Fase 1, sin cambios de formato: semántica de CONFIG, AUDIO, TRACK, CONTROL y STATE; `ultimoSeq` y control de flujo; el bloque ADPCM al detalle (predictor big-endian, cabecera = estado antes de la primera muestra, relleno del último bloque). Se implementan en `core` todas las tramas. |
| 1 | 2026-10-17 | Precisiones de la Fase 2, sin cambios de formato: STATE cada 250 ms desde el HELLO; PING cada 1 s siempre; watchdogs de 5 s (receptor) y 4 s (emisor); fin de conexión = DETENER; seq por conexión; `ultimoSeq` cuenta los AUDIO descartados; VACIAR = `pause()` + `flush()` y conserva la pausa; umbral de arranque; `bufferMs` sin el silencio de relleno; definición de corte; control de flujo con `seqCorte`. |
| 1 | 2026-10-18 | Tras la revisión de la Fase 2, sin cambios de formato: pausa, `ultimoSeq` y `cortes` son por conexión (también en el primer STATE); DETENER quita la pausa; se quita la regla de "más bloques en vuelo que la capacidad", que nunca cambiaba el resultado. |
