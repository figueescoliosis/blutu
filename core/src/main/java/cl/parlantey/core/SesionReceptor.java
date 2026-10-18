package cl.parlantey.core;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Lado receptor (servidor) de una conexión. Dos hilos la usan:
 * <ul>
 * <li>el de red llama a {@link #iniciar} (envía HELLO) y luego a {@link #procesarTrama} en bucle;</li>
 * <li>el de STATE llama a {@link #tickEstado} cada pocos ms, solo después de {@link #iniciar}.</li>
 * </ul>
 * CONFIG, VACIAR y DETENER se aplican en la {@link ColaBloques} (generaciones) en el orden en que
 * llegan; PAUSA y REANUDAR, en el {@link Reproductor}. No asigna memoria por trama de audio.
 */
public final class SesionReceptor {

    /** Avisos al dueño de la sesión, desde el hilo de red. No deben bloquear. */
    public interface Oyente {
        void pruebaTerminada(long bytesRecibidos, long duracionMs);

        void track(Track track);
    }

    /** STATE cada 250 ms (PROTOCOLO.md). */
    public static final long INTERVALO_ESTADO_NS = 250000000L;
    /** Sin ninguna trama durante 5 s, la conexión se da por muerta (el emisor envía PING cada 1 s). */
    public static final long LIMITE_SILENCIO_NS = 5000000000L;
    /** Lo más que el primer STATE espera a que el hilo de audio ponga las métricas en cero. */
    static final long ESPERA_REINICIO_NS = 500000000L;

    private final LectorTramas lector;
    private final EscritorTramas escritor;
    private final Hello hello;
    private final ColaBloques cola;
    private final Reproductor reproductor;
    private final Oyente oyente;
    private final Reloj reloj;

    /** CONFIG vigente (solo hilo de red); null antes del primero y tras DETENER. */
    private Config config;
    private volatile boolean iniciada;
    private volatile long nanosUltimaTrama;
    private long proximoEstadoNs;
    private long inicioNs;

    private boolean enPrueba;
    private long bytesPrueba;
    private long inicioPruebaNs;

    /**
     * @param cola        null si la sesión no reproduce (pruebas de enlace de la Fase 0)
     * @param reproductor null igual que {@code cola}
     */
    public SesionReceptor(InputStream entrada, OutputStream salida, Hello hello, ColaBloques cola,
            Reproductor reproductor, Oyente oyente, Reloj reloj) {
        this.lector = new LectorTramas(entrada);
        this.escritor = new EscritorTramas(salida);
        this.hello = hello;
        this.cola = cola;
        this.reproductor = reproductor;
        this.oyente = oyente;
        this.reloj = reloj;
    }

    /**
     * Envía HELLO. Siempre es la primera trama de la conexión. Lo de la conexión anterior no pasa
     * a esta: {@code ultimoSeq} vuelve a {@link Protocolo#SEQ_NINGUNO}, la pausa se quita y las
     * métricas (cortes) vuelven a 0 antes del primer STATE.
     */
    public void iniciar() throws IOException {
        if (cola != null) {
            cola.reiniciarSeq();
        }
        if (reproductor != null) {
            reproductor.pedirPausa(false);
            reproductor.reiniciarMetricas();
        }
        escritor.hello(hello);
        long ahora = reloj.nanos();
        nanosUltimaTrama = ahora;
        proximoEstadoNs = ahora;
        inicioNs = ahora;
        iniciada = true;
    }

    /**
     * Lee y procesa una trama (bloquea hasta que llegue). Devuelve el código si llegó BYE, o -1.
     *
     * @throws ProtocoloException si la trama es inválida. Si el problema es el contenido (CONFIG
     *         inválido, AUDIO de largo incorrecto, payload corto), antes envía BYE(2).
     */
    public int procesarTrama() throws IOException {
        int tipo = lector.leer();
        nanosUltimaTrama = reloj.nanos();
        try {
            return despachar(tipo);
        } catch (ProtocoloException e) {
            try {
                escritor.bye(Protocolo.BYE_PROTOCOLO);
            } catch (IOException ignorada) {
                // Se cierra igual.
            }
            throw e;
        }
    }

    private int despachar(int tipo) throws IOException {
        DataInputStream d = lector.datos();
        switch (tipo) {
            case Protocolo.CONFIG:
                recibirConfig(Config.leer(d));
                break;
            case Protocolo.AUDIO:
                recibirAudio(d);
                break;
            case Protocolo.TRACK:
                Track track = Track.leer(d);
                if (oyente != null) {
                    oyente.track(track);
                }
                break;
            case Protocolo.CONTROL:
                recibirControl(d.readUnsignedByte());
                break;
            case Protocolo.PING:
                escritor.pong(d.readLong());
                break;
            case Protocolo.TEST_DATA:
                recibirTestData();
                break;
            case Protocolo.BYE:
                return d.readUnsignedByte();
            default:
                // Tipo desconocido o que el receptor no usa: el payload ya se consumió.
                break;
        }
        return -1;
    }

    private void recibirConfig(Config c) throws ProtocoloException {
        if (cola != null && (c.bytesBloque() > cola.bytesPorBloque()
                || c.canales * c.muestrasPorBloque > Reproductor.MAX_MUESTRAS_BLOQUE)) {
            throw new ProtocoloException("muestrasPorBloque " + c.muestrasPorBloque + " con " + c.canales
                    + " canales no soportado por este receptor");
        }
        boolean mismoFormato = config != null && config.sampleRate == c.sampleRate
                && config.canales == c.canales && config.muestrasPorBloque == c.muestrasPorBloque;
        config = c;
        if (cola != null) {
            if (mismoFormato) {
                cola.actualizarConfig(c);
            } else {
                cola.nuevaGeneracion(c);
            }
        }
    }

    private void recibirAudio(DataInputStream d) throws IOException {
        if (lector.largo() < 4) {
            throw new ProtocoloException("AUDIO sin seq");
        }
        long seq = d.readInt() & 0xFFFFFFFFL;
        if (config == null || cola == null) {
            if (cola != null) {
                cola.recibido(seq); // sin CONFIG vigente: se descarta, pero cuenta para ultimoSeq
            }
            return;
        }
        if (lector.largo() != config.largoAudio()) {
            throw new ProtocoloException("AUDIO de " + lector.largo() + " bytes; con este CONFIG son "
                    + config.largoAudio());
        }
        ColaBloques.Bloque b = cola.tomarLibre();
        if (b == null) {
            cola.recibido(seq); // pool agotado: desborde (no debería pasar con el control de flujo)
            return;
        }
        int bytes = config.bytesBloque();
        d.readFully(b.datos, 0, bytes);
        b.largo = bytes;
        cola.encolar(b, seq);
    }

    private void recibirControl(int comando) {
        switch (comando) {
            case Protocolo.CONTROL_PAUSA:
                if (reproductor != null) {
                    reproductor.pedirPausa(true);
                }
                break;
            case Protocolo.CONTROL_REANUDAR:
                if (reproductor != null) {
                    reproductor.pedirPausa(false);
                }
                break;
            case Protocolo.CONTROL_VACIAR:
                if (cola != null && config != null) {
                    cola.nuevaGeneracion(config);
                }
                break;
            case Protocolo.CONTROL_DETENER:
                config = null;
                if (cola != null) {
                    cola.nuevaGeneracion(null);
                }
                if (reproductor != null) {
                    reproductor.pedirPausa(false); // un audio nuevo empieza sin pausa
                }
                break;
            default:
                break; // comando desconocido: se ignora
        }
    }

    private void recibirTestData() throws IOException {
        if (!enPrueba) {
            enPrueba = true;
            bytesPrueba = 0;
            inicioPruebaNs = lector.nanosCabecera();
        }
        bytesPrueba += Protocolo.LARGO_CABECERA + lector.largo();
        if ((lector.flags() & Protocolo.FLAG_ULTIMA) != 0) {
            enPrueba = false;
            long duracionMs = (System.nanoTime() - inicioPruebaNs) / 1000000L;
            escritor.testResult(bytesPrueba, duracionMs);
            if (oyente != null) {
                oyente.pruebaTerminada(bytesPrueba, duracionMs);
            }
        }
    }

    /**
     * Hilo de STATE: envía STATE si ya toca (cada 250 ms). Devuelve false si pasaron más de 5 s sin
     * ninguna trama: hay que cerrar la conexión.
     */
    public boolean tickEstado(long ahoraNs) throws IOException {
        if (!iniciada) {
            return true;
        }
        if (ahoraNs - nanosUltimaTrama > LIMITE_SILENCIO_NS) {
            return false;
        }
        if (ahoraNs - proximoEstadoNs < 0) {
            return true;
        }
        if (reproductor != null && reproductor.reinicioPendiente() && ahoraNs - inicioNs < ESPERA_REINICIO_NS) {
            // Que el primer STATE no lleve los cortes de la conexión anterior; el hilo de audio
            // los pone en cero en ms. Si no lo hace (no está corriendo), el STATE sale igual.
            proximoEstadoNs = ahoraNs + 10000000L;
            return true;
        }
        proximoEstadoNs += INTERVALO_ESTADO_NS;
        if (ahoraNs - proximoEstadoNs >= 0) {
            proximoEstadoNs = ahoraNs + INTERVALO_ESTADO_NS; // atrasado: no ponerse al día de golpe
        }
        long bufferMs = 0;
        long ultimoSeq = Protocolo.SEQ_NINGUNO;
        if (cola != null) {
            synchronized (cola) { // ultimoSeq y los bloques en cola, del mismo instante
                ultimoSeq = cola.ultimoSeq();
                if (reproductor != null) {
                    bufferMs = reproductor.bufferMs();
                }
            }
        }
        int cortes = reproductor != null ? reproductor.cortes() : 0;
        boolean sonando = reproductor != null && reproductor.reproduciendo();
        escritor.state(bufferMs, cortes, ultimoSeq, sonando);
        return true;
    }

    /** Nanosegundos hasta el próximo STATE (para dormir el hilo de STATE). */
    public long nanosHastaEstado(long ahoraNs) {
        return Math.max(0, proximoEstadoNs - ahoraNs);
    }

    /** Fin de la conexión: detiene la reproducción (como DETENER) y quita la pausa. */
    public void terminar() {
        config = null;
        if (cola != null) {
            cola.nuevaGeneracion(null);
        }
        if (reproductor != null) {
            reproductor.pedirPausa(false);
        }
    }

    /** "Detener receptor": BYE(0) al emisor. */
    public void despedir() throws IOException {
        escritor.bye(Protocolo.BYE_NORMAL);
    }

    /**
     * Atiende una conexión sin reproducción (Fase 0) en el hilo actual, hasta que llegue BYE.
     *
     * @return el código del BYE recibido
     * @throws java.io.EOFException si la conexión se corta sin BYE
     * @throws ProtocoloException si llega una trama inválida
     */
    public static int atender(InputStream entrada, OutputStream salida, Hello hello, Oyente oyente)
            throws IOException {
        SesionReceptor sesion = new SesionReceptor(entrada, salida, hello, null, null, oyente, Reloj.SISTEMA);
        sesion.iniciar();
        while (true) {
            int bye = sesion.procesarTrama();
            if (bye >= 0) {
                return bye;
            }
        }
    }
}
