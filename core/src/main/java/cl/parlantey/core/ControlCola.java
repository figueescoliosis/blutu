package cl.parlantey.core;

import java.io.IOException;

/**
 * Cola de reproducción del emisor sobre un {@link FlujoEmisor}: abre las pistas (sin bloquear) con
 * una {@link Fabrica}, prepara la siguiente para que el cambio sea sin pausa, y atiende siguiente,
 * anterior, pausa, cambio de preset y reconexión. Siguiente y anterior son relativos a la pista que
 * se escucha, no a la que se está enviando. Lo usa un solo hilo (el de E/S del emisor).
 */
public final class ControlCola {

    /** Pistas de la cola. La implementación de la app abre archivos; los tests, señales. */
    public interface Fabrica {
        int cantidad();

        /** Empieza a abrir la pista {@code indice} desde {@code desdeMs}; no debe bloquear. */
        Apertura abrir(int indice, long desdeMs);
    }

    /** Una pista abriéndose (decodificador, primer bloque, metadatos). */
    public interface Apertura {
        /** true cuando terminó de abrir, bien o mal. */
        boolean lista();

        /** La fuente, ya con el formato de salida del decodificador; null si falló. */
        Fuente fuente();

        Track track();

        /** Por qué falló (null si no). */
        String error();

        /** Libera lo abierto (también si todavía no terminó). */
        void cerrar();
    }

    /** "Anterior" con más de esto sonado reinicia la pista. */
    static final long REINICIAR_SI_MAS_DE_MS = 3000;

    private final FlujoEmisor flujo;
    private Fabrica fabrica;
    private Preset preset = Preset.NORMAL;

    // Pista que se envía (y su apertura, para liberarla).
    private int indiceActual = -1;
    private Apertura aperturaActual;
    // Pista anterior, que todavía suena en un cambio sin pausa.
    private int indiceAnterior = -1;
    // Abriéndose para reproducir ya.
    private Apertura pendiente;
    private int indicePendiente = -1;
    // Preparándose como siguiente.
    private Apertura preparando;
    private int indicePreparando = -1;
    private Apertura aperturaSiguiente;
    private int indiceSiguiente = -1;
    private boolean siguienteListo;

    private long cambiosVistos;
    private long ultimaPosicionMs;
    private boolean finalizada;
    private String ultimoError;
    private long errores;
    // Para retomar tras una reconexión.
    private int retomarIndice = -1;
    private long retomarDesdeMs;

    public ControlCola(long objetivoMs) {
        this.flujo = new FlujoEmisor(objetivoMs);
    }

    public FlujoEmisor flujo() {
        return flujo;
    }

    // --- Conexión.

    public void conectar(EscritorTramas escritor, FlujoEmisor.Estados estados) {
        flujo.conectar(escritor, estados);
        if (retomarIndice >= 0) {
            // Lo que estaba en el buffer del receptor se perdió: retomar desde lo que se escuchó.
            int i = retomarIndice;
            retomarIndice = -1;
            reproducir(i, retomarDesdeMs);
        }
    }

    public void desconectar() {
        int sonando = indiceSonando();
        if (sonando >= 0 && !flujo.detenido() && !finalizada) {
            retomarIndice = sonando;
            retomarDesdeMs = ultimaPosicionMs;
        }
        flujo.desconectar();
        flujo.detener(); // sin conexión no envía nada; la reconexión reabre desde lo escuchado
        cerrarTodo();
    }

    // --- Cola y comandos.

    /** Fija la cola (no empieza a sonar). */
    public void cola(Fabrica fabrica) {
        this.fabrica = fabrica;
    }

    public void preset(Preset preset) {
        this.preset = preset;
    }

    public Preset preset() {
        return preset;
    }

    /** Reproduce la pista {@code indice} desde {@code desdeMs}, cortando lo que suene (VACIAR). */
    public void reproducir(int indice, long desdeMs) {
        if (fabrica == null || indice < 0 || indice >= fabrica.cantidad()) {
            return;
        }
        cerrar(pendiente);
        pendiente = fabrica.abrir(indice, desdeMs);
        indicePendiente = indice;
        finalizada = false;
        retomarIndice = -1; // lo que se elige gana a lo que se iba a retomar
        if (flujo.pausado()) {
            flujo.reanudar(); // elegir una pista es querer escucharla
        }
    }

    public void siguiente() {
        int i = indiceSonando();
        if (fabrica != null && i + 1 < fabrica.cantidad()) {
            reproducir(i + 1, 0);
        } else {
            detener();
        }
    }

    public void anterior() {
        int i = indiceSonando();
        if (i < 0) {
            return;
        }
        if (ultimaPosicionMs > REINICIAR_SI_MAS_DE_MS || i == 0) {
            reproducir(i, 0);
        } else {
            reproducir(i - 1, 0);
        }
    }

    public void pausar() {
        flujo.pausar();
    }

    public void reanudar() {
        flujo.reanudar();
    }

    public void detener() {
        flujo.detener();
        cerrarTodo();
        indiceActual = -1;
        indiceAnterior = -1;
        retomarIndice = -1;
        finalizada = false;
    }

