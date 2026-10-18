package cl.parlantey.emisor.ui

import android.os.SystemClock
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cl.parlantey.core.Preset
import cl.parlantey.emisor.Cancion
import cl.parlantey.emisor.Dispositivo
import cl.parlantey.emisor.Enlace
import cl.parlantey.emisor.Local
import cl.parlantey.emisor.Medicion
import cl.parlantey.emisor.Modo
import cl.parlantey.emisor.OpcionPreset
import cl.parlantey.emisor.Reproduccion
import cl.parlantey.emisor.ResumenMusica
import cl.parlantey.emisor.ResumenTono
import cl.parlantey.emisor.UiEstado
import cl.parlantey.emisor.WifiGalaxy
import cl.parlantey.emisor.f0
import cl.parlantey.emisor.f1
import cl.parlantey.emisor.tasaTexto
import kotlinx.coroutines.delay

/** Lo que la pantalla puede pedir; lo implementan MainActivity y EmisorViewModel. */
data class Acciones(
    val pedirPermiso: () -> Unit,
    val abrirAjustesApp: () -> Unit,
    val encenderBluetooth: () -> Unit,
    val refrescar: () -> Unit,
    val conectar: (Dispositivo) -> Unit,
    val probar: () -> Unit,
    val desconectar: () -> Unit,
    val elegirWifi: (WifiGalaxy) -> Unit,
    val elegirPreset: (OpcionPreset) -> Unit,
    val elegirTasa: (Int) -> Unit,
    val iniciarTono: () -> Unit,
    val detenerAudio: () -> Unit,
    val agregarCanciones: () -> Unit,
    val vaciarCola: () -> Unit,
    val reproducir: (Int) -> Unit,
    val alternarPausa: () -> Unit,
    val siguiente: () -> Unit,
    val anterior: () -> Unit,
    val copiarMusica: () -> Unit,
    val borrarMusica: () -> Unit,
    val copiarMediciones: () -> Unit,
    val borrarMediciones: () -> Unit,
    val copiarPruebasTono: () -> Unit,
    val borrarPruebasTono: () -> Unit,
    val cerrarError: () -> Unit,
)

private val TASAS = listOf(48000, 44100)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Pantalla(ui: UiEstado, acciones: Acciones) {
    val direccion = LocalLayoutDirection.current
    val local = ui.local
    val servicio = ui.servicio
    Scaffold(topBar = { TopAppBar(title = { Text("ParlanteY · Emisor") }) }) { relleno ->
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(
                start = relleno.calculateStartPadding(direccion) + 16.dp,
                end = relleno.calculateEndPadding(direccion) + 16.dp,
                top = relleno.calculateTopPadding() + 8.dp,
                bottom = relleno.calculateBottomPadding() + 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ui.error?.let { error -> item { TarjetaMensaje(error, true, acciones.cerrarError) } }
            ui.aviso?.let { aviso -> item { TarjetaMensaje(aviso, false, acciones.cerrarError) } }
            val enlace = servicio.enlace
            val r = servicio.reproduccion
            when {
                !local.btDisponible -> item { Aviso("Este teléfono no tiene Bluetooth.") }
                !local.permisoOk -> item { TarjetaPermiso(acciones) }
                enlace != Enlace.Inactivo -> item { TarjetaEnlace(enlace, r, local, servicio.ultimoKBps, acciones) }
                !local.btEncendido -> item { TarjetaEncender(acciones) }
                else -> listaVinculados(local.vinculados, acciones)
            }
            if (local.btDisponible && local.permisoOk) {
                musica(ui, enlace is Enlace.Conectado, acciones)
                if (enlace is Enlace.Conectado) {
                    item { Text("Pruebas", style = MaterialTheme.typography.titleMedium) }
                    item { TarjetaConectado(enlace, r, local.wifi, acciones) }
                    item { TarjetaTono(enlace, r, local, servicio.ultimoKBps, acciones) }
                }
            }
            if (servicio.musica.isNotEmpty()) {
                item { Cabecera("Sesiones de música", "Copiar para NOTAS (Fase 3)", acciones.copiarMusica, acciones.borrarMusica) }
                items(servicio.musica.asReversed()) { TarjetaResumenMusica(it) }
            }
            if (servicio.pruebasTono.isNotEmpty()) {
                item { Cabecera("Pruebas de tono", "Copiar para NOTAS (Fase 2)", acciones.copiarPruebasTono, acciones.borrarPruebasTono) }
                items(servicio.pruebasTono.asReversed()) { TarjetaPruebaTono(it) }
            }
            if (servicio.mediciones.isNotEmpty()) {
                item { Cabecera("Pruebas de enlace", "Copiar", acciones.copiarMediciones, acciones.borrarMediciones) }
                items(servicio.mediciones.asReversed()) { TarjetaMedicion(it) }
            }
        }
    }
}

