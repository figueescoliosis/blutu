package cl.parlantey.core;

/**
 * Cola acotada de bloques AUDIO entre el hilo de red (productor) y el de audio (consumidor), con un
 * pool preasignado: no asigna memoria al encolar ni al sacar. Usa un solo monitor ({@code this}).
 *
 * <p><b>Generaciones:</b> VACIAR, DETENER y un CONFIG con otro formato los aplica el hilo de red con
 * {@link #nuevaGeneracion}: devuelve al pool lo encolado, incrementa la generación y fija el CONFIG
 * vigente (null = detenido). Así, en la cola solo hay bloques de la generación vigente. El hilo de
 * audio saca indicando la generación que ya aplicó: si quedó atrás, {@link #sacar} no le entrega
 * nada (ni descarta nada) hasta que aplique la nueva. Nunca suena audio de antes de un VACIAR.
 */
public final class ColaBloques {

    /** Un bloque del pool. Sus campos los escribe el hilo de red y los lee el de audio tras {@link #sacar}. */
    public static final class Bloque {
        public final byte[] datos;
        public int largo;
        public long seq;

        Bloque(int capacidad) {
            datos = new byte[capacidad];
        }
    }

    private final int bytesPorBloque;
    private final Bloque[] libres;
    private int cantidadLibres;
    private final Bloque[] cola;
    private int inicio;
    private int cantidad;

    private int generacion;
    private Config config;
    private long ultimoSeq = Protocolo.SEQ_NINGUNO;
    private int desbordes;

    /**
     * @param bloques           tamaño del pool (p. ej. 188: 4000 ms a 48 kHz con bloques de 1024)
     * @param bytesPorBloque    capacidad de cada bloque (1032 para estéreo con 1024 muestras)
     */
    public ColaBloques(int bloques, int bytesPorBloque) {
        this.bytesPorBloque = bytesPorBloque;
        libres = new Bloque[bloques];
        cola = new Bloque[bloques];
        for (int i = 0; i < bloques; i++) {
            libres[i] = new Bloque(bytesPorBloque);
        }
        cantidadLibres = bloques;
    }

    public int capacidadBloques() {
        return cola.length;
    }

    public int bytesPorBloque() {
        return bytesPorBloque;
    }

    // --- Hilo de red.

    /** Un bloque libre para llenar, o null si el pool está agotado (se cuenta un desborde). */
    public synchronized Bloque tomarLibre() {
        if (cantidadLibres == 0) {
            desbordes++;
            return null;
        }
        Bloque b = libres[--cantidadLibres];
        libres[cantidadLibres] = null;
        return b;
    }

    /** Encola un bloque (queda en la generación vigente) y actualiza el último seq recibido. */
    public synchronized void encolar(Bloque b, long seq) {
        b.seq = seq;
        ultimoSeq = seq;
        cola[(inicio + cantidad) % cola.length] = b;
        cantidad++;
        notifyAll();
    }

    /** Registra un AUDIO recibido que no se encola (sin CONFIG o pool agotado). */
    public synchronized void recibido(long seq) {
        ultimoSeq = seq;
    }

    /**
     * Devuelve al pool todo lo encolado, incrementa la generación y fija el CONFIG vigente
     * (null = DETENER). Despierta al hilo de audio.
     */
    public synchronized void nuevaGeneracion(Config nuevo) {
        while (cantidad > 0) {
            libres[cantidadLibres++] = cola[inicio];
            cola[inicio] = null;
            inicio = (inicio + 1) % cola.length;
            cantidad--;
        }
        inicio = 0;
        generacion++;
        config = nuevo;
        notifyAll();
    }

    /** Cambia el CONFIG vigente sin vaciar (mismo formato, otro bufferObjetivoMs). */
    public synchronized void actualizarConfig(Config nuevo) {
        config = nuevo;
        notifyAll();
    }

    // --- Hilo de audio.

    /**
     * Saca el próximo bloque, o devuelve null si no hay o si {@code generacionAplicada} no es la
     * vigente (el hilo de audio debe aplicar primero la generación nueva).
     */
    public synchronized Bloque sacar(int generacionAplicada) {
        if (cantidad == 0 || generacionAplicada != generacion) {
            return null;
        }
        Bloque b = cola[inicio];
        cola[inicio] = null;
        inicio = (inicio + 1) % cola.length;
        cantidad--;
        return b;
    }

    public synchronized void devolver(Bloque b) {
        libres[cantidadLibres++] = b;
        notifyAll();
    }

    /** Espera hasta {@code ms} milisegundos o hasta que llegue un bloque o una generación nueva. */
    public synchronized void esperar(long ms) {
        if (ms <= 0) {
            return;
        }
        try {
            wait(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Despierta a quien esté en {@link #esperar} (p. ej. tras PAUSA o REANUDAR). */
    public synchronized void despertar() {
        notifyAll();
    }

    // --- Lecturas (cualquier hilo).

    public synchronized int generacion() {
        return generacion;
    }

    public synchronized Config config() {
        return config;
    }

    public synchronized int enCola() {
        return cantidad;
    }

    public synchronized long ultimoSeq() {
        return ultimoSeq;
    }

    public synchronized int desbordes() {
        return desbordes;
    }
}
