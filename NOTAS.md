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
  - `./gradlew :core:testDebugUnitTest` → 26 tests, 0 fallas (`TramasTest` 18, `SesionTest` 8).
  - `SesionTest` hace la sesión completa emisor ↔ receptor sobre TCP de loopback: HELLO, pings, prueba de enlace con bytes exactos, versión distinta → BYE(1), trama corrupta, payload corto y corte sin BYE.
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
  minSdkVersion:'9' · dex 035 (classes.dex) · sin Kotlin ni AndroidX · 50553 bytes
  OK: receptor-debug.apk apto para Android 2.3 (API 9)
  ```
  Los digests v1 de `META-INF/MANIFEST.MF` son SHA-1, como exige Android < 4.3.
- **Referencias del dex contra el SDK real** (`herramientas/verificar_api.py`): se resolvieron las 90 referencias externas y los 44 tipos del framework que usa el dex final del receptor.
  - Contra el `android.jar` de **API 10** (2.3.3): **0 faltantes**.
  - Contra **API 9**: falta solo `BluetoothAdapter.listenUsingInsecureRfcommWithServiceRecord`, que está detrás de `SDK_INT >= 10`.
- **Dependencias del receptor**:
  - `./gradlew :receptor:dependencies --configuration debugRuntimeClasspath` (y `releaseRuntimeClasspath`) → solo `project ':core'`.
  - El informe completo de `:receptor:dependencies` sí nombra kotlin-stdlib, pero solo en configuraciones de herramientas de AGP 9: `androidLintTool`, `kotlinBuildToolsApiClasspath`, `kotlinCompilerClasspath` y `unified-test-platform…`. Pasa igual en un módulo Java puro con `enableKotlin = false`.
  - Ninguna configuración de la app trae Kotlin ni AndroidX, y `verificarApk` confirma que el dex no referencia `Lkotlin/` ni `Landroidx/`.
- **Avisos conocidos del build**, que no vienen del proyecto:
  - AGP 9.4.1 usa `Configuration.setVisible` (deprecado en Gradle 9, se elimina en 11).
  - javac 21 avisa que el nivel 8 es obsoleto; está silenciado con `-Xlint:-options`.

### Dispositivos

| | Receptor | Emisor |
|---|---|---|
| Modelo | Samsung Galaxy Y GT-S5360 `[PENDIENTE: confirmar en Ajustes → Acerca del teléfono]` | `[PENDIENTE]` |
| Android / API | 2.3.x `[PENDIENTE]` | `[PENDIENTE]` |
| Versión de la app | 0.0.1 (protocolo v1) | 0.0.1 (protocolo v1) |

### Instalación y conexión `[PENDIENTE]`

- [ ] Receptor instalado en el Galaxy Y (método: adb / microSD).
- [ ] Receptor abierto: muestra «Esperando conexión…» y el nombre/MAC del teléfono.
- [ ] Emisor instalado y con el permiso «Dispositivos cercanos» concedido.
- [ ] Teléfonos vinculados («Hacer visible» en el receptor → Ajustes → Bluetooth en el emisor).
- [ ] El emisor conecta y muestra «Receptor «…» · Android API 10 · protocolo v1».
- [ ] Conexión estable: ≥ 5 min conectados con el RTT en vivo actualizándose, sin cortes.
- [ ] Desconectar y reconectar sin reabrir la app receptor.

### Mediciones de enlace `[PENDIENTE]`

Condiciones: `[distancia entre teléfonos, misma habitación o no, batería, otros dispositivos BT/Wi-Fi cerca]`.

Hacer al menos 3 pruebas con el Wi-Fi del Galaxy Y **encendido** y 3 con el Wi-Fi **apagado**, marcando el chip correspondiente en el emisor antes de cada prueba. Luego tocar **Copiar** en «Mediciones» y pegar aquí la tabla:

```
(pegar aquí)
```

### Conclusión de la Fase 0 `[PENDIENTE]`

- Throughput según el receptor: Wi-Fi ON ≈ ___ KB/s · Wi-Fi OFF ≈ ___ KB/s.
- RTT promedio: ___ ms.
- Umbrales con la regla del 70 %, incluyendo el overhead de las tramas AUDIO (ver PROTOCOLO.md §6):

  | Preset | KB/s medidos |
  |---|---|
  | Segura | ≥ 17,7 |
  | Normal | ≥ 35,4 |
  | Alta | ≥ 69,9 |

- Preset Auto esperado: ___.
- Si el peor caso queda bajo 17,7 KB/s: detenerse y proponer adelantar el transporte TCP (Fase 5).