@Composable
private fun Tarjeta(contenido: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { contenido() }
    }
}

@Composable
private fun Aviso(texto: String) = Tarjeta { Text(texto, style = MaterialTheme.typography.bodyLarge) }

@Composable
private fun TarjetaMensaje(texto: String, esError: Boolean, cerrar: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = if (esError) {
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            )
        } else {
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        },
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(texto, style = MaterialTheme.typography.bodyLarge)
            TextButton(onClick = cerrar, modifier = Modifier.align(Alignment.End)) { Text("Cerrar") }
        }
    }
}

@Composable
private fun TarjetaPermiso(acciones: Acciones) = Tarjeta {
    Text("Permiso de Bluetooth", style = MaterialTheme.typography.titleMedium)
    Text(
        "Para listar los teléfonos vinculados y conectarse al Galaxy Y hace falta el permiso «Dispositivos " +
            "cercanos». El de notificaciones es opcional: sirve para ver la conexión y detenerla desde la barra.",
    )
    Button(onClick = acciones.pedirPermiso, modifier = Modifier.fillMaxWidth()) { Text("Dar permiso") }
    TextButton(onClick = acciones.abrirAjustesApp) { Text("Si no aparece el diálogo: abrir ajustes de la app") }
}

@Composable
private fun TarjetaEncender(acciones: Acciones) = Tarjeta {
    Text("El Bluetooth está apagado.", style = MaterialTheme.typography.titleMedium)
    Button(onClick = acciones.encenderBluetooth, modifier = Modifier.fillMaxWidth()) { Text("Encender Bluetooth") }
}

