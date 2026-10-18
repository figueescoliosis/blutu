package cl.parlantey.core;

/**
 * {@link Salida} de prueba que imita al AudioTrack de Android 2.3 (MODE_STREAM) con tiempo virtual:
 * <ul>
 * <li>Tras abrir o vaciar (flush), no reproduce hasta que el buffer se llena (FS_FILLING).</li>
 * <li>{@code escribir} sin estar sonando acepta solo lo que cabe; sonando, pedir más de lo que
 * cabe bloquearía al hilo de audio: se marca como error.</li>
 * <li>La posición avanza de a {@code granularidad} frames (el período del mezclador) y es un
 * contador de 32 bits que puede partir de cualquier valor.</li>
 * <li>Solo un hilo puede llamarla.</li>
 * </ul>
 * El tiempo avanza con {@link #avanzar}. Cuenta las faltas de datos audibles ({@link #faltas}).
 */
final class SalidaFalsa implements Salida {

    private final int capacidad;
    private final int granularidad;
    private final int posicionInicial;

    // Configuración de fallas.
    boolean fallarAbrir;
    /** Máximo de frames que acepta cada escribir (para probar escrituras parciales). */
    int maxPorEscritura = Integer.MAX_VALUE;
    /** escribir devuelve 0 mientras sea true. */
    boolean escrituraRota;
    /** La posición no avanza (AudioFlinger colgado) hasta que se vuelva a abrir. */
    boolean trabada;
    /** Como {@link #trabada}, pero reabrir tampoco la arregla. */
    boolean trabadaSiempre;

    // Estado.
    private Thread hilo;
    private boolean abierta;
    private int tasa;
    private int canales;
    private boolean sonando;
    private boolean llenando;
    private boolean pausada;
    private long pendiente; // frames escritos que aún no suenan
    private int posicion;
    private long nanosAcumulados;
    private long fraccion; // frames·1e9 sin consumir

    // Registro.
    int aperturas;
    int vaciados;
    int llamadasEscribir;
    int llamadasEscribirSinSonar;
    long framesEscritos;
    long framesSonados;
    /** Veces que, sonando y ya lleno una vez, el mezclador no encontró datos. */
    int faltas;
    String error;
    /** Primeras muestras escritas del canal 0, para revisar qué se escribió. */
    final short[] historial;
    int largoHistorial;
    /** Si es true, cuenta en {@link #sobreUmbral} las muestras escritas mayores que {@link #umbral}. */
    boolean vigilar;
    /** Activa {@link #vigilar} en el próximo {@link #vaciar}. */
    boolean vigilarAlVaciar;
    int umbral;
    long sobreUmbral;

    SalidaFalsa(int capacidad, int granularidad, int posicionInicial, int largoHistorial) {
        this.capacidad = capacidad;
        this.granularidad = granularidad;
        this.posicionInicial = posicionInicial;
        this.historial = new short[largoHistorial];
    }

    SalidaFalsa(int capacidad) {
        this(capacidad, 256, 0, 0);
    }

    private void hilo() {
        Thread t = Thread.currentThread();
        if (hilo == null) {
            hilo = t;
        } else if (hilo != t) {
            fallar("la llamó otro hilo: " + t.getName());
        }
    }

    private void fallar(String mensaje) {
        if (error == null) {
            error = mensaje;
        }
    }

    @Override
    public boolean abrir(int tasa, int canales) {
        hilo();
        if (abierta) {
            fallar("abrir sin cerrar");
        }
        if (fallarAbrir) {
            return false;
        }
        aperturas++;
        abierta = true;
        trabada = false;
        pausada = false;
        this.tasa = tasa;
        this.canales = canales;
        sonando = false;
        llenando = true;
        pendiente = 0;
        posicion = posicionInicial;
        fraccion = 0;
        return true;
    }

    @Override
    public int capacidadFrames() {
        hilo();
        return capacidad;
    }

    @Override
    public int escribir(short[] pcm, int desde, int frames) {
        hilo();
        if (!abierta) {
            fallar("escribir sin abrir");
            return -3;
        }
        llamadasEscribir++;
        if (!sonando) {
            llamadasEscribirSinSonar++;
        }
        if (escrituraRota) {
            return 0;
        }
        long libre = capacidad - pendiente;
        if (sonando && frames > libre) {
            fallar("escribir(" + frames + ") con " + libre + " libres bloquearía al hilo de audio");
        }
        int n = (int) Math.min(Math.min(frames, libre), maxPorEscritura);
        for (int i = 0; i < n; i++) {
            short v = pcm[desde + i * canales];
            if (largoHistorial < historial.length) {
                historial[largoHistorial++] = v;
            }
            if (vigilar && v > umbral) {
                sobreUmbral++;
            }
        }
        pendiente += n;
        framesEscritos += n;
        if (llenando && pendiente >= capacidad) {
            llenando = false;
        }
        return n;
    }

    @Override
    public int framesReproducidos() {
        hilo();
        return posicion;
    }

    @Override
    public void reproducir() {
        hilo();
        if (pausada) {
            // AudioFlinger 2.3 saca de las activas a una pista en pausa; al reanudarla
            // (addTrack_l) vuelve a FS_FILLING: no suena hasta llenarse otra vez.
            pausada = false;
            if (pendiente < capacidad) {
                llenando = true;
            }
        }
        sonando = true;
    }

    @Override
    public void pausar() {
        hilo();
        if (sonando) {
            pausada = true;
        }
        sonando = false;
    }

    @Override
    public void vaciar() {
        hilo();
        sonando = false;
        pausada = false;
        vaciados++;
        if (vigilarAlVaciar) {
            vigilar = true;
        }
        pendiente = 0;
        posicion = posicionInicial;
        llenando = true;
        fraccion = 0;
    }

    @Override
    public void detener() {
        vaciar();
    }

    @Override
    public void cerrar() {
        hilo();
        abierta = false;
        sonando = false;
    }

    /** Avanza el tiempo virtual: el mezclador consume de a {@code granularidad} frames. */
    void avanzar(long nanos) {
        nanosAcumulados += nanos;
        if (!abierta || !sonando || llenando || trabada || trabadaSiempre) {
            return;
        }
        fraccion += nanos * tasa;
        long periodoNs = (long) granularidad * 1000000000L;
        while (fraccion >= periodoNs) {
            fraccion -= periodoNs;
            if (pendiente >= granularidad) {
                pendiente -= granularidad;
                posicion += granularidad;
                framesSonados += granularidad;
            } else {
                faltas++; // underrun: el mezclador pone silencio y la posición no avanza
            }
        }
    }

    /**
     * Como cuando muere mediaserver en 2.3: AudioTrack se recrea solo por dentro, vacío, con la
     * posición en 0 y esperando llenarse; quien escribe no se entera.
     */
    void recrearSola() {
        pendiente = 0;
        posicion = 0;
        llenando = true;
        fraccion = 0;
    }

    boolean sonando() {
        return sonando;
    }

    boolean abierta() {
        return abierta;
    }

    int canales() {
        return canales;
    }

    int tasa() {
        return tasa;
    }

    long pendiente() {
        return pendiente;
    }
}
