package cl.parlantey.pc;

import cl.parlantey.core.Protocolo;
import java.io.PrintStream;
import java.util.List;

/**
 * {@code ParlanteY.exe --diagnostico} (o {@code java -jar … --diagnostico}): qué ve el programa en
 * este PC. Nunca se cae: si algo falla, lo dice. Lo usa también la compilación en Windows de GitHub.
 */
final class Diagnostico {
    private Diagnostico() {
    }

    static void ejecutar(PrintStream o) {
        o.println("ParlanteY PC · protocolo v" + Protocolo.VERSION);
        o.println("Sistema: " + System.getProperty("os.name") + " " + System.getProperty("os.version") + " ("
                + System.getProperty("os.arch") + "), Java " + System.getProperty("java.version"));
        try {
            o.println("JNA " + com.sun.jna.Native.VERSION);
        } catch (Throwable e) {
            o.println("JNA no carga: " + e);
        }
        try {
            // En Windows x64 deben ser 560 / 40 / 4 (lo comprueba la compilación de GitHub).
            o.println("Estructuras: InfoDispositivo " + new BluetoothWindows.InfoDispositivo().size() + ", ParamsBusqueda "
                    + new BluetoothWindows.ParamsBusqueda().size() + ", ParamsRadio " + new BluetoothWindows.ParamsRadio().size());
        } catch (Throwable e) {
            o.println("Estructuras: " + e);
        }
        o.println("-- Bluetooth");
        try {
            if (!BluetoothWindows.hayRadio()) {
                o.println("Sin adaptador Bluetooth encendido.");
            } else {
                List<Dispositivo> l = new BluetoothWindows().vinculados();
                o.println("Adaptador encendido. Vinculados: " + l.size());
                for (Dispositivo d : l) {
                    o.println("  " + d);
                }
            }
        } catch (Throwable e) {
            o.println("Bluetooth no disponible: " + e.getMessage());
        }
        o.println("-- Audio");
        CapturaWindows c = new CapturaWindows(new Ajustes());
        try {
            c.iniciar();
            o.println("Captura de la salida predeterminada: " + c.descripcion());
            Thread.sleep(500);
        } catch (Throwable e) {
            o.println("Captura no disponible: " + e.getMessage());
        } finally {
            c.detener();
        }
        o.println("diagnóstico terminado");
    }
}
