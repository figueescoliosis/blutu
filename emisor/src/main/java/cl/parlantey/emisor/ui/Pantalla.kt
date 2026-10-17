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
import cl.parlantey.emisor.Dispositivo
import cl.parlantey.emisor.Enlace
import cl.parlantey.emisor.Local
import cl.parlantey.emisor.Medicion
import cl.parlantey.emisor.OpcionPreset
import cl.parlantey.emisor.ResumenTono
import cl.parlantey.emisor.Tono
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
    val detenerTono: () -> Unit,
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
            when {
                !local.btDisponible -> item { Aviso("Este teléfono no tiene Bluetooth.") }
                !local.permisoOk -> item { TarjetaPermiso(acciones) }
                enlace != Enlace.Inactivo -> item { TarjetaEnlace(enlace, servicio.tono, local, servicio.ultimoKBps, acciones) }
                !local.btEncendido -> item { TarjetaEncender(acciones) }
                else -> listaVinculados(local.vinculados, acciones)
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
private fun TarjetaEnlace(enlace: Enlace, tono: Tono?, local: Local, kBps: Double, acciones: Acciones) {
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
            if (tono != null) Text("El tono sigue al reconectar.")
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
            if (tono != null) Text("El tono sigue al reconectar.")
            OutlinedButton(onClick = acciones.desconectar, modifier = Modifier.fillMaxWidth()) { Text("Detener") }
        }
        is Enlace.BluetoothApagado -> Tarjeta {
            Text("Bluetooth apagado", style = MaterialTheme.typography.titleMedium)
            Text("Se reconecta solo con ${enlace.dispositivo.nombre} al encenderlo.")
            Button(onClick = acciones.encenderBluetooth, modifier = Modifier.fillMaxWidth()) { Text("Encender Bluetooth") }
            OutlinedButton(onClick = acciones.desconectar, modifier = Modifier.fillMaxWidth()) { Text("Detener") }
        }
        is Enlace.Conectado -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TarjetaConectado(enlace, tono, local.wifi, acciones)
            TarjetaTono(enlace, tono, local, kBps, acciones)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TarjetaConectado(c: Enlace.Conectado, tono: Tono?, wifi: WifiGalaxy, acciones: Acciones) = Tarjeta {
    Text("Conectado a ${c.dispositivo.nombre}", style = MaterialTheme.typography.titleMedium)
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
    Button(onClick = acciones.probar, enabled = !c.midiendo && tono == null, modifier = Modifier.fillMaxWidth()) {
        Text("Probar enlace (≈ 6 s)")
    }
    if (tono != null) Text("La prueba de enlace se puede correr con el tono detenido.", style = MaterialTheme.typography.bodySmall)
    OutlinedButton(onClick = acciones.desconectar, enabled = !c.midiendo, modifier = Modifier.fillMaxWidth()) {
        Text("Desconectar")
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TarjetaTono(c: Enlace.Conectado, tono: Tono?, local: Local, kBps: Double, acciones: Acciones) = Tarjeta {
    Text("Tono de prueba (440 Hz)", style = MaterialTheme.typography.titleMedium)
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
            Text("Tono de prueba")
        }
    } else {
        HorizontalDivider()
        Text(
            "${tono.preset.nombre}${if (tono.opcion == OpcionPreset.AUTO) " (Auto)" else ""} · " +
                "${tasaTexto(tono.tasaSalida)} kHz ${if (tono.canales == 2) "estéreo" else "mono"}",
            style = MaterialTheme.typography.bodyMedium,
        )
        val v = tono.vivo
        if (v == null) {
            Text("Esperando el primer STATE…")
        } else {
            Text(
                "Buffer ${v.bufferMs} ms · ${v.cortes} cortes",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            LinearProgressIndicator(
                progress = { (v.bufferMs / 4000f).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                (if (v.reproduciendo) "▶ Sonando" else "⏸ Llenando / sin sonar") +
                    " · nivel estimado ${v.nivelMs} ms · seq ${v.ultimoSeq}",
                color = if (v.reproduciendo) Color.Unspecified else MaterialTheme.colorScheme.error,
            )
        }
        Button(onClick = acciones.detenerTono, modifier = Modifier.fillMaxWidth()) { Text("Detener tono") }
    }
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