    /** Otro preset: la pista que suena sigue desde donde va con el preset nuevo. */
    public void cambiarPreset(Preset nuevo) {
        if (nuevo == preset) {
            return;
        }
        preset = nuevo;
        int i = indiceSonando();
        if (i >= 0 && !flujo.detenido() && !finalizada) {
            reproducir(i, ultimaPosicionMs);
        }
    }

    // --- Paso.

    /** Da un paso del flujo y atiende las aperturas. Devuelve lo mismo que {@link FlujoEmisor#paso}. */
    public long paso(long ahoraNs) throws IOException {
        if (pendiente != null && pendiente.lista()) {
            empezarPendiente();
        }
        if (preparando != null && preparando.lista()) {
            terminarPreparacion();
        }
        long espera = flujo.paso(ahoraNs);
        if (flujo.cambiosDePista() != cambiosVistos) {
            cambiosVistos = flujo.cambiosDePista();
            // El flujo pasó a la siguiente: la actual ya se envió entera.
            cerrar(aperturaActual);
            indiceAnterior = indiceActual;
            indiceActual = indiceSiguiente;
            aperturaActual = aperturaSiguiente;
            aperturaSiguiente = null;
            indiceSiguiente = -1;
            siguienteListo = false;
            prepararSiguiente(indiceActual + 1);
        }
        if (espera == FlujoEmisor.FIN && flujo.terminado()) {
            finalizada = true;
        }
        if (flujo.actual() != null) {
            ultimaPosicionMs = flujo.posicionMs();
        }
        return espera;
    }

    private void empezarPendiente() {
        Apertura a = pendiente;
        int i = indicePendiente;
        pendiente = null;
        CodificadorPista pista = crearPista(a, i);
        if (pista == null) {
            // No se pudo: la siguiente que se pueda abrir.
            if (i + 1 < fabrica.cantidad()) {
                reproducir(i + 1, 0);
            } else {
                detener();
            }
            return;
        }
        cerrar(aperturaActual);
        cerrar(preparando);
        cerrar(aperturaSiguiente);
        preparando = null;
        aperturaSiguiente = null;
        siguienteListo = false;
        aperturaActual = a;
        indiceActual = i;
        indiceAnterior = -1;
        flujo.reproducir(pista, a.track(), true);
        cambiosVistos = flujo.cambiosDePista();
        ultimaPosicionMs = 0;
        prepararSiguiente(i + 1);
    }

    private void prepararSiguiente(int desde) {
        cerrar(preparando);
        preparando = null;
        indiceSiguiente = -1;
        flujo.siguiente(null, null);
        if (fabrica != null && desde >= 0 && desde < fabrica.cantidad()) {
            preparando = fabrica.abrir(desde, 0);
            indicePreparando = desde;
        }
    }

    private void terminarPreparacion() {
        Apertura a = preparando;
        int i = indicePreparando;
        preparando = null;
        CodificadorPista pista = crearPista(a, i);
        if (pista == null) {
            prepararSiguiente(i + 1); // se salta la que no abre
            return;
        }
        aperturaSiguiente = a;
        indiceSiguiente = i;
        siguienteListo = true;
        flujo.siguiente(pista, a.track());
    }

    /** CodificadorPista de una apertura terminada, o null (y se anota el error) si no sirve. */
    private CodificadorPista crearPista(Apertura a, int indice) {
        String error = a.error();
        Fuente f = a.fuente();
        if (error == null && f != null) {
            try {
                return new CodificadorPista(f, preset);
            } catch (IllegalArgumentException e) {
                error = e.getMessage();
            }
        }
        ultimoError = "Pista " + (indice + 1) + ": " + (error != null ? error : "no se pudo abrir");
        errores++;
        a.cerrar();
        return null;
    }

    private void cerrarTodo() {
        cerrar(pendiente);
        cerrar(preparando);
        cerrar(aperturaActual);
        cerrar(aperturaSiguiente);
        pendiente = null;
        preparando = null;
        aperturaActual = null;
        aperturaSiguiente = null;
        indiceSiguiente = -1;
        siguienteListo = false;
    }

    private static void cerrar(Apertura a) {
        if (a != null) {
            a.cerrar();
        }
    }

    // --- Consultas.

    /** La pista que se escucha ahora (−1 si ninguna). */
    public int indiceSonando() {
        if (flujo.sonandoPrevia() && indiceAnterior >= 0) {
            return indiceAnterior;
        }
        return indiceActual;
    }

    /** Lo que ya sonó de la pista que se escucha, en ms (estimado). */
    public long posicionMs() {
        return ultimaPosicionMs;
    }

    public boolean pausado() {
        return flujo.pausado();
    }

    /** true si la última pista terminó y ya sonó entera en el receptor. */
    public boolean finalizada() {
        return finalizada;
    }

    /** true si hay una pista abriéndose para empezar a sonar. */
    public boolean abriendo() {
        return pendiente != null;
    }

    public boolean siguienteLista() {
        return siguienteListo;
    }

    public String ultimoError() {
        return ultimoError;
    }

    public long errores() {
        return errores;
    }
}
