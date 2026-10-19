package cl.parlantey.pc;

import cl.parlantey.core.Salida;

/** Salida falsa que consume a tiempo real (reloj del sistema). Cuenta las faltas de datos. */
final class SalidaTiempoReal implements Salida {
    private final int capacidad = 8192;
    private int tasa;
    private long escritos;
    private long base;
    private long inicioNs;
    private boolean sonando;
    volatile long framesSonados;
    volatile int faltas;
    volatile boolean abierta;

    @Override
    public synchronized boolean abrir(int tasa, int canales) {
        this.tasa = tasa;
        escritos = 0;
        base = 0;
        sonando = false;
        abierta = true;
        return true;
    }

    private long consumidos() {
        if (!sonando) {
            return base;
        }
        long ahora = System.nanoTime();
        long c = base + (ahora - inicioNs) * tasa / 1000000000L;
        if (c > escritos) {
            if (escritos - base > 0) {
                faltas++;
            }
            base = escritos;
            inicioNs = ahora;
            c = escritos;
        }
        return c;
    }

    @Override
    public int capacidadFrames() {
        return capacidad;
    }

    @Override
    public synchronized int escribir(short[] pcm, int desde, int frames) {
        int n = (int) Math.min(frames, capacidad - (escritos - consumidos()));
        if (n <= 0) {
            return 0;
        }
        escritos += n;
        return n;
    }

    @Override
    public synchronized int framesReproducidos() {
        long c = consumidos();
        framesSonados = c;
        return (int) c;
    }

    @Override
    public synchronized void reproducir() {
        if (!sonando) {
            base = consumidos();
            inicioNs = System.nanoTime();
            sonando = true;
        }
    }

    @Override
    public synchronized void pausar() {
        base = consumidos();
        sonando = false;
    }

    @Override
    public synchronized void vaciar() {
        sonando = false;
        escritos = 0;
        base = 0;
    }

    @Override
    public void detener() {
        vaciar();
    }

    @Override
    public synchronized void cerrar() {
        abierta = false;
        sonando = false;
    }
}
