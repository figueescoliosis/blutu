package cl.parlantey.core;

/**
 * PCM16 capturado en vivo, entre el hilo que lo captura ({@link #escribir}, p. ej. AudioRecord) y el
 * hilo de E/S que lo envía ({@link #leer}, como {@link Fuente}). Buffer circular preasignado: ningún
 * lado bloquea ni asigna memoria.
 *
 * <p>Lo pendiente está acotado: si el envío se atrasa (enlace lento o cortado) y se llena, se
 * descarta lo más antiguo. Así la latencia no crece: al volver el enlace se envía lo más reciente.
 */
public final class ColaCaptura implements Fuente {

    private final int tasa;
    private final int canales;
    /** Capacidad en frames. */
    private final int capacidad;
    private final short[] datos;

    // Con el monitor de this.
    private int inicio; // frame más antiguo
    private int pendientes; // frames
    private long descartados; // frames
    private long escritos; // frames
    private int pico; // |muestra| máxima desde la última llamada a picoYReiniciar
    private boolean cerrada;

    /**
     * @param maxMs lo más que puede quedar pendiente antes de descartar lo más antiguo
     */
    public ColaCaptura(int tasa, int canales, long maxMs) {
        if (tasa <= 0 || canales < 1 || canales > Downmix.MAX_CANALES_ENTRADA || maxMs <= 0) {
            throw new IllegalArgumentException("ColaCaptura(" + tasa + ", " + canales + ", " + maxMs + ")");
        }
        this.tasa = tasa;
        this.canales = canales;
        this.capacidad = (int) Math.max(1, tasa * maxMs / 1000);
        this.datos = new short[capacidad * canales];
    }

    @Override
    public int tasa() {
        return tasa;
    }

    @Override
    public int canales() {
        return canales;
    }

    /**
     * Agrega {@code frames} frames intercalados de {@code pcm} desde la muestra {@code desde}. Si no
     * caben, descarta lo más antiguo (también de lo recién llegado, si es más que la capacidad).
     */
    public synchronized void escribir(short[] pcm, int desde, int frames) {
        if (cerrada || frames <= 0) {
            return;
        }
        for (int i = desde, fin = desde + frames * canales; i < fin; i++) {
            int v = pcm[i];
            if (v < 0) {
                v = -v;
            }
            if (v > pico) {
                pico = v;
            }
        }
        escritos += frames;
        if (frames > capacidad) {
            int sobra = frames - capacidad;
            descartados += sobra;
            desde += sobra * canales;
            frames = capacidad;
        }
        int libres = capacidad - pendientes;
        if (frames > libres) {
            int quitar = frames - libres;
            inicio = (inicio + quitar) % capacidad;
            pendientes -= quitar;
            descartados += quitar;
        }
        int fin = (inicio + pendientes) % capacidad;
        int primero = Math.min(frames, capacidad - fin);
        System.arraycopy(pcm, desde, datos, fin * canales, primero * canales);
        if (frames > primero) {
            System.arraycopy(pcm, desde + primero * canales, datos, 0, (frames - primero) * canales);
        }
        pendientes += frames;
    }

    /** Lo que haya, hasta {@code frames}; 0 si no hay nada; {@link #FIN} si se cerró. No bloquea. */
    @Override
    public synchronized int leer(short[] destino, int desdeFrame, int frames) {
        if (pendientes == 0) {
            return cerrada ? FIN : 0;
        }
        int n = Math.min(frames, pendientes);
        int primero = Math.min(n, capacidad - inicio);
        System.arraycopy(datos, inicio * canales, destino, desdeFrame * canales, primero * canales);
        if (n > primero) {
            System.arraycopy(datos, 0, destino, (desdeFrame + primero) * canales, (n - primero) * canales);
        }
        inicio = (inicio + n) % capacidad;
        pendientes -= n;
        return n;
    }

    /** Descarta lo pendiente (p. ej. al reconectar: lo viejo ya no sirve). */
    public synchronized void vaciar() {
        descartados += pendientes;
        inicio = 0;
        pendientes = 0;
    }

    /** Después de lo pendiente, {@link #leer} devuelve {@link #FIN}. */
    public synchronized void cerrar() {
        cerrada = true;
    }

    public synchronized long msPendientes() {
        return pendientes * 1000L / tasa;
    }

    public synchronized long msDescartados() {
        return descartados * 1000L / tasa;
    }

    public synchronized long framesDescartados() {
        return descartados;
    }

    public synchronized long framesEscritos() {
        return escritos;
    }

    /** |muestra| máxima escrita desde la llamada anterior (0 = solo silencio). */
    public synchronized int picoYReiniciar() {
        int p = pico;
        pico = 0;
        return p;
    }
}