private fun LazyListScope.listaVinculados(vinculados: List<Dispositivo>, acciones: Acciones) {
    item {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Elige el Galaxy Y",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = acciones.refrescar) { Text("Actualizar") }
        }
    }
    if (vinculados.isEmpty()) {
        item {
            Aviso(
                "No hay teléfonos vinculados. En el Galaxy Y abre ParlanteY y toca «Hacer visible»; " +
                    "luego, aquí, Ajustes → Bluetooth → vincular.",
            )
        }
    } else {
        items(vinculados, key = { it.direccion }) { d ->
            Card(modifier = Modifier.fillMaxWidth().clickable { acciones.conectar(d) }) {
                ListItem(
                    headlineContent = { Text(d.nombre) },
                    supportingContent = { Text(d.direccion) },
                )
            }
        }
        item {
            Text(
                "La app receptor debe estar abierta en el Galaxy Y.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun TarjetaEnlace(enlace: Enlace, r: Reproduccion?, local: Local, kBps: Double, acciones: Acciones) {
    val sigue = when (r?.modo) {
        Modo.TONO -> "El tono sigue al reconectar."
        Modo.MUSICA -> "La música sigue al reconectar, desde donde iba."
        null -> null
    }
    when (enlace) {
        Enlace.Inactivo -> Unit
        is Enlace.Conectando -> Tarjeta {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator()
                Spacer(Modifier.width(16.dp))
                Text(
                    "Conectando con ${enlace.dispositivo.nombre}…" +
                        if (enlace.intento > 0) " (intento ${enlace.intento + 1})" else "",
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            if (sigue != null) Text(sigue)
            OutlinedButton(onClick = acciones.desconectar, modifier = Modifier.fillMaxWidth()) { Text("Detener") }
        }
        is Enlace.Reconectando -> Tarjeta {
            val ahora by produceState(SystemClock.elapsedRealtime(), enlace) {
                while (true) {
                    value = SystemClock.elapsedRealtime()
                    delay(250)
                }
            }
            val segundos = ((enlace.proximoMs - ahora + 999) / 1000).coerceAtLeast(0)
            Text(
                "Reconectando con ${enlace.dispositivo.nombre} en $segundos s… (intento ${enlace.intento})",
                style = MaterialTheme.typography.titleMedium,
            )
            Text("Causa: ${enlace.causa}.")
            if (sigue != null) Text(sigue)
            OutlinedButton(onClick = acciones.desconectar, modifier = Modifier.fillMaxWidth()) { Text("Detener") }
        }
        is Enlace.BluetoothApagado -> Tarjeta {
            Text("Bluetooth apagado", style = MaterialTheme.typography.titleMedium)
            Text("Se reconecta solo con ${enlace.dispositivo.nombre} al encenderlo.")
            Button(onClick = acciones.encenderBluetooth, modifier = Modifier.fillMaxWidth()) { Text("Encender Bluetooth") }
            OutlinedButton(onClick = acciones.desconectar, modifier = Modifier.fillMaxWidth()) { Text("Detener") }
        }
        is Enlace.Conectado -> Tarjeta {
            Text("Conectado a ${enlace.dispositivo.nombre}", style = MaterialTheme.typography.titleMedium)
            if (enlace.midiendo) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text("Midiendo el enlace (20 pings y 5 s de datos)…")
            }
            Text(textoAuto(kBps), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TarjetaConectado(c: Enlace.Conectado, r: Reproduccion?, wifi: WifiGalaxy, acciones: Acciones) = Tarjeta {
    Text("Conexión y prueba de enlace", style = MaterialTheme.typography.titleMedium)
    Text(
        "Receptor «${c.receptor.nombre}» · Android API ${c.receptor.apiLevel} · protocolo v${c.receptor.version}",
        style = MaterialTheme.typography.bodyMedium,
    )
    val ahora by produceState(SystemClock.elapsedRealtime(), c.desdeMs) {
        while (true) {
            value = SystemClock.elapsedRealtime()
            delay(1000)
        }
    }
    val segundos = (ahora - c.desdeMs) / 1000
    val rtt = c.rttMs?.let { "${it.f0()} ms" } ?: "—"
    Text("Conectado hace %d:%02d · RTT %s".format(segundos / 60, segundos % 60, rtt), style = MaterialTheme.typography.bodyMedium)

    HorizontalDivider()
    Text("Wi-Fi del Galaxy Y (para anotar la prueba de enlace):", style = MaterialTheme.typography.bodyMedium)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = wifi == WifiGalaxy.ENCENDIDO,
            onClick = { acciones.elegirWifi(WifiGalaxy.ENCENDIDO) },
            label = { Text("Encendido") },
        )
        FilterChip(
            selected = wifi == WifiGalaxy.APAGADO,
            onClick = { acciones.elegirWifi(WifiGalaxy.APAGADO) },
            label = { Text("Apagado") },
        )
    }
    if (c.midiendo) {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        Text("Midiendo: 20 pings y 5 s de datos…")
    }
    Button(onClick = acciones.probar, enabled = !c.midiendo && r == null, modifier = Modifier.fillMaxWidth()) {
        Text("Probar enlace (≈ 6 s)")
    }
    if (r != null) Text("La prueba de enlace se corre sin audio sonando.", style = MaterialTheme.typography.bodySmall)
    OutlinedButton(onClick = acciones.desconectar, enabled = !c.midiendo, modifier = Modifier.fillMaxWidth()) {
        Text("Desconectar")
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TarjetaTono(c: Enlace.Conectado, r: Reproduccion?, local: Local, kBps: Double, acciones: Acciones) = Tarjeta {
    Text("Tono de prueba (440 Hz)", style = MaterialTheme.typography.titleMedium)
    val tono = r?.takeIf { it.modo == Modo.TONO }
    val activo = tono == null && !c.midiendo
    Text("Preset", style = MaterialTheme.typography.bodyMedium)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (o in OpcionPreset.entries) {
            FilterChip(
                selected = local.opcion == o,
                onClick = { acciones.elegirPreset(o) },
                enabled = activo,
                label = { Text(o.etiqueta) },
            )
        }
    }
    Text(textoAuto(kBps), style = MaterialTheme.typography.bodySmall)
    Text("Tasa del tono", style = MaterialTheme.typography.bodyMedium)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (t in TASAS) {
            FilterChip(
                selected = local.tasa == t,
                onClick = { acciones.elegirTasa(t) },
                enabled = activo,
                label = { Text("${tasaTexto(t)} kHz") },
            )
        }
    }
    if (tono == null) {
        Button(onClick = acciones.iniciarTono, enabled = !c.midiendo, modifier = Modifier.fillMaxWidth()) {
            Text(if (r != null) "Tono de prueba (detiene la música)" else "Tono de prueba")
        }
    } else {
        HorizontalDivider()
        Text(
            "${tono.preset.nombre}${if (tono.opcion == OpcionPreset.AUTO) " (Auto)" else ""} · " +
                "${tasaTexto(tono.tasaSalida)} kHz ${if (tono.canales == 2) "estéreo" else "mono"}",
            style = MaterialTheme.typography.bodyMedium,
        )
        EnVivoTexto(tono)
        Button(onClick = acciones.detenerAudio, modifier = Modifier.fillMaxWidth()) { Text("Detener tono") }
    }
}

/** Buffer, cortes y estado del receptor según el último STATE. */
@Composable
private fun EnVivoTexto(r: Reproduccion) {
    val v = r.vivo
    if (v == null) {
        Text("Esperando el primer STATE…")
        return
    }
    Text(
        "Buffer ${v.bufferMs} ms · ${v.cortes} cortes",
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
        color = if (v.cortes == 0L) Color.Unspecified else MaterialTheme.colorScheme.error,
    )
    LinearProgressIndicator(
        progress = { (v.bufferMs / 4000f).coerceIn(0f, 1f) },
        modifier = Modifier.fillMaxWidth(),
    )
    Text(
        (if (v.reproduciendo) "▶ Sonando" else if (r.pausado) "⏸ En pausa" else "… Llenando / sin sonar") +
            " · nivel estimado ${v.nivelMs} ms · seq ${v.ultimoSeq}",
        style = MaterialTheme.typography.bodySmall,
    )
}

private fun tiempo(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}

/** La cola y los controles. Sin conexión, la cola se puede armar igual. */
@OptIn(ExperimentalLayoutApi::class)
private fun LazyListScope.musica(ui: UiEstado, conectado: Boolean, acciones: Acciones) {
    val r = ui.servicio.reproduccion?.takeIf { it.modo == Modo.MUSICA }
    val canciones = ui.canciones
    item {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Música", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = acciones.agregarCanciones, enabled = !ui.local.agregando) {
                Text(if (ui.local.agregando) "Agregando…" else "Agregar canciones")
            }
            if (canciones.isNotEmpty()) TextButton(onClick = acciones.vaciarCola) { Text("Quitar todo") }
        }
    }
    item {
        Tarjeta {
            if (r == null) {
                Text(
                    when {
                        canciones.isEmpty() -> "La cola está vacía. Toca «Agregar canciones» (MP3, M4A, FLAC, Opus…)."
                        !conectado -> "Conéctate al Galaxy Y para reproducir."
                        else -> "Toca una canción para reproducirla."
                    },
                )
            } else {
                Text(r.titulo, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                if (r.artista.isNotEmpty()) Text(r.artista)
                val estado = when {
                    r.abriendo -> " · abriendo…"
                    r.pausado -> " · en pausa"
                    else -> ""
                }
                Text(
                    tiempo(r.posicionMs) + (if (r.duracionMs > 0) " / ${tiempo(r.duracionMs)}" else "") + estado +
                        " · ${r.preset.nombre}${if (r.opcion == OpcionPreset.AUTO) " (Auto)" else ""}" +
                        if (r.tasaSalida > 0) " · ${tasaTexto(r.tasaSalida)} kHz ${if (r.canales == 2) "estéreo" else "mono"}" else "",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (r.duracionMs > 0) {
                    LinearProgressIndicator(
                        progress = { (r.posicionMs.toFloat() / r.duracionMs).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = acciones.anterior, modifier = Modifier.weight(1f)) { Text("⏮") }
                    Button(onClick = acciones.alternarPausa, modifier = Modifier.weight(1f)) {
                        Text(if (r.pausado) "▶" else "⏸")
                    }
                    OutlinedButton(onClick = acciones.siguiente, modifier = Modifier.weight(1f)) { Text("⏭") }
                    OutlinedButton(onClick = acciones.detenerAudio, modifier = Modifier.weight(1f)) { Text("⏹") }
                }
                EnVivoTexto(r)
                r.error?.let { Text("Se saltó: $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
            if (conectado || r != null) {
                Text("Preset", style = MaterialTheme.typography.bodyMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (o in OpcionPreset.entries) {
                        FilterChip(
                            selected = ui.local.opcion == o,
                            onClick = { acciones.elegirPreset(o) },
                            label = { Text(o.etiqueta) },
                        )
                    }
                }
                if (r != null) Text("Cambiar el preset sigue la canción desde el mismo punto.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    itemsIndexed(canciones) { i, c -> FilaCancion(i, c, r?.indice == i, conectado, acciones) }
}

@Composable
private fun FilaCancion(i: Int, c: Cancion, actual: Boolean, conectado: Boolean, acciones: Acciones) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(enabled = conectado) { acciones.reproducir(i) },
        colors = if (actual) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        } else {
            CardDefaults.cardColors()
        },
    ) {
        ListItem(
            headlineContent = {
                Text((if (actual) "▶ " else "${i + 1}. ") + c.titulo, fontWeight = if (actual) FontWeight.Bold else null)
            },
            supportingContent = {
                Text(
                    listOf(c.artista, if (c.duracionMs > 0) tiempo(c.duracionMs) else "", c.tipo)
                        .filter { it.isNotEmpty() }.joinToString(" · "),
                )
            },
        )
    }
}

@Composable
private fun TarjetaResumenMusica(r: ResumenMusica) = Tarjeta {
    val dur = (r.hastaMs - r.desdeMs) / 1000
    Text(
        "${r.inicio} · ${"%d:%02d".format(dur / 60, dur % 60)}${if (r.terminado) "" else " (en curso)"}",
        style = MaterialTheme.typography.bodySmall,
    )
    Text(
        "${r.cortes} cortes",
        style = MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.Bold,
        color = if (r.cortes == 0L) Color.Unspecified else MaterialTheme.colorScheme.error,
    )
    Text("${r.pistas.size} canciones · ${r.tipos.joinToString(", ")} · ${r.formatos.joinToString(", ")}")
    Text("Preset ${r.presets.joinToString(", ")}${if (r.opcion == OpcionPreset.AUTO) " (Auto)" else ""}")
    if (r.muestrasBuffer > 0) Text("Buffer mín/prom/máx ${r.bufferMinMs}/${r.bufferPromMs}/${r.bufferMaxMs} ms")
    if (r.pausasMs.isNotEmpty()) Text("Pausa → confirmada: ${r.pausasMs.joinToString(", ")} ms")
    if (r.siguientesMs.isNotEmpty()) Text("Siguiente → suena: ${r.siguientesMs.joinToString(", ")} ms")
    Text("Reconexiones: ${r.reconexiones}${if (r.errores > 0) " · ${r.errores} sin abrir" else ""}")
}

private fun textoAuto(kBps: Double): String {
    val auto = Preset.elegirAuto(kBps)
    return when {
        kBps.isNaN() -> "Auto: Normal (sin prueba de enlace en esta sesión)"
        auto == null -> "Auto: Segura, aunque ${kBps.f1()} KB/s no alcanza ni para Segura"
        else -> "Auto: ${auto.nombre} (${kBps.f1()} KB/s medidos)"
    }
}

@Composable
private fun Cabecera(titulo: String, copiar: String, alCopiar: () -> Unit, alBorrar: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(titulo, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        TextButton(onClick = alCopiar) { Text(copiar) }
        TextButton(onClick = alBorrar) { Text("Borrar") }
    }
}

@Composable
private fun TarjetaPruebaTono(r: ResumenTono) = Tarjeta {
    val dur = (r.hastaMs - r.desdeMs) / 1000
    Text(
        "${r.inicio} · ${"%d:%02d".format(dur / 60, dur % 60)}${if (r.terminado) "" else " (en curso)"}",
        style = MaterialTheme.typography.bodySmall,
    )
    Text(
        "${r.cortes} cortes",
        style = MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.Bold,
        color = if (r.cortes == 0L) Color.Unspecified else MaterialTheme.colorScheme.error,
    )
    Text(
        "${r.preset.nombre}${if (r.opcion == OpcionPreset.AUTO) " (Auto)" else ""} · " +
            "${tasaTexto(r.tasaSalida)} kHz ${if (r.canales == 2) "estéreo" else "mono"}",
    )
    if (r.muestrasBuffer > 0) Text("Buffer mín/prom/máx ${r.bufferMinMs}/${r.bufferPromMs}/${r.bufferMaxMs} ms")
    Text(
        "Reconexiones: ${r.reconexiones}" +
            (r.recuperacionMs?.let { " · encender BT → suena: ${(it / 1000.0).f1()} s" } ?: ""),
    )
}

@Composable
private fun TarjetaMedicion(m: Medicion) = Tarjeta {
    Text("${m.hora} · Wi-Fi del Galaxy Y: ${m.wifi.etiqueta}", style = MaterialTheme.typography.bodySmall)
    Text(
        "${m.kBpsReceptor.f1()} KB/s",
        style = MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.Bold,
    )
    Text(
        "Según el emisor: ${m.kBpsEmisor.f1()} KB/s · RTT mín/prom/máx " +
            "${m.rttMinMs.f0()}/${m.rttPromMs.f0()}/${m.rttMaxMs.f0()} ms",
    )
    Text("${(m.bytes / 1000.0).f1()} KB en ${(m.duracionMs / 1000.0).f1()} s", style = MaterialTheme.typography.bodySmall)
    if (!m.bytesCoinciden) {
        Text("⚠ El receptor contó otra cantidad de bytes que la enviada.", color = MaterialTheme.colorScheme.error)
    }
    HorizontalDivider()
    for (p in Preset.entries) {
        val necesita = p.kBpsNecesarios(Preset.TASA_REFERENCIA)
        Text("${if (m.kBpsReceptor >= necesita) "✓" else "✗"} ${p.nombre}: necesita ≥ ${necesita.f1()} KB/s")
    }
    val auto = m.presetAuto
    if (auto != null) {
        Text("Preset Auto: ${auto.nombre}", fontWeight = FontWeight.Bold)
    } else {
        Text(
            "No alcanza ni para Segura: habría que adelantar el transporte TCP (Fase 5).",
            color = MaterialTheme.colorScheme.error,
            fontWeight = FontWeight.Bold,
        )
    }
}
