package cl.parlantey.emisor.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cl.parlantey.emisor.Dispositivo
import cl.parlantey.emisor.EstadoConexion
import cl.parlantey.emisor.Medicion
import cl.parlantey.emisor.Preset
import cl.parlantey.emisor.UiEstado
import cl.parlantey.emisor.WifiGalaxy
import cl.parlantey.emisor.f0
import cl.parlantey.emisor.f1

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
    val copiarMediciones: () -> Unit,
    val borrarMediciones: () -> Unit,
    val cerrarError: () -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Pantalla(ui: UiEstado, acciones: Acciones) {
    val direccion = LocalLayoutDirection.current
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
            ui.error?.let { error -> item { TarjetaError(error, acciones.cerrarError) } }
            when {
                !ui.btDisponible -> item { Aviso("Este teléfono no tiene Bluetooth.") }
                !ui.permisoOk -> item { TarjetaPermiso(acciones) }
                !ui.btEncendido -> item { TarjetaEncender(acciones) }
                else -> when (val c = ui.conexion) {
                    EstadoConexion.Desconectado -> listaVinculados(ui.vinculados, acciones)
                    is EstadoConexion.Conectando -> item { TarjetaConectando(c, acciones) }
                    is EstadoConexion.Conectado -> item { TarjetaConectado(c, ui.wifi, acciones) }
                }
            }
            if (ui.mediciones.isNotEmpty()) {
                item { CabeceraMediciones(acciones) }
                items(ui.mediciones.asReversed()) { TarjetaMedicion(it) }
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
private fun TarjetaError(texto: String, cerrar: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
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
    Text("Para listar los teléfonos vinculados y conectarse al Galaxy Y hace falta el permiso «Dispositivos cercanos».")
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
private fun TarjetaConectando(c: EstadoConexion.Conectando, acciones: Acciones) = Tarjeta {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator()
        Spacer(Modifier.width(16.dp))
        Text("Conectando a ${c.dispositivo.nombre}…", style = MaterialTheme.typography.titleMedium)
    }
    OutlinedButton(onClick = acciones.desconectar, modifier = Modifier.fillMaxWidth()) { Text("Cancelar") }
}

@Composable
private fun TarjetaConectado(c: EstadoConexion.Conectado, wifi: WifiGalaxy, acciones: Acciones) = Tarjeta {
    Text("Conectado a ${c.dispositivo.nombre}", style = MaterialTheme.typography.titleMedium)
    Text(
        "Receptor «${c.receptor.nombre}» · Android API ${c.receptor.apiLevel} · protocolo v${c.receptor.version}",
        style = MaterialTheme.typography.bodyMedium,
    )
    val segundos = (c.ahoraMs - c.desdeMs) / 1000
    val rtt = c.rttMs?.let { "${it.f0()} ms" } ?: "—"
    Text(
        "Conectado hace %d:%02d · RTT %s · %d pings".format(segundos / 60, segundos % 60, rtt, c.pings),
        style = MaterialTheme.typography.bodyMedium,
    )

    HorizontalDivider()
    Text("Wi-Fi del Galaxy Y (para anotar la medición):", style = MaterialTheme.typography.bodyMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
    Button(onClick = acciones.probar, enabled = !c.midiendo, modifier = Modifier.fillMaxWidth()) {
        Text("Probar enlace (≈ 6 s)")
    }
    OutlinedButton(onClick = acciones.desconectar, enabled = !c.midiendo, modifier = Modifier.fillMaxWidth()) {
        Text("Desconectar")
    }
}

@Composable
private fun CabeceraMediciones(acciones: Acciones) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Mediciones", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        TextButton(onClick = acciones.copiarMediciones) { Text("Copiar") }
        TextButton(onClick = acciones.borrarMediciones) { Text("Borrar") }
    }
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
        val alcanza = m.kBpsReceptor >= p.kBpsNecesarios
        Text("${if (alcanza) "✓" else "✗"} ${p.nombre}: necesita ≥ ${p.kBpsNecesarios.f1()} KB/s")
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
