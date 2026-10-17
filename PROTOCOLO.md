# Protocolo ParlanteY — versión 1

Fuente de verdad del protocolo entre el **emisor** (Android 8+) y el **receptor** (Galaxy Y, Android 2.3).
Todo cambio exige: actualizar este archivo + los tests de `core` + `Protocolo.VERSION` (y el historial al final).

Implementación: `core/src/main/java/cl/parlantey/core/` (`Protocolo`, `LectorTramas`, `EscritorTramas`, `Hello`, `SesionEmisor`, `SesionReceptor`).

## Estado de implementación

| Tipo | Fase 0 | Pendiente |
|---|---|---|
| 0x01 HELLO | ✅ | |
| 0x02 CONFIG | | Fase 1 |
| 0x03 AUDIO | | Fase 1 |
| 0x04 TRACK | | Fase 1 |
| 0x05 CONTROL | | Fase 1 |
| 0x06 STATE | | Fase 1 |
| 0x07 PING / 0x08 PONG | ✅ | |
| 0x09 TEST_DATA / 0x0A TEST_RESULT | ✅ | |
| 0x0B BYE | ✅ | |

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
4. Sigue el intercambio de tramas. En la Fase 0, el emisor envía un PING por segundo mientras está inactivo, como keepalive y para medir el RTT en vivo.
5. Cualquiera de los dos puede cerrar con **BYE(código)**. Si la conexión se pierde, el receptor vuelve a `accept()` solo.

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

## 6. Bloque IMA ADPCM (payload de AUDIO tras `seq`) — se implementa en la Fase 1

- Cada bloque se puede decodificar de forma aislada.
- Primero, por canal: `predictor i16, índicePaso u8, reservado u8` (estado del códec al inicio del bloque).
- Luego, por canal y en forma planar: `muestrasPorBloque / 2` bytes. En cada byte, el nibble bajo es la muestra anterior.
- `muestrasPorBloque` = 1024.
- Tablas IMA estándar (89 pasos + tabla de ajuste de índice). Predictor saturado a int16, índice a 0..88. El codificador replica el estado del decodificador.
- Tamaño de una trama AUDIO: 8 (cabecera) + 4 (seq) + canales × (4 + 512) bytes.
  - Mono: 528 bytes por 1024 muestras.
  - Estéreo: 1044 bytes.

  Con eso, a 48 kHz el flujo ocupa ≈ 24,8 KB/s en mono y ≈ 48,9 KB/s en estéreo; Segura (mono, 24 kHz) ≈ 12,4 KB/s.

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
