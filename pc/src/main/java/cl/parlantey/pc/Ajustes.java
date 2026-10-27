package cl.parlantey.pc;

import java.util.prefs.Preferences;

/** Lo que el programa recuerda entre ejecuciones (en el registro de Windows, vía java.util.prefs). */
public final class Ajustes {
    private final Preferences p;

    public Ajustes() {
        this(Preferences.userRoot().node("cl/parlantey/pc"));
    }

    Ajustes(Preferences p) {
        this.p = p;
    }

    /** KB/s de la última prueba de enlace con ese receptor (NaN si no hay): base del preset Auto. */
    public double kBps(long direccion) {
        return p.getDouble("kBps." + Long.toHexString(direccion), Double.NaN);
    }

    public void kBps(long direccion, double kBps) {
        p.putDouble("kBps." + Long.toHexString(direccion), kBps);
    }

    public long ultimoDispositivo() {
        return p.getLong("ultimo", 0);
    }

    public void ultimoDispositivo(long direccion) {
        p.putLong("ultimo", direccion);
    }

    public String opcion() {
        return p.get("opcion", "AUTO");
    }

    public void opcion(String o) {
        p.put("opcion", o);
    }

    /** Buffer objetivo en vivo (ms): Normal 500, Baja 250 (por defecto), Mínima 150. */
    public long latenciaMs() {
        return p.getLong("latenciaMs", 250);
    }

    public void latenciaMs(long ms) {
        p.putLong("latenciaMs", ms);
    }

    public boolean silenciar() {
        return p.getBoolean("silenciar", true);
    }

    public void silenciar(boolean si) {
        p.putBoolean("silenciar", si);
    }

    /**
     * ID de la salida de Windows que dejamos silenciada (p. ej. el programa se cerró a la fuerza), o
     * null si ninguna. Se restaura esa misma salida, aunque ya no sea la predeterminada.
     */
    public String silencioPendiente() {
        String id = p.get("silencioPendiente", "");
        return id.isEmpty() ? null : id;
    }

    public void silencioPendiente(String idSalida) {
        p.put("silencioPendiente", idSalida == null ? "" : idSalida);
        try {
            p.flush();
        } catch (Exception ignorada) {
            // se guarda igual más tarde
        }
    }
}
